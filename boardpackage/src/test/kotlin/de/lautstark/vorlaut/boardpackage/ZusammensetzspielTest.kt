package de.lautstark.vorlaut.boardpackage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The compound-word game, played through.
 *
 * `tools/make-zusammensetzspiel.py` writes the real twenty-round package with
 * its recordings; this builds the same shape in three rounds and walks it, so
 * that "the flag makes the game playable" is a thing the suite says rather than
 * a thing somebody remembers checking on a tablet once.
 *
 * What it is really pinning is the chain. `navigate-and-speak` asserts one
 * flagged button against the plain one beside it, which is the right shape for a
 * conformance fixture and stops one board short of the question this repository
 * had: does a press that speaks and turns the page do it **again**, round after
 * round, and arrive at the end. A flag that classified correctly and then lost
 * the walk on round two would pass every other test here.
 *
 * See docs/zusammensetzspiel.md, which also records the three things playing it
 * turned up.
 */
class ZusammensetzspielTest {
    private companion object {
        /** The rounds, shortened. Parts, the compound they make, and three they do not. */
        val ROUNDS =
            listOf(
                Round("Spiegel", "Ei", "Spiegelei", listOf("Spiegelbild", "Eierbecher", "Schneemann")),
                Round("Hand", "Schuh", "Handschuh", listOf("Handtuch", "Schuhkarton", "Regenbogen")),
                Round("Schnee", "Mann", "Schneemann", listOf("Schneeball", "Feuerwehrmann", "Zahnbürste")),
            )

        /**
         * Which seat the right answer sits in, round by round — the first three
         * of the generator's twenty.
         *
         * Written out there and here rather than computed, because every short
         * arithmetic rotation is one a child can learn instead of the words:
         * any linear rule modulo four repeats with a period of four.
         */
        val SEATS = listOf(1, 3, 0)
    }

    data class Round(
        val partA: String,
        val partB: String,
        val correct: String,
        val wrong: List<String>,
    )

    private fun boardId(index: Int) = "runde-%02d".format(index + 1)

    /**
     * A key that says its word and then turns to [goesTo] — SPEC.md 7.3's
     * speak-on-navigate.
     *
     * A wrong answer is the same key with [goesTo] naming the board it is
     * already standing on: it speaks, and the navigation lands where it was.
     * The generator writes them that way so that all four answers wear the same
     * corner wedge; written as `speak_immediately` the right one is the only
     * `Wedge.Onward` on the board and the marking gives the answer away.
     */
    private fun speakingKey(
        id: String,
        word: String,
        goesTo: String,
    ) = """
        { "id": "$id", "label": "$word", "vocalization": "$word",
          "load_board": { "id": "$goesTo" },
          "ext_lautstark_speak_on_navigate": true }
        """.trimIndent()

    private fun board(
        index: Int,
        round: Round,
    ): String {
        val here = boardId(index)
        val onward = if (index + 1 < ROUNDS.size) boardId(index + 1) else "geschafft"
        val correctSeat = SEATS[index]
        val answers = round.wrong.toMutableList().apply { add(correctSeat, round.correct) }
        val keys =
            answers.mapIndexed { seat, word ->
                speakingKey("antwort-${seat + 1}", word, if (word == round.correct) onward else here)
            }
        val prompts =
            listOf(round.partA to "teil-a", round.partB to "teil-b").map { (word, id) ->
                """{ "id": "$id", "label": "$word", "vocalization": "$word", "ext_lautstark_speak_immediately": true }"""
            }
        val order = answers.indices.joinToString(", ") { "\"antwort-${it + 1}\"" }
        return """
            {
              "format": "open-board-0.1", "id": "$here", "locale": "de", "name": "Runde ${index + 1}",
              "buttons": [ ${(prompts + keys).joinToString(", ")} ],
              "grid": { "rows": 2, "columns": 4,
                        "order": [[null, "teil-a", "teil-b", null], [$order]] }
            }
            """.trimIndent()
    }

    private val boards: Map<String, String> =
        buildMap {
            put(
                "start",
                """
                {
                  "format": "open-board-0.1", "id": "start", "locale": "de", "name": "Zusammensetzspiel",
                  "buttons": [ ${speakingKey("los", "Los geht's", boardId(0))} ],
                  "grid": { "rows": 1, "columns": 1, "order": [["los"]] }
                }
                """.trimIndent(),
            )
            ROUNDS.forEachIndexed { index, round -> put(boardId(index), board(index, round)) }
            put(
                "geschafft",
                """
                {
                  "format": "open-board-0.1", "id": "geschafft", "locale": "de", "name": "Geschafft",
                  "buttons": [ { "id": "lob", "label": "Geschafft", "vocalization": "Geschafft",
                                 "ext_lautstark_speak_immediately": true },
                               ${speakingKey("nochmal", "Noch einmal", "start")} ],
                  "grid": { "rows": 1, "columns": 2, "order": [["lob", "nochmal"]] }
                }
                """.trimIndent(),
            )
        }

    private val accepted: ImportResult.Accepted by lazy {
        val paths = boards.keys.joinToString(", ") { "\"$it\": \"boards/$it.obf\"" }
        val manifest =
            """
            {
              "format": "open-board-0.1",
              "root": "boards/start.obf",
              "paths": { "boards": { $paths }, "images": {}, "sounds": {} },
              "ext_lautstark_spec_version": "1.4.0",
              "ext_lautstark_package_id": "b7f2c0d4-0000-4000-8000-5a6d69656c00",
              "ext_lautstark_package_name": "Zusammensetzspiel",
              "ext_lautstark_modified": "2026-08-31T12:00:00Z",
              "ext_lautstark_symbol_source": "none",
              "ext_lautstark_redistributable": true,
              "ext_lautstark_release_time_ms": 300
            }
            """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            (mapOf("manifest.json" to manifest) + boards.mapKeys { "boards/${it.key}.obf" }).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        BoardPackageImporter.import(out.toByteArray()) as ImportResult.Accepted
    }

