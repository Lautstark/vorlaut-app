package de.lautstark.vorlaut.boardpackage

import java.time.Instant

/** The bare identity of a package already on the device. */
data class StoredPackage(
    val id: String,
    val modified: Instant,
)

/**
 * What importing a package should do to what is already stored (SPEC.md 8).
 *
 * Kept separate from the parser because it is the one decision that depends on
 * device state rather than on the bytes being imported — and because getting it
 * wrong is destructive rather than merely wrong: a package that replaces the
 * wrong stored one takes a vocabulary somebody depends on with it.
 */
sealed interface ReimportDecision {
    /** No package with this id is stored. Two packages may share a name. */
    data object InstallNew : ReimportDecision

    /**
     * Wholesale replacement, not a merge — content the new package does not
     * contain must be gone afterwards, because a deleted button was usually
     * deleted for a reason.
     */
    data class Replace(
        val stored: StoredPackage,
    ) : ReimportDecision

    /**
     * The stored package is the same age or newer. The importer must not silently
     * replace it; skipping or asking is the caller's choice, but treating an older
     * package as an update is not.
     */
    data class AlreadyCurrent(
        val stored: StoredPackage,
    ) : ReimportDecision
}

fun decideReimport(
    incoming: BoardPackage,
    stored: Collection<StoredPackage>,
): ReimportDecision {
    // Keyed on the package id and nothing else. A name is not an identity:
    // fixture `identity-b` is a copy of `identity-a` with the same name and a
    // fresh id, and an importer keyed on the name would destroy the original.
    val match = stored.firstOrNull { it.id == incoming.id } ?: return ReimportDecision.InstallNew
    return if (match.modified.isBefore(incoming.modified)) {
        ReimportDecision.Replace(match)
    } else {
        ReimportDecision.AlreadyCurrent(match)
    }
}
