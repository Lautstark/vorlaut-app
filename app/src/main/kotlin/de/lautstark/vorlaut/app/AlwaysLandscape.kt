package de.lautstark.vorlaut.app

import android.content.Context
import android.graphics.Point
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
 * Landscape is not the invariant. *Unmoving* is: a quarter turn that arrives
 * for two frames and leaves again has already cost the child the button they
 * were reaching for, even though every frame of it was landscape.
 *
 * Asking Android for landscape would have been the obvious way and it no longer
 * works: it ignores a fixed `screenOrientation` on large screens from Android
 * 16, and the property that opted out of that is gone at targetSdk 37, which is
 * what this app compiles against. Measured on a Galaxy Tab, where the request
 * was simply not honoured.
 *
 * So the one thing that has to be undone is Android's own turning, and
 * [boardTurns] undoes it from the display and nothing else. Both numbers it
 * needs -- how far the display is turned, and which way round the display is at
 * rest -- come from one read of one [android.view.Display], so they cannot
 * describe two different moments. That is the whole fix over the build before
 * this one, which took the turn from the display and the shape from the window
 * Compose had measured. Those are two clocks. They agree almost always, and
 * during a rotation they briefly do not -- the display reports the new turn one
 * layout pass before the window is resized to match -- and for that pass the
 * board was drawn a quarter out and then snapped back. Which of the two is
 * ahead decides whether the arithmetic comes out right, which is why it moved
 * on some rotations and not others. A window that is not the display's shape at
 * all -- split screen, freeform, a letterbox -- held it out of true for as long
 * as the window lasted.
 *
 * The window is still what the board is *sized* to, below, and that is a
 * different question from which way it faces: the constraints say how much room
 * there is, the display says which way is nailed. Only the second one is a
 * promise to the child, so only the second one is defended here.
 *
 * Two things this cannot reach, both because they are not our window: anything
 * Android draws itself -- the pinning notice, a toast, a permission sheet -- and
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
    val glass = glass()
    val turns = boardTurns(glass.quarter, glass.naturalIsLandscape)

    Box(
        modifier
            .fillMaxSize()
            .background(VorlautBoard.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (turns == 0) {
            content()
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
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
 * How far to turn the board so that it lands on the same glass every time.
 *
 * [quarter] is how far Android has turned the window away from the tablet's
 * natural orientation, so `-quarter` puts the board back where it was and the
 * board stops moving. That is the whole of it on a tablet whose natural
 * orientation is already landscape.
 *
 * On one whose natural orientation is portrait -- a Galaxy Tab, and most of
 * them -- `-quarter` lands the board portrait, so it takes one further quarter,
 * and there are two ways to add it. The one kept is the one where a tablet
 * reporting `ROTATION_90` -- an ordinary landscape hold, and what this tablet
 * reports when it is picked up -- needs no turn from us at all. The other is
 * upside down for the whole life of the tablet, which is why the sign here is a
 * fact about a device somebody held, not a preference.
 *
 * What makes this a nail rather than a rule about landscape is that
 * `boardTurns(q) + q` is the same for all four values of `q`. The board's angle
 * against the glass is a constant; the turn only ever cancels Android's.
 */
internal fun boardTurns(
    quarter: Int,
    naturalIsLandscape: Boolean,
): Int = Math.floorMod(if (naturalIsLandscape) -quarter else 1 - quarter, 4)

/**
 * The two facts about the glass, read together.
 *
 * @property quarter how far the display is turned from its natural orientation.
 * @property naturalIsLandscape whether the display is wider than it is tall
 *   when it is not turned at all. A property of the hardware, and the reason
 *   the two are returned as one value: it can only be worked out by pairing the
 *   display's shape *now* with the turn it is under *now*, and pairing two
 *   moments is what used to go wrong.
 */
private data class Glass(
    val quarter: Int,
    val naturalIsLandscape: Boolean,
)

@Composable
private fun glass(): Glass {
    val context = LocalContext.current
    // Read so that a configuration change recomposes this and the display is
    // asked again. The activity is not recreated for one any more.
    LocalConfiguration.current

    val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // The maximum metrics, not the current ones: the current ones are this
        // window, which in split screen is not the display, and the display is
        // the thing the board is nailed to.
        val bounds = windows.maximumWindowMetrics.bounds
        glassOf(context.display?.rotation, bounds.width(), bounds.height())
    } else {
        @Suppress("DEPRECATION")
        val display = windows.defaultDisplay

        @Suppress("DEPRECATION")
        val size = Point().also { display.getRealSize(it) }
        glassOf(display.rotation, size.x, size.y)
    }
}

/**
 * [width] and [height] are the display as it is *now*, already turned by
 * [rotation], so undoing that turn says which way round it is at rest.
 */
private fun glassOf(
    rotation: Int?,
    width: Int,
    height: Int,
): Glass {
    val quarter =
        when (rotation) {
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> 0
        }
    return Glass(quarter, (width >= height) == (quarter % 2 == 0))
}
