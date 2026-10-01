package de.lautstark.vorlaut.app

import de.lautstark.vorlaut.boardpackage.BoardPackage
import de.lautstark.vorlaut.boardpackage.BoardPackageImporter
import de.lautstark.vorlaut.boardpackage.ImportResult
import de.lautstark.vorlaut.boardpackage.ImportWarning
import de.lautstark.vorlaut.boardpackage.ReimportDecision
import de.lautstark.vorlaut.boardpackage.StoredPackage
import de.lautstark.vorlaut.boardpackage.decideReimport
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Keeps imported packages in app-private storage.
 *
 * Everything lands under the app's own files directory, which is what makes the
 * viewer's promise keepable: SPEC.md 5.2 says a non-redistributable package must
 * not take any path that moves its bytes off the device.
 *
 * "Plus no network permission" used to be the third clause here and is not true
 * any more — the app takes a package over the LAN now. What is left is
 * app-private storage, no backup, and a receiver with one route that only goes
 * inwards; see AndroidManifest.xml, which carries the whole of that argument,
 * and PackageReceiverTest, which is what holds it.
 */
class PackageStore(
    private val root: File,
) {
    /** One stored package: the original bytes, plus what parsing them produced. */
    data class Entry(
        val boardPackage: BoardPackage,
        val warnings: List<ImportWarning>,
        val archive: File,
    )

    sealed interface Outcome {
        data class Installed(
            val entry: Entry,
        ) : Outcome

        data class Replaced(
            val entry: Entry,
            val previous: StoredPackage,
        ) : Outcome

        /**
         * The stored copy is the same age or newer. SPEC.md 8 forbids silently
         * replacing it — treating an older package as an update is how a
         * vocabulary gets quietly rolled back.
         */
        data class AlreadyCurrent(
            val incoming: BoardPackage,
            val stored: StoredPackage,
        ) : Outcome

        data class Refused(
            val rejection: ImportResult.Rejected,
        ) : Outcome
    }

    private val packagesDir = File(root, "packages")
    private val stagingDir = File(root, "staging")

    /**
     * Ordered by package id, not by directory name. The two used to be the
     * same order, because the directory *was* the id with its punctuation
     * flattened; now that a directory name ends in a hash, sorting by it would
     * shuffle the list the day a tablet migrates, for no reason a person could
     * see.
     */
    fun list(): List<Entry> = stored().map { it.second }.sortedBy { it.boardPackage.id }

    /** Every readable package directory, with what was read out of it. */
    private fun stored(): List<Pair<File, Entry>> =
        packagesDir
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { directory -> read(directory)?.let { directory to it } }

    /**
     * The directory currently holding package [id], whatever it is called.
     *
     * Found by the id inside the archive rather than computed from the id,
     * because a tablet that has been in use since before [directoryNameFor]
     * changed holds its packages under the old, lossy names — and those have
     * to keep opening, updating and being removed as if nothing had happened.
     * The manifest is the one name for a package that has never changed.
     */
    private fun locate(id: String): File? = stored().firstOrNull { it.second.boardPackage.id == id }?.first

    private fun read(directory: File): Entry? {
        val archive = File(directory, ARCHIVE_NAME).takeIf { it.isFile } ?: return null
        // Re-parsed on read rather than cached as a sidecar file. The parse is
        // cheap and a cache would be a second source of truth that can disagree
        // with the bytes it describes.
        val result = BoardPackageImporter.import(archive.readBytes())
        return (result as? ImportResult.Accepted)?.let {
            Entry(it.boardPackage, it.warnings, archive)
        }
    }

    fun import(bytes: ByteArray): Outcome {
        val result = BoardPackageImporter.import(bytes)
        if (result is ImportResult.Rejected) return Outcome.Refused(result)
        val accepted = result as ImportResult.Accepted
        val incoming = accepted.boardPackage

        val stored = list().map { StoredPackage(it.boardPackage.id, it.boardPackage.modified) }
        return when (val decision = decideReimport(incoming, stored)) {
            is ReimportDecision.AlreadyCurrent -> {
                Outcome.AlreadyCurrent(incoming, decision.stored)
            }

            is ReimportDecision.InstallNew -> {
                val directory = commit(incoming, bytes)
                Outcome.Installed(Entry(incoming, accepted.warnings, File(directory, ARCHIVE_NAME)))
            }

            is ReimportDecision.Replace -> {
                val directory = commit(incoming, bytes)
                Outcome.Replaced(
                    Entry(incoming, accepted.warnings, File(directory, ARCHIVE_NAME)),
                    decision.stored,
                )
            }
        }
    }

    /**
     * Writes the package somewhere else entirely, then swaps it in.
     *
     * SPEC.md 8: replacement must be wholesale rather than a merge, and atomic —
     * a failure partway must leave the previously stored package intact, because
     * the device must never end an import with no working vocabulary. So the new
     * copy is staged complete, the old directory is moved aside rather than
     * deleted, and only once the new one is in place is the old one removed.
     *
     * What gets moved aside is the directory that *holds this id*, found by
     * reading it ([locate]) — never whatever happens to sit at the name this id
     * would be given. Those were once the same thing, and that is how
     * `family.home` arriving deleted `family_home`: both flattened to one
     * directory name, and the second import replaced the first package as if it
     * were an update to it. A package installed under its old name is
     * replaced by one under its new name, so a tablet migrates one Sammlung at
     * a time, as each is next updated, and needs no migration step of its own.
     *
     * Returns the directory the package now lives in.
     */
    private fun commit(
        boardPackage: BoardPackage,
        bytes: ByteArray,
    ): File {
        packagesDir.mkdirs()
        stagingDir.mkdirs()
        val name = directoryNameFor(boardPackage.id)
        val staged = File(stagingDir, "$name-${System.nanoTime()}")
        staged.deleteRecursively()
        staged.mkdirs()
        File(staged, ARCHIVE_NAME).writeBytes(bytes)

        val previous = locate(boardPackage.id)
        val destination = File(packagesDir, name)
        // Something already at the new name that is not this package. With a
        // full SHA-256 in the name that is no longer a collision between ids;
        // it would be a directory nobody can read. Whatever it is, it is not
        // ours to delete — SPEC.md 8 is about one id replacing itself, and a
        // directory whose package we cannot name is not provably that.
        if (destination.exists() && destination != previous) {
            staged.deleteRecursively()
            error("could not install package ${boardPackage.id}: ${destination.name} is occupied")
        }
        val displaced =
            previous?.let { old ->
                File(stagingDir, "${old.name}-displaced-${System.nanoTime()}").takeIf { old.renameTo(it) }
            }
        // The old copy would not move. Carrying on would either fail at the
        // rename below or, for a package under its old name, leave two copies
        // of one id behind; stopping here leaves the old one exactly as it was.
        if (previous != null && displaced == null) {
            staged.deleteRecursively()
            error("could not install package ${boardPackage.id}")
        }
        if (!staged.renameTo(destination)) {
            // Put the old one back before giving up. Ending here with neither is
            // the outcome the atomicity rule exists to prevent.
            if (previous != null) displaced?.renameTo(previous)
            staged.deleteRecursively()
            error("could not install package ${boardPackage.id}")
        }
        displaced?.deleteRecursively()
        return destination
    }

    /**
     * Forgets a Sammlung entirely.
     *
     * Moved aside first and deleted after, the same way [commit] replaces one:
     * a half-deleted directory would be read back on the next listing as a
     * package with no archive in it. There is no undo and the caller is
     * expected to have asked.
     */
    fun remove(id: String) {
        // Located by what it holds, for the same reason [commit] does it: the
        // directory may carry an old-style name, and the old-style name of
        // this id may belong to a different package.
        val directory = locate(id) ?: return
        stagingDir.mkdirs()
        val condemned = File(stagingDir, "${directory.name}-removed-${System.nanoTime()}")
        if (directory.renameTo(condemned)) condemned.deleteRecursively() else directory.deleteRecursively()
    }

    /**
     * A package id is opaque and arrives from a file somebody was handed, so it
     * never becomes a path component as-is.
     *
     * The name is a readable prefix — so that a person looking at the files
     * directory can tell which Sammlung is which — and then the SHA-256 of the
     * whole id, which is what actually makes it unique. The prefix alone is
     * what this used to be, and it was not an identity: it flattened every
     * character but letters, digits and `-` to `_` and cut at 64, so
     * `family.home` and `family_home`, or two long ids sharing their first 64
     * characters, were one directory, and SPEC.md 8 says those are two
     * packages. Directories named the old way are still read; see [locate].
     */
    private fun directoryNameFor(id: String): String {
        val readable =
            id
                .map { if (it.isLetterOrDigit() || it == '-') it else '_' }
                .joinToString("")
                .take(READABLE_PREFIX)
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return readable + "-" + digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ARCHIVE_NAME = "package.obz"

        /** 32 + 1 + 64 hex stays far below any filesystem's 255. */
        const val READABLE_PREFIX = 32
    }
}

/** Formats an instant for display without dragging in a formatter dependency. */
fun Instant.readable(): String = toString().replace('T', ' ').removeSuffix("Z") + " UTC"
