package de.lautstark.vorlaut.app

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.lautstark.vorlaut.app.design.AppBar
import de.lautstark.vorlaut.app.design.Btn
import de.lautstark.vorlaut.app.design.BtnTier
import de.lautstark.vorlaut.app.design.Notice
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut

/**
 * The screen that listens.
 *
 * One sentence, one address, one line saying what to do with it. Nothing else is
 * on it, because anybody looking at it is looking for the number.
 *
 * **The socket lives exactly as long as this screen does.** Bound when it
 * appears, closed when it is left, and closed again when the app goes to the
 * background — there is no foreground service and no port open while a child is
 * using the board. That is not a performance decision; it is most of what makes
 * the INTERNET permission in the manifest a narrow thing rather than a standing
 * one, and it is why the receiver is owned here rather than by the activity.
 *
 * There is deliberately no progress bar. The plate that later says „ersetzt"
 * says „wird empfangen" first — `Lautstark/design`'s design.md §4.3 sends
 * progress to "the control that started it", and §4.2 closes the motion budget
 * at 130ms for colour and 220ms for size or position, which an indeterminate
 * loop is neither.
 */
@Composable
fun EmpfangenScreen(
    state: ImportUiState,
    onArriving: (Long) -> Unit,
    onPackage: (ByteArray) -> PackageReceiver.Reply,
    onLanded: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vorlaut.colors

    // The receiver calls these from its own thread, long after the composition
    // that created them. rememberUpdatedState so a recomposition does not leave
    // the socket holding a lambda closed over stale state.
    val arriving by rememberUpdatedState(onArriving)
    val handle by rememberUpdatedState(onPackage)

    var address by remember { mutableStateOf(LanAddress.current()) }

    // Whether the port was actually taken. It can be busy — another copy of
    // this app, or anything else on the device that got there first — and a
    // screen showing an address that answers nothing is worse than one saying
    // so, because the person would go and check a correct number.
    var listening by remember { mutableStateOf(true) }

    /*
     * A package landed, and this screen is done.
     *
     * Set on the receiver's thread and acted on here, on the main thread, rather
     * than the handler navigating for itself. That is not tidiness: leaving this
     * screen disposes the receiver, so a handler that navigated would be asking
     * the socket to shut down from inside its own connection, and it would be
     * doing it before the response it is in the middle of writing had been
     * written.
     *
     * A flag of this screen's own rather than watching `state.lastOutcome`,
     * because that is also set by the file picker: an outcome left over from an
     * earlier import would bounce somebody straight back out of a screen they
     * had only just opened.
     */
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(landed) { if (landed) onLanded() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val receiver =
            PackageReceiver(
                maxBytes = ImportViewModel.MAX_PACKAGE_BYTES,
                onArriving = { arriving(it) },
                onPackage = { bytes ->
                    handle(bytes).also { landed = true }
                },
            )

        // Started on ON_START rather than here, and stopped on ON_STOP, so that
        // the port closes when the app is put away and comes back when it is
        // picked up — without the person having to leave and re-enter the screen.
        // The address is re-read each time for the same reason: a tablet that
        // changed network while the app was away would otherwise show the number
        // it had yesterday.
        val watcher =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        address = LanAddress.current()
                        listening = receiver.start()
                    }

                    Lifecycle.Event.ON_STOP -> {
                        receiver.close()
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycle.addObserver(watcher)
        onDispose {
            lifecycle.removeObserver(watcher)
            receiver.close()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AppBar(where = stringResource(R.string.receive_where))

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Vorlaut.metrics.screenMargin, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            val incoming = state.arriving
            if (!listening) {
                Box(Modifier.widthIn(max = 460.dp)) {
                    Notice(stringResource(R.string.receive_port_busy), bad = true)
                }
            } else if (incoming == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Pulse()
                    Txt(
                        stringResource(R.string.receive_waiting),
                        style = Vorlaut.type.title.copy(fontSize = 21.sp),
                        color = c.text,
                    )
                }
            } else {
                // The same plate the outcome arrives on, doing the same job one
                // step earlier. Its ✕ is absent on purpose: there is nothing to
                // dismiss about something that is still happening.
                Box(Modifier.widthIn(max = 460.dp)) {
                    Notice(stringResource(R.string.receive_running), busy = true)
                }
            }

            Address(if (listening) address else null)

            Txt(
                when {
                    !listening -> stringResource(R.string.receive_port_busy_hint)
                    address == null -> stringResource(R.string.receive_no_network)
                    incoming != null -> stringResource(R.string.receive_running_hint, megabytes(incoming))
                    else -> stringResource(R.string.receive_hint)
                },
                style = Vorlaut.type.body,
                color = c.textDim,
                align = TextAlign.Center,
                // senden.css holds this line to 44ch. A measure, not a layout:
                // the sentence under the address is read once and should not
                // run the width of a tablet.
                modifier = Modifier.widthIn(max = 352.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Vorlaut.metrics.screenMargin, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Btn(stringResource(R.string.back), onBack, tier = BtnTier.Quiet)
        }
    }
}

/**
 * The address, read rather than typed.
 *
 * Grouped exactly as the editor's four input boxes are grouped and dimmed in
 * exactly the same two places, so that what has to be copied is already picked
 * out on the screen it is copied from. That correspondence is the only reason
 * this is not simply a line of text, and it is the whole of `senden.css`'s
 * `.anzeige`.
 *
 * Big because of where it is read from: a tablet on a table and a keyboard an
 * arm's length away. Monospace and tabular so that four numbers changing one at
 * a time do not shift the ones beside them.
 */
@Composable
private fun Address(address: String?) {
    val c = Vorlaut.colors
    val digits =
        Vorlaut.type.mono.copy(
            fontSize = 44.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.02).em,
        )

    if (address == null) {
        Txt("—", style = digits, color = c.textFaint)
        return
    }

    val octets = address.split('.')
    Row(verticalAlignment = Alignment.Bottom) {
        octets.forEachIndexed { index, octet ->
            if (index > 0) Txt(".", style = digits, color = c.textFaint)
            // The first two step back and the last two do not. On a home network
            // the first half is the router's habit and the second half is this
            // tablet — so the two numbers actually worth reading carefully are
            // the two at full strength.
            Txt(octet, style = digits, color = if (index < 2) c.textFaint else c.text)
        }
    }
}

