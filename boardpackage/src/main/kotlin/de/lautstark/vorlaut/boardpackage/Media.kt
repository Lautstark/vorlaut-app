package de.lautstark.vorlaut.boardpackage

/**
 * Image and audio inspection, from headers only.
 *
 * Nothing here decodes a picture or a sound. SPEC.md 5.3 is explicit that the
 * size decision must be made from the image header **before allocating a full
 * bitmap** — decoding a deliberately huge image in order to discover it is too
 * big is precisely the crash the cap exists to prevent. Reading IHDR is enough.
 */
internal object Media {
    /** SPEC.md 5.3. Not advisory: an image over this is refused, never downscaled. */
    const val MAX_IMAGE_EDGE = 1024

    /** SPEC.md 6. Well past any utterance a button should hold. */
    const val MAX_AUDIO_SECONDS = 30.0

    data class Dimensions(
        val width: Int,
        val height: Int,
    ) {
        val exceedsCap: Boolean get() = width > MAX_IMAGE_EDGE || height > MAX_IMAGE_EDGE

        override fun toString(): String = "${width}x$height"
    }

    /**
     * The real dimensions of [bytes], or null if this is not an image format the
     * viewer accepts.
     *
     * SPEC.md 5.3 requires that these come from the image itself and **not** from
     * `images[].width` and `height`, which are declarations OBF does not
     * guarantee. Trusting the declaration would make the cap bypassable by a
     * builder writing the wrong number; fixture `oversized-image` has an image
     * that declares 512 and is 2048.
     *
     * PNG and JPEG only. SPEC.md 5.3 makes those two mandatory and leaves WebP a
     * MAY; a third header parser with no fixture to hold it honest is code that
     * would go wrong quietly, so WebP is treated as undecodable rather than
     * half-supported.
     */
    fun dimensionsOf(bytes: ByteArray): Dimensions? = pngDimensions(bytes) ?: jpegDimensions(bytes)

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngDimensions(bytes: ByteArray): Dimensions? {
        if (bytes.size < 24) return null
        for (i in PNG_MAGIC.indices) if (bytes[i] != PNG_MAGIC[i]) return null
        // IHDR is required by the PNG spec to be the first chunk, so width and
        // height sit at fixed offsets and no scan is needed.
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        val width = readIntBigEndian(bytes, 16)
        val height = readIntBigEndian(bytes, 20)
        if (width <= 0 || height <= 0) return null
        return Dimensions(width, height)
    }

