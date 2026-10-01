package de.lautstark.vorlaut.app

import de.lautstark.vorlaut.boardpackage.RejectionCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

    /**
     * A fixture with its package id swapped. The fixtures' ids are UUIDs and
     * never collide; the ids a builder may legally write are any string, and
     * the ones that broke the old directory naming are the ones that differ
     * only in punctuation.
     */
    private fun withId(
        name: String,
        id: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            ZipInputStream(fixture(name).inputStream()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    var data = zin.readBytes()
                    if (entry.name == "manifest.json") {
                        data =
                            Regex("\"ext_lautstark_package_id\"\\s*:\\s*\"[^\"]*\"")
                                .replace(String(data), "\"ext_lautstark_package_id\": \"$id\"")
                                .toByteArray()
                    }
                    zout.putNextEntry(ZipEntry(entry.name))
                    zout.write(data)
                    zout.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Puts a package into [root] exactly the way PackageStore did before
     * directory names carried a hash: the id with everything but letters,
     * digits and `-` flattened to `_`, cut at 64. Spelled out here rather than
     * borrowed from the store, because the point is to pin down what tablets
     * in use already hold, and that does not change when the store does.
     */
    private fun installTheOldWay(
        root: File,
        bytes: ByteArray,
        id: String,
    ): File {
        val name = id.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("").take(64)
        val directory = File(File(root, "packages"), name)
        directory.mkdirs()
        File(directory, "package.obz").writeBytes(bytes)
        return directory
    }

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

    @Test
    fun `two ids that differ only in punctuation are two packages`() {
        val store = store()
        assertTrue(store.import(withId("identity-a.obz", "family.home")) is PackageStore.Outcome.Installed)
        // This used to come back Replaced, and family.home was gone: both ids
        // flattened to one directory name. SPEC.md 8 makes them strangers.
        assertTrue(store.import(withId("identity-b.obz", "family_home")) is PackageStore.Outcome.Installed)
        assertEquals(setOf("family.home", "family_home"), store.list().map { it.boardPackage.id }.toSet())
    }

    @Test
    fun `two long ids sharing their first 64 characters are two packages`() {
        val store = store()
        val stem = "x".repeat(70)
        store.import(withId("identity-a.obz", "${stem}1"))
        store.import(withId("identity-b.obz", "${stem}2"))
        assertEquals(2, store.list().size)
    }

    @Test
    fun `a package stored under the old directory name is still listed`() {
        val root = temporaryFolder.newFolder()
        installTheOldWay(root, fixture("identity-a.obz"), ID_A)
        val store = PackageStore(root)
        assertEquals(listOf(ID_A), store.list().map { it.boardPackage.id })
        assertTrue(store.list().single().archive.isFile)
    }

    @Test
    fun `a package stored under the old name is updated, not duplicated`() {
        val root = temporaryFolder.newFolder()
        val old = installTheOldWay(root, fixture("identity-a.obz"), ID_A)
        val store = PackageStore(root)

        val outcome = store.import(fixture("identity-a-v2.obz"))
        assertTrue("found the old copy as the stored one: $outcome", outcome is PackageStore.Outcome.Replaced)
        assertEquals("one id, one package", 1, store.list().size)
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
        assertTrue("the old-named copy was migrated away, not left beside", !old.exists())

        // And an older one still does not roll it back, now that it lives under
        // its new name.
        assertTrue(store.import(fixture("identity-a.obz")) is PackageStore.Outcome.AlreadyCurrent)
    }

    @Test
    fun `a package stored under the old name can be removed`() {
        val root = temporaryFolder.newFolder()
        installTheOldWay(root, fixture("identity-a.obz"), ID_A)
        val store = PackageStore(root)
        store.remove(ID_A)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `a package under the old name of a different id is never deleted`() {
        // The tablet holds family.home in the directory the old naming gave it,
        // `family_home`. Now family_home arrives: the old code would have found
        // its own name taken and deleted what was there.
        val root = temporaryFolder.newFolder()
        installTheOldWay(root, withId("identity-a.obz", "family.home"), "family.home")
        val store = PackageStore(root)

        assertTrue(store.import(withId("identity-b.obz", "family_home")) is PackageStore.Outcome.Installed)
        assertEquals(setOf("family.home", "family_home"), store.list().map { it.boardPackage.id }.toSet())

        // Removing one leaves the other.
        store.remove("family_home")
        assertEquals(listOf("family.home"), store.list().map { it.boardPackage.id })
    }

    private companion object {
        const val ID_A = "1f0a5c2e-0000-4000-8000-00000000000a"
    }
}
