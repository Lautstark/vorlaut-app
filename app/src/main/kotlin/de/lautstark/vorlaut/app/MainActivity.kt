package de.lautstark.vorlaut.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import de.lautstark.vorlaut.app.design.Vorlaut
import de.lautstark.vorlaut.app.design.VorlautTheme
import kotlinx.coroutines.launch

/**
 * Where the app is.
 *
 * [Board] is the front door whenever there is a Sammlung to open: this is a
 * talker, and a child picking the tablet up should get a board rather than a
 * file list. The other two are the adult's, reached on purpose.
 */
private sealed interface Route {
    data object Board : Route

    data object Sammlungen : Route

    data class Warnings(
        val packageId: String,
    ) : Route

    data object Settings : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: ImportViewModel = viewModel()
            val state by model.state.collectAsState()
            val boardModel: BoardViewModel = viewModel()
            val boardState by boardModel.state.collectAsState()

            val handover = remember { Handover(applicationContext) }
            var pinIsSet by remember { mutableStateOf(handover.isPinSet) }
            var prompt by remember { mutableStateOf<PinPurpose?>(null) }
            var pinBusy by remember { mutableStateOf(false) }
            var pinWrong by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            var route by remember { mutableStateOf<Route>(Route.Sammlungen) }
            var opened by remember { mutableStateOf(false) }

