package de.lautstark.vorlaut.boardpackage

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity group (SPEC.md 8), which is the one part of the fixture set where
 * import order matters and where the assertion is about device state rather than
 * about one package.
 *
 * Getting this wrong is destructive rather than merely wrong: replacing the wrong
 * stored package takes a vocabulary somebody depends on with it.
 */
class IdentityTest {
    /** Stands in for the device's storage. Keyed on package id and nothing else. */
    private class Device {
        private val stored = LinkedHashMap<String, BoardPackage>()

        val ids: Set<String> get() = stored.keys

        fun packageWithId(id: String): BoardPackage? = stored[id]

        fun import(fixture: String): Pair<ImportResult.Accepted, ReimportDecision> {
            val result = BoardPackageImporter.import(Fixtures.readBytes("$fixture.obz"))
            assertTrue("$fixture should import", result is ImportResult.Accepted)
            val accepted = result as ImportResult.Accepted
            val known = stored.values.map { StoredPackage(it.id, it.modified) }
            val decision = decideReimport(accepted.boardPackage, known)
            when (decision) {
                is ReimportDecision.InstallNew,
                is ReimportDecision.Replace,
                -> {
                    // Replacement is wholesale, not a merge: content the new package
                    // does not contain must be gone afterwards, because a deleted
                    // button was usually deleted for a reason.
                    stored[accepted.boardPackage.id] = accepted.boardPackage
                }

                is ReimportDecision.AlreadyCurrent -> {
                    Unit
                }
            }
            return accepted to decision
        }
    }

    @Test
    fun `importing a, then b, then a-v2 leaves two packages with a replaced in place`() {
        val device = Device()

        val (a, aDecision) = device.import("identity-a")
        assertEquals(ReimportDecision.InstallNew, aDecision)

        val (b, bDecision) = device.import("identity-b")
        // Same name, different id. A fresh id means a different vocabulary that
        // happens to share a name - an importer keyed on the name would destroy A.
        assertEquals("both packages are named Nursery", a.boardPackage.name, b.boardPackage.name)
        assertEquals(ReimportDecision.InstallNew, bDecision)
        assertExpectedDevice("identity-b", device)

        val (v2, v2Decision) = device.import("identity-a-v2")
        assertTrue(
            "identity-a-v2 must replace identity-a, not sit beside it",
            v2Decision is ReimportDecision.Replace,
        )
        assertEquals(
            "it must replace A, not B",
            a.boardPackage.id,
            (v2Decision as ReimportDecision.Replace).stored.id,
        )
        assertExpectedDevice("identity-a-v2", device)

        // The old vocalization must be gone. An importer that merges instead of
        // replacing leaves behind buttons the builder deleted.
        val replaced = device.packageWithId(a.boardPackage.id)!!
        val vocalizations = replaced.boards.flatMap { it.buttons }.mapNotNull { it.vocalization }
        assertTrue(
            "stale content survived the replacement: $vocalizations",
            "I want to paint" !in vocalizations,
        )
        assertTrue("the new content is missing", "I would like to paint" in vocalizations)

        // B was untouched by all of it.
        assertEquals(
            "I want to sing",
            device
                .packageWithId(b.boardPackage.id)!!
                .boards
                .single()
                .buttons
                .single()
                .vocalization,
        )
    }

    @Test
    fun `a package that is not newer never silently replaces the stored one`() {
        val device = Device()
        device.import("identity-a-v2")
        // Re-importing the older A over the newer v2 is a downgrade, not an update.
        val (_, decision) = device.import("identity-a")
        assertTrue(
            "an older package must not be treated as an update",
            decision is ReimportDecision.AlreadyCurrent,
        )
        assertEquals(
            "the newer content must have survived",
            "I would like to paint",
            device
                .packageWithId("1f0a5c2e-0000-4000-8000-00000000000a")!!
                .boards
                .single()
                .buttons
                .single()
                .vocalization,
        )
    }

    /** Checks the device against a fixture's own `after_importing` block. */
    private fun assertExpectedDevice(
        fixture: String,
        device: Device,
    ) {
        val expected = Fixtures.readJson("$fixture.expected.json")
        val after = expected.child("after_importing") ?: return
        val wanted =
            after
                .array("packages_on_device")
                .orEmpty()
                .mapNotNull { it.textOrNull() }
                .toSet()
        assertEquals("after importing up to $fixture", wanted, device.ids)
    }

    @Test
    fun `identity-a-v2 states the resolution it must get`() {
        val reimport: JsonObject = Fixtures.readJson("identity-a-v2.expected.json").child("reimport")!!
        assertEquals("replace", reimport.string("resolution"))
        assertEquals("identity-a", reimport.string("matches"))
    }
}
