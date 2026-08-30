package de.lautstark.vorlaut.app

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerId
import androidx.core.content.edit
import de.lautstark.vorlaut.boardpackage.BoardPackage
import de.lautstark.vorlaut.boardpackage.MAX_PRESS_TIMING_MS

/**
 * How long a press must be held before it counts, and how long the board is deaf
 * afterwards — resolved, in milliseconds, with 0 meaning off.
 *
 * SPEC.md 7.5 is the whole of the behaviour. These two are what a package asked
 * for, or what the caregiver set here instead; by the time a number is in one of
 * these it is settled, in range, and nothing downstream has to ask where it came
 * from.
 */
@Immutable
data class PressTimings(
    val holdMs: Int,
    val releaseMs: Int,
) {
    companion object {
        val Off = PressTimings(0, 0)

        /** What a package asks for, before any override is applied. */
        fun of(boardPackage: BoardPackage?) =
            PressTimings(
                holdMs = boardPackage?.holdTimeMs ?: 0,
                releaseMs = boardPackage?.releaseTimeMs ?: 0,
            )
    }
}

/**
 * The override, applied — the one rule that decides what the board does.
 *
 * A pure function and not a method, so it can be held against its cases without
 * a Context in the way. There are only four states here and three of them are
 * easy to get subtly wrong: null means the package decides, 0 means the
 * *caregiver* said off, and those two are not the same answer even though they
 * often produce the same number.
 */
internal fun resolvePressTimings(
    holdOverrideMs: Int?,
    releaseOverrideMs: Int?,
    fromPackage: PressTimings,
): PressTimings =
    PressTimings(
        holdMs = holdOverrideMs ?: fromPackage.holdMs,
        releaseMs = releaseOverrideMs ?: fromPackage.releaseMs,
    )

/** SPEC.md 7.5's range, for a number that did not come through the importer. */
internal fun clampTiming(ms: Int): Int = ms.coerceIn(0, MAX_PRESS_TIMING_MS)

/**
 * The caregiver's answer to the two timings, which may be "whatever the Sammlung
 * says".
 *
 * **Three states per setting, and the third one is the default.** A package
 * carries the author's number (SPEC.md 4.1), and this is the tablet's own — the
 * spec says outright that a viewer offering one SHOULD let it win. Absent here
 * means the package decides, which is what makes a Sammlung exported with a
 * sensible hold time work on arrival without anybody opening this screen.
 *
 * **Stored apart from the package and never written back into it.** The
 * tempting shortcut is to copy a package's value into these keys on import, so
 * that one number answers everything. It is wrong in a way that only shows up
 * weeks later: re-importing an updated Sammlung is routine — PackageStore has
 * `Replaced` and `AlreadyCurrent` outcomes for exactly that — and the copy would
 * quietly overwrite whatever the caregiver had tuned, on the one action that
 * looks least like a settings change. So an override is a separate fact, and the
 * only thing that clears it is somebody clearing it.
 *
 * One pair for the device rather than one per Sammlung, because this describes a
 * hand and not a vocabulary. The same child using three Sammlungen needs the
 * same hold time in all three, and a per-package override would ask a caregiver
 * to re-tune on every import — the failure the package field exists to prevent,
 * reintroduced one floor up.
 */
class PressSettings(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Milliseconds, or null to take the Sammlung's. */
    var holdOverrideMs: Int?
        get() = read(KEY_HOLD)
        set(value) = write(KEY_HOLD, value)

    var releaseOverrideMs: Int?
        get() = read(KEY_RELEASE)
        set(value) = write(KEY_RELEASE, value)

    /** What the board should actually do, for the package it is showing. */
    fun resolve(boardPackage: BoardPackage?): PressTimings =
        resolvePressTimings(holdOverrideMs, releaseOverrideMs, PressTimings.of(boardPackage))

    private fun read(key: String): Int? =
        if (!preferences.contains(key)) {
            null
        } else {
            // Clamped on the way out as well as in. A stored value can outlive
            // the step list that produced it, and SPEC.md 7.5's ceiling is about
            // what a board is usable at rather than about where a number came
            // from.
            clampTiming(preferences.getInt(key, 0))
        }

    private fun write(
        key: String,
        value: Int?,
    ) = preferences.edit {
        if (value == null) remove(key) else putInt(key, clampTiming(value))
    }

    private companion object {
        const val STORE = "press"
        const val KEY_HOLD = "hold_ms"
        const val KEY_RELEASE = "release_ms"
    }
}

/**
 * One board's pointer arbitration and its cooldown clock.
 *
 * **Why the board needs a single one of these rather than each cell minding
 * itself.** Every cell runs its own gesture detector, and two fingers landing on
 * two cells are two gestures that know nothing about each other — which is
 * exactly the fault this whole change is for: a hand arriving flat puts three
 * words in the sentence. So the first pointer down takes the board and the rest
 * are ignored until it lifts.
 *
 * That part is not a setting and is always on. There is no board in this app
 * where pressing two words at once means anything — the sentence bar is a
 * sequence — so a preference would only offer a way to turn correct behaviour
 * off.
 *
 * The cooldown lives here for the same reason: SPEC.md 7.5 makes it a property
 * of the board rather than of the button, so that a bounce landing on the cell
 * next door is caught as readily as one landing twice on the same cell.
 */
@Stable
class BoardPresses {
    private var owner: PointerId? = null
    private var deafUntil: Long = 0L

    /** The button drawing itself as pressed, if any. */
    var holding: String? by mutableStateOf(null)
        private set

    /** True when this pointer now owns the board. */
    fun claim(id: PointerId): Boolean {
        if (owner != null) return false
        owner = id
        return true
    }

    fun release(id: PointerId) {
        if (owner != id) return
        owner = null
        holding = null
    }

    /** SPEC.md 7.5: presses beginning inside the window are dropped, not queued. */
    fun deaf(now: Long): Boolean = now < deafUntil

    fun activated(
        now: Long,
        releaseMs: Int,
    ) {
        deafUntil = now + releaseMs
    }

    fun show(buttonId: String) {
        holding = buttonId
    }

    fun clear() {
        holding = null
    }
}
