package de.lautstark.vorlaut.boardpackage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tablet package the builder actually wrote, opened by this importer.
 *
 * The sibling of [BuilderPackageTest], and the argument for it is the same one:
 * the conformance fixtures say what the format is, and they say nothing about
 * whether the one program that writes packages and the one program that reads
 * them agree. Both sides can pass their own suites against two correct readings
 * of one specification and still not meet.
 *
 * What is different is what it covers. [BuilderPackageTest] opens a *talker*
 * Sammlung — five keys in a fixed 2x3 with the speaker's corner empty, every
 * button speaking at once, and a ring of `load_board`s. That exercises about a
 * third of what SPEC.md describes, because a five-key device has no use for the
 * rest. This one opens a tablet Sammlung, which uses the other two thirds: an
 * arbitrary grid, buttons that compose into the message bar, `background_color`
 * carrying a word class, `:speak`, `:clear` and `:home`, and navigation between
 * pages that is not a ring.
 *
 * `builder/vorlaut-tablet.obz` is therefore a **sample, not a fixture**, for
 * exactly the reason the one beside it is. docs/exchange-pin.md forbids copying
 * the conformance fixtures in, because a copied fixture stops tracking the spec
 * and then passes forever. This file tracks nothing: it is a snapshot of what
 * one builder wrote on one day, and its whole job is to be re-cut when the
 * export changes. See `builder/README.md`.
 */
class BuilderTabletPackageTest {
    private val bytes: ByteArray by lazy {
        val stream =
            javaClass.getResourceAsStream("/builder/vorlaut-tablet.obz")
                ?: error("the tablet sample is missing from the test resources")
        stream.use { it.readBytes() }
    }

    private fun imported(): ImportResult.Accepted {
        val result = BoardPackageImporter.import(bytes)
        if (result is ImportResult.Rejected) {
            error("the builder's own package was rejected: ${result.code.wireName} (${result.detail})")
        }
        return result as ImportResult.Accepted
    }

    private fun board(id: String) = imported().boardPackage.boards.first { it.id == id }

    private fun button(
        boardId: String,
        buttonId: String,
    ) = board(boardId).buttons.first { it.id == buttonId }

    @Test
    fun `the builder's tablet package imports, with nothing to warn about`() {
        val result = imported()
        // Not merely accepted. A warning here would mean the builder wrote
        // something a caregiver would be shown a complaint about, which for the
        // one program that is supposed to produce these is a defect on one side
        // or the other.
        assertEquals("warnings on a builder-written package", emptyList<ImportWarning>(), result.warnings)
    }

    @Test
    fun `it says who it is, the way SPEC 3 and 8 require`() {
        val pkg = imported().boardPackage
        assertEquals(SpecVersion(1, 0, 0), pkg.specVersion)
        assertTrue("package id is not a uuid: ${pkg.id}", pkg.id.matches(Regex("[0-9a-f-]{36}")))
        assertNotNull(pkg.modified)
        // False whatever the symbols are: the flag says "may be passed on", and a
        // package carries one person's vocabulary in one person's voice.
        assertEquals(false, pkg.redistributable)
        // The picture on this board was uploaded rather than picked, so it
        // belongs to no symbol collection at all — SPEC.md 5.1's third value.
        assertEquals(SymbolSource.NONE, pkg.symbolSource)
        assertEquals("de-DE-KatjaNeural", pkg.ttsVoice)
    }

    @Test
    fun `the grid is the one the builder chose, not one this importer assumed`() {
        val pkg = imported().boardPackage
        // The whole point of the second sample. The talker's grid is 2x3 and
        // always 2x3; a tablet Sammlung carries its own size, and every board in
        // it carries the same one — which is what makes a button's position
        // learnable across pages.
        assertEquals(2, pkg.boards.size)
        for (one in pkg.boards) {
            assertEquals("rows on ${one.id}", 3, one.rows)
            assertEquals("columns on ${one.id}", 5, one.columns)
            assertEquals("row count on ${one.id}", 3, one.cells.size)
            for (row in one.cells) assertEquals("cells in a row of ${one.id}", 5, row.size)
        }
        // A cell nothing sits in is null rather than a button with nothing on
        // it, and a board may be mostly empty.
        val start = board("board-1")
        assertEquals("board-1-r1c1", start.cells[0][0])
        assertNull("an empty cell is empty", start.cells[1][0])
        assertEquals("board-1-r3c1", start.cells[2][0])
    }

    @Test
    fun `buttons compose into the message bar, which the talker's never do`() {
        // The default and the common case on a tablet, and the thing a five-key
        // package cannot demonstrate: every button in BuilderPackageTest is
        // SpeakImmediately, because the device has no bar to compose in.
        assertEquals(OnActivate.Append, button("board-1", "board-1-r1c1").onActivate)
        assertEquals("ich", button("board-1", "board-1-r1c1").spokenText)
    }

