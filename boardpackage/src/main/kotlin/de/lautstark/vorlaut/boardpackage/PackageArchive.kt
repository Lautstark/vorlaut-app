package de.lautstark.vorlaut.boardpackage

/**
 * Reads the media a package carries, after it has been imported.
 *
 * The importer resolves which archive path each button's picture and clip live
 * at and records those paths; it deliberately does not hand back the bytes,
 * because at import time nothing needs them and holding a board's worth of
 * decoded images would be the memory the 1024 cap exists to bound.
 *
 * Rendering does need them, so this reads them back on demand — through the same
 * central-directory reader the import used, so a member found here is a member
 * found under the same rules: names decoded as UTF-8, compared in NFC, and no
 * scanning for local file headers.
 */
class PackageArchive private constructor(
    private val archive: ZipArchive,
) {
    /**
     * The bytes at [path], or null if the member is absent or unreadable.
     *
     * Null rather than an exception: by the time anything renders, the package has
     * already been accepted, and a member that has gone missing since is a
     * degraded button rather than a crash.
     */
    fun read(path: String): ByteArray? =
        try {
            archive.read(path)
        } catch (_: ZipArchive.MalformedArchive) {
            null
        }

    companion object {
        /** Null when [bytes] is not a readable package. */
        fun open(bytes: ByteArray): PackageArchive? =
            try {
                PackageArchive(ZipArchive.open(bytes))
            } catch (_: Exception) {
                null
            }
    }
}
