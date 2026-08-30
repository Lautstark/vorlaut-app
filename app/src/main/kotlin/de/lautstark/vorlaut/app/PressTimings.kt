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
 * a Context in the way. The distinction worth keeping straight is that null and
 * [PressMode.AtOnce] are different answers: null is "whatever the Sammlung
 * asked for", and AtOnce is a caregiver saying *no* to it. They usually produce
 * the same two zeroes, which is exactly why it is easy to write code that
 * conflates them and never notice.
 */
internal fun resolvePressTimings(
    mode: PressMode?,
    fromPackage: PressTimings,
): PressTimings = mode?.timings ?: fromPackage

/** SPEC.md 7.5's range, for a number that did not come through the importer. */
internal fun clampTiming(ms: Int): Int = ms.coerceIn(0, MAX_PRESS_TIMING_MS)

/**
 * The three ways a board can answer a touch, as a caregiver picks them.
 *
 * **Named modes here, milliseconds in the editor, and that split is the point.**
 * The two numbers underneath are real and SPEC.md 7.5 defines them precisely, but
 * nobody tuning this for their own child thinks in milliseconds — they know the
 * symptom. Two questions with six numeric steps each is an author's control, and
 * the author already has one: `accessPanel` in `vorlaut-editor` still offers
 * every value, because somebody building a board sets a considered default once.
 * This screen is where a parent adjusts on a bad afternoon, and it should ask a
 * question they can answer from what they just watched happen.
 *
 * **Ordered cheapest first, which is the whole design.** [Once] costs nothing —
 * a pause after a press is not felt until the *next* word — and it fixes one
 * press arriving as three. [Held] adds a delay to every single word, and buys
 * rejection of a button brushed on the way to another. So a caregiver walks down
 * the list and stops at the first one that works, rather than reaching for the
 * strongest and living with a board that feels slow.
 *
 * Naming them for what the board does rather than for how the child presses.
 * "Für unruhige Hände" would read as a label on the person holding the tablet,
 * on a screen their parent opens; "Einmal pro Druck" is the same setting
 * described by its effect, and is checkable against what actually happens.
 */
enum class PressMode(
    val timings: PressTimings,
) {
    /** Every touch counts, the moment it lifts. What every board did before 1.3.0. */
    AtOnce(PressTimings(holdMs = 0, releaseMs = 0)),

    /**
     * One activation per press. The cooldown alone: a finger that bounces, or a
     * tremor, stops writing the same word twice.
     */
    Once(PressTimings(holdMs = 0, releaseMs = 600)),

    /**
     * Only a press that stays. The hold as well, so a button brushed on the way
     * to another is not a word — at the cost of a wait before every one.
     */
    Held(PressTimings(holdMs = 400, releaseMs = 800)),
    ;

    companion object {
        /**
         * The mode a stored pair of numbers means.
         *
         * Only ever needed for a pair written by the millisecond pickers this
         * screen used to have, which existed for one afternoon. Rather than
         * discard a setting somebody chose — the failure this whole file is
         * careful about elsewhere — the nearest mode is taken and written back,
         * so the screen shows something true and the legacy keys go.
         */
        fun nearest(timings: PressTimings): PressMode =
            entries.minBy { mode ->
                val hold = mode.timings.holdMs - timings.holdMs
                val release = mode.timings.releaseMs - timings.releaseMs
                hold * hold + release * release
            }
    }
}

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

    /**
     * The mode this tablet is set to, or null to take the Sammlung's.
     *
     * Reading migrates a pair left by the millisecond pickers this screen used to
     * have: it is mapped to the nearest mode and written back, so the legacy keys
     * exist for exactly one read and a setting somebody chose is not silently
     * dropped.
     */
    var mode: PressMode?
        get() {
            preferences.getString(KEY_MODE, null)?.let { stored ->
                return PressMode.entries.firstOrNull { it.name == stored }
            }
            if (!preferences.contains(KEY_HOLD) && !preferences.contains(KEY_RELEASE)) return null
            val legacy =
                PressTimings(
                    holdMs = read(KEY_HOLD) ?: 0,
                    releaseMs = read(KEY_RELEASE) ?: 0,
                )
            return PressMode.nearest(legacy).also { mode = it }
        }
        set(value) =
            preferences.edit {
                if (value == null) remove(KEY_MODE) else putString(KEY_MODE, value.name)
                // Gone for good once a mode is written, so the migration above
                // cannot fire a second time and undo a later choice.
                remove(KEY_HOLD)
                remove(KEY_RELEASE)
            }

    /** What the board should actually do, for the package it is showing. */
    fun resolve(boardPackage: BoardPackage?): PressTimings = resolvePressTimings(mode, PressTimings.of(boardPackage))

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

    private companion object {
        const val STORE = "press"
        const val KEY_MODE = "mode"

        // What the millisecond pickers wrote, read once and then removed.
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