    private fun key(
        boardId: String,
        keyId: String,
    ): Button =
        accepted.boardPackage.boards
            .single { it.id == boardId }
            .buttons
            .single { it.id == keyId }

    /** Where a press lands, which is SPEC.md 7.4's rule and not the app's. */
    private fun landing(
        button: Button,
        showing: String,
    ): String =
        when (val action = button.onActivate) {
            is OnActivate.Navigate -> action.boardId
            OnActivate.Home -> accepted.boardPackage.rootBoardId
            is OnActivate.AppendThenNavigate -> error("nothing in this game appends")
            is OnActivate.SpeakThenNavigate -> action.then.boardId
            else -> showing
        }

    @Test
    fun `the package imports clean`() {
        assertTrue(
            "a hand-built game package must import without warnings: ${accepted.warnings}",
            accepted.warnings.isEmpty(),
        )
        assertEquals("start", accepted.boardPackage.rootBoardId)
        assertEquals(ROUNDS.size + 2, accepted.boardPackage.boards.size)
    }

    /** A key found by the word it says, which is how the walk finds one too. */
    private fun saying(
        boardId: String,
        word: String,
    ): Button =
        accepted.boardPackage.boards
            .single { it.id == boardId }
            .buttons
            .single { it.spokenText == word }

    @Test
    fun `the right answer speaks its word and turns to the next round`() {
        assertEquals(
            OnActivate.SpeakThenNavigate(OnActivate.Navigate("runde-02")),
            saying("runde-01", "Spiegelei").onActivate,
        )
        // And it is not in the seat the round before it was: SEATS puts round 1
        // in the second and round 2 in the fourth. A right key that is always
        // the first is a board to be learned rather than a word.
        assertEquals("antwort-2", saying("runde-01", "Spiegelei").id)
        assertEquals("antwort-4", saying("runde-02", "Handschuh").id)
    }

    @Test
    fun `a wrong answer speaks its own word and stays standing`() {
        val wrong = saying("runde-01", "Spiegelbild")
        assertEquals(
            OnActivate.SpeakThenNavigate(OnActivate.Navigate("runde-01")),
            wrong.onActivate,
        )

        val bar = MessageBar()
        assertEquals("a wrong key says its own word", "Spiegelbild", bar.press(wrong))
        assertEquals("and lands where it was", "runde-01", landing(wrong, "runde-01"))
        assertTrue("no board in this game has a bar to fill", bar.contents().isEmpty())
    }

    @Test
    fun `the game plays through from start to geschafft, one right answer per round`() {
        val bar = MessageBar()
        var showing = accepted.boardPackage.rootBoardId

        // The start key carries the flag too: it says "Los geht's" and opens
        // round one, which is one press for what is otherwise two.
        val start = key(showing, "los")
        assertEquals("Los geht's", bar.press(start))
        showing = landing(start, showing)
        assertEquals("runde-01", showing)

        ROUNDS.forEachIndexed { index, round ->
            assertEquals("round ${index + 1} is where the walk is standing", boardId(index), showing)

            // Looked up on the board actually showing, which is what makes the
            // chaining part of the assertion: a press that should have turned
            // the page and did not fails here on a key that is not where the
            // walk is.
            val right =
                accepted.boardPackage.boards
                    .single { it.id == showing }
                    .buttons
                    .single { it.spokenText == round.correct }

            assertEquals("round ${index + 1} says its word", round.correct, bar.press(right))
            showing = landing(right, showing)
        }

        assertEquals("geschafft", showing)
        assertTrue("the bar is empty at every step of a game with no bar", bar.contents().isEmpty())
    }

    @Test
    fun `every key that speaks resolves audio, and the wedge cannot tell the answers apart`() {
        val answers =
            accepted.boardPackage.boards
                .single { it.id == "runde-01" }
                .buttons
                .filter { it.id.startsWith("antwort-") }
        assertEquals(4, answers.size)

        // All four are the same shape. This is the whole reason a wrong key
        // navigates to the board it is already on: `BoardScreen` draws
        // `Wedge.Onward` for speak-then-navigate and `Wedge.Sound` for
        // `speak_immediately`, so written the obvious way the right key would
        // be the only Onward on the board and a child who cannot read could win
        // by looking at the corners.
        assertTrue(
            "all four answers must classify alike, or the marking gives the answer away",
            answers.all { it.onActivate is OnActivate.SpeakThenNavigate },
        )

        // A key that carries the flag in order to be heard must be given
        // something to say. TTS here because the in-process package bakes no
        // clips; the generated one ships a recording per word, because this
        // viewer plays recordings and nothing else.
        assertTrue("a speaking key must resolve audio", answers.all { it.audio == AudioSource.Tts })
        assertEquals(ButtonState.NORMAL, answers.first().state)
    }

    @Test
    fun `nothing in the game appends, because no board here has a bar`() {
        val bar = MessageBar()
        accepted.boardPackage.boards
            .flatMap { it.buttons }
            .forEach { bar.press(it) }
        assertTrue(
            "the speaking modifier must not touch the bar, on any key of any board",
            bar.contents().isEmpty(),
        )
        assertNull(
            "and no key of this game is an appending one",
            accepted.boardPackage.boards
                .flatMap { it.buttons }
                .firstOrNull { it.onActivate == OnActivate.Append || it.onActivate is OnActivate.AppendThenNavigate },
        )
    }
}
