package de.lautstark.vorlaut.app

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * The one socket this app ever opens: a listener that takes a board package
 * from the editor over the home network and hands it to the importer.
 *
 * ## One route, and why the count matters more than the code
 *
 * `POST /paket`, plus the `OPTIONS` the browser sends before it. **There is no
 * `GET`, no listing, and no second path**, and that is a licensing constraint
 * rather than a small feature set. `exchange/SPEC.md` §5.2 says a
 * non-redistributable package "MUST NOT be offered for export, sharing, backup
 * upload, or any other path that moves its bytes off the device" — and a socket
 * that answers a `GET` is exactly such a path. The direction this class runs is
 * the sanctioned one: §5.2's own wording is a licensee putting a package "on
 * that person's device", which is what arrives here.
 *
 * So the shape of the routing below is the guarantee. Anything reachable but
 * unlisted, any handler that reads from the store instead of writing to it, and
 * the app has quietly grown the path §5.2 forbids. `PackageReceiverTest` asserts
 * the count, because a comment does not survive the next person in a hurry.
 *
 * ## Plain CORS, and deliberately nothing more
 *
 * `Access-Control-Allow-Origin`, `-Methods`, `-Headers`. That is the whole of
 * what a browser needs here, and it was measured rather than guessed
 * (`Lautstark/design`, `docs/mocks/README.md`): Chrome does not apply
 * mixed-content blocking to a private address, so an `https` editor page can
 * reach `http://192.168.x.x`; and Private Network Access is **not** the
 * mechanism — Chrome 151 never sends `Access-Control-Request-Private-Network`,
 * and receivers that answered `Access-Control-Allow-Private-Network` and
 * receivers that omitted it were accepted identically. There is deliberately no
 * PNA header below. What actually gates the transfer is the Local Network Access
 * *permission*, which is the sender's browser asking the sender, and nothing on
 * the tablet participates in it.
 *
 * `*` for the origin rather than the editor's own, because CORS is not the
 * boundary here and pretending otherwise would only strand people. The editor
 * runs from GitHub Pages, from a checkout on `localhost`, and possibly one day
 * from a plain `http` copy; an allowlist would break the last two and stop
 * nothing. The boundary is elsewhere and is made of three things: the socket
 * exists only while somebody is looking at the receive screen, the only thing
 * the route can do with bytes is offer them to the importer, and whatever
 * arrives is named on screen in a list with an Entfernen next to it.
 */
