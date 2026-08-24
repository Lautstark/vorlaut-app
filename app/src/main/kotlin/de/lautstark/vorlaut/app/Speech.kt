package de.lautstark.vorlaut.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
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

    private var onStateChange: (Speaking) -> Unit = {}
    private var player: MediaPlayer? = null

    @Volatile private var ttsReady = false

    private val tts =
        TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = report(Speaking.Silent)

                @Deprecated("Superseded by onError(String, Int)", ReplaceWith(""))
                override fun onError(utteranceId: String?) = report(Speaking.Silent)

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) = report(Speaking.Silent)
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
                setOnCompletionListener { report(Speaking.Silent) }
                setOnErrorListener { _, _, _ ->
                    report(Speaking.Silent)
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
                    report(Speaking.Clip(buttonId))
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
        if (!ttsReady || text.isBlank()) return
        report(Speaking.Synthesised(buttonId))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, buttonId ?: MESSAGE_BAR_UTTERANCE)
    }

    fun stop() {
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
