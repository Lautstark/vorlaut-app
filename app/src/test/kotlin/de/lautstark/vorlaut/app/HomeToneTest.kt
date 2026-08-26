package de.lautstark.vorlaut.app

import de.lautstark.vorlaut.app.design.VorlautBoard
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The two tones a start key's picture is drawn in.
 *
 * Six numbers that are right or wrong by nothing anybody can see in the source,
 * and wrong in a way that looks like a feature nobody wired up: get the fifth
 * column's units wrong - Compose applies it in 0..255 where the editor's
 * feColorMatrix works in 0..1 - and every offset is off by a factor of 255,
 * black maps to black, and the key renders as a dark plate with a dark drawing
 * on it. That is indistinguishable from the filter simply not being applied.
 *
 * So this asserts the two ends of the map rather than the numbers: black in the
 * pictogram has to arrive as the bar's ink and white as the bar's plate, which
 * is the sentence HOME_TONES's own note makes. It reads the tokens rather than
 * repeating their hexes, so re-solving the palette moves the expectation with
 * the thing it is about instead of failing here.
 */
class HomeToneTest {
    /** The matrix, applied the way Compose applies it: `R' = a*R + b*G + c*B +
     *  d*A + e`, every channel in 0..255, then clamped. */
    private fun through(
        r: Int,
        g: Int,
        b: Int,
        a: Int = 255,
    ): List<Int> {
        val m = HOME_TONES.values
        return (0..3).map { row ->
            val at = row * 5
            (m[at] * r + m[at + 1] * g + m[at + 2] * b + m[at + 3] * a + m[at + 4])
                .roundToInt()
                .coerceIn(0, 255)
        }
    }

    private fun bytes(colour: androidx.compose.ui.graphics.Color) =
        listOf(colour.red, colour.green, colour.blue, colour.alpha)
            .map { (it * 255f).roundToInt() }

    @Test
    fun `the drawing's black becomes the bar's ink`() {
        // The strokes of the house. If this comes back near black, the offsets
        // were written in 0..1 and the key is a dark square with a dark house
        // on it.
        assertEquals(bytes(VorlautBoard.icon), through(0, 0, 0))
    }

    @Test
    fun `the drawing's white becomes the key itself`() {
        // The interior of the house, which is the case invert() gets wrong: it
        // sends this to pure black, and a black interior on a dark key reads as
        // a hole cut through the key rather than as a drawing on it.
        assertEquals(bytes(VorlautBoard.barPlate), through(255, 255, 255))
    }

    @Test
    fun `it is a straight line between the two, not a threshold`() {
        // Mid-grey lands halfway. The symbols are antialiased line art, so a
        // map that only got the ends right would leave every edge in the wrong
        // tone - which on a 500px pictogram scaled into a cell is most of what
        // is on screen.
        val ink = bytes(VorlautBoard.icon)
        val plate = bytes(VorlautBoard.barPlate)
        val middle = through(128, 128, 128)
        (0..2).forEach { at ->
            assertEquals((ink[at] + plate[at]) / 2f, middle[at].toFloat(), 1.5f)
        }
    }

    @Test
    fun `transparency is left alone, so the key shows through the ground`() {
        // The prescribed symbols are black on nothing rather than black on
        // white. Touching alpha here would paint the transparent ground in one
        // of the two tones and the drawing would come out as a filled square.
        assertEquals(0, through(0, 0, 0, a = 0)[3])
        assertEquals(255, through(0, 0, 0, a = 255)[3])
    }
}
