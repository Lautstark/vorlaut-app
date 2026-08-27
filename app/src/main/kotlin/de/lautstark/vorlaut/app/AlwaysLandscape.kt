package de.lautstark.vorlaut.app

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.view.OrientationEventListener
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import de.lautstark.vorlaut.app.design.VorlautBoard
import kotlin.math.abs

/**
 * How far past a boundary the tablet has to be tipped before the board accepts
 * a new quarter. Without it a tablet held at 45 degrees flickers between two
 * answers, which is worse than either of them.
 */
private const val QUARTER_MARGIN = 35

/**
 * The board, kept landscape and kept upright, whichever way the tablet is held.
 *
 * Asking Android for landscape is the obvious way to do this and it no longer
 * works: Android 16 ignores a fixed `screenOrientation` on large screens, and
 * the property that opted out of that is gone at targetSdk 37, which is what
 * this app compiles against. Measured on a Galaxy Tab, where the request was
 * simply not honoured. So the turn is ours.
 *
 * Keying it off the window's shape was not enough, and this is the part worth
 * knowing. A fixed quarter turn is fixed to the *tablet*, so the board rides
 * round with it and lands upside down half the time. What the turn has to
 * follow is gravity, and gravity is not something the window can be asked
 * about: with auto-rotate off — which is what anyone setting up a child's
 * tablet is told to do — the window never changes at all, however the tablet is
 * held.
 *
 * So two numbers, and the difference between them is the answer:
 *
 *  - `q`, which way up the tablet is being held, in quarter turns clockwise
 *    from its natural orientation. This comes off the accelerometer and is true
 *    whether or not Android is acting on it.
 *  - `r`, how far Android has already turned the window to compensate. With
 *    auto-rotate on this tracks `q` and the difference is zero; with it off it
 *    stays put and the difference is the whole tilt.
 *
 * The two count opposite ways round, which is measured rather than assumed: on
 * a tablet tipped one quarter clockwise Android reports `ROTATION_270`, not
 * `ROTATION_90`. So an untouched board sits at `q + r` quarter turns from
 * upright — zero whenever auto-rotate is doing its job, since the two then
 * cancel — and `-(q + r)` is what puts it back.
 *
 * The board is then forced landscape by one further quarter where that leaves
 * it portrait, always in the same direction, so that a sideways board is always
 * the same request: turn the tablet clockwise. Which way up it already is must
 * not change the answer, or the request stops being learnable.
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
    val q = rememberTabletQuarter()
    val r = windowQuarter()

    Box(
        modifier
            .fillMaxSize()
            .background(VorlautBoard.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            var turns = Math.floorMod(-(q + r), 4)
            val windowIsLandscape = maxWidth >= maxHeight
            // Landscape once turned, or one more quarter until it is. Always
            // the same direction: the board being sideways is a request to turn
            // the tablet, and the request has to be the same one every time.
            if (windowIsLandscape != (turns % 2 == 0)) turns = Math.floorMod(turns - 1, 4)

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

/**
 * Which way up the tablet is being held: quarter turns clockwise from its
 * natural orientation, straight off the accelerometer.
 *
 * A tablet lying flat has no answer to give — [OrientationEventListener] says
 * so with `ORIENTATION_UNKNOWN` — and the last one it gave is kept, because a
 * board that reshuffles itself when somebody puts the tablet down on the table
 * is a board that has moved for no reason the person can see.
 */
@Composable
private fun rememberTabletQuarter(): Int {
    val context = LocalContext.current
    var quarter by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener =
            object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(degrees: Int) {
                    if (degrees == ORIENTATION_UNKNOWN) return
                    val next = ((degrees + 45) / 90) % 4
                    if (next == quarter) return
                    // Only once it is clearly inside the new quarter, not the
                    // moment it crosses the line.
                    val off = abs(degrees - next * 90).let { minOf(it, 360 - it) }
                    if (off <= QUARTER_MARGIN) quarter = next
                }
            }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return quarter
}

/** How far Android has already turned the window, in the same quarter turns. */
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
