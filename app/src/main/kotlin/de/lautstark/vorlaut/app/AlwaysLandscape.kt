package de.lautstark.vorlaut.app

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import de.lautstark.vorlaut.app.design.VorlautBoard

/**
 * The board, nailed to the glass.
 *
 * It is drawn landscape, filling the screen, on the same pixels every time.
 * Turning the tablet does not move it: held the right way round it is upright,
 * held the other way round it is upside down, exactly as a sheet of paper taped
 * to the screen would be. That is what a lock is. A board is a page whose shape
 * and position a child learns, and the strongest version of that promise is one
 * the device cannot interrupt.
 *
 * Asking Android for landscape would have been the obvious way and it no longer
 * works: it ignores a fixed `screenOrientation` on large screens from Android
 * 16, and the property that opted out of that is gone at targetSdk 37, which is
 * what this app compiles against. Measured on a Galaxy Tab, where the request
 * was simply not honoured.
 *
 * So the one thing that has to be undone is Android's own turning. The window
 * is rotated by `r` quarters away from the tablet's natural orientation
 * whenever auto-rotate acts, so `-r` puts the board back where it was, and one
 * further quarter forces landscape wherever that leaves it portrait. The result
 * is constant: on this tablet the board sits three quarters from natural in
 * every one of the four cases, which is the whole point — there is nothing to
 * work out from gravity, because nothing is supposed to follow gravity.
 *
 * Two things this cannot reach, both because they are not our window: anything
 * Android draws itself — the pinning notice, a toast, a permission sheet — and
 * anything Compose puts in a window of its own, which is every `Dialog` and
 * `Popup`. That is why this wraps the board and not the whole app. The board
 * has none of those; the list screens do, and a list is the one shape that was
 * never wrong in portrait anyway.
 *
 * The safe area is a property of the physical screen, so it is held back out
 * here, before the turn, rather than inside it where the status bar's inset
 * would end up padding one of the board's sides.
 */
@Composable
fun AlwaysLandscape(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val r = windowQuarter()

    Box(
        modifier
            .fillMaxSize()
            .background(VorlautBoard.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            var turns = Math.floorMod(-r, 4)
            val windowIsLandscape = maxWidth >= maxHeight
            // Added rather than subtracted, and it matters: the two are the two
            // ways round a landscape board can be nailed on, and one of them is
            // upside down for the whole life of the tablet. This is the one
            // where a tablet reporting ROTATION_90 -- an ordinary landscape
            // hold, and what a Galaxy Tab reports when it is picked up -- needs
            // no turn from us at all.
            if (windowIsLandscape != (turns % 2 == 0)) turns = Math.floorMod(turns + 1, 4)

            if (turns == 0) {
                content()
            } else {
                val turned = turns % 2 == 1
                Box(
                    Modifier
                        .align(Alignment.Center)
                        // requiredSize rather than size: the point is to be
                        // larger than the window in one direction, and the
                        // incoming constraints exist to forbid exactly that.
                        .requiredSize(
                            width = if (turned) maxHeight else maxWidth,
                            height = if (turned) maxWidth else maxHeight,
                        ).graphicsLayer { rotationZ = turns * 90f },
                ) {
                    content()
                }
            }
        }
    }
}

/** How far Android has turned the window away from the tablet's natural orientation. */
@Composable
private fun windowQuarter(): Int {
    val context = LocalContext.current
    // Read so that a configuration change recomposes this and the rotation is
    // asked for again. The activity is not recreated for one any more.
    LocalConfiguration.current
    val rotation =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }
    return when (rotation) {
        Surface.ROTATION_90 -> 1
        Surface.ROTATION_180 -> 2
        Surface.ROTATION_270 -> 3
        else -> 0
    }
}
