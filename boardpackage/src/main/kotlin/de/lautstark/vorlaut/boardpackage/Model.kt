package de.lautstark.vorlaut.boardpackage

import java.time.Instant

/**
 * The result of importing one `.obz`. Every failure this importer can have is one
 * of these two branches — nothing throws, because the caller is a screen a
 * caregiver is looking at, not a stack trace.
 */
sealed interface ImportResult {
    /**
     * The package imported. It may still carry warnings: the importer is strict
     * about packages and lenient about buttons (SPEC.md 9), so a button with a
     * missing picture degrades that one button rather than losing the vocabulary.
     */
    data class Accepted(
        val boardPackage: BoardPackage,
        val warnings: List<ImportWarning>,
    ) : ImportResult

    /**
     * Nothing was imported and anything already stored is untouched. [detail] is
     * for the person importing, who has to be told which package failed and why
     * (SPEC.md 9.1); it is deliberately not part of any equality check.
     */
    data class Rejected(
        val code: RejectionCode,
        val detail: String,
    ) : ImportResult
}

data class BoardPackage(
    val id: String,
    val name: String,
    val modified: Instant,
    val symbolSource: SymbolSource,
    val redistributable: Boolean,
    val ttsVoice: String?,
    /**
     * SPEC.md 4.1: draw extra space between the first column and the second, on
     * every board in this package.
     *
     * A hint about drawing and nothing else. It marks the leftmost column as the
     * one that stays reachable — MetaTalk sets that column apart for exactly that
     * reason — but nothing here makes a button persistent, and no field does: a
     * column that stays put is one the builder wrote onto every board. Reading
     * this as an instruction to carry column 1 over from the previous board would
     * render a package that repeats it correctly and one that does not wrongly.
     *
     * False when the manifest says nothing, which is the case for every package
     * written against 1.0.0.
     */
    val firstColumnGap: Boolean,
    val specVersion: SpecVersion,
    val rootBoardId: String,
    val boards: List<Board>,
)

/**
 * SPEC.md 5.1: one symbol source per package, and the importer records it rather
 * than verifying it — there is no symbol library on the device to check against.
 */
enum class SymbolSource(
    val wireName: String,
) {
    ARASAAC("arasaac"),
    METACOM("metacom"),
    NONE("none"),
    ;

    companion object {
        fun fromWire(value: String): SymbolSource? = entries.firstOrNull { it.wireName == value }
    }
}

