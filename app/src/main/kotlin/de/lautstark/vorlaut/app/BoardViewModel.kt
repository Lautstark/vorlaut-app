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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives one open package: which board is showing, what is in the message bar,
 * and what is making a sound.
 */
class BoardViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val speech = Speech(application)
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

    fun open(
        boardPackage: BoardPackage,
        warnings: List<ImportWarning>,
        archiveBytes: ByteArray,
    ) {
        media.clear()
        media = BoardMedia(PackageArchive.open(archiveBytes))
        bar = MessageBar()
        val root = boardPackage.boards.firstOrNull { it.id == boardPackage.rootBoardId }
        speech.configureVoice(boardPackage.ttsVoice, root?.locale)
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

        when (val action = button.onActivate) {
            is OnActivate.Navigate -> {
                speech.stop()
                _state.value = state.copy(currentBoardId = action.boardId)
                return
            }

            OnActivate.Home -> {
                speech.stop()
                _state.value = state.copy(currentBoardId = boardPackage.rootBoardId)
                return
            }

            else -> {
                Unit
            }
        }

        val spoken = bar.press(button)
        val entries = bar.contents()

        when (button.onActivate) {
            OnActivate.Append, OnActivate.SpeakImmediately -> {
                // The button's own voice: its clip if it has one, synthesis if not.
                utter(button)
            }

            OnActivate.SpeakBar -> {
                // The bar has no clip of its own. Speaking a composed sentence is
                // always synthesis, even when every entry in it came from a button
                // that had a recording — so the speak button carries the same
                // synthesising mark as any other button using the device voice.
                spoken?.let { speech.speak(button.id, it) }
            }

            else -> {
                Unit
            }
        }

        // What the bar shows is the vocalization, not the label. SPEC.md 7.3's
        // sentence says the bar "renders labels", but the message-bar fixture's
        // scenario has w3 — label "Apfel", vocalization "einen Apfel" — putting
        // `einen Apfel` in the bar. SPEC.md 13 makes the fixture normative where
        // the two disagree, so the fixture wins and the sentence is the bug.
        _state.value = _state.value.copy(entries = entries.map { it.spoken ?: it.display ?: "" })
    }

    /**
     * SPEC.md 9.2: a clip is played; a button whose audio was promised and is
     * missing falls back to synthesis and is already marked degraded. A button
     * that never had a clip also synthesises, and is not marked — that is a
     * TTS-only board working as designed.
     */
    private fun utter(button: Button) {
        val source = button.audio
        if (source is AudioSource.Recorded) {
            val bytes = media.audio(source.path)
            if (bytes != null) {
                speech.playClip(button.id, bytes)
                return
            }
        }
        button.spokenText?.let { speech.speak(button.id, it) }
    }

    fun clearBar() {
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
    val entries: List<String> = emptyList(),
    val speaking: Speech.Speaking = Speech.Speaking.Silent,
) {
    val board: Board?
        get() = boardPackage?.boards?.firstOrNull { it.id == currentBoardId }

    /** True while this button is speaking with the device voice rather than a clip. */
    fun isSynthesising(button: Button): Boolean = (speaking as? Speech.Speaking.Synthesised)?.buttonId == button.id

    fun isPlayingClip(button: Button): Boolean = (speaking as? Speech.Speaking.Clip)?.buttonId == button.id

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
