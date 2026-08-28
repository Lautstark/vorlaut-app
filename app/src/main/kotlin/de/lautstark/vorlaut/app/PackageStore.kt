package de.lautstark.vorlaut.app

import de.lautstark.vorlaut.boardpackage.BoardPackage
import de.lautstark.vorlaut.boardpackage.BoardPackageImporter
import de.lautstark.vorlaut.boardpackage.ImportResult
import de.lautstark.vorlaut.boardpackage.ImportWarning
import de.lautstark.vorlaut.boardpackage.ReimportDecision
import de.lautstark.vorlaut.boardpackage.StoredPackage
import de.lautstark.vorlaut.boardpackage.decideReimport
import java.io.File
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

    fun list(): List<Entry> =
        packagesDir
            .listFiles()
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { read(it) }

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
                commit(incoming, bytes)
                Outcome.Installed(Entry(incoming, accepted.warnings, archiveFor(incoming.id)))
            }

            is ReimportDecision.Replace -> {
                commit(incoming, bytes)
                Outcome.Replaced(
                    Entry(incoming, accepted.warnings, archiveFor(incoming.id)),
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
     */
    private fun commit(
        boardPackage: BoardPackage,
        bytes: ByteArray,
    ) {
        packagesDir.mkdirs()
        stagingDir.mkdirs()
        val staged = File(stagingDir, "${directoryNameFor(boardPackage.id)}-${System.nanoTime()}")
        staged.deleteRecursively()
        staged.mkdirs()
        File(staged, ARCHIVE_NAME).writeBytes(bytes)

        val destination = File(packagesDir, directoryNameFor(boardPackage.id))
        val displaced = File(stagingDir, "${destination.name}-displaced-${System.nanoTime()}")
        val hadPrevious = destination.exists() && destination.renameTo(displaced)
        if (!staged.renameTo(destination)) {
            // Put the old one back before giving up. Ending here with neither is
            // the outcome the atomicity rule exists to prevent.
            if (hadPrevious) displaced.renameTo(destination)
            staged.deleteRecursively()
            error("could not install package ${boardPackage.id}")
        }
        if (hadPrevious) displaced.deleteRecursively()
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
        val directory = File(packagesDir, directoryNameFor(id))
        if (!directory.exists()) return
        stagingDir.mkdirs()
        val condemned = File(stagingDir, "${directory.name}-removed-${System.nanoTime()}")
        if (directory.renameTo(condemned)) condemned.deleteRecursively() else directory.deleteRecursively()
    }

    private fun archiveFor(id: String) = File(File(packagesDir, directoryNameFor(id)), ARCHIVE_NAME)

    /**
     * A package id is opaque and arrives from a file somebody was handed, so it
     * never becomes a path component as-is.
     */
    private fun directoryNameFor(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("").take(MAX_NAME)

    private companion object {
        const val ARCHIVE_NAME = "package.obz"
        const val MAX_NAME = 64
    }
}

/** Formats an instant for display without dragging in a formatter dependency. */
fun Instant.readable(): String = toString().replace('T', ' ').removeSuffix("Z") + " UTC"
