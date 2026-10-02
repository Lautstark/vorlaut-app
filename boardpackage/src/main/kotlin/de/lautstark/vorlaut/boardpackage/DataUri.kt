package de.lautstark.vorlaut.boardpackage

import java.util.Base64

/**
 * SPEC.md 5's `data`: a picture or a clip carried inline in the board as a
 * base64 data URI rather than as a member of the archive.
 *
 * The spec discourages a builder from writing it and requires an importer to
 * accept it, and both halves are right. Nothing in the family writes one; but
 * an `.obf` from somewhere else may, and before this every such button came in
 * as image_missing — a board that looked fine in the program that made it and
 * arrived on the tablet with its pictures gone.
 *
 * Such a member has no archive path, so the URI itself stands where the path
 * would: it is what the model records and what [PackageArchive.read] is later
 * asked for. That keeps "where are this button's bytes" one string with one
 * reader, instead of a second field every consumer would have to remember.
 *
 * Only `;base64` is read. The spec says base64, and a percent-encoded binary
 * image is not something any builder writes; refusing it is a degraded button
 * with a warning that says why, which is the honest outcome.
 */
internal object DataUri {
    private const val SCHEME = "data:"

    fun isDataUri(address: String): Boolean = address.startsWith(SCHEME, ignoreCase = true)

    /** The bytes the URI carries, or null if it is not a base64 data URI. */
    fun decode(uri: String): ByteArray? {
        if (!isDataUri(uri)) return null
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val header = uri.substring(SCHEME.length, comma)
        if (header.split(';').none { it.trim().equals("base64", ignoreCase = true) }) return null
        // MIME rather than the strict decoder: a builder that wraps its base64
        // at 76 columns has written valid base64, and whitespace is all the
        // MIME decoder forgives that the strict one does not.
        return try {
            Base64.getMimeDecoder().decode(uri.substring(comma + 1)).takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
