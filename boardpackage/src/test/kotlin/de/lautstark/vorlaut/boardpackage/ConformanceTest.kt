package de.lautstark.vorlaut.boardpackage

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The conformance suite. These fixtures are the acceptance criteria for the
 * importer: an importer is conformant at v1.0.0 when it produces, for each
 * fixture, the outcome in the matching `.expected.json`.
 */
class ConformanceTest {
    /**
     * Fixtures that are known not to be self-consistent, and are therefore not
     * asserted against.
     *
     * A blocked fixture is **not** a skipped one. Each entry is checked to still
     * be failing, so the day the fixture is fixed upstream this suite goes red and
     * says so — removing a block has to be a deliberate act, not something that
     * happens by drift.
     */
    private val blocked =
        mapOf(
            "multipage" to
                """
                multipage.obz and multipage.expected.json contradict each other, so no importer
                can satisfy both.

                  boards/food.obf declares board `essen` as rows 1, columns 3, with
                  order [["e1","e3","e2"]] and a button e3 (label "Café",
                  image_id "img-cafe" -> images/café.png). e3 is not hidden and the
                  grid is internally consistent, so SPEC.md 7.1 renders it.

                  multipage.expected.json declares `essen` as rows 1, columns 2 and
                  lists only e1 and e2.

                The package is almost certainly the correct half: the expectation's own
                notes discuss "Button e3 and its image images/café.png", and the spec
                README credits this fixture with covering "a non-ASCII member" - which is
                only reachable through e3. As written, the expectation makes the fixture
                cover nothing non-ASCII at all.

                It is not ours to pick a winner. SPEC.md 13's "the fixture is normative"
                rule resolves prose against fixtures, and this is fixture against fixture.
                Reported upstream; the fix is in exchange/tools/make_fixtures.mjs, whose
                `members` and `expected` are two sibling literals rather than one.
                """.trimIndent(),
        )

    @Test
    fun `every fixture in the index is accounted for`() {
        val index = Fixtures.index()
        // The spec README says "all 11" in one sentence and "12 packages" in its
        // table; SPEC.md 13 says twelve and the index has twelve. The index is the
        // machine-readable one and step 1 of the contract, so it decides.
        assertEquals("fixture count changed under the pin", 12, index.size)
        val unknownBlocks = blocked.keys - index.map { it.name }.toSet()
        assertTrue(
            "blocked fixtures that no longer exist: $unknownBlocks",
            unknownBlocks.isEmpty(),
        )
    }

