package de.lautstark.vorlaut.boardpackage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `unknown-ext` — the fixture that catches an importer written with a strict
 * schema validator bolted on. Every field it lists under `ignored` must be parsed
 * past with no effect and, importantly, **no warning**: an unknown field is the
 * format working as designed, and warning about it would fill the caregiver-facing
 * list with noise and train people to ignore it.
 */
class IgnoredFieldsTest {
    private val accepted =
        BoardPackageImporter.import(Fixtures.readBytes("unknown-ext.obz")) as ImportResult.Accepted

    @Test
    fun `unknown fields raise no warnings at all`() {
        assertEquals(emptyList<ImportWarning>(), accepted.warnings)
    }

    @Test
    fun `the talker's namespace gets no special handling`() {
        val button =
            accepted.boardPackage.boards
                .single()
                .buttons
                .single()
        // ext_vorlaut_color holds "#3B5BDB" and looks exactly like a colour. It is
        // the talker's namespace and must be treated as any other vendor's, so it
        // must not reach the button. The two namespaces are deliberately not
        // unified - see the spec's adr/0001.
        assertNull("ext_vorlaut_color must not be read as a button colour", button.backgroundColor)
        assertNull(button.borderColor)
    }

    @Test
    fun `the fixture's ignored list is the one this test speaks for`() {
        val ignored =
            Fixtures
                .readJson("unknown-ext.expected.json")
                .array("ignored")
                .orEmpty()
                .mapNotNull { it.textOrNull() }
        assertTrue("button f1#ext_vorlaut_color" in ignored)
        assertTrue("manifest.ext_someoneelse_tracking_id" in ignored)
        assertTrue("button f1#wibble" in ignored)
    }
}
