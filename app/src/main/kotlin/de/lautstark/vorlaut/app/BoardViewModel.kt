package de.lautstark.vorlaut.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.lautstark.vorlaut.boardpackage.AudioSource
import de.lautstark.vorlaut.boardpackage.Board
import de.lautstark.vorlaut.boardpackage.BoardPackage
import de.lautstark.vorlaut.boardpackage.Button
import de.lautstark.vorlaut.boardpackage.ButtonState
import de.lautstark.vorlaut.boardpackage.ImportWarning
import de.lautstark.vorlaut.boardpackage.MessageBar
import de.lautstark.vorlaut.boardpackage.OnActivate
import de.lautstark.vorlaut.boardpackage.PackageArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives one open package: which board is showing, what is in the message bar,
 * and what is making a sound.
 */
class BoardViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val speech = Speech()
    private var bar = MessageBar()

    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    private var media: BoardMedia = BoardMedia(null)

    init {
        speech.observe { speaking ->
            viewModelScope.launch { _state.value = _state.value.copy(speaking = speaking) }
        }
    }

    fun mediaLoader(): BoardMedia = media

    /**
     * Opens a stored package.
     *
     * The archive is read on a worker. A package may be tens of megabytes, and
     * reading it on the main thread is the same mistake as preparing audio there:
     * invisible on the small fixtures, a stall on a real vocabulary.
     */
    fun open(
        boardPackage: BoardPackage,
        warnings: List<ImportWarning>,
        archive: java.io.File,
    ) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { PackageArchive.open(archive.readBytes()) }
            media.clear()
            media = BoardMedia(loaded)
            finishOpening(boardPackage, warnings)
        }
    }

    private fun finishOpening(
        boardPackage: BoardPackage,
        warnings: List<ImportWarning>,
    ) {
        bar = MessageBar()
        _state.value =
            BoardUiState(
                boardPackage = boardPackage,
                warnings = warnings,
                currentBoardId = boardPackage.rootBoardId,
                entries = emptyList(),
            )
    }

    fun close() {
        speech.stop()
        media.clear()
        _state.value = BoardUiState()
    }

    /**
     * One press, resolved by SPEC.md 7.3's table and then made to happen.
     *
     * The bar decides what a press means; this decides what it sounds like. The
     * two are kept apart because the first is domain logic the fixtures pin and
     * the second is a device concern they say nothing about.
     */
    fun press(button: Button) {
        val state = _state.value
        val boardPackage = state.boardPackage ?: return

        // A visibly dead button must stay dead. SPEC.md 7.4: a button that looks
        // live and ignores the person pressing it teaches them the device ignores
        // them, so this refuses rather than doing something approximate.
        if (button.onActivate == OnActivate.Disabled) return

        val action = button.onActivate

        // Plain navigation: silent, and the bar goes with it untouched.
        if (action is OnActivate.Navigation) {
            speech.stop()
            _state.value = state.copy(currentBoardId = destination(action, boardPackage))
            return
        }

        bar.press(button)
        val entries = bar.contents()

        when (action) {
            OnActivate.Append, OnActivate.SpeakImmediately,
            is OnActivate.AppendThenNavigate, is OnActivate.SpeakThenNavigate,
            -> {
                // The button's own voice: its clip, or silence if it has none.
                //
                // A carrying button speaks like the word button it also is, and
                // the speech is deliberately not stopped for the navigation that
                // follows - it is this button's own word, cut off half a syllable
                // in by the board change if the navigation were treated as one of
                // the silent ones above.
                utter(button)
            }

            OnActivate.SpeakBar -> {
                // A `:speak` button in the grid is the bar's speak control put on
                // the board, and says the sentence the same way: each entry in
                // the voice it was recorded in. This handed the joined line to
                // the device voice, which the bar's own control stopped doing and
                // this was never changed with it.
                speakBar()
            }

            else -> {
                Unit
            }
        }

        /* Where a carrying button leaves the person standing (SPEC.md 7.3).
         *
         * Written in the *same* state update as the entry, which is what makes
         * the order the spec requires true rather than merely likely: the entry
         * MUST be in the bar by the time the new board is drawn, and two updates
         * would give Compose a frame in which the new board is on screen and the
         * word that opened it is not.
         */
        val landing =
            when (action) {
                is OnActivate.AppendThenNavigate -> destination(action.then, boardPackage)

                // The speaking modifier lands the same way, and the speech
                // above is deliberately not stopped for it either: the word is
                // the reason the button carries the flag, and cutting it off
                // at the board change would leave the press indistinguishable
                // from the plain navigation beside it.
                is OnActivate.SpeakThenNavigate -> destination(action.then, boardPackage)

                else -> _state.value.currentBoardId
            }

        // What the bar shows is the vocalization, not the label — SPEC.md 7.3,
        // which now says so outright. MessageBar decides it; this only stores
        // what came back.
        _state.value = _state.value.copy(entries = entries, currentBoardId = landing)
    }

    /** Which board a navigating press lands on. `:home` follows `manifest.root`
     *  rather than wherever the walk started. */
    private fun destination(
        action: OnActivate.Navigation,
        boardPackage: BoardPackage,
    ): String? =
        when (action) {
            is OnActivate.Navigate -> action.boardId
            OnActivate.Home -> boardPackage.rootBoardId
        }

    /**
     * SPEC.md 9.2's clip, and none of its fallback.
     *
     * A button with no recording — one that never had one, or one whose file the
     * package lost — is silent, and the grid marks it as having no voice.
     * Pressing it still stops whatever was sounding, because a press that leaves
     * the previous word playing reads as if the wrong button spoke.
     */
    private fun utter(button: Button) {
        val bytes = (button.audio as? AudioSource.Recorded)?.let { media.audio(it.path) }
        if (bytes == null) speech.stop() else speech.playClip(button.id, bytes)
    }

    /**
     * SPEC.md 7.4's `:speak`, reached from the bar rather than from a button in
     * the grid, and said in the package's own voice.
     *
     * A package may still carry `:speak` or `:clear` buttons and those keep
     * working; the bar's controls are the same behaviours put where they
     * belong, so that a fifteen-cell board does not spend three of its cells on
     * punctuation.
     *
     * Each entry is played from the recording it was pressed on. An entry that
     * has none is passed over rather than read out by the device voice — the
     * word is missing from the sentence either way, and the bar draws it faded
     * so that it is missing visibly rather than only audibly.
     */
    fun speakBar() {
        val clips = bar.contents().mapNotNull { entry -> entry.soundPath?.let { media.audio(it) } }
        if (clips.isEmpty()) speech.stop() else speech.speakSequence(clips)
    }

    fun undo() {
        speech.stop()
        bar.removeLast()
        _state.value = _state.value.copy(entries = bar.contents())
    }

    fun clearBar() {
        speech.stop()
        bar = MessageBar()
        _state.value = _state.value.copy(entries = emptyList())
    }

    override fun onCleared() {
        speech.release()
        media.clear()
    }
}

data class BoardUiState(
    val boardPackage: BoardPackage? = null,
    val warnings: List<ImportWarning> = emptyList(),
    val currentBoardId: String? = null,
    val entries: List<MessageBar.Entry> = emptyList(),
    val speaking: Speech.Speaking = Speech.Speaking.Silent,
) {
    val board: Board?
        get() = boardPackage?.boards?.firstOrNull { it.id == currentBoardId }

    /** True while this button's own recording is playing. */
    fun isPlayingClip(button: Button): Boolean = (speaking as? Speech.Speaking.Clip)?.buttonId == button.id

    /**
     * SPEC.md 4.1: whether this package asks for the first column to be set apart.
     *
     * A property of the package rather than of the board on screen, and drawn on
     * every one of its boards. A gap that came and went from page to page would
     * move every other column as the user navigated, which is the opposite of
     * what a column that stays reachable is for.
     */
    val firstColumnGap: Boolean
        get() = boardPackage?.firstColumnGap == true

    /** Buttons the importer marked. Kept as a set so the grid can ask cheaply. */
    val degraded: Set<String>
        get() =
            board
                ?.buttons
                .orEmpty()
                .filter { it.state == ButtonState.DEGRADED }
                .map { it.id }
                .toSet()
}