    private fun jpegDimensions(bytes: ByteArray): Dimensions? {
        if (bytes.size < 4) return null
        if ((bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return null
        var at = 2
        while (at + 9 < bytes.size) {
            if ((bytes[at].toInt() and 0xFF) != 0xFF) return null
            val marker = bytes[at + 1].toInt() and 0xFF
            // Standalone markers carry no length field.
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                at += 2
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) return null
            val length = readShortBigEndian(bytes, at + 2)
            if (length < 2) return null
            if (marker in SOF_MARKERS) {
                if (at + 9 >= bytes.size) return null
                val height = readShortBigEndian(bytes, at + 5)
                val width = readShortBigEndian(bytes, at + 7)
                if (width <= 0 || height <= 0) return null
                return Dimensions(width, height)
            }
            at += 2 + length
        }
        return null
    }

    /** Frame markers that carry dimensions. DHT/DAC/DNL share the 0xC_ range. */
    private val SOF_MARKERS =
        setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

    sealed interface Audio {
        /** Playable, with a known duration in seconds. */
        data class Playable(
            val seconds: Double,
        ) : Audio

        /** Readable container, but the duration could not be established. */
        data object DurationUnknown : Audio

        /** Not a format the viewer accepts, or corrupt. */
        data object Undecodable : Audio
    }

    /**
     * SPEC.md 6: Ogg Opus, and 16 kHz mono 16-bit PCM WAV as the one legacy shape
     * worth accepting — a caregiver who recorded their own voice should not be
     * told to transcode.
     */
    fun inspectAudio(bytes: ByteArray): Audio =
        when {
            startsWith(bytes, "OggS") -> oggOpusDuration(bytes)
            startsWith(bytes, "RIFF") -> wavDuration(bytes)
            else -> Audio.Undecodable
        }

    private fun oggOpusDuration(bytes: ByteArray): Audio {
        var at = 0
        var preSkip = 0
        var sawOpusHead = false
        var lastGranule = -1L
        while (at + 27 <= bytes.size) {
            if (!startsWith(bytes, "OggS", at)) return Audio.Undecodable
            val segmentCount = bytes[at + 26].toInt() and 0xFF
            val tableAt = at + 27
            if (tableAt + segmentCount > bytes.size) return Audio.Undecodable
            var payload = 0
            for (i in 0 until segmentCount) payload += bytes[tableAt + i].toInt() and 0xFF
            val dataAt = tableAt + segmentCount
            if (dataAt + payload > bytes.size) return Audio.Undecodable

            if (!sawOpusHead && payload >= 12 && startsWith(bytes, "OpusHead", dataAt)) {
                // preSkip is samples the decoder discards; it is part of the
                // granule count and not part of the audible clip.
                preSkip = readShortLittleEndian(bytes, dataAt + 10)
                sawOpusHead = true
            }
            lastGranule = readLongLittleEndian(bytes, at + 6)
            at = dataAt + payload
        }
        if (!sawOpusHead) return Audio.Undecodable
        if (lastGranule < 0) return Audio.DurationUnknown
        // Opus always decodes at 48 kHz. The 24 kHz in SPEC.md 6 is the rate fed
        // to the encoder and is recorded in OpusHead as an informational field; a
        // check that assumed it here would be wrong on every correct file.
        val samples = lastGranule - preSkip
        if (samples <= 0) return Audio.DurationUnknown
        return Audio.Playable(samples.toDouble() / 48_000.0)
    }

    private fun wavDuration(bytes: ByteArray): Audio {
        if (bytes.size < 12 || !startsWith(bytes, "WAVE", 8)) return Audio.Undecodable
        var at = 12
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var format = 0
        var dataSize = -1
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = readIntLittleEndian(bytes, at + 4)
            if (size < 0) return Audio.Undecodable
            val body = at + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return Audio.Undecodable
                    format = readShortLittleEndian(bytes, body)
                    channels = readShortLittleEndian(bytes, body + 2)
                    sampleRate = readIntLittleEndian(bytes, body + 4)
                    bitsPerSample = readShortLittleEndian(bytes, body + 14)
                }

                "data" -> {
                    dataSize = minOf(size, bytes.size - body)
                }
            }
            at = body + size + (size and 1) // chunks are word-aligned
        }
        // SPEC.md 6 names exactly one legacy shape. Accepting others would quietly
        // widen the format past what a builder is allowed to rely on.
        val isToleratedShape =
            format == WAV_FORMAT_PCM && channels == 1 && sampleRate == 16_000 && bitsPerSample == 16
        if (!isToleratedShape) return Audio.Undecodable
        if (dataSize < 0) return Audio.Undecodable
        val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
        if (bytesPerSecond <= 0) return Audio.DurationUnknown
        return Audio.Playable(dataSize.toDouble() / bytesPerSecond.toDouble())
    }

    private const val WAV_FORMAT_PCM = 1

    private fun startsWith(
        bytes: ByteArray,
        magic: String,
        at: Int = 0,
    ): Boolean {
        if (at + magic.length > bytes.size) return false
        for (i in magic.indices) {
            if (bytes[at + i].toInt() and 0xFF != magic[i].code) return false
        }
        return true
    }

    private fun readIntBigEndian(
        bytes: ByteArray,
        at: Int,
    ): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun readShortBigEndian(
        bytes: ByteArray,
        at: Int,
    ): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun readShortLittleEndian(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readIntLittleEndian(
        bytes: ByteArray,
        at: Int,
    ): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun readLongLittleEndian(
        bytes: ByteArray,
        at: Int,
    ): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }
}
