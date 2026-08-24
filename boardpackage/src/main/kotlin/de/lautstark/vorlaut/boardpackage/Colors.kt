package de.lautstark.vorlaut.boardpackage

/**
 * SPEC.md 7.2: colours are CSS `rgb(r, g, b)` or `#RRGGBB`. Anything else falls
 * back to the viewer default and warns — it is not a fault, because nothing about
 * the button is actually missing.
 */
internal object Colors {
    private val HEX = Regex("^#([0-9a-fA-F]{6})$")
    private val RGB = Regex("""^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$""")

    /** Normalised to `#RRGGBB`, or null if [raw] is not a colour this viewer reads. */
    fun parse(raw: String): String? {
        val trimmed = raw.trim()
        HEX.matchEntire(trimmed)?.let { return "#" + it.groupValues[1].uppercase() }
        val rgb = RGB.matchEntire(trimmed) ?: return null
        val channels = (1..3).map { rgb.groupValues[it].toInt() }
        if (channels.any { it > 255 }) return null
        return "#" + channels.joinToString("") { "%02X".format(it) }
    }
}
