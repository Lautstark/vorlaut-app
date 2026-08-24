package de.lautstark.vorlaut.app

import de.lautstark.vorlaut.boardpackage.RejectionCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The storage half of the import path, on the JVM.
 *
 * PackageStore touches no Android API — it is handed a directory — so the rules
 * that matter most here can be checked without an emulator: that a replacement is
 * wholesale, that an older package never rolls a newer one back, and that a
 * refused package leaves what is already stored alone.
 */
class PackageStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val fixtures: File by lazy {
        val configured =
            System.getProperty("exchange.fixtures")
                ?: error("exchange.fixtures is not set; run through Gradle")
        File(configured)
    }

    private fun fixture(name: String) = fixtures.resolve(name).readBytes()

    private fun store() = PackageStore(temporaryFolder.newFolder())

    @Test
    fun `a package is installed and can be listed back`() {
        val store = store()
        val outcome = store.import(fixture("identity-a.obz"))
        assertTrue(outcome is PackageStore.Outcome.Installed)
        val stored = store.list()
        assertEquals(1, stored.size)
        assertEquals("Nursery", stored.single().boardPackage.name)
        assertTrue("the archive was not copied in", stored.single().archive.isFile)
    }

    @Test
    fun `two packages sharing a name both survive`() {
        val store = store()
        store.import(fixture("identity-a.obz"))
        store.import(fixture("identity-b.obz"))
        val stored = store.list()
        assertEquals("a name is not an identity", 2, stored.size)
        assertEquals(setOf("Nursery"), stored.map { it.boardPackage.name }.toSet())
        assertEquals(2, stored.map { it.boardPackage.id }.toSet().size)
    }

    @Test
    fun `a newer package replaces in place and takes the stale content with it`() {
        val store = store()
        store.import(fixture("identity-a.obz"))
        store.import(fixture("identity-b.obz"))
        val outcome = store.import(fixture("identity-a-v2.obz"))
        assertTrue(outcome is PackageStore.Outcome.Replaced)

        assertEquals("still two packages", 2, store.list().size)
        val replaced = store.list().single { it.boardPackage.id.endsWith("000a") }
        // Replacement is wholesale rather than a merge: a merge leaves behind
        // buttons the builder deleted, and a deleted button is deleted for a reason.
        assertEquals(
            "I would like to paint",
            replaced.boardPackage.boards
                .single()
                .buttons
                .single()
                .vocalization,
        )
        val untouched = store.list().single { it.boardPackage.id.endsWith("000b") }
        assertEquals(
            "I want to sing",
            untouched.boardPackage.boards
                .single()
                .buttons
                .single()
                .vocalization,
        )
    }

    @Test
    fun `an older package does not roll a newer one back`() {
        val store = store()
        store.import(fixture("identity-a-v2.obz"))
        val outcome = store.import(fixture("identity-a.obz"))
        assertTrue(
            "an older package must not silently replace a newer one",
            outcome is PackageStore.Outcome.AlreadyCurrent,
        )
        assertEquals(
            "I would like to paint",
            store
                .list()
                .single()
                .boardPackage.boards
                .single()
                .buttons
                .single()
                .vocalization,
        )
    }

    @Test
    fun `a refused package leaves what is already stored untouched`() {
        val store = store()
        store.import(fixture("identity-a.obz"))
        val before =
            store
                .list()
                .single()
                .boardPackage.modified

        val outcome = store.import(fixture("malformed-zip.obz"))
        assertTrue(outcome is PackageStore.Outcome.Refused)
        assertEquals(
            RejectionCode.PACKAGE_UNREADABLE,
            (outcome as PackageStore.Outcome.Refused).rejection.code,
        )
        // Nothing imported means nothing disturbed. A partial import that leaves
        // half a vocabulary in place is the failure this rule exists to prevent.
        assertEquals(1, store.list().size)
        assertEquals(
            before,
            store
                .list()
                .single()
                .boardPackage.modified,
        )
    }

    @Test
    fun `an opaque package id never becomes a path component`() {
        val store = store()
        store.import(fixture("minimal.obz"))
        val entry = store.list().single()
        assertNotNull(entry.archive)
        assertTrue(
            "the stored path escaped its directory: ${entry.archive}",
            entry.archive.canonicalPath.startsWith(temporaryFolder.root.canonicalPath),
        )
    }

    @Test
    fun `the warning list is kept with the package and survives a reload`() {
        val store = store()
        store.import(fixture("missing-audio.obz"))
        // Reachable later, from a fresh read of storage - SPEC.md 9.3 is explicit
        // that a toast at import time is not sufficient.
        val reloaded = store.list().single()
        assertEquals(
            listOf("sound_missing"),
            reloaded.warnings.map { it.code.wireName },
        )
    }
}
