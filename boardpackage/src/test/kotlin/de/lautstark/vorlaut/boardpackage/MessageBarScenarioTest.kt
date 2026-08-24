package de.lautstark.vorlaut.boardpackage

import kotlinx.serialization.json.JsonObject
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
        val result = BoardPackageImporter.import(Fixtures.readBytes("message-bar.obz"))
        val accepted = result as ImportResult.Accepted
        val buttons =
            accepted.boardPackage.boards
                .single()
                .buttons
                .associateBy { it.id }

        val bar = MessageBar()
        val scenario = Fixtures.readJson("message-bar.expected.json").array("scenario")!!
        scenario.filterIsInstance<JsonObject>().forEachIndexed { index, step ->
            val buttonId = step.string("step")!!.removePrefix("activate ")
            val button = buttons.getValue(buttonId)
            val spoken = bar.press(button)

            val wantedBar = step.array("bar").orEmpty().mapNotNull { it.textOrNull() }
            assertEquals(
                "step $index (${step.string("step")}): bar contents",
                wantedBar,
                bar.contents().mapNotNull { it.spoken },
            )
            assertEquals(
                "step $index (${step.string("step")}): what was spoken",
                step.string("spoken"),
                spoken,
            )
        }
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
