package de.lautstark.vorlaut.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Holds the screen awake while [enabled].
 *
 * Applied to the board and nowhere else. A talker that goes dark mid-sentence
 * has stopped being a talker: the person using it has to wake it before they can
 * finish saying the thing they had started saying, and the sentence they had
 * composed is behind a lock screen. The package list is a different matter — it
 * is read for a moment and put down, and pinning it awake would only cost
 * battery.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