/**
 * Alive without being busy.
 *
 * The tablet is doing nothing until something arrives and a spinner would claim
 * otherwise, so this is a dot breathing rather than anything turning. Opacity
 * only, which is the one thing `Lautstark/design`'s §4.2 motion budget spends
 * freely, and it is gone entirely while a package is arriving — by then the
 * plate is saying so in words.
 */
@Composable
private fun Pulse() {
    val c = Vorlaut.colors

    // Somebody who has turned animations off gets a steady dot rather than a
    // frozen one. senden.css says the same thing in its own idiom
    // (`@media (prefers-reduced-motion: reduce) { .puls { animation: none;
    // opacity: 1 } }`), and unlike a bar there is no honest still frame to
    // stop on — a dot held at 30% is just a dimmer dot.
    //
    // Read here rather than borrowed, because the equivalent helper is being
    // written in Components.kt on claude/ticker-reduced-motion and has not
    // landed. When it does, this should collapse into it: rememberInfinite-
    // Transition runs off withInfiniteAnimationFrameNanos, which the
    // MotionDurationScale that slows ordinary animations never reaches, so
    // every infinite animation in this app needs the same explicit check.
    if (animationsOff()) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.accent),
        )
        return
    }

    val cycle = rememberInfiniteTransition(label = "puls")
    val opacity by cycle.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Reverse),
        label = "puls",
    )
    Box(
        Modifier
            .size(10.dp)
            .alpha(opacity)
            .clip(RoundedCornerShape(999.dp))
            .background(c.accent),
    )
}

/**
 * Whether this device has been told to stop animating things.
 *
 * `ANIMATOR_DURATION_SCALE` at zero is what Android's "Remove animations"
 * accessibility toggle writes, and it is the platform's answer to the web's
 * `prefers-reduced-motion`. These tablets belong to people who did not choose
 * their hardware, and somebody who turned that switch on because motion makes
 * them ill is not helped by an exception that breathes for as long as the
 * screen is open.
 */
@Composable
private fun animationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Rounded, because the point is the order of magnitude and not the byte count. */
private fun megabytes(bytes: Long): Int = ((bytes + 512 * 1024) / (1024 * 1024)).toInt()
