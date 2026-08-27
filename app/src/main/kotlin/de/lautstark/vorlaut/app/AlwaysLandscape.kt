package de.lautstark.vorlaut.app

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
import de.lautstark.vorlaut.app.design.VorlautBoard

/**
 * A quarter turn, so the board is always landscape even when the tablet is not.
 *
 * Asking Android for landscape is the obvious way to do this and it no longer
 * works: Android 16 ignores a fixed `screenOrientation` on large screens, and
 * the property that opted out of that is gone at targetSdk 37, which is what
 * this app compiles against. Measured on a Galaxy Tab, where the request was
 * simply not honoured.
 *
 * So the rotation is ours. In a portrait window the content is measured
 * landscape — the window's two sides swapped — and drawn turned, which fills
 * the screen rather than letterboxing it and leaves the person holding the
 * tablet one obvious thing to do about it. Turn it clockwise and the board
 * comes upright; with auto-rotate on, the window itself becomes landscape at
 * that moment and this stops applying, so the turn is a correction the person
 * makes once rather than a state the app holds them in.
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
    Box(
        modifier
            .fillMaxSize()
            .background(VorlautBoard.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= maxHeight) {
                content()
            } else {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        // requiredSize rather than size: the point is to be
                        // larger than the window in one direction, and the
                        // incoming constraints exist to forbid exactly that.
                        .requiredSize(width = maxHeight, height = maxWidth)
                        .graphicsLayer { rotationZ = -90f },
                ) {
                    content()
                }
            }
        }
    }
}
