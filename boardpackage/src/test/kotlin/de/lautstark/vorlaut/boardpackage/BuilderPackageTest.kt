package de.lautstark.vorlaut.boardpackage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A package the builder actually wrote, opened by this importer.
 *
 * The conformance fixtures are the acceptance criteria and this is not one of
 * them. They are written by `make_fixtures.mjs`, by hand, to exercise the
 * format's corners; they say nothing about whether the one program that writes
 * packages in real life and the one program that reads them agree. Both sides
 * can pass their own suites and still not meet - vorlaut validates its output
 * against its own reading of SPEC.md, and this importer validates fixtures
 * against the same document. Two correct readings of one specification is what
 * a round trip is for.
 *
 * `builder/vorlaut-diy.obz` is therefore a **sample, not a fixture**, and it is
 * committed here on purpose despite docs/exchange-pin.md's rule against copying
 * fixtures in. The rule exists so that a spec change shows up as a failing
 * build, and a copied *fixture* silently stops tracking the spec. This file
 * tracks nothing: it is a snapshot of what one builder wrote on one day, and
 * its whole job is to be re-cut when the export changes. See
 * `builder/README.md` for where it came from and how to make a new one.
 *
 * What it caught the first time it was run, before it was committed: every
 * board said `locale: "en"` over German sentences, because the builder took the
 * field from its own page language. On a tablet that is a German board read
 * aloud in an English voice, and nothing on either side could see it - vorlaut's
 * checks passed, this importer's fixtures passed, and the field was
 * well-formed. Fixed in vorlaut-diy-talker@3006e38.
 */
class BuilderPackageTest {
    private val bytes: ByteArray by lazy {
        val stream =
            javaClass.getResourceAsStream("/builder/vorlaut-diy.obz")
                ?: error("the builder sample is missing from the test resources")
        stream.use { it.readBytes() }
    }

    private fun imported(): ImportResult.Accepted {
        val result = BoardPackageImporter.import(bytes)
        if (result is ImportResult.Rejected) {
            error("the builder's own package was rejected: ${result.code.wireName} (${result.detail})")
        }
        return result as ImportResult.Accepted
    }

    @Test
    fun `the builder's package imports, with nothing to warn about`() {
        val result = imported()
        // Not merely accepted: a warning here would mean the builder wrote
        // something a caregiver would be shown a complaint about, which for the
        // one program that is supposed to produce these is a defect either in
        // the export or in this importer.
        assertEquals("warnings on a builder-written package", emptyList<ImportWarning>(), result.warnings)
    }

    @Test
    fun `it says who it is, the way SPEC 3 and 8 require`() {
        val pkg = imported().boardPackage
        // Higher than the 1.1.0 this importer implements. SPEC.md 12 requires a
        // higher *minor* to be accepted — a minor version only adds fields and
        // actions, and 10.3 already says what to do with the ones this importer
        // does not know — so the number moving here is the rule working, not
        // drift to chase.
        assertEquals(SpecVersion(1, 2, 0), pkg.specVersion)
        // A UUID, minted with the Sammlung and never re-derived at export time.
        assertTrue("package id is not a uuid: ${pkg.id}", pkg.id.matches(Regex("[0-9a-f-]{36}")))
        assertNotNull(pkg.modified)
        // The builder writes false whatever the symbols are: the flag says "may
        // be passed on", and a package carries one person's vocabulary in one
        // person's voice. SPEC.md 5.2 only *requires* it for METACOM.
        assertEquals(false, pkg.redistributable)
        // This sample's picture was uploaded rather than picked, so it belongs
        // to no symbol collection.
        assertEquals(SymbolSource.NONE, pkg.symbolSource)
    }

    @Test
    fun `the language is the one the sentences are in`() {
        val pkg = imported().boardPackage
        // The regression this sample exists for. BoardViewModel hands
        // `root.locale` to configureVoice() as the fallback for when the named
        // voice is unavailable - which is the normal case, since the hint names
        // an Azure voice no tablet has. So this field decides how it sounds.
        assertEquals("de-DE", pkg.boards.first { it.id == pkg.rootBoardId }.locale)
        assertTrue("every board carries the locale", pkg.boards.all { it.locale == "de-DE" })
        assertEquals("de-DE-KatjaNeural", pkg.ttsVoice)
    }

    @Test
    fun `the five keys keep the positions they have on the case`() {
        val pkg = imported().boardPackage
        val root = pkg.boards.first { it.id == pkg.rootBoardId }
        assertEquals(2, root.rows)
        assertEquals(3, root.columns)
        // Top left is empty because that is where the speaker is. A viewer that
        // re-flowed these into a tidy row would take away what somebody knows
        // with their hand - so the hole is content, not an accident.
        assertNull("the speaker's corner is not a button", root.cells[0][0])
        assertEquals("set-1-key-1", root.cells[0][1])
        assertEquals("set-1-set", root.cells[1][0])
    }

    @Test
    fun `a key speaks at once and the set key navigates`() {
        val pkg = imported().boardPackage
        val root = pkg.boards.first { it.id == pkg.rootBoardId }
        val speech = root.buttons.first { it.id == "set-1-key-1" }
        // The device speaks on press and has no message bar to compose in.
        assertEquals(OnActivate.SpeakImmediately, speech.onActivate)
        assertEquals("Ich habe Hunger", speech.spokenText)
        assertNotNull("the key carries its recording", speech.audio)
        assertNotNull("and its picture", speech.imagePath)
        assertEquals(ButtonState.NORMAL, speech.state)

        // The ring: each set key names the next board, and the last comes back
        // round to the first, which is what the device does.
        val ring =
            pkg.boards.associate { board ->
                board.id to (board.buttons.first { it.id == "${board.id}-set" }.onActivate as OnActivate.Navigate).boardId
            }
        assertEquals(mapOf("set-1" to "set-2", "set-2" to "set-1"), ring)
    }

    @Test
    fun `every picture and recording it promises is in the archive`() {
        val result = imported()
        val archive = PackageArchive.open(bytes) ?: error("the sample is not a readable archive")
        for (board in result.boardPackage.boards) {
            for (button in board.buttons) {
                button.imagePath?.let {
                    assertNotNull("picture missing from the archive: $it", archive.read(it))
                }
                (button.audio as? AudioSource.Recorded)?.let {
                    val clip = archive.read(it.path) ?: error("clip missing from the archive: ${it.path}")
                    // Ogg Opus, written by the browser's own encoder. Checking the
                    // container here rather than trusting the extension: this is
                    // the one file type in a package that no test on either side
                    // decodes, and a truncated stream would look fine by name.
                    assertEquals("OggS", String(clip.copyOfRange(0, 4), Charsets.US_ASCII))
                    assertEquals("OpusHead", String(clip.copyOfRange(28, 36), Charsets.US_ASCII))
                }
            }
        }
    }
}