            val picker =
                androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(model::importFrom) }

            LaunchedEffect(Unit) { consumeIncoming(intent)?.let(model::importFrom) }

            /* The front door. With a Sammlung on the device the app opens on the
               board it was last on — the list is where an adult goes on purpose,
               not what a child is handed. Runs once, so that leaving the board
               deliberately does not bounce straight back into it. */
            LaunchedEffect(state.stored) {
                if (opened || state.stored.isEmpty()) return@LaunchedEffect
                val entry =
                    state.stored.firstOrNull { it.boardPackage.id == handover.lastPackageId }
                        ?: state.stored.first()
                handover.lastPackageId = entry.boardPackage.id
                boardModel.open(entry.boardPackage, entry.warnings, entry.archive)
                route = Route.Board
                opened = true
            }

            // The board is left by holding the handle. If a PIN exists it is
            // asked for; if it does not, leaving simply happens. There is no
            // mode in between, because a tablet in a child's hands has no
            // opposite state to be in.
            fun leaveBoard() {
                if (pinIsSet) prompt = PinPurpose.Unlock else route = Route.Sammlungen
            }

            // Android's pinning follows the board rather than a mode: asked
            // for on arrival, released on the way out. It is best-effort and
            // usually refused — pinning is off on most tablets and no app can
            // turn it on — so nothing here depends on it working. Settings says
            // plainly whether it is in force; this just asks.
            LaunchedEffect(route) {
                if (route == Route.Board) {
                    handover.pinToScreen(this@MainActivity)
                    hideSystemBars()
                } else {
                    handover.releaseScreen(this@MainActivity)
                    showSystemBars()
                }
            }

            // And again whenever the app comes back to the front, because the
            // route has not changed then and the effect above will not run. A
            // tablet that was legitimately unpinned — the caregiver knew the
            // gesture, or the system dropped it — otherwise stays unpinned for
            // the rest of the day, and nothing on screen says so.
            val onBoard by rememberUpdatedState(route == Route.Board)
            DisposableEffect(Unit) {
                val watcher =
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME && onBoard) {
                            handover.pinToScreen(this@MainActivity)
                            hideSystemBars()
                        }
                    }
                lifecycle.addObserver(watcher)
                onDispose { lifecycle.removeObserver(watcher) }
            }

            VorlautTheme {
                Box(Modifier.fillMaxSize().background(Vorlaut.colors.bg)) {
                    when (val here = route) {
                        Route.Board -> {
                            // Back is one of the ordinary ways out, so while the
                            // tablet is handed over it costs the same PIN the
                            // long press does.
                            BackHandler(enabled = true) { leaveBoard() }
                            TalkerScreen(
                                state = boardState,
                                media = boardModel.mediaLoader(),
                                onPress = boardModel::press,
                                onSpeak = boardModel::speakBar,
                                onUndo = boardModel::undo,
                                onClear = boardModel::clearBar,
                                onLeave = ::leaveBoard,
                            )
                        }

                        Route.Sammlungen -> {
                            SammlungenScreen(
                                state = state,
                                onAdd = { picker.launch(IMPORTABLE_TYPES) },
                                onOpen = { entry ->
                                    handover.lastPackageId = entry.boardPackage.id
                                    boardModel.open(entry.boardPackage, entry.warnings, entry.archive)
                                    route = Route.Board
                                },
                                onWarnings = { route = Route.Warnings(it.boardPackage.id) },
                                onRemove = { entry ->
                                    // The board may still be holding this
                                    // package open behind the list. Let it go
                                    // before the files under it disappear.
                                    val id = entry.boardPackage.id
                                    if (handover.lastPackageId == id) {
                                        handover.lastPackageId = null
                                        boardModel.close()
                                    }
                                    model.remove(id)
                                },
                                onSettings = { route = Route.Settings },
                            )
                        }

                        is Route.Warnings -> {
                            val entry = state.stored.firstOrNull { it.boardPackage.id == here.packageId }
                            WarningsScreen(
                                packageName = entry?.boardPackage?.name.orEmpty(),
                                warnings = entry?.warnings.orEmpty(),
                                onBack = { route = Route.Sammlungen },
                            )
                        }

                        Route.Settings -> {
                            SettingsScreen(
                                pinIsSet = pinIsSet,
                                pinningAvailable = handover.isPinningAllowedBySystem(),
                                onSetPin = { prompt = PinPurpose.Choose },
                                onRemovePin = { prompt = PinPurpose.Remove },
                                onFixPinning = {
                                    runCatching {
                                        startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                                    }
                                },
                                onBack = { route = Route.Sammlungen },
                            )
                        }
                    }

                    prompt?.let { purpose ->
                        PinPrompt(
                            purpose = purpose,
                            busy = pinBusy,
                            wrong = pinWrong,
                            onDismiss = {
                                prompt = null
                                pinWrong = false
                            },
                            onSubmit = { entered ->
                                pinWrong = false
                                pinBusy = true
                                scope.launch {
                                    when (purpose) {
                                        PinPurpose.Choose -> {
                                            if (PinHash.isAcceptable(entered)) {
                                                handover.setPin(entered)
                                                pinIsSet = true
                                                prompt = null
                                            } else {
                                                pinWrong = true
                                            }
                                        }

                                        PinPurpose.Unlock -> {
                                            if (handover.verify(entered)) {
                                                handover.releaseScreen(this@MainActivity)
                                                prompt = null
                                                route = Route.Sammlungen
                                            } else {
                                                pinWrong = true
                                            }
                                        }

                                        PinPurpose.Remove -> {
                                            if (handover.verify(entered)) {
                                                handover.removePin()
                                                pinIsSet = false
                                                prompt = null
                                            } else {
                                                pinWrong = true
                                            }
                                        }
                                    }
                                    pinBusy = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * Takes the clock and the navigation bar off the board.
     *
     * They cost 112px at the top and 104px at the foot of a 1200px tablet — 18%
     * of the height, on the one screen where height is what the grid is made
     * of. On a 6x11 board that is the difference between cramped and legible,
     * and a child does not need the battery percentage.
     *
     * Only the board. The three list screens keep theirs: an adult stands in
     * front of those, the clock is useful there, and nothing on them is short
     * of room.
     *
     * **This hides, it does not lock.** A swipe from either edge brings the bars
     * back for a moment, which is exactly how the person holding the tablet
     * reaches the way out — and the unpin gesture is unaffected either way.
     * Nothing here adds to what pinning already does or does not guarantee.
     *
     * API 30 is where the controller arrived. Below it the bars stay, and
     * `safeDrawing` in TalkerScreen keeps holding the grid clear of them, which
     * is the same behaviour this app has had all along.
     */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.insetsController?.show(WindowInsets.Type.systemBars())
    }

    private fun consumeIncoming(intent: Intent?): Uri? {
        intent ?: return null
        val uri =
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> intent.extraStream()
                else -> null
            } ?: return null
        intent.action = null
        intent.data = null
        return uri
    }

    @Suppress("DEPRECATION")
    private fun Intent.extraStream(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private companion object {
        /**
         * `.obz` has no registered media type. SPEC.md 2 gives it
         * `application/zip`, but providers routinely report an unknown
         * extension as `application/octet-stream`, so both are offered — a
         * picker that cannot see the file is not a picker.
         */
        val IMPORTABLE_TYPES =
            arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")
    }
}