    @Test
    fun `fixtures produce the outcome their expectation states`() {
        val failures = ArrayList<String>()
        for (entry in Fixtures.index()) {
            // The identity group is order-dependent and asserts device state, so it
            // has its own test rather than being checked one package at a time.
            if (entry.name.startsWith("identity-")) continue
            val expected = Fixtures.readJson(entry.expected)
            val mismatches = runFixture(entry, expected)

            if (entry.name in blocked) {
                if (mismatches.isEmpty) {
                    failures +=
                        "${entry.name} is on the blocked list but now passes. " +
                        "The upstream contradiction looks fixed - remove it from `blocked`.\n" +
                        blocked.getValue(entry.name).prependIndent("    ")
                }
                continue
            }
            if (!mismatches.isEmpty) failures += "${entry.name}:\n${mismatches.report()}"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    private fun runFixture(
        entry: FixtureEntry,
        expected: JsonObject,
    ): Mismatches {
        val mismatches = Mismatches(entry.name)
        // Fed as bytes, not unzipped first: malformed-zip tests the unzipping.
        val result = BoardPackageImporter.import(Fixtures.readBytes(entry.file))

        when (expected.string("outcome")) {
            "rejected" -> {
                if (result !is ImportResult.Rejected) {
                    mismatches.check(false) { "expected rejection, but the package was accepted" }
                    return mismatches
                }
                val code = expected.child("rejection")?.string("code")
                mismatches.check(result.code.wireName == code) {
                    "expected rejection $code, was ${result.code.wireName}"
                }
            }

            "accepted" -> {
                if (result !is ImportResult.Accepted) {
                    val rejected = result as ImportResult.Rejected
                    mismatches.check(false) {
                        "expected acceptance, was rejected: ${rejected.code.wireName} (${rejected.detail})"
                    }
                    return mismatches
                }
                checkPackage(expected, result, mismatches)
            }

            else -> {
                mismatches.check(false) { "expectation states no outcome" }
            }
        }
        return mismatches
    }

    private fun checkPackage(
        expected: JsonObject,
        result: ImportResult.Accepted,
        mismatches: Mismatches,
    ) {
        val pkg = result.boardPackage
        expected.child("package")?.let { wanted ->
            mismatches.expectString(wanted, "id", pkg.id, "package")
            mismatches.expectString(wanted, "name", pkg.name, "package")
            mismatches.expectString(wanted, "modified", pkg.modified.toString(), "package")
            mismatches.expectString(wanted, "symbol_source", pkg.symbolSource.wireName, "package")
            mismatches.expectBoolean(wanted, "redistributable", pkg.redistributable, "package")
            mismatches.expectString(wanted, "tts_voice", pkg.ttsVoice, "package")
            mismatches.expectString(wanted, "root_board", pkg.rootBoardId, "package")
        }

        // Order within boards and buttons is not significant.
        val boardsById = pkg.boards.associateBy { it.id }
        val wantedBoards = expected.array("boards").orEmpty().filterIsInstance<JsonObject>()
        mismatches.check(wantedBoards.size == pkg.boards.size) {
            "expected ${wantedBoards.size} boards, got ${pkg.boards.size} " +
                "(${pkg.boards.joinToString { it.id }})"
        }
        for (wanted in wantedBoards) {
            val id = wanted.string("id")
            val board = boardsById[id]
            if (board == null) {
                mismatches.check(false) { "board $id is missing" }
                continue
            }
            mismatches.expectString(wanted, "name", board.name, "board $id")
            mismatches.expectString(wanted, "locale", board.locale, "board $id")
            mismatches.expectInt(wanted, "rows", board.rows, "board $id")
            mismatches.expectInt(wanted, "columns", board.columns, "board $id")
            mismatches.expectString(wanted, "color", board.color, "board $id")
        }

        val buttonsByKey = pkg.boards.flatMap { board -> board.buttons.map { (board.id to it.id) to it } }.toMap()
        val wantedButtons = expected.array("buttons").orEmpty().filterIsInstance<JsonObject>()
        mismatches.check(wantedButtons.size == buttonsByKey.size) {
            "expected ${wantedButtons.size} rendered buttons, got ${buttonsByKey.size} " +
                "(${buttonsByKey.keys.joinToString { "${it.first}/${it.second}" }})"
        }
        for (wanted in wantedButtons) {
            val key = wanted.string("board") to wanted.string("id")
            val where = "button ${key.first}/${key.second}"
            val button = buttonsByKey[key]
            if (button == null) {
                mismatches.check(false) { "$where is missing" }
                continue
            }
            mismatches.expectString(wanted, "label", button.label, where)
            mismatches.expectString(wanted, "vocalization", button.vocalization, where)
            mismatches.expectString(wanted, "on_activate", button.onActivate.wireName, where)
            mismatches.expectString(wanted, "image", button.imagePath, where)
            mismatches.expectString(wanted, "audio", button.audio?.wireName, where)
            mismatches.expectString(wanted, "state", button.state.wireName, where)
        }

        checkWarnings(expected, result, mismatches)
    }

    private fun checkWarnings(
        expected: JsonObject,
        result: ImportResult.Accepted,
        mismatches: Mismatches,
    ) {
        // Compared as a set keyed on (code, board, button). `detail` is prose for a
        // human and its wording is expected to drift, so it is never compared.
        val wanted =
            expected
                .array("warnings")
                .orEmpty()
                .filterIsInstance<JsonObject>()
                .map {
                    Triple(it.string("code"), it.string("board"), it.string("button"))
                }.toSet()
        val actual =
            result.warnings
                .map {
                    Triple(it.code.wireName, it.boardId, it.buttonId)
                }.toSet()

        (wanted - actual).forEach { missing ->
            mismatches.check(false) { "expected warning $missing, which was not raised" }
        }
        (actual - wanted).forEach { extra ->
            mismatches.check(false) { "unexpected warning $extra" }
        }
    }
}
