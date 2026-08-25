package de.lautstark.vorlaut.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImportViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val store = PackageStore(application.filesDir)

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { store.list() }
            _state.value = _state.value.copy(stored = stored, busy = false)
        }
    }

    /**
     * Forgets a Sammlung. The board is closed first if it is the open one —
     * rendering a package whose files have gone is a crash waiting for the next
     * symbol to be drawn.
     */
    fun remove(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(id) }
            _state.value = _state.value.copy(lastOutcome = null, readError = null)
            refresh()
        }
    }

    fun importFrom(uri: Uri) {
        _state.value = _state.value.copy(busy = true, lastOutcome = null, readError = null)
        viewModelScope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    val bytes =
                        try {
                            readBytes(uri)
                        } catch (e: Exception) {
                            return@withContext ReadFailure(e.message ?: "the file could not be read")
                        }
                    store.import(bytes)
                }
            when (outcome) {
                is ReadFailure -> {
                    _state.value = _state.value.copy(busy = false, readError = outcome.message)
                }

                is PackageStore.Outcome -> {
                    _state.value = _state.value.copy(busy = false, lastOutcome = outcome)
                }
            }
            refresh()
        }
    }

    private class ReadFailure(
        val message: String,
    )

    /**
     * Copies the picked file into memory before anything else looks at it.
     *
     * The size guard is not politeness: the importer is handed a byte array, and
     * a hostile or simply enormous file would otherwise be read whole on a phone
     * before the format checks that would have refused it ever run.
     */
    private fun readBytes(uri: Uri): ByteArray {
        val resolver = getApplication<Application>().contentResolver
        return resolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "nothing could be opened at that location" }
            val buffer = ByteArray(READ_CHUNK)
            val collected = java.io.ByteArrayOutputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                collected.write(buffer, 0, read)
                require(collected.size() <= MAX_PACKAGE_BYTES) {
                    "the file is larger than ${MAX_PACKAGE_BYTES / (1024 * 1024)} MB, " +
                        "which is past anything a board package should be"
                }
            }
            collected.toByteArray()
        }
    }

    private companion object {
        const val READ_CHUNK = 64 * 1024

        /**
         * SPEC.md 2.1 puts a package's practical ceiling at 50 MB and is explicit
         * that this is not a conformance limit. This is the app's own refusal to
         * read something absurd, set well above it.
         */
        const val MAX_PACKAGE_BYTES = 128 * 1024 * 1024
    }
}

data class ImportUiState(
    val stored: List<PackageStore.Entry> = emptyList(),
    val lastOutcome: PackageStore.Outcome? = null,
    val readError: String? = null,
    val busy: Boolean = false,
)

/** Only used so the view model can name what it is holding. */
val PackageStore.Entry.archiveName: String get() = File(archive.path).name
