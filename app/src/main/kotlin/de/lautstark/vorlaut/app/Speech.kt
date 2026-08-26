package de.lautstark.vorlaut.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Everything that makes a sound.
 *
 * Two paths, and SPEC.md 9.2 turns on keeping them apart. A baked clip is what
 * the builder recorded and is played as-is. Synthesis is used where a button has
 * no clip — which is a designed path for a TTS-only board, not a failure, and is
 * why a button with no audio at all is not marked degraded.
 */
class Speech(
    context: Context,
) {
    /** What is making a sound right now, so the board can show it. */
    sealed interface Speaking {
        data object Silent : Speaking

        /** A baked clip is playing on this button. */
        data class Clip(
            val buttonId: String,
        ) : Speaking

        /**
         * Synthesis is running. Held separately from [Clip] because a button
         * falling back to the device voice has to look different while it is
         * doing it — the sound is the app's, not the package's, and a caregiver
         * listening for a recorded voice should be able to see which it got.
         */
        data class Synthesised(
            val buttonId: String?,
        ) : Speaking
    }

    /**
     * One thing to say, so that a sentence can be several.
     *
     * `:speak` used to hand the whole composed line to the device voice, which
     * meant a sentence built entirely out of recorded buttons came back in a
     * different voice than the buttons themselves — the thing a person notices
     * immediately and cannot unhear.
     */
    sealed interface Utterance {
        /** A baked clip, played as-is. */
        class Clip(
            val bytes: ByteArray,
        ) : Utterance

        /** A line for the device voice, where a button had no recording. */
        data class Synth(
            val text: String,
        ) : Utterance
    }

    private var onStateChange: (Speaking) -> Unit = {}
    private var player: MediaPlayer? = null

    /* The sentence being said, one utterance at a time. `generation` is what
       makes stop() final: a clip that finishes after it, or a synthesis whose
       onDone arrives late, finds its generation stale and stops rather than
       waking the rest of a sentence nobody is waiting for any more. */
    private val main = Handler(Looper.getMainLooper())
    private var queue: List<Utterance> = emptyList()
    private var at = 0
    private var generation = 0
    private var awaiting: String? = null
    private var onSynthDone: (() -> Unit)? = null

    @Volatile private var ttsReady = false

    private val tts =
        TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finishedSpeaking(utteranceId)

                @Deprecated("Superseded by onError(String, Int)", ReplaceWith(""))
                override fun onError(utteranceId: String?) = finishedSpeaking(utteranceId)

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) = finishedSpeaking(utteranceId)
            },
        )
    }

    fun observe(listener: (Speaking) -> Unit) {
        onStateChange = listener
    }

    private fun report(state: Speaking) = onStateChange(state)

    /**
     * Chooses the voice for a package.
     *
     * `ext_lautstark_tts_voice` is a hint and nothing more: SPEC.md 4.1 says the
     * importer falls back to the platform default when the named voice is
     * unavailable and **MUST NOT fail**. The board's `locale` is the second
     * choice, because it is what the spec says selects the voice when the named
     * one is not there.
     */
    fun configureVoice(
        preferredVoice: String?,
        locale: String?,
    ) {
        if (!ttsReady) return
        val named = preferredVoice?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (named != null) {
            tts.voice = named
            return
        }
        val wanted = locale?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        // setLanguage reports MISSING_DATA / NOT_SUPPORTED rather than throwing;
        // either way the platform default stays in place and speech still happens.
        tts.setLanguage(wanted)
    }

    /** Plays a baked clip from bytes. Nothing is written to disk to do it. */
    fun playClip(
        buttonId: String,
        bytes: ByteArray,
    ) {
        stop()
        startClip(buttonId, bytes) { report(Speaking.Silent) }
    }

    private fun startClip(
        buttonId: String?,
        bytes: ByteArray,
        onDone: () -> Unit,
    ) {
        val source =
            object : MediaDataSource() {
                override fun readAt(
                    position: Long,
                    buffer: ByteArray,
                    offset: Int,
                    size: Int,
                ): Int {
                    if (position >= bytes.size) return -1
                    val length = minOf(size.toLong(), bytes.size - position).toInt()
                    System.arraycopy(bytes, position.toInt(), buffer, offset, length)
                    return length
                }

                override fun getSize(): Long = bytes.size.toLong()

                override fun close() = Unit
            }
        player =
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(source)
                setOnCompletionListener { onDone() }
                setOnErrorListener { _, _, _ ->
                    onDone()
                    false
                }
                // prepareAsync, not prepare. Preparing synchronously decodes on
                // the calling thread, and on a mid-range device that is the best
                // part of a second of frozen UI between the press and the sound —
                // measured at about two on an emulator. A communication aid that
                // stalls when it is pressed teaches the person pressing it to
                // press again, and the button is marked as speaking only once it
                // actually is.
                setOnPreparedListener {
                    it.start()
                    // A sentence marks nothing: the entries are in the bar, not
                    // on the board, and there is no cell to light.
                    if (buttonId != null) report(Speaking.Clip(buttonId))
                }
                prepareAsync()
            }
    }

    /** Synthesises [text]. Used only where a button has no clip of its own. */
    fun speak(
        buttonId: String?,
        text: String,
    ) {
        stop()
        startSynth(buttonId, text)
    }

    /**
     * Says a whole sentence, one utterance after the next.
     *
     * Every entry that had a recording is played in the voice it was recorded
     * in; the rest fall to the device voice, in place, so a mixed sentence
     * still says all of itself. The alternative — handing the joined text to
     * the device voice — spoke more smoothly and in the wrong voice, and this
     * screen belongs to the package rather than to the phone.
     *
     * The cost is audible and is the point of the trade: each word was recorded
     * on its own, so the sentence comes out word by word rather than as one
     * breath.
     */
    fun speakSequence(items: List<Utterance>) {
        stop()
        if (items.isEmpty()) return
        queue = items
        at = 0
        step(generation)
    }

    private fun step(mine: Int) {
        if (mine != generation) return
        val item = queue.getOrNull(at)
        if (item == null) {
            queue = emptyList()
            at = 0
            report(Speaking.Silent)
            return
        }
        when (item) {
            is Utterance.Clip -> startClip(null, item.bytes) { advance(mine) }
            is Utterance.Synth -> if (!startSynth(null, item.text)) advance(mine) else Unit
        }
    }

    private fun advance(mine: Int) {
        if (mine != generation) return
        at += 1
        // Back to the main thread: a clip finishing and a synthesis finishing
        // arrive on different threads, and the next utterance builds a
        // MediaPlayer, which wants a looper it can post its callbacks to.
        main.post { step(mine) }
    }

    /** Returns false when nothing will speak, so a sequence can move on. */
    private fun startSynth(
        buttonId: String?,
        text: String,
    ): Boolean {
        if (!ttsReady || text.isBlank()) return false
        val mine = generation
        // The listener is one object for every utterance there will ever be, and
        // QUEUE_FLUSH makes the *previous* utterance report done. Without an id
        // to compare, that report would be taken for this one's and walk the
        // sentence on a step early.
        val id = buttonId ?: "$MESSAGE_BAR_UTTERANCE-$mine-$at"
        awaiting = id
        onSynthDone = { if (mine == generation) advance(mine) }
        report(Speaking.Synthesised(buttonId))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        return true
    }

    private fun finishedSpeaking(utteranceId: String?) {
        if (utteranceId != null && utteranceId != awaiting) return
        awaiting = null
        val next = onSynthDone
        onSynthDone = null
        if (next != null) next() else report(Speaking.Silent)
    }

    fun stop() {
        generation += 1
        queue = emptyList()
        at = 0
        awaiting = null
        onSynthDone = null
        player?.run {
            runCatching { if (isPlaying) stop() }
            release()
        }
        player = null
        if (ttsReady) tts.stop()
        report(Speaking.Silent)
    }

    fun release() {
        stop()
        tts.shutdown()
    }

    private companion object {
        const val MESSAGE_BAR_UTTERANCE = "message-bar"
    }
}
