package de.lautstark.vorlaut.boardpackage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `message-bar` fixture's `scenario`: an ordered walk through button presses
 * with the bar contents after each. Unlike boards and buttons, scenario order is
 * significant.
 */
class MessageBarScenarioTest {
    @Test
    fun `the scenario walk produces the stated bar and speech at every step`() {
        walkScenario("message-bar.obz", "message-bar.expected.json")
    }

    @Test
    fun `an entry keeps the recording its press came from, or none`() {
        // The bar has to be able to say a sentence in the package's own voice,
        // so every entry remembers which clip it arrived on and not only what it
        // would say without one. This fixture's buttons carry no audio, which is
        // the half that has to stay null: those words fall to the device voice,
        // in place, and the sentence does not stop at them.
        val accepted = BoardPackageImporter.import(Fixtures.readBytes("message-bar.obz")) as ImportResult.Accepted
        val buttons =
            accepted.boardPackage.boards
                .single()
                .buttons
                .associateBy { it.id }
        val bar = MessageBar()
        bar.press(buttons.getValue("w3"))
        val entry = bar.contents().single()
        assertEquals(null, entry.soundPath)
        assertEquals("einen Apfel", entry.spoken)
    }

    @Test
    fun `backspace removes a whole entry and not a character`() {
        val accepted = BoardPackageImporter.import(Fixtures.readBytes("message-bar.obz")) as ImportResult.Accepted
        val buttons =
            accepted.boardPackage.boards
                .single()
                .buttons
                .associateBy { it.id }
        val bar = MessageBar()

        // w3's label is one word and its vocalization is two. The press arrived as
        // one thing and has to leave as one - this is the whole reason the bar
        // holds entries rather than words.
        bar.press(buttons.getValue("w3"))
        assertEquals(listOf("einen Apfel"), bar.contents().mapNotNull { it.spoken })
        bar.press(buttons.getValue("a3"))
        assertTrue("backspace must remove the entire entry", bar.contents().isEmpty())
    }

    @Test
    fun `German text survives the whole path from archive to bar`() {
        val accepted = BoardPackageImporter.import(Fixtures.readBytes("message-bar.obz")) as ImportResult.Accepted
        val labels =
            accepted.boardPackage.boards
                .single()
                .buttons
                .map { it.label }
        // An importer that mangles UTF-8 somewhere between the zip and the bar has
        // to fail on the text that actually ships, not pass on an ASCII stand-in.
        assertTrue("umlauts did not survive", "Löschen" in labels)
        assertTrue("umlauts did not survive", "Zurück" in labels)
        assertTrue("the eszett did not survive", "Fußball" in labels)
    }
}
