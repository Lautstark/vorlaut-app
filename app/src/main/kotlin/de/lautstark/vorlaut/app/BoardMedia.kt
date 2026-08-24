package de.lautstark.vorlaut.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import de.lautstark.vorlaut.boardpackage.PackageArchive

/**
 * Decodes the pictures a board draws, and keeps them.
 *
 * Two things this must not do. It must not decode an image at full size to then
 * draw it into a button a fraction of that: a board is a grid, and thirty
 * buttons decoded at the 1024 cap would be 120 MiB of bitmap on a device whose
 * per-app heap is smaller than that. And it must not decode the same file once
 * per button — `multipage` shares one back arrow across two boards under one
 * image id, and a viewer that copies it per board wastes memory for nothing.
 *
 * So bitmaps are sampled down to roughly the size they will be drawn at, and
 * cached by archive path.
 */
class BoardMedia(
    private val archive: PackageArchive?,
) {
    private val cache = HashMap<String, ImageBitmap?>()

    /**
     * The picture at [path], sampled for a button about [targetPx] across, or
     * null if it cannot be drawn.
     *
     * Null is a normal answer. The importer already marked as degraded any button
     * whose picture was missing, oversized or undecodable, so a null here is
     * either that button or a file that has gone since — and either way the
     * button draws without a picture rather than not at all.
     */
    fun image(
        path: String?,
        targetPx: Int,
    ): ImageBitmap? {
        if (path == null || archive == null) return null
        val key = "$path@${bucket(targetPx)}"
        return cache.getOrPut(key) { decode(path, bucket(targetPx)) }
    }

    /** The clip at [path], or null. Handed to the player as bytes, never a file. */
    fun audio(path: String): ByteArray? = archive?.read(path)

    private fun decode(
        path: String,
        targetPx: Int,
    ): ImageBitmap? {
        val bytes = archive?.read(path) ?: return null
        // Measure first, with inJustDecodeBounds, so nothing large is allocated
        // before the sample size is known. This is the same order the importer
        // reads the header in, and for the same reason.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        targetPx: Int,
    ): Int {
        if (targetPx <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return sample
    }

    /**
     * Rounds the requested size up to a power of two.
     *
     * Without this the cache key would change with every few pixels of layout
     * jitter — a rotation, a bar appearing — and the same picture would be
     * decoded again at 253 px and 256 px and 260 px.
     */
    private fun bucket(targetPx: Int): Int {
        var size = MIN_BUCKET
        while (size < targetPx && size < MAX_BUCKET) size *= 2
        return size
    }

    fun clear() = cache.clear()

    private companion object {
        const val MIN_BUCKET = 64

        /** SPEC.md 5.3 caps images at 1024, so nothing usefully decodes above it. */
        const val MAX_BUCKET = 1024
    }
}
