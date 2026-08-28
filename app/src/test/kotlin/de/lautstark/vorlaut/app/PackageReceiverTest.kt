package de.lautstark.vorlaut.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.Socket

/**
 * **This file is the reason the INTERNET permission is defensible.**
 *
 * The manifest used to say there must never be one, and the argument attached to
 * that absence was that a viewer which cannot reach the network cannot move a
 * non-redistributable package's bytes off the device — `exchange/SPEC.md` §5.2.
 * The permission is here now, so that argument is gone and a narrower one has
 * taken its place: the app **receives** a package and has no code path that
 * sends one. One route, POST and OPTIONS only.
 *
 * A narrower argument is only worth the wider one it replaced if something
 * checks it, and `the route offers no way to read anything` is that something.
 * It is not a test of a feature — it is a test that a feature is absent, and it
 * exists to fail loudly for whoever adds `GET /pakete` one afternoon without
 * having read §5.2 first.
 */
class PackageReceiverTest {
    /**
     * The whole surface, swept.
     *
     * Every method that could ask for something, against the one route and
     * against the paths somebody probing a tablet would try. A `405` or a `404`
     * is a pass; a body with package content in it is not, and the only way to
     * produce one is to have written a handler that reads from the store —
     * which is exactly what this makes impossible to do quietly.
     */
    @Test
    fun `the route offers no way to read anything`() {
        serving { port ->
            for (method in listOf("GET", "HEAD", "PUT", "PATCH", "DELETE", "TRACE")) {
                val answer = send(port, "$method ${PackageReceiver.PATH} HTTP/1.1\r\nHost: t\r\n\r\n")
                assertEquals("$method ${PackageReceiver.PATH} must not be served", "405", answer.status)
            }

            val elsewhere =
                listOf(
                    "/",
                    "/pakete",
                    "/paket/",
                    "/paket/1",
                    "/sammlungen",
                    "/packages",
                    "/index.html",
                    "/api/paket",
                )
            for (path in elsewhere) {
                for (method in listOf("GET", "POST", "OPTIONS")) {
                    val answer = send(port, "$method $path HTTP/1.1\r\nHost: t\r\n\r\n")
                    assertEquals("$method $path must not exist", "404", answer.status)
                }
            }
        }
    }

    /**
     * A refused GET says which way the route runs, rather than only that it
     * failed. `Allow: POST, OPTIONS` is the machine-readable form of the §5.2
     * argument: something asked this tablet for a package, and is being told the
     * route only carries them the other way.
     */
    @Test
    fun `a refused GET names the only two methods`() {
        serving { port ->
            val answer = send(port, "GET ${PackageReceiver.PATH} HTTP/1.1\r\nHost: t\r\n\r\n")
            assertEquals("405", answer.status)
            assertEquals("POST, OPTIONS", answer.header("allow"))
        }
    }

    /** A query string is not a second route, and must not become a listing. */
    @Test
    fun `a query string does not make a second route`() {
        serving { port ->
            assertEquals("405", send(port, "GET /paket?list=1 HTTP/1.1\r\nHost: t\r\n\r\n").status)
        }
    }

    @Test
    fun `the preflight answers plain CORS and no private network header`() {
        serving { port ->
            val answer =
                send(
                    port,
                    "OPTIONS ${PackageReceiver.PATH} HTTP/1.1\r\n" +
                        "Host: t\r\n" +
                        "Origin: https://lautstark.github.io\r\n" +
                        "Access-Control-Request-Method: POST\r\n" +
                        "Access-Control-Request-Headers: content-type\r\n\r\n",
                )
            assertEquals("204", answer.status)
            assertEquals("*", answer.header("access-control-allow-origin"))
            assertEquals("POST, OPTIONS", answer.header("access-control-allow-methods"))
            assertEquals("Content-Type", answer.header("access-control-allow-headers"))

            // Measured dead, in Lautstark/design's docs/mocks/README.md: Chrome
            // 151 never asks for this, and a receiver omitting it was accepted
            // identically. Asserted absent so nobody adds it back on the
            // strength of a blog post.
            assertNull(answer.header("access-control-allow-private-network"))
        }
    }

