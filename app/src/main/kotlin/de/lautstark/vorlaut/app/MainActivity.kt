package de.lautstark.vorlaut.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/** Where the app is. Three places, and no library needed to say so. */
private sealed interface Route {
    data object Packages : Route

    data object Board : Route

    data class Warnings(
        val packageId: String,
    ) : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: ImportViewModel = viewModel()
            val state by model.state.collectAsState()

            val picker =
                androidx.activity.compose.rememberLauncherForActivityResult(
                    // OpenDocument rather than GetContent: it gives a persistable,
                    // re-readable URI and lets the caller filter by type. The file
                    // is copied into app-private storage immediately either way.
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(model::importFrom) }

            // A package arriving by VIEW or SEND is handled once, on the intent
            // that started or resumed the activity.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                consumeIncoming(intent)?.let(model::importFrom)
            }

            val boardModel: BoardViewModel = viewModel()
            val boardState by boardModel.state.collectAsState()
            var route by remember { mutableStateOf<Route>(Route.Packages) }

            MaterialTheme {
                Scaffold { padding ->
                    val inset = Modifier.padding(padding)
                    when (val here = route) {
                        Route.Packages -> {
                            ImportScreen(
                                state = state,
                                onPickFile = { picker.launch(IMPORTABLE_TYPES) },
                                onOpen = { entry ->
                                    boardModel.open(
                                        entry.boardPackage,
                                        entry.warnings,
                                        entry.archive.readBytes(),
                                    )
                                    route = Route.Board
                                },
                                onShowWarnings = { entry ->
                                    route = Route.Warnings(entry.boardPackage.id)
                                },
                                modifier = inset,
                            )
                        }

                        Route.Board -> {
                            TalkerScreen(
                                state = boardState,
                                media = boardModel.mediaLoader(),
                                onPress = boardModel::press,
                                onOpenWarnings = {
                                    route = Route.Warnings(boardState.boardPackage?.id.orEmpty())
                                },
                                onClosePackage = {
                                    boardModel.close()
                                    route = Route.Packages
                                },
                                modifier = inset,
                            )
                        }

                        is Route.Warnings -> {
                            val entry = state.stored.firstOrNull { it.boardPackage.id == here.packageId }
                            WarningsScreen(
                                packageName = entry?.boardPackage?.name.orEmpty(),
                                warnings = entry?.warnings.orEmpty(),
                                onBack = {
                                    route =
                                        if (boardState.boardPackage == null) Route.Packages else Route.Board
                                },
                                modifier = inset,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun consumeIncoming(intent: Intent?): Uri? {
        intent ?: return null
        val uri =
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> intent.extraStream()
                else -> null
            } ?: return null
        // Cleared so a configuration change does not re-import the same file.
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
         * `.obz` has no registered media type. SPEC.md 2 gives it `application/zip`,
         * but content providers routinely report an unknown extension as
         * `application/octet-stream`, so both are offered — a picker that cannot
         * see the file is not a picker. Anything that is not a board package is
         * refused by the importer with a reason, which is a better place to find
         * out than a filter that silently hides the file the user meant.
         */
        val IMPORTABLE_TYPES =
            arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")
    }
}
