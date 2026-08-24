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
import androidx.lifecycle.viewmodel.compose.viewModel

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

            MaterialTheme {
                Scaffold { padding ->
                    ImportScreen(
                        state = state,
                        onPickFile = { picker.launch(IMPORTABLE_TYPES) },
                        modifier =
                            androidx.compose.ui.Modifier
                                .padding(padding),
                    )
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
