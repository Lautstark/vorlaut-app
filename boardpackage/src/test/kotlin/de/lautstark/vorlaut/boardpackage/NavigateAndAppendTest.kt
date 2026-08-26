package de.lautstark.vorlaut.boardpackage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `navigate-and-append` fixture: SPEC.md 7.3's append-on-navigate, added in
 * 1.2.0.
 *
 * The carrier phrase. A button reading "Ich will" puts its word in the sentence
 * and opens the board its object is on, from one press — which is how a sentence
 * starter is built, and what the format could not express until this version.
 *
 * What makes the fixture an assertion about the flag rather than about
 * navigation is its pairing: `c1` and `c2` lead to the same board and differ
 * only in the field, and `e2` and `e3` do the same for `:home`.
 */
class NavigateAndAppendTest {
    private val accepted =
        BoardPackageImporter.import(Fixtures.readBytes("navigate-and-append.obz")) as ImportResult.Accepted

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
        walkScenario("navigate-and-append.obz", "navigate-and-append.expected.json")
    }

    @Test
    fun `the flag makes a carrier of a load_board button and leaves the one beside it alone`() {
        assertEquals(
            OnActivate.AppendThenNavigate(OnActivate.Navigate("essen")),
            button("start", "c1").onActivate,
        )
        // The same target, no flag. Plain navigation, and it must stay plain -
        // this is the pair the fixture exists for.
        assertEquals(OnActivate.Navigate("essen"), button("start", "c2").onActivate)
    }

    @Test
    fun `the flag rides on colon-home as well, which is the one action it may`() {
        assertEquals(
            OnActivate.AppendThenNavigate(OnActivate.Home),
            button("essen", "e2").onActivate,
        )
        assertEquals(OnActivate.Home, button("essen", "e3").onActivate)
    }

    @Test
    fun `the flag on a button that navigates nowhere is ignored, and in silence`() {
        // SPEC.md 7.3: no warning and no fault. A line in front of a caregiver
        // about a button behaving exactly as its author expects is worse than
        // nothing - it teaches them the warning list is noise.
        assertEquals(OnActivate.SpeakImmediately, button("start", "c3").onActivate)
        assertTrue(
            "the flag where nothing navigates must not warn",
            accepted.warnings.isEmpty(),
        )
    }

    @Test
    fun `a carrying button gets the audio its appending half needs`() {
        // Navigation resolves no audio, appending does, and this button is both.
        // Silent is the quiet failure: the board still changes, so nothing looks
        // broken, and the word that opened it was never said.
        assertEquals(AudioSource.Tts, button("start", "c1").audio)
        assertEquals(AudioSource.Tts, button("essen", "e2").audio)
        assertEquals(null, button("start", "c2").audio)
        assertEquals(null, button("essen", "e3").audio)
    }

    @Test
    fun `a carrying button puts its vocalization in the bar, like any other entry`() {
        val bar = MessageBar()
        bar.press(button("start", "c1"))
        assertEquals(listOf("ich will"), bar.contents().mapNotNull { it.spoken })

        // And it leaves as one press, which is what the bar holding entries is
        // for - the navigation afterwards is not part of what backspace undoes.
        bar.removeLast()
        assertTrue(bar.contents().isEmpty())
    }
}