data class SpecVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** The version this importer implements. */
        val IMPLEMENTED = SpecVersion(1, 1, 0)

        fun parse(value: String): SpecVersion? {
            val parts = value.split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            if (numbers.any { it < 0 }) return null
            return SpecVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

data class Board(
    val id: String,
    val name: String,
    /** SPEC.md 7.1 requires it; null when a board omitted it anyway. */
    val locale: String?,
    val rows: Int,
    val columns: Int,
    /** `#RRGGBB`, or null when the board did not set one or it did not parse. */
    val color: String?,
    /** Row-major, one entry per cell; null where the cell is empty. */
    val cells: List<List<String?>>,
    /** Only the buttons that actually render, in grid order. */
    val buttons: List<Button>,
)

data class Button(
    val id: String,
    val label: String?,
    val vocalization: String?,
    val onActivate: OnActivate,
    /** Archive path of the picture, NFC-normalised. Null when there is none. */
    val imagePath: String?,
    val audio: AudioSource?,
    val state: ButtonState,
    val backgroundColor: String?,
    val borderColor: String?,
) {
    /** What SPEC.md 7.3 hands to speech: the vocalization, falling back to the label. */
    val spokenText: String? get() = vocalization ?: label
}

/**
 * What one press does. `load_board` wins over an action if a button somehow
 * carries both (SPEC.md 7.3).
 */
sealed interface OnActivate {
    /** The wire name used by the conformance fixtures' `on_activate` field. */
    val wireName: String

    /**
     * The two presses that change which board is showing.
     *
     * They are one type because SPEC.md 7.3's append-on-navigate applies to both
     * and to nothing else, so [AppendThenNavigate] can hold exactly the things
     * it is allowed to hold rather than any `OnActivate` and a rule in prose.
     * The screen has the same question — where does this press go — and gets to
     * ask it once.
     */
    sealed interface Navigation : OnActivate

    /** Append one entry to the message bar. The default and the common case. */
    data object Append : OnActivate {
        override val wireName = "append"
    }

    /** Speak at once, leaving the bar alone (`ext_lautstark_speak_immediately`). */
    data object SpeakImmediately : OnActivate {
        override val wireName = "speak_immediately"
    }

    /** `:speak` — speak the whole bar and leave it standing. */
    data object SpeakBar : OnActivate {
        override val wireName = "speak_bar"
    }

    /** `:clear` — empty the bar, speak nothing. */
    data object Clear : OnActivate {
        override val wireName = "clear"
    }

    /** `:backspace` — remove the last entry, not the last character. */
    data object Backspace : OnActivate {
        override val wireName = "backspace"
    }

    /** `:home` — navigate to the board named by `manifest.root`. */
    data object Home : Navigation {
        override val wireName = "home"
    }

    /** `load_board` — navigate, and do not touch the bar. */
    data class Navigate(
        val boardId: String,
    ) : Navigation {
        override val wireName = "navigate:$boardId"
    }

    /**
     * `ext_lautstark_append_on_navigate` (SPEC.md 4.3, 7.3, since 1.2.0): append
     * one entry exactly as [Append] does, **then** navigate. One press, both
     * halves, and the order is normative — the entry has to be in the bar by the
     * time the new board is drawn.
     *
     * This is the carrier phrase, which is how a sentence starter is built:
     * "ich will" belongs in the sentence, and the board its object is on is
     * where the next press has to happen. Without it that is two presses on two
     * boards, the second of them after leaving the board that named it.
     *
     * A wrapper rather than a flag on [Navigate] and [Home], because the flag
     * would then have to be answered by every site that matches on those two —
     * and the interesting sites are the ones that must **not** treat this as
     * plain navigation, which a defaulted boolean lets them go on doing. This
     * way the compiler asks each of them.
     */
    data class AppendThenNavigate(
        val then: Navigation,
    ) : OnActivate {
        override val wireName = "append+${then.wireName}"
    }

    /**
     * An action outside SPEC.md 7.4. The button renders and is visibly dead,
     * which is the honest outcome: a button that looks live and ignores the
     * person pressing it teaches them the device ignores them.
     */
    data object Disabled : OnActivate {
        override val wireName = "disabled"
    }

    /**
     * An `actions` array whose members are all implemented. SPEC.md 7.4 defines
     * only the failing case — one unimplemented action disables the whole button
     * — and says nothing about what running several implemented ones in sequence
     * means, so this carries them in order and leaves the semantics to the caller.
     * No fixture exercises it.
     */
    data class Sequence(
        val actions: List<OnActivate>,
    ) : OnActivate {
        override val wireName = actions.joinToString("+") { it.wireName }
    }
}

/** Where a button's own speech comes from. Null when the button makes no sound. */
sealed interface AudioSource {
    /** A clip baked into the package, at this archive path. */
    data class Recorded(
        val path: String,
    ) : AudioSource

    /**
     * Synthesised speech. This is a designed path, not a failure — a board built
     * without recorded audio is a normal board (SPEC.md 9.2).
     */
    data object Tts : AudioSource

    val wireName: String
        get() =
            when (this) {
                is Recorded -> path
                Tts -> "tts"
            }
}

enum class ButtonState(
    val wireName: String,
) {
    NORMAL("normal"),

    /** Something the package promised is missing; the button is visibly marked. */
    DEGRADED("degraded"),

    /** An unimplemented action; the button is visibly dead. */
    DISABLED("disabled"),
}

/**
 * A warning persisted with the imported package (SPEC.md 9.3). [detail] is prose
 * for a human and is deliberately excluded from equality against a fixture — the
 * wording will drift and a test that compares it would be testing the wording.
 */
data class ImportWarning(
    val code: WarningCode,
    val boardId: String?,
    val buttonId: String?,
    val detail: String,
) {
    /** The tuple the fixtures actually compare on. */
    val identity: Triple<WarningCode, String?, String?> get() = Triple(code, boardId, buttonId)
}

enum class WarningCode(
    val wireName: String,
    val degrades: Boolean,
) {
    // SPEC.md 9.2 - these mark their button.
    IMAGE_MISSING("image_missing", degrades = true),
    IMAGE_OVERSIZED("image_oversized", degrades = true),
    IMAGE_UNDECODABLE("image_undecodable", degrades = true),
    SOUND_MISSING("sound_missing", degrades = true),
    SOUND_UNDECODABLE("sound_undecodable", degrades = true),
    SOUND_TOO_LONG("sound_too_long", degrades = true),
    ACTION_UNSUPPORTED("action_unsupported", degrades = false),
    BUTTON_MISSING("button_missing", degrades = false),

    // SPEC.md 9.4 - a defect worth fixing upstream, but nothing the user sees is
    // wrong, so the button stays normal.
    PATH_NORMALIZATION("path_normalization", degrades = false),
    PATH_CONFLICT("path_conflict", degrades = false),
    IMAGE_REFERENCE_IGNORED("image_reference_ignored", degrades = false),
    COLOR_UNPARSEABLE("color_unparseable", degrades = false),
}

enum class RejectionCode(
    val wireName: String,
) {
    PACKAGE_UNREADABLE("package_unreadable"),
    PATH_UNSAFE("path_unsafe"),
    MANIFEST_MISSING("manifest_missing"),
    MANIFEST_INVALID("manifest_invalid"),
    FORMAT_UNSUPPORTED("format_unsupported"),
    SPEC_VERSION_UNSUPPORTED("spec_version_unsupported"),
    ROOT_MISSING("root_missing"),
    BOARD_INVALID("board_invalid"),
    GRID_MALFORMED("grid_malformed"),
    LICENCE_INCONSISTENT("licence_inconsistent"),
}