    @Test
    fun `a button says what its vocalization says, not what its label shows`() {
        val apple = button("board-1", "board-1-r1c3")
        // SPEC.md 7.3, and the case the message-bar fixture is normative about:
        // a button whose label is one word and whose vocalization is a phrase
        // puts the phrase in the bar, so the bar reads as the sentence it is
        // about to say rather than as the row of keys that built it.
        assertEquals("Apfel", apple.label)
        assertEquals("einen Apfel", apple.vocalization)
        assertEquals("einen Apfel", apple.spokenText)
    }

    @Test
    fun `the three bar actions and navigation all arrive`() {
        // SPEC.md 7.4. None of these can appear in a talker package at all, so
        // this is the first time the builder and this importer have had to agree
        // about any of them.
        assertEquals(OnActivate.SpeakBar, button("board-1", "board-1-r3c1").onActivate)
        assertEquals(OnActivate.Clear, button("board-1", "board-1-r3c2").onActivate)
        assertEquals(OnActivate.Home, button("board-2", "board-2-r3c5").onActivate)

        // Navigation between pages, which is a graph rather than the device's
        // ring: one button leads from the start page to the food page, and
        // nothing leads back except `:home`.
        val toFood = button("board-1", "board-1-r1c4").onActivate
        assertTrue("not a navigation: $toFood", toFood is OnActivate.Navigate)
        assertEquals("board-2", (toFood as OnActivate.Navigate).boardId)
        assertEquals(imported().boardPackage.rootBoardId, "board-1")
    }

    @Test
    fun `a word class arrives as a background colour`() {
        val pkg = imported().boardPackage
        // The Modified Fitzgerald Key, which is how German AAC boards are
        // coloured. The builder stores the class and resolves it to a colour on
        // the way out, so what has to survive here is the colour: this importer
        // has no idea what a word class is and should not learn.
        //
        // These are AsTeRICS Grid's light ramp. If they move, they move in the
        // builder and this sample is re-cut with them.
        assertEquals("#FDFD96", button("board-1", "board-1-r1c1").backgroundColor) // pronoun
        assertEquals("#C7F3C7", button("board-1", "board-1-r1c2").backgroundColor) // verb
        assertEquals("#FFDA89", button("board-1", "board-1-r1c3").backgroundColor) // noun
        assertEquals("#D8AF97", button("board-1", "board-1-r1c4").backgroundColor) // category
        // Every board still carries its own whole-page colour, which is a
        // different job: word classes colour a word, this colours a place.
        assertEquals("#3B5BDB", board("board-1").color)
        assertEquals("#159947", board("board-2").color)
        assertTrue(
            "board colours are not distinct",
            pkg.boards
                .map { it.color }
                .toSet()
                .size == 2,
        )
    }

    @Test
    fun `only the buttons that speak carry a clip`() {
        val pkg = imported().boardPackage
        // The builder bakes a clip where pressing the button speaks its own
        // text, and nowhere else. BoardViewModel utters on Append and on
        // SpeakImmediately; navigation is silent and `:speak` always synthesises
        // the composed sentence, because the bar has no clip of its own. A clip
        // on any of those would be an archive member nothing can play, on a
        // board that may carry hundreds of buttons.
        val speaks = listOf("board-1-r1c1", "board-1-r1c2", "board-1-r1c3")
        for (id in speaks) {
            assertTrue("no clip on $id", button("board-1", id).audio is AudioSource.Recorded)
        }
        for (id in listOf("board-1-r1c4", "board-1-r3c1", "board-1-r3c2")) {
            assertNull("a clip on a button that never speaks: $id", button("board-1", id).audio)
        }
        assertNull("a clip on :home", button("board-2", "board-2-r3c5").audio)

        // Two of the three clips are one member of the archive: the sample's
        // stand-in synthesiser answers two of these sentences identically, and
        // content-addressed naming quite correctly writes one file for them.
        // Asserted because it is the behaviour rather than an accident.
        val paths =
            pkg.boards
                .flatMap { it.buttons }
                .mapNotNull { (it.audio as? AudioSource.Recorded)?.path }
        assertTrue("no clips at all", paths.isNotEmpty())
        assertTrue("clips were not de-duplicated", paths.toSet().size < paths.size)
    }

    @Test
    fun `every picture and recording it promises is in the archive`() {
        val result = imported()
        val archive = PackageArchive.open(bytes) ?: error("the sample is not a readable archive")
        var pictures = 0
        for (board in result.boardPackage.boards) {
            for (button in board.buttons) {
                button.imagePath?.let {
                    pictures++
                    assertNotNull("picture missing from the archive: $it", archive.read(it))
                }
                (button.audio as? AudioSource.Recorded)?.let {
                    val clip = archive.read(it.path) ?: error("clip missing from the archive: ${it.path}")
                    // Ogg Opus, written by the browser's own encoder. Checking the
                    // container rather than trusting the extension: this is the one
                    // file type in a package that no test on either side decodes,
                    // and a truncated stream would look fine by name.
                    assertEquals("OggS", String(clip.copyOfRange(0, 4), Charsets.US_ASCII))
                    assertEquals("OpusHead", String(clip.copyOfRange(28, 36), Charsets.US_ASCII))
                }
            }
        }
        // A tablet package with no pictures in it never asks this importer to
        // resolve one, which is most of what an image path is for.
        assertTrue("the sample carries no pictures", pictures > 0)
    }
}