class PackageReceiver(
    /**
     * The ceiling, passed in rather than declared here. `ImportViewModel`
     * already refuses a file past this size on the way in from the picker, and
     * a package is not allowed to be bigger because it came over the wire.
     */
    private val maxBytes: Int,
    /**
     * The port to bind. The default is the one the editor knows; a test passes
     * `0` and reads [boundPort] back, so that running the suite does not depend
     * on 8765 being free on the machine running it.
     */
    private val port: Int = PORT,
    /** Told the declared size the moment a body starts arriving. */
    private val onArriving: (Long) -> Unit = {},
    /** Handed the complete bytes, on the connection's own thread. */
    private val onPackage: (ByteArray) -> Reply,
) : AutoCloseable {
    /** What the importer made of the bytes, in the terms the wire speaks. */
    sealed interface Reply {
        /** `200` — [outcome] is `installed`, `replaced` or `already_current`. */
        data class Stored(
            val outcome: String,
            val name: String,
        ) : Reply

        /**
         * `422`, and the split between these two fields is the contract.
         *
         * [reason] is a **code from a closed set**, never prose: the importer's
         * own `RejectionCode.wireName`, or one of [Codes] when the refusal
         * happened before the importer was reached. The sender never matches on
         * prose — it renders its own German sentence and shows the code as a
         * bare token, because a code is what somebody can read down a telephone.
         * A sentence fragment in here surfaces as English inside a German
         * dialog.
         *
         * [detail] is the prose, and it is for a log rather than for a screen.
         */
        data class Refused(
            val reason: String,
            val detail: String,
        ) : Reply
    }

    private var socket: ServerSocket? = null
    private var thread: Thread? = null

    /** The port actually bound, once [start] has run. */
    val boundPort: Int get() = socket?.localPort ?: port

    /**
     * Binds the port and starts listening. Idempotent, so a recomposition that
     * runs the effect again does not fight over the port.
     *
     * Bound on every interface rather than one, because the address the screen
     * shows is whichever one the router gave this tablet, and a tablet that
     * changes network while the screen is open should not need the screen
     * reopening.
     */
    fun start(): Boolean {
        if (socket != null) return true
        val bound = ServerSocket()
        val taken =
            runCatching {
                bound.reuseAddress = true
                bound.bind(InetSocketAddress(port))
            }.isSuccess
        // The port can be busy — another copy of this app, or anything else on
        // the device that got there first. This is called from a lifecycle
        // callback, so throwing would take the activity down over a screen the
        // person can simply leave. It reports instead, and the screen says so
        // rather than showing an address that answers nothing.
        if (!taken) {
            runCatching { bound.close() }
            return false
        }
        socket = bound
        thread =
            Thread({ accept(bound) }, "paket-empfangen").apply {
                isDaemon = true
                start()
            }
        return true
    }

    /**
     * Closes the port. Leaving the screen closes it, and so does backgrounding
     * the app: there is no foreground service and no socket open while a child
     * is using the board.
     *
     * Closing the [ServerSocket] is what frees the port, and it happens here and
     * at once. The accept thread is not joined, deliberately: it is a daemon, it
     * notices on its next `accept()`, and this is called from the main thread
     * during composition — a join would be the UI waiting on a socket read, for
     * no guarantee it does not already have.
     */
    override fun close() {
        socket?.let { runCatching { it.close() } }
        socket = null
        thread = null
    }

    /**
     * One connection at a time, on purpose.
     *
     * Two packages importing at once would race for the same store, and the
     * screen has one line to report on. A second sender waits in the backlog for
     * as long as the first takes, which is the correct answer rather than a
     * limitation — nobody sends two packages to one tablet simultaneously.
     */
    private fun accept(bound: ServerSocket) {
        while (!bound.isClosed) {
            val connection =
                try {
                    bound.accept()
                } catch (_: IOException) {
                    // close() shuts the socket from under accept(); that is the
                    // ordinary way this loop ends and is not worth reporting.
                    return
                }
            // One bad connection must not take the listener with it. Without
            // this the screen would go on saying it was waiting while nothing
            // was listening at all, which is the worst of the available
            // failures: it is invisible.
            runCatching { connection.use { serve(it) } }
        }
    }

    private fun serve(connection: Socket) {
        connection.soTimeout = READ_TIMEOUT_MS
        try {
            val input = connection.getInputStream().buffered()
            val out = BufferedOutputStream(connection.getOutputStream())
            val request =
                readHead(input)
                    ?: return respond(out, 400, refusal(Codes.UNREADABLE_REQUEST, "the request could not be read"))
            route(input, out, request)
            out.flush()
        } catch (_: IOException) {
            // A sender that hung up, or a read that timed out. There is nothing
            // to say to a socket that is no longer there.
        }
    }

    /**
     * The routing table, and it is this whole function.
     *
     * Every method and every path that is not the one route is answered here
     * rather than falling through to a handler, so that adding a second route
     * means editing this and cannot happen by accident.
     */
    private fun route(
        input: InputStream,
        out: BufferedOutputStream,
        request: Head,
    ) {
        if (request.path != PATH) {
            return respond(out, 404, refusal(Codes.NO_SUCH_ROUTE, "this device has one route and it is $PATH"))
        }
        when (request.method) {
            "OPTIONS" -> respond(out, 204, null)

            "POST" -> receive(input, out, request)

            // Named separately from 404 because it is the more useful answer:
            // something asked this device for a package rather than offering
            // one, and `Allow` says the route only goes the other way.
            else -> respond(out, 405, refusal(Codes.METHOD_NOT_ALLOWED, "$PATH takes POST, and nothing else"))
        }
    }

    private fun receive(
        input: InputStream,
        out: BufferedOutputStream,
        request: Head,
    ) {
        // Strict where the intent filter is lax, and the asymmetry is
        // deliberate. A file picker reports whatever a provider guessed from an
        // extension, so the filter has to be broad or it cannot see the file at
        // all; the sender here is a program working from a written contract, and
        // a wrong media type from it is a bug worth naming.
        val mediaType =
            request
                .header("content-type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        if (mediaType != PACKAGE_MEDIA_TYPE) {
            return respond(out, 415, refusal(Codes.WRONG_MEDIA_TYPE, "a package is sent as $PACKAGE_MEDIA_TYPE"))
        }

        val declared =
            request.header("content-length")?.trim()?.toLongOrNull()
                ?: return respond(out, 411, refusal(Codes.LENGTH_REQUIRED, "the length of the package has to be declared"))

        // Answered from the declared length rather than after reading, so that
        // an absurd package is refused before it is on the tablet at all.
        if (declared > maxBytes) {
            return respond(
                out,
                413,
                refusal(Codes.TOO_LARGE, "the package is larger than ${maxBytes / (1024 * 1024)} MB"),
            )
        }

        onArriving(declared)
        val bytes = ByteArray(declared.toInt())
        var filled = 0
        while (filled < bytes.size) {
            val read = input.read(bytes, filled, bytes.size - filled)
            if (read < 0) return respond(out, 400, refusal(Codes.INCOMPLETE, "the package arrived incomplete"))
            filled += read
        }

        when (val reply = onPackage(bytes)) {
            is Reply.Stored -> {
                respond(out, 200, """{"outcome":${quote(reply.outcome)},"name":${quote(reply.name)}}""")
            }

            is Reply.Refused -> {
                respond(out, 422, refusal(reply.reason, reply.detail))
            }
        }
    }

    private fun refusal(
        reason: String,
        detail: String,
    ) = """{"outcome":"refused","reason":${quote(reason)},"detail":${quote(detail)}}"""

    private fun respond(
        out: BufferedOutputStream,
        status: Int,
        body: String?,
    ) {
        val payload = body?.toByteArray(Charsets.UTF_8)
        val head =
            buildString {
                append("HTTP/1.1 $status ${reason(status)}\r\n")
                // Measured, not assumed: these three are the whole of what the
                // browser wants. See the class comment on the PNA header that is
                // not here.
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type\r\n")
                if (status == 405) append("Allow: POST, OPTIONS\r\n")
                if (payload != null) {
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                } else {
                    append("Content-Length: 0\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
        out.write(head.toByteArray(Charsets.US_ASCII))
        payload?.let { out.write(it) }
        out.flush()
    }

    /** The request line and headers, which is all of a request this reads. */
    private class Head(
        val method: String,
        val path: String,
        private val headers: Map<String, String>,
    ) {
        fun header(name: String): String? = headers[name]
    }

    private fun readHead(input: InputStream): Head? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            // Lowercased on the way in: header names are case-insensitive and a
            // lookup that assumed otherwise would work against one sender only.
            headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
        }
        // The query string is dropped rather than parsed. There is nothing to
        // ask this route for, so `/paket?anything` is the same one route.
        return Head(parts[0].uppercase(), parts[1].substringBefore('?'), headers)
    }

    /**
     * One CRLF-terminated line, bounded.
     *
     * The bound is the point: without it a sender that never sends a newline
     * grows a StringBuilder until the tablet runs out of memory, and this socket
     * is reachable from every device on the home network.
     */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_LINE) {
            val byte =
                try {
                    input.read()
                } catch (_: SocketException) {
                    return null
                }
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(byte.toChar())
        }
        return null
    }

    private fun reason(status: Int) =
        when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            413 -> "Content Too Large"
            415 -> "Unsupported Media Type"
            422 -> "Unprocessable Content"
            else -> "Error"
        }

    /**
     * A JSON string, escaped by hand.
     *
     * Two values ever go through here and one of them is a Sammlung's name,
     * which is whatever somebody typed into the editor — quotation marks and
     * newlines included. A response the sender cannot parse would report a
     * successful import as a failure.
     */
    private fun quote(value: String): String =
        buildString {
            append('"')
            for (character in value) {
                when {
                    character == '"' -> append("\\\"")
                    character == '\\' -> append("\\\\")
                    character == '\n' -> append("\\n")
                    character == '\r' -> append("\\r")
                    character == '\t' -> append("\\t")
                    character < ' ' -> append("\\u%04x".format(character.code))
                    else -> append(character)
                }
            }
            append('"')
        }

    /**
     * The receiver's own refusal codes, for the refusals that happen before the
     * importer is reached.
     *
     * Deliberately a closed set beside the importer's `RejectionCode`, and
     * deliberately not overlapping it: between them they are every value that
     * can appear in a `reason`, and a sender can hold both lists.
     */
    object Codes {
        const val NO_SUCH_ROUTE = "no_such_route"
        const val METHOD_NOT_ALLOWED = "method_not_allowed"
        const val WRONG_MEDIA_TYPE = "wrong_media_type"
        const val LENGTH_REQUIRED = "length_required"
        const val TOO_LARGE = "too_large"
        const val INCOMPLETE = "incomplete"
        const val UNREADABLE_REQUEST = "unreadable_request"

        /** The importer was reached and threw rather than answering. */
        const val IMPORT_FAILED = "import_failed"

        // There is deliberately no code here for "the port was never taken".
        // A refusal needs a connection to travel on, and if [start] failed
        // there is no socket for one to arrive at — a sender meets a closed
        // port and learns it from the transport, not from us. That case is
        // told to the person in front of the tablet instead, which is where
        // it can actually be acted on.
    }

    companion object {
        /**
         * The only path, and the test that keeps it the only path is
         * `PackageReceiverTest`.
         */
        const val PATH = "/paket"

        /**
         * Fixed rather than ephemeral, because the screen shows four numbers and
         * not five. The editor knows this port; a person copying an address off
         * a tablet should not also be copying a port off it.
         */
        const val PORT = 8765

        const val PACKAGE_MEDIA_TYPE = "application/zip"

        private const val MAX_LINE = 8 * 1024
        private const val READ_TIMEOUT_MS = 60_000
    }
}
