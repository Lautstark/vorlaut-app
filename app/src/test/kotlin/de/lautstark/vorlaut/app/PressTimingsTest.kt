package de.lautstark.vorlaut.app

import de.lautstark.vorlaut.boardpackage.MAX_PRESS_TIMING_MS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the two answers wins — the Sammlung's, or this tablet's — and what
 * the named modes underneath resolve to.
 *
 * exchange/SPEC.md 4.1 gives the package the author's default and says a viewer
 * offering its own setting SHOULD let that win. The whole of that rule is
 * [resolvePressTimings], and it has one genuinely slippery case: see the third
 * test.
 */
class PressTimingsTest {
    private val fromPackage = PressTimings(holdMs = 300, releaseMs = 500)

    @Test
    fun `with no mode chosen the Sammlung decides`() {
        // The default, and the case that matters most: a tablet nobody has
        // touched behaves the way the person who built the boards intended,
        // which is the reason the field is in the package at all.
        assertEquals(fromPackage, resolvePressTimings(null, fromPackage))
    }

    @Test
    fun `a chosen mode wins over the Sammlung, both numbers together`() {
        assertEquals(PressMode.Held.timings, resolvePressTimings(PressMode.Held, fromPackage))
        assertEquals(PressMode.Once.timings, resolvePressTimings(PressMode.Once, fromPackage))
    }

    @Test
    fun `choosing Sofort is a choice, not an absence`() {
        // The case the fourth chip exists for. A caregiver who has watched a
        // hold time make things worse picks "Sofort", and that has to survive a
        // Sammlung asking for 300 - otherwise the setting appears to do nothing
        // and the only way out is editing the package.
        //
        // null and AtOnce produce different answers here and the same two
        // zeroes almost everywhere else, which is what makes conflating them
        // easy and the mistake invisible until somebody's daughter has a bad
        // afternoon.
        assertEquals(PressTimings.Off, resolvePressTimings(PressMode.AtOnce, fromPackage))
        assertEquals(fromPackage, resolvePressTimings(null, fromPackage))
    }

    @Test
    fun `a package that asks for nothing leaves the board as it always was`() {
        // Every Sammlung exported before 1.3.0 is this case: activate on
        // contact, no cooldown, which is what the app did before any of this.
        assertEquals(PressTimings.Off, resolvePressTimings(null, PressTimings.of(null)))
    }

    @Test
    fun `the modes are ordered by what they cost, cheapest first`() {
        // The whole design of the screen rests on this order, so it is asserted
        // rather than left to the enum's declaration order being read
        // charitably. A pause costs nothing until the next word; a hold is a
        // delay on every word - so a caregiver walking down the list and
        // stopping at the first one that works never pays for more than they
        // needed.
        assertEquals(
            "only the last mode may cost time on every word",
            listOf(false, false, true),
            PressMode.entries.map { it.timings.holdMs > 0 },
        )
        assertEquals(
            "each mode is at least as careful as the one before",
            PressMode.entries.map { it.timings.releaseMs }.sorted(),
            PressMode.entries.map { it.timings.releaseMs },
        )
    }

    @Test
    fun `every mode stays inside what the format allows`() {
        // SPEC.md 7.5 clamps at the ceiling, so a preset above it would be a
        // number this app writes and no conformant reader honours.
        for (mode in PressMode.entries) {
            assertEquals(mode.timings.holdMs, clampTiming(mode.timings.holdMs))
            assertEquals(mode.timings.releaseMs, clampTiming(mode.timings.releaseMs))
        }
    }

    @Test
    fun `a pair left by the millisecond pickers maps to the nearest mode`() {
        // The migration. Those pickers existed for one afternoon, but silently
        // discarding a setting somebody chose is the failure this file is
        // careful about everywhere else.
        assertEquals(PressMode.AtOnce, PressMode.nearest(PressTimings(0, 0)))
        assertEquals(PressMode.Once, PressMode.nearest(PressTimings(0, 500)))
        assertEquals(PressMode.Held, PressMode.nearest(PressTimings(500, 1000)))
        // The pair used while testing the first build, which is the one pair
        // known to exist anywhere.
        assertEquals(PressMode.Held, PressMode.nearest(PressTimings(800, 1500)))
    }

    @Test
    fun `a stored number is held to the same ceiling the format has`() {
        assertEquals(MAX_PRESS_TIMING_MS, clampTiming(60_000))
        assertEquals(0, clampTiming(-1))
        assertEquals(300, clampTiming(300))
    }
}