    @Test
    fun `a package is handed on and its outcome comes back`() {
        val handed = mutableListOf<ByteArray>()
        serving(
            reply = { PackageReceiver.Reply.Stored("replaced", "Kernvokabular") },
            onPackage = { handed += it },
        ) { port ->
            val payload = "PK pretend package".toByteArray()
            val answer = post(port, payload, PackageReceiver.PACKAGE_MEDIA_TYPE)
            assertEquals("200", answer.status)
            assertEquals("{\"outcome\":\"replaced\",\"name\":\"Kernvokabular\"}", answer.body)
            assertEquals(1, handed.size)
            assertTrue(payload.contentEquals(handed.single()))
        }
    }

    /**
     * The refusal's `reason` is a code and its `detail` is the prose, and they
     * are two fields rather than one string.
     *
     * The sender never matches on prose: it writes its own German sentence and
     * shows the code beside it as a bare token, because a code is what somebody
     * can read down a telephone. Collapsing them would put an English sentence
     * fragment inside a German dialog.
     */
    @Test
    fun `a refusal carries a code and its prose separately`() {
        serving(
            reply = {
                PackageReceiver.Reply.Refused("manifest_missing", "no manifest.json in the archive")
            },
        ) { port ->
            val answer = post(port, ByteArray(8), PackageReceiver.PACKAGE_MEDIA_TYPE)
            assertEquals("422", answer.status)
            assertEquals(
                "{\"outcome\":\"refused\",\"reason\":\"manifest_missing\"," +
                    "\"detail\":\"no manifest.json in the archive\"}",
                answer.body,
            )
        }
    }

    /**
     * Every refusal the receiver makes for itself carries a code too, from its
     * own closed set. A sender holding both lists can then act on any refusal
     * without reading a word of English.
     */
    @Test
    fun `the receiver's own refusals are coded, never bare prose`() {
        serving(max = 1024) { port ->
            val cases =
                listOf(
                    PackageReceiver.Codes.NO_SUCH_ROUTE to
                        send(port, "GET /nope HTTP/1.1\r\nHost: t\r\n\r\n"),
                    PackageReceiver.Codes.METHOD_NOT_ALLOWED to
                        send(port, "GET ${PackageReceiver.PATH} HTTP/1.1\r\nHost: t\r\n\r\n"),
                    PackageReceiver.Codes.WRONG_MEDIA_TYPE to
                        post(port, ByteArray(4), "text/plain"),
                )
            for ((code, answer) in cases) {
                assertTrue(
                    "expected reason \"$code\", was ${answer.body}",
                    answer.body.contains("\"reason\":\"$code\""),
                )
            }
        }
    }

    /**
     * A Sammlung's name is whatever somebody typed into the editor, quotation
     * marks included. A response the sender cannot parse would report a
     * successful import as a failure.
     */
    @Test
    fun `a name with a quotation mark in it stays parseable`() {
        val awkward = "Ein \"Test\"\nzweite Zeile"
        serving(reply = { PackageReceiver.Reply.Stored("installed", awkward) }) { port ->
            val answer = post(port, ByteArray(4), PackageReceiver.PACKAGE_MEDIA_TYPE)
            assertEquals("200", answer.status)
            assertEquals(
                "{\"outcome\":\"installed\",\"name\":\"Ein \\\"Test\\\"\\nzweite Zeile\"}",
                answer.body,
            )
        }
    }

    @Test
    fun `the wrong content type is refused`() {
        serving { port ->
            assertEquals("415", post(port, ByteArray(4), "text/plain").status)
        }
    }

    /** Parameters on the media type are a browser's business, not a refusal. */
    @Test
    fun `a charset parameter on the media type is not a refusal`() {
        serving { port ->
            assertEquals("200", post(port, ByteArray(4), "application/zip; charset=binary").status)
        }
    }

