package de.lautstark.vorlaut.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board is nailed to the glass.
 *
 * This was found on a tablet twice and never on a machine, because both times
 * the mistake was in the arithmetic and the arithmetic was buried in a
 * composable that needs a window to run. It is a function of two integers now,
 * so the promise can be stated here instead of held in somebody's hands.
 *
 * The promise is not "landscape". It is that the board does not move: whatever
 * angle it sits at against the physical screen, that angle is the same in all
 * four of the turns Android can put the window through. So the assertions are
 * about `turns + quarter` -- the angle that survives Android's own turning --
 * rather than about any single return value.
 */
class AlwaysLandscapeTest {
    private val quarters = 0..3

    /** Where the board ends up on the glass, measured from the tablet at rest. */
    private fun onGlass(
        quarter: Int,
        naturalIsLandscape: Boolean,
    ) = Math.floorMod(boardTurns(quarter, naturalIsLandscape) + quarter, 4)

    @Test
    fun `the board sits at one angle on the glass, whatever Android does to the window`() {
        for (naturalIsLandscape in listOf(true, false)) {
            val angles = quarters.map { onGlass(it, naturalIsLandscape) }.toSet()
            assertEquals(
                "natural ${if (naturalIsLandscape) "landscape" else "portrait"}: " +
                    "the board took more than one angle across the four rotations",
                1,
                angles.size,
            )
        }
    }

    @Test
    fun `that one angle is a landscape one`() {
        // The glass is landscape at rest on a natural-landscape tablet, so the
        // board has to sit square to it there and across it on the other.
        for (naturalIsLandscape in listOf(true, false)) {
            for (quarter in quarters) {
                val square = onGlass(quarter, naturalIsLandscape) % 2 == 0
                assertTrue(
                    "natural ${if (naturalIsLandscape) "landscape" else "portrait"}, " +
                        "rotation $quarter: the board landed portrait on the glass",
                    square == naturalIsLandscape,
                )
            }
        }
    }

    @Test
    fun `an ordinary landscape hold on a Galaxy Tab needs no turn at all`() {
        // Natural portrait, reporting ROTATION_90: the tablet as it is picked
        // up. There are two ways round to nail a landscape board on and this is
        // the one that is not upside down. Checked on the device; the emulator
        // is natural landscape and cannot tell the two apart.
        assertEquals(0, boardTurns(quarter = 1, naturalIsLandscape = false))
    }

    @Test
    fun `every turn is one of the four`() {
        for (naturalIsLandscape in listOf(true, false)) {
            for (quarter in quarters) {
                val turns = boardTurns(quarter, naturalIsLandscape)
                assertTrue("turns out of range: $turns", turns in quarters)
            }
        }
    }
}
