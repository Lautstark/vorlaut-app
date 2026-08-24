package de.lautstark.vorlaut.boardpackage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Rules SPEC.md states as MUSTs and the fixture set does not reach.
 *
 * The spec's README is candid that the fixtures overstate nothing and cover less
 * than the document does — there is no fixture for zip-slip, for Zip64, for a
 * licence-inconsistent package, for a future major version, or for a malformed
 * grid. Those are exactly the paths that reject a package outright, so leaving
 * them to a first real encounter is how an importer discovers them on somebody's
 * tablet. The archives here are built in-process rather than committed, so this
 * stays a test of the rules and not a second, unpinned fixture set.
 */
class SpecRulesTest {
    private fun archive(vararg members: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            members.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(
        specVersion: String = "1.0.0",
        format: String = "open-board-0.1",
        symbolSource: String = "arasaac",
        redistributable: Boolean = true,
    ) = """
        {
          "format": "$format",
          "root": "boards/b.obf",
          "paths": { "boards": { "b": "boards/b.obf" }, "images": {}, "sounds": {} },
          "ext_lautstark_spec_version": "$specVersion",
          "ext_lautstark_package_id": "test-id",
          "ext_lautstark_package_name": "Test",
          "ext_lautstark_modified": "2026-08-24T09:00:00Z",
          "ext_lautstark_symbol_source": "$symbolSource",
          "ext_lautstark_redistributable": $redistributable
        }
        """.trimIndent()

    private fun board(
        rows: Int = 1,
        columns: Int = 1,
        order: String = """[["b1"]]""",
    ) = """
        {
          "format": "open-board-0.1",
          "id": "b",
          "locale": "en",
          "name": "Board",
          "buttons": [ { "id": "b1", "label": "Hello" } ],
          "grid": { "rows": $rows, "columns": $columns, "order": $order }
        }
        """.trimIndent()

    private fun rejectionOf(bytes: ByteArray): RejectionCode? = (BoardPackageImporter.import(bytes) as? ImportResult.Rejected)?.code

    @Test
    fun `a member name that escapes the archive root is refused`() {
        // Zip-slip. On Android this writes outside the app's storage, which is why
        // SPEC.md 2 requires rejecting the package without extracting it.
        val bytes =
            archive(
                "manifest.json" to manifest(),
                "boards/b.obf" to board(),
                "../escape.png" to "x",
            )
        assertEquals(RejectionCode.PATH_UNSAFE, rejectionOf(bytes))
    }

    @Test
    fun `a nested traversal segment is refused too`() {
        val bytes =
            archive(
                "manifest.json" to manifest(),
                "boards/b.obf" to board(),
                "images/../../escape.png" to "x",
            )
        assertEquals(RejectionCode.PATH_UNSAFE, rejectionOf(bytes))
    }

    @Test
    fun `an absolute member name is refused`() {
        val bytes =
            archive(
                "manifest.json" to manifest(),
                "boards/b.obf" to board(),
                "/etc/passwd" to "x",
            )
        assertEquals(RejectionCode.PATH_UNSAFE, rejectionOf(bytes))
    }

    @Test
    fun `a metacom package that claims to be redistributable is refused`() {
        // SPEC.md 5.2. METACOM is licensed per person; baking its pixels into a
        // file that may then travel hands the collection over. This is a licensing
        // decision taken deliberately and is not to be relaxed in an implementation.
        val bytes =
            archive(
                "manifest.json" to manifest(symbolSource = "metacom", redistributable = true),
                "boards/b.obf" to board(),
            )
        assertEquals(RejectionCode.LICENCE_INCONSISTENT, rejectionOf(bytes))
    }

    @Test
    fun `a metacom package that is honest about redistribution imports`() {
        val bytes =
            archive(
                "manifest.json" to manifest(symbolSource = "metacom", redistributable = false),
                "boards/b.obf" to board(),
            )
        val result = BoardPackageImporter.import(bytes)
        assertTrue(result is ImportResult.Accepted)
        val pkg = (result as ImportResult.Accepted).boardPackage
        assertEquals(SymbolSource.METACOM, pkg.symbolSource)
        // The flag must be stored with the package rather than discarded once the
        // import is done: the constraint has to outlive the import, because the
        // feature that would violate it will be written by someone who was not
        // here for this decision.
        assertEquals(false, pkg.redistributable)
    }

    @Test
    fun `a higher major version is refused and a higher minor version is not`() {
        // SPEC.md 12: a major bump means a package valid under the old version
        // would be misread under the new one. A minor bump only adds fields and
        // actions, and SPEC.md 10.3 already says what to do with those.
        assertEquals(
            RejectionCode.SPEC_VERSION_UNSUPPORTED,
            rejectionOf(archive("manifest.json" to manifest(specVersion = "2.0.0"), "boards/b.obf" to board())),
        )
        val forward =
            BoardPackageImporter.import(
                archive("manifest.json" to manifest(specVersion = "1.9.0"), "boards/b.obf" to board()),
            )
        assertTrue("a higher minor version must still import", forward is ImportResult.Accepted)
    }

    @Test
    fun `a grid that does not match its own dimensions is a package-level fault`() {
        // SPEC.md 7.1: a viewer guessing at the structure would place buttons
        // somewhere other than where the builder put them, and for someone
        // navigating by position that is worse than no board at all.
        val tooFewRows =
            archive(
                "manifest.json" to manifest(),
                "boards/b.obf" to board(rows = 2, columns = 1, order = """[["b1"]]"""),
            )
        assertEquals(RejectionCode.GRID_MALFORMED, rejectionOf(tooFewRows))

        val wrongRowWidth =
            archive(
                "manifest.json" to manifest(),
                "boards/b.obf" to board(rows = 1, columns = 3, order = """[["b1","b2"]]"""),
            )
        assertEquals(RejectionCode.GRID_MALFORMED, rejectionOf(wrongRowWidth))
    }

    @Test
    fun `a package with the wrong format or no manifest is refused`() {
        assertEquals(
            RejectionCode.FORMAT_UNSUPPORTED,
            rejectionOf(archive("manifest.json" to manifest(format = "open-board-0.2"), "boards/b.obf" to board())),
        )
        assertEquals(
            RejectionCode.MANIFEST_MISSING,
            rejectionOf(archive("boards/b.obf" to board())),
        )
        assertEquals(
            RejectionCode.PACKAGE_UNREADABLE,
            rejectionOf("this is not a zip at all".toByteArray()),
        )
    }

    @Test
    fun `a root that is not in paths-boards is refused`() {
        val bytes =
            archive(
                "manifest.json" to manifest().replace("\"root\": \"boards/b.obf\"", "\"root\": \"boards/absent.obf\""),
                "boards/b.obf" to board(),
            )
        assertEquals(RejectionCode.ROOT_MISSING, rejectionOf(bytes))
    }

    @Test
    fun `a button named by the grid but never defined leaves an empty cell and warns`() {
        val bytes =
            archive(
                "manifest.json" to manifest(),
                "boards/b.obf" to board(rows = 1, columns = 2, order = """[["b1","ghost"]]"""),
            )
        val accepted = BoardPackageImporter.import(bytes) as ImportResult.Accepted
        assertEquals(
            1,
            accepted.boardPackage.boards
                .single()
                .buttons.size,
        )
        assertEquals(
            listOf(Triple(WarningCode.BUTTON_MISSING, "b", "ghost")),
            accepted.warnings.map { it.identity },
        )
    }

    @Test
    fun `a hidden button and one left out of the grid are both unrendered`() {
        val document =
            """
            {
              "format": "open-board-0.1", "id": "b", "locale": "en", "name": "Board",
              "buttons": [
                { "id": "b1", "label": "Shown" },
                { "id": "b2", "label": "Hidden", "hidden": true },
                { "id": "b3", "label": "Not in the grid" }
              ],
              "grid": { "rows": 1, "columns": 2, "order": [["b1", "b2"]] }
            }
            """.trimIndent()
        val accepted =
            BoardPackageImporter.import(
                archive("manifest.json" to manifest(), "boards/b.obf" to document),
            ) as ImportResult.Accepted
        assertEquals(
            listOf("b1"),
            accepted.boardPackage.boards
                .single()
                .buttons
                .map { it.id },
        )
        // Neither is a fault: SPEC.md 7.1 and 7.2 say both simply do not render.
        assertEquals(emptyList<ImportWarning>(), accepted.warnings)
    }
}
