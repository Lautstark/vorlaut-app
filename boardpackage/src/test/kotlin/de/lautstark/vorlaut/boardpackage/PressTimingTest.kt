package de.lautstark.vorlaut.boardpackage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `press-timings` fixture: SPEC.md 4.1 and 7.5, added in 1.3.0.
 *
 * How long a press must rest on a button before it counts, and how long after an
 * activation the board is deaf. The first two things this format carries that
 * describe the person holding the tablet rather than the board in front of them.
 *
 * What makes the fixture an assertion about *two* fields rather than one is that
 * they hold different numbers. An importer that reads one and writes it to both
 * passes a fixture where they agree, and two adjacent integers of the same type
 * and unit are exactly where that goes wrong.
 */
class PressTimingTest {
    private val accepted =
        BoardPackageImporter.import(Fixtures.readBytes("press-timings.obz")) as ImportResult.Accepted

    private fun importedWith(vararg overrides: Pair<String, JsonElement>): BoardPackage {
        val edited = withManifest(Fixtures.readBytes("press-timings.obz"), overrides.toMap())
        return (BoardPackageImporter.import(edited) as ImportResult.Accepted).boardPackage
    }

    @Test
    fun `both timings arrive, and they do not arrive as each other`() {
        assertEquals(300, accepted.boardPackage.holdTimeMs)
        assertEquals(500, accepted.boardPackage.releaseTimeMs)
    }

    @Test
    fun `the timings change nothing about the board they are on`() {
        // SPEC.md 7.5: they say when a press counts, never what it does. So
        // every rule in 7.3 and 7.4 applies to this package unchanged, and an
        // importer that ignored both fields would produce this same model.
        val board = accepted.boardPackage.boards.single()
        assertEquals(listOf("b1", "b2"), board.buttons.map { it.id })
        assertTrue(board.buttons.all { it.onActivate == OnActivate.Append })
        assertTrue(accepted.warnings.isEmpty())
    }

    @Test
    fun `a package that says nothing is a package with both timings off`() {
        // SPEC.md 7.5: absent is 0 and 0 is off. Every fixture written before
        // 1.3.0 is this case, which is what makes the version minor - a board
        // that activates on contact is what every viewer did before now.
        val minimal = (BoardPackageImporter.import(Fixtures.readBytes("minimal.obz")) as ImportResult.Accepted)
        assertEquals(0, minimal.boardPackage.holdTimeMs)
        assertEquals(0, minimal.boardPackage.releaseTimeMs)
    }

    @Test
    fun `a timing past the ceiling is clamped rather than honoured`() {
        // The clamp is the format's, not this viewer's taste: a package is
        // authored on one machine and opened on another, and a hold time of a
        // minute is a board nobody can use. SPEC.md 7.5 puts the ceiling here
        // because here is the last place it can be enforced.
        val pkg =
            importedWith(
                "ext_lautstark_hold_time_ms" to JsonPrimitive(60_000),
                "ext_lautstark_release_time_ms" to JsonPrimitive(2001),
            )
        assertEquals(MAX_PRESS_TIMING_MS, pkg.holdTimeMs)
        assertEquals(MAX_PRESS_TIMING_MS, pkg.releaseTimeMs)
    }

    @Test
    fun `a number too large for an Int is still a number, and still clamps`() {
        // The reason Json has a long() beside its int(). Read through int(),
        // this fails toIntOrNull and reads as absent - which would turn the
        // hold *off* where the spec says pin it at the ceiling. Off is the
        // safer of the two to land on, which is why it must not be landed on
        // by accident.
        val pkg = importedWith("ext_lautstark_hold_time_ms" to JsonPrimitive(3_000_000_000L))
        assertEquals(MAX_PRESS_TIMING_MS, pkg.holdTimeMs)
    }

    @Test
    fun `a nonsense timing is off, and never a refusal`() {
        // SPEC.md 7.5 asks a reader to degrade rather than fail: not an
        // integer, or negative, is 0. Refusing the package would take a whole
        // vocabulary away from somebody over one malformed field.
        val pkg =
            importedWith(
                // A string, which is the shape a hand-edited manifest lands in.
                "ext_lautstark_hold_time_ms" to JsonPrimitive("300"),
                "ext_lautstark_release_time_ms" to JsonPrimitive(-1),
            )
        assertEquals(0, pkg.holdTimeMs)
        assertEquals(0, pkg.releaseTimeMs)

        val fractional = importedWith("ext_lautstark_hold_time_ms" to JsonPrimitive(0.4))
        assertEquals(0, fractional.holdTimeMs)
    }
}