    /**
     * A refusal must survive the close, and this is the test that says so.
     *
     * 415 and 413 both answer without reading the body, deliberately — the media
     * type is wrong before the body matters, and refusing on the declared length
     * is what keeps an oversized package off the tablet entirely. That leaves
     * unread bytes in the receive buffer, and closing a socket in that state
     * makes the OS send RST rather than FIN. An RST tells the peer to discard
     * what is already in *its* receive buffer — which is the refusal we just
     * wrote, so the sender meets a connection reset instead of being told what
     * was wrong.
     *
     * A megabyte, because the bug is a race that a four-byte body usually wins:
     * it survived a full green build and only showed up later, in a different
     * test, once. This is here so that whoever decides `linger` looks
     * unnecessary finds out why it is not.
     */
    @Test
    fun `a refusal still arrives when the body was never read`() {
        serving(max = 1024 * 1024) { port ->
            val answer = post(port, ByteArray(1024 * 1024), "text/plain")
            assertEquals("415", answer.status)
            assertTrue(
                "the refusal must carry its code, was ${answer.body}",
                answer.body.contains("\"reason\":\"${PackageReceiver.Codes.WRONG_MEDIA_TYPE}\""),
            )
        }
    }

    @Test
    fun `a package past the ceiling is refused on its declared length`() {
        var reached = false
        serving(max = 1024, onPackage = { reached = true }) { port ->
            // The body is never sent, so a pass here also says the refusal
            // happened before the bytes did rather than after the tablet had
            // already read them.
            val answer =
                send(
                    port,
                    "POST ${PackageReceiver.PATH} HTTP/1.1\r\n" +
                        "Host: t\r\n" +
                        "Content-Type: ${PackageReceiver.PACKAGE_MEDIA_TYPE}\r\n" +
                        "Content-Length: 99999\r\n\r\n",
                )
            assertEquals("413", answer.status)
            assertTrue("nothing should have reached the importer", !reached)
        }
    }

    /** Nothing is listening once the screen is gone. */
    @Test
    fun `closing gives the port back`() {
        val receiver = PackageReceiver(maxBytes = 1024, port = 0, onPackage = { stored() })
        receiver.start()
        val port = receiver.boundPort
        receiver.close()
        val refused =
            runCatching { Socket("127.0.0.1", port).use { } }.isFailure
        assertTrue("the socket must be gone once the screen is left", refused)
    }

    // ---------------------------------------------------------------- plumbing

    private fun stored() = PackageReceiver.Reply.Stored("installed", "Test")

    private class Answer(
        val status: String,
        private val headers: Map<String, String>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name]
    }

    /**
     * Bound on port 0 rather than on 8765: whether the suite passes must not
     * depend on what else happens to be listening on the machine running it.
     */
    private fun serving(
        max: Int = 1024 * 1024,
        reply: () -> PackageReceiver.Reply = { stored() },
        onPackage: (ByteArray) -> Unit = {},
        body: (Int) -> Unit,
    ) {
        val receiver =
            PackageReceiver(
                maxBytes = max,
                port = 0,
                onPackage = { bytes ->
                    onPackage(bytes)
                    reply()
                },
            )
        receiver.use {
            it.start()
            body(it.boundPort)
        }
    }

    private fun post(
        port: Int,
        payload: ByteArray,
        mediaType: String,
    ): Answer =
        talk(port) { out ->
            out.write(
                (
                    "POST ${PackageReceiver.PATH} HTTP/1.1\r\n" +
                        "Host: t\r\n" +
                        "Content-Type: $mediaType\r\n" +
                        "Content-Length: ${payload.size}\r\n\r\n"
                ).toByteArray(),
            )
            out.write(payload)
        }

    private fun send(
        port: Int,
        request: String,
    ): Answer = talk(port) { it.write(request.toByteArray()) }

    private fun talk(
        port: Int,
        write: (OutputStream) -> Unit,
    ): Answer =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            write(out)
            out.flush()
            val whole = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val split = whole.indexOf("\r\n\r\n")
            val head = if (split < 0) whole else whole.substring(0, split)
            val lines = head.split("\r\n")
            val headers =
                lines
                    .drop(1)
                    .mapNotNull { line ->
                        val colon = line.indexOf(':')
                        if (colon <= 0) {
                            null
                        } else {
                            line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
                        }
                    }.toMap()
            Answer(
                status = lines.first().split(' ').getOrElse(1) { "" },
                headers = headers,
                body = if (split < 0) "" else whole.substring(split + 4),
            )
        }
}
