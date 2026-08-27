package de.lautstark.vorlaut.app

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Everything that makes a sound: the clips the builder recorded, and nothing
 * else.
 *
 * SPEC.md 9.2 permits a button with no clip of its own to be read out by the
 * device voice, and this viewer does not take that path. A sentence that comes
 * out half in the voice it was recorded in and half in the phone's is the thing
 * a person notices immediately and cannot unhear, and this screen belongs to
 * the package rather than to the phone.
 *
 * So a button with no recording says nothing, and is drawn as having no voice —
 * see `ButtonCell`. That is a fault a caregiver can see and go and fix in the
 * builder, where a stranger's voice reading the word is a fault nobody ever
 * fixes, because it sounds like it is working.
 */
class Speech {
    /** What is making a sound right now, so the board can show it. */
    sealed interface Speaking {
        data object Silent : Speaking

        /** A baked clip is playing on this button. */
        data class Clip(
            val buttonId: String,
        ) : Speaking
    }

    private var onStateChange: (Speaking) -> Unit = {}
    private var player: Ahead? = null

    /* The sentence being said, one clip at a time. `generation` is what makes
       stop() final: a clip that finishes after it finds its generation stale and
       stops rather than waking the rest of a sentence nobody is waiting for any
       more. */
    private val main = Handler(Looper.getMainLooper())
    private var queue: List<ByteArray> = emptyList()
    private var at = 0
    private var generation = 0
    private var ahead: Ahead? = null
    private var aheadAt = -1

    fun observe(listener: (Speaking) -> Unit) {
        onStateChange = listener
    }

    private fun report(state: Speaking) = onStateChange(state)

    /** Plays a baked clip from bytes. Nothing is written to disk to do it. */
    fun playClip(
        buttonId: String,
        bytes: ByteArray,
    ) {
        stop()
        play(Ahead(bytes) { report(Speaking.Silent) }, buttonId)
    }

    /**
     * Says a whole sentence, one recording after the next.
     *
     * Every entry is played in the voice it was recorded in; an entry that has
     * no recording is not in [clips] at all, because the caller dropped it
     * rather than hand it to a voice that is not the package's.
     *
     * The cost is audible and is the point of the trade: each word was recorded
     * on its own, so the sentence comes out word by word rather than as one
     * breath. What is left between the words is the recordings' own edges — the
     * decoder is no longer waited for, which is what [Ahead] is about.
     */
    fun speakSequence(clips: List<ByteArray>) {
        stop()
        if (clips.isEmpty()) return
        queue = clips
        at = 0
        step(generation)
    }

    private fun step(mine: Int) {
        if (mine != generation) return
        val clip = queue.getOrNull(at)
        if (clip == null) {
            queue = emptyList()
            at = 0
            dropAhead()
            report(Speaking.Silent)
            return
        }
        play(takeAhead(at) ?: Ahead(clip) { advance(mine) }, null)
        // The clip after this one is decoded while this one sounds, so its turn
        // costs nothing but the switch.
        val next = queue.getOrNull(at + 1)
        if (next != null && aheadAt != at + 1) {
            dropAhead()
            ahead = Ahead(next) { advance(mine) }
            aheadAt = at + 1
        }
    }

    /** The player prepared for [index], if that is the one waiting. */
    private fun takeAhead(index: Int): Ahead? {
        val ready = ahead?.takeIf { aheadAt == index }
        if (ready == null) {
            dropAhead()
            return null
        }
        ahead = null
        aheadAt = -1
        return ready
    }

    private fun dropAhead() {
        ahead?.release()
        ahead = null
        aheadAt = -1
    }

    private fun advance(mine: Int) {
        if (mine != generation) return
        at += 1
        // Back to the main thread: a clip finishes on the player's own thread,
        // and the next one builds a MediaPlayer, which wants a looper it can
        // post its callbacks to.
        main.post { step(mine) }
    }

    private fun play(
        clip: Ahead,
        buttonId: String?,
    ) {
        player?.release()
        player = clip
        clip.start {
            clip.player.start()
            // A sentence marks nothing: the entries are in the bar, not on the
            // board, and there is no cell to light.
            if (buttonId != null) report(Speaking.Clip(buttonId))
        }
    }

    fun stop() {
        generation += 1
        queue = emptyList()
        at = 0
        dropAhead()
        player?.release()
        player = null
        report(Speaking.Silent)
    }

    fun release() = stop()

    /**
     * One clip, made ready before its turn.
     *
     * Preparing a MediaPlayer is where the pause between two snippets of a
     * spoken sentence came from: the decoder was handed the next clip only once
     * the one before it had finished, so every word paid that setup again, in
     * silence. A player is now built and prepared while the clip before it is
     * still sounding, and starts the moment its turn comes.
     */
    private class Ahead(
        bytes: ByteArray,
        private val onDone: () -> Unit,
    ) {
        val player = MediaPlayer()

        private var ready = false
        private var failed = false
        private var started = false
        private var over = false
        private var begin: (() -> Unit)? = null

        init {
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
            player.apply {
                setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(source)
                setOnCompletionListener { finish() }
                setOnErrorListener { _, _, _ ->
                    failed = true
                    // A clip that fails before its turn stays quiet about it:
                    // reporting done now would walk the sentence on while the
                    // word before it is still being said. [start] picks it up.
                    if (started) finish()
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
                    ready = true
                    begin?.let { go ->
                        begin = null
                        go()
                    }
                }
                prepareAsync()
            }
        }

        /** Plays now if the decoder is ready, and otherwise the moment it is. */
        fun start(go: () -> Unit) {
            started = true
            when {
                failed -> finish()
                ready -> go()
                else -> begin = go
            }
        }

        fun release() {
            begin = null
            over = true
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }

        private fun finish() {
            if (over) return
            over = true
            onDone()
        }
    }
}
