package de.lautstark.vorlaut.app

import de.lautstark.vorlaut.boardpackage.MAX_PRESS_TIMING_MS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the two answers wins: the Sammlung's, or this tablet's.
 *
 * exchange/SPEC.md 4.1 gives the package the author's default and says a viewer
 * offering its own setting SHOULD let that win. The whole of that rule is
 * [resolvePressTimings], and it has one genuinely slippery case — see the third
 * test.
 */
class PressTimingsTest {
    private val fromPackage = PressTimings(holdMs = 300, releaseMs = 500)

    @Test
    fun `with no override the Sammlung decides`() {
        // The default, and the case that matters most: a tablet nobody has
        // touched behaves the way the person who built the boards intended,
        // which is the reason the field is in the package at all.
        assertEquals(fromPackage, resolvePressTimings(null, null, fromPackage))
    }

    @Test
    fun `an override wins over the Sammlung, one setting at a time`() {
        // Overriding the hold must not drag the pause along with it. They are
        // separate settings because a user commonly needs one and not the other.
        assertEquals(
            PressTimings(holdMs = 800, releaseMs = 500),
            resolvePressTimings(800, null, fromPackage),
        )
        assertEquals(
            PressTimings(holdMs = 300, releaseMs = 1000),
            resolvePressTimings(null, 1000, fromPackage),
        )
    }

    @Test
    fun `an override of zero is a choice, not an absence`() {
        // The case the three states exist for. A caregiver who has watched a
        // hold time make things worse sets it to Aus, and that has to survive a
        // Sammlung that asks for 300 - otherwise the setting appears to do
        // nothing and the only way out is editing the package.
        //
        // Written as `?:` this reads correctly; written as `if (override > 0)`
        // it does not, and the two are indistinguishable until somebody's
        // daughter has a bad afternoon.
        assertEquals(
            PressTimings(holdMs = 0, releaseMs = 0),
            resolvePressTimings(0, 0, fromPackage),
        )
    }

    @Test
    fun `a package that asks for nothing leaves the board as it always was`() {
        // Every Sammlung exported before 1.3.0 is this case: activate on
        // contact, no cooldown, which is what the app did before any of this.
        assertEquals(PressTimings.Off, resolvePressTimings(null, null, PressTimings.of(null)))
    }

    @Test
    fun `a stored number is held to the same ceiling the format has`() {
        // A preference outlives the step list that wrote it. SPEC.md 7.5's cap
        // is about what a board stays usable at, so it applies to a number this
        // screen produced just as much as to one that arrived in a package.
        assertEquals(MAX_PRESS_TIMING_MS, clampTiming(60_000))
        assertEquals(0, clampTiming(-1))
        assertEquals(300, clampTiming(300))
    }
}
