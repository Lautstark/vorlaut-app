package de.lautstark.vorlaut.boardpackage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `navigate-and-speak` fixture: SPEC.md 7.3's speak-on-navigate, added in
 * 1.4.0.
 *
 * The twin of `navigate-and-append` and worth reading beside it. Same two
 * boards, same words, the same flagged-against-unflagged pair at the same
 * target; what differs is which modifier rides along, and therefore whether one
 * press leaves a word in the bar or says one out loud.
 *
 * It is the appending modifier's sibling one board model along. A board with a
 * message bar wants the entry appended on the way through; a board without one
 * — the five-key talker's four keys, where a key is a whole sentence and there
 * is nothing to compose — wants it spoken, because there is nothing for it to
 * join.
 */
class NavigateAndSpeakTest {
    private val accepted =
        BoardPackageImporter.import(Fixtures.readBytes("navigate-and-speak.obz")) as ImportResult.Accepted

    private fun button(
        boardId: String,
        buttonId: String,
    ): Button =
        accepted.boardPackage.boards
            .single { it.id == boardId }
            .buttons
            .single { it.id == buttonId }

    @Test
    fun `the scenario walk produces the stated bar, speech and board at every step`() {
        walkScenario("navigate-and-speak.obz", "navigate-and-speak.expected.json")
    }

    @Test
    fun `the flag makes a speaker of a load_board button and leaves the one beside it alone`() {
        assertEquals(
            OnActivate.SpeakThenNavigate(OnActivate.Navigate("essen")),
            button("start", "k1").onActivate,
        )
        // The same target, no flag. Plain navigation, and it must stay plain -
        // this is the pair the fixture exists for, and the only difference
        // between the two buttons is the field.
        assertEquals(OnActivate.Navigate("essen"), button("start", "k2").onActivate)
    }

    @Test
    fun `the flag beside an action is ignored, which is where it differs from its sibling`() {
        // SPEC.md 7.3 narrows this modifier to `load_board`: unlike
        // append-on-navigate it is **not** extended to `action: ":home"`, and
        // beside `:home` it MUST be ignored. e2 carries the flag and e3 does
        // not, and the fixture's point is that the two are indistinguishable.
        assertEquals(OnActivate.Home, button("essen", "e2").onActivate)
        assertEquals(OnActivate.Home, button("essen", "e3").onActivate)
    }

    @Test
    fun `the flag on a button that navigates nowhere is ignored, and in silence`() {
        // SPEC.md 7.3: no warning and no fault, exactly as the appending flag
        // is where it has no navigation. A line in front of a caregiver about a
        // button behaving as its author expects teaches them the list is noise.
        assertEquals(OnActivate.SpeakImmediately, button("start", "k4").onActivate)
        assertTrue(
            "the flag where nothing navigates must not warn",
            accepted.warnings.isEmpty(),
        )
    }

    @Test
    fun `a speaking button gets the audio the whole flag is for`() {
        // Navigation resolves no audio on its own, and this button is
        // navigation plus a word. Silent is the quiet failure: the board still
        // changes, so nothing looks broken, and the sentence that opened it was
        // never said - which leaves k1 indistinguishable from k2.
        assertEquals(AudioSource.Tts, button("start", "k1").audio)
        assertEquals(null, button("start", "k2").audio)
    }

    @Test
    fun `a speaking button says its vocalization and leaves the bar alone`() {
        // The difference from navigate-and-append's c1, and the whole of it:
        // this modifier speaks and says nothing at all about the bar.
        val bar = MessageBar()
        assertEquals("ich will", bar.press(button("start", "k1")))
        assertTrue("the speaking modifier must not touch the bar", bar.contents().isEmpty())
    }
}
