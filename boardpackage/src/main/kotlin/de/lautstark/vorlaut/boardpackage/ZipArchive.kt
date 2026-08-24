package de.lautstark.vorlaut.boardpackage

import java.text.Normalizer
import java.util.zip.Inflater

/**
 * A minimal ZIP reader, written rather than borrowed because SPEC.md 2 and 11
 * ask for things `java.util.zip` will not do on request.
 *
 * The importer **MUST read the central directory** and **MUST NOT** recover
 * members by scanning for local file headers: a package whose directory is
 * unreadable is rejected whole rather than salvaged in part. `ZipInputStream`
 * does exactly the forbidden thing, and `ZipFile` needs a real file on disk when
 * the contract here is to be handed bytes. Both also happily read Zip64, which
 * this format forbids, and neither will enforce an extraction bound.
 *
 * So this reads the directory itself, decodes every name as UTF-8 without a CP437
 * fallback, and refuses anything the format does not permit.
 */
internal class ZipArchive private constructor(
    private val bytes: ByteArray,
    val entries: List<Entry>,
) {
    internal class Entry(
        /** The name exactly as stored. */
        val rawName: String,
        /** [rawName] normalised to NFC, which is what lookups compare on. */
        val name: String,
        val method: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int,
    ) {
        val isNormalised: Boolean get() = rawName == name
    }

    /** Non-null when any member name arrived in a form other than NFC. */
    val denormalisedName: String? = entries.firstOrNull { !it.isNormalised }?.rawName

    private val byName: Map<String, Entry> = entries.associateBy { it.name }

    operator fun contains(name: String): Boolean = normalise(name) in byName

    /**
     * The bytes of one member, inflated if needed. Returns null when there is no
     * such member; throws [MalformedArchive] when the member is there but its
     * data is not readable.
     */
    fun read(name: String): ByteArray? {
        val entry = byName[normalise(name)] ?: return null
        // The local header is used only to find where this entry's data starts.
        // The directory remains the authority on what exists; nothing here scans
        // for headers to discover members.
        val header = entry.localHeaderOffset
        if (header < 0 || header + LOCAL_HEADER_MIN > bytes.size) {
            throw MalformedArchive("local header for ${entry.name} lies outside the archive")
        }
        if (readInt(header) != LOCAL_HEADER_SIGNATURE) {
            throw MalformedArchive("local header signature missing for ${entry.name}")
        }
        val nameLength = readShort(header + 26)
        val extraLength = readShort(header + 28)
        val start = header + LOCAL_HEADER_MIN + nameLength + extraLength
        val end = start + entry.compressedSize
        if (start < 0 || end > bytes.size || end < start) {
            throw MalformedArchive("data for ${entry.name} lies outside the archive")
        }
        val raw = bytes.copyOfRange(start, end)
        return when (entry.method) {
            METHOD_STORED -> raw
            METHOD_DEFLATED -> inflate(raw, entry.uncompressedSize, entry.name)
            else -> throw MalformedArchive("unsupported compression method ${entry.method}")
        }
    }

    private fun inflate(
        raw: ByteArray,
        expected: Int,
        name: String,
    ): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw)
            val out = ByteArray(expected)
            var written = 0
            while (written < expected && !inflater.finished()) {
                val n = inflater.inflate(out, written, expected - written)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw MalformedArchive("truncated deflate stream for $name")
                }
                written += n
            }
            if (written != expected) throw MalformedArchive("short inflate for $name")
            return out
        } catch (e: java.util.zip.DataFormatException) {
            throw MalformedArchive("corrupt deflate stream for $name: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    private fun readShort(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readInt(at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /** The archive could not be read as a whole. Always a package-level rejection. */
    internal class MalformedArchive(
        message: String,
    ) : Exception(message)

    /** A member name that is absolute or escapes the archive root (zip-slip). */
    internal class UnsafePath(
        message: String,
    ) : Exception(message)

    companion object {
        private const val EOCD_SIGNATURE = 0x06054b50
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
        private const val CENTRAL_SIGNATURE = 0x02014b50
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        private const val LOCAL_HEADER_MIN = 30
        private const val CENTRAL_HEADER_MIN = 46
        private const val EOCD_MIN = 22
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val FLAG_ENCRYPTED = 1 shl 0
        private const val ZIP64_MARKER_16 = 0xFFFF
        private const val ZIP64_MARKER_32 = -1 // 0xFFFFFFFF read as a signed int

        /**
         * SPEC.md 2: an importer SHOULD bound the total uncompressed size and the
         * compression ratio it will accept. A 600-byte archive can expand to
         * gigabytes and the viewer runs on a phone, so the bound is not academic.
         */
        private const val MAX_TOTAL_UNCOMPRESSED = 256L * 1024 * 1024
        private const val MAX_COMPRESSION_RATIO = 200L

        /** Below this, a high ratio says nothing — headers alone skew the sums. */
        private const val RATIO_FLOOR = 4096L

        fun normalise(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

        /**
         * Reads the central directory. Throws [MalformedArchive] if the archive is
         * unreadable as a whole, or [UnsafePath] for a member name that escapes —
         * which is checked here, before any member is inflated, because SPEC.md 2
         * requires rejecting such a package *without extracting it*.
         */
        fun open(bytes: ByteArray): ZipArchive {
            val eocd = findEndOfCentralDirectory(bytes)
            if (eocd >= 4 && readIntAt(bytes, eocd - 4) == ZIP64_LOCATOR_SIGNATURE) {
                throw MalformedArchive("Zip64 archives are not permitted")
            }
            val entryCount = readShortAt(bytes, eocd + 10)
            val directorySize = readIntAt(bytes, eocd + 12)
            val directoryOffset = readIntAt(bytes, eocd + 16)
            if (entryCount == ZIP64_MARKER_16 ||
                directorySize == ZIP64_MARKER_32 ||
                directoryOffset == ZIP64_MARKER_32
            ) {
                throw MalformedArchive("Zip64 archives are not permitted")
            }
            if (directoryOffset < 0 || directoryOffset > bytes.size) {
                throw MalformedArchive("central directory offset lies outside the archive")
            }

            val entries = ArrayList<Entry>(entryCount)
            var at = directoryOffset
            var totalUncompressed = 0L
            var totalCompressed = 0L
            repeat(entryCount) {
                if (at + CENTRAL_HEADER_MIN > bytes.size) {
                    throw MalformedArchive("central directory is truncated")
                }
                if (readIntAt(bytes, at) != CENTRAL_SIGNATURE) {
                    throw MalformedArchive("central directory signature not found")
                }
                val flags = readShortAt(bytes, at + 8)
                if (flags and FLAG_ENCRYPTED != 0) {
                    throw MalformedArchive("encrypted archives are not permitted")
                }
                val method = readShortAt(bytes, at + 10)
                if (method != METHOD_STORED && method != METHOD_DEFLATED) {
                    throw MalformedArchive("unsupported compression method $method")
                }
                val compressedSize = readIntAt(bytes, at + 20)
                val uncompressedSize = readIntAt(bytes, at + 24)
                if (compressedSize < 0 || uncompressedSize < 0) {
                    throw MalformedArchive("Zip64-sized member is not permitted")
                }
                val nameLength = readShortAt(bytes, at + 28)
                val extraLength = readShortAt(bytes, at + 30)
                val commentLength = readShortAt(bytes, at + 32)
                val localHeaderOffset = readIntAt(bytes, at + 42)
                val nameStart = at + CENTRAL_HEADER_MIN
                if (nameStart + nameLength > bytes.size) {
                    throw MalformedArchive("central directory is truncated")
                }

                // Always UTF-8, whether or not general purpose bit 11 says so.
                // SPEC.md 2 forbids a CP437 or platform-default fallback: the
                // fallback is what turns `images/café.png` into a missing image on
                // a format whose users do not write in ASCII.
                val rawName = String(bytes, nameStart, nameLength, Charsets.UTF_8)
                requireSafePath(rawName)

                totalUncompressed += uncompressedSize.toLong()
                totalCompressed += compressedSize.toLong()
                if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED) {
                    throw MalformedArchive("package expands past the extraction bound")
                }

                entries +=
                    Entry(
                        rawName = rawName,
                        name = normalise(rawName),
                        method = method,
                        compressedSize = compressedSize,
                        uncompressedSize = uncompressedSize,
                        localHeaderOffset = localHeaderOffset,
                    )
                at = nameStart + nameLength + extraLength + commentLength
            }

            if (totalCompressed >= RATIO_FLOOR &&
                totalUncompressed / maxOf(totalCompressed, 1L) > MAX_COMPRESSION_RATIO
            ) {
                throw MalformedArchive("package compression ratio exceeds the accepted bound")
            }
            return ZipArchive(bytes, entries)
        }

        /**
         * SPEC.md 2: names use `/`, are relative, and contain no `..`. On Android a
         * name that escapes writes outside the app's storage, so this is checked
         * against the directory before anything is extracted.
         */
        private fun requireSafePath(name: String) {
            if (name.startsWith("/") || name.startsWith("\\")) {
                throw UnsafePath("member name is absolute: $name")
            }
            if (name.length >= 2 && name[1] == ':') {
                throw UnsafePath("member name carries a drive letter: $name")
            }
            // Backslashes are not a permitted separator, and treating them as one
            // here is what stops `..\\escape` from reading as an ordinary name.
            val segments = name.split('/', '\\')
            if (segments.any { it == ".." }) {
                throw UnsafePath("member name escapes the archive root: $name")
            }
        }

        private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
            if (bytes.size < EOCD_MIN) throw MalformedArchive("file is too small to be a zip")
            // The comment may be up to 65535 bytes, so the record can sit that far
            // from the end. Scanning back for it is not the same as scanning for
            // local headers: this finds the directory, it does not replace it.
            val earliest = maxOf(0, bytes.size - EOCD_MIN - 0xFFFF)
            for (at in bytes.size - EOCD_MIN downTo earliest) {
                if (readIntAt(bytes, at) == EOCD_SIGNATURE) return at
            }
            throw MalformedArchive("end of central directory record not found")
        }

        private fun readShortAt(
            bytes: ByteArray,
            at: Int,
        ): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

        private fun readIntAt(
            bytes: ByteArray,
            at: Int,
        ): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}
