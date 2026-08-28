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

    /**
     * Puts the outcome line away.
     *
     * It used to stay until the next import or removal replaced it, which is
     * right for a refusal and wrong for "„Alltag zu Hause“ hinzugefügt." — that
     * one sat at the top of the list until something else happened, sometimes
     * for days.
     */
    fun dismissNotice() {
        _state.value = _state.value.copy(lastOutcome = null, readError = null)
    }

    /**
     * A package has started arriving over the network, and its declared size is
     * all that is known about it yet.
     *
     * Not its name, and that is the drawn design rather than a shortfall against
     * it — `Lautstark/design` at 4c72d40 settled it after this was built. The
     * wire carries no name, because a name in a header is a *claim* by the
     * sender while the name in the package is the *fact*. Showing the claim
     * during the transfer and the fact a second later is how a screen
     * contradicts itself: a header saying „Kernvokabular" over an archive that
     * installs as „Alltag zu Hause". So the screen says how big, and the outcome
     * line names it a moment later — read out of the package rather than
     * asserted about it.
     */
    fun receiving(declaredBytes: Long) {
        _state.value = _state.value.copy(arriving = declaredBytes, lastOutcome = null, readError = null)
    }

    /**
     * The bytes that arrived, through the same importer the file picker uses.
     *
     * **Called on the receiver's own thread and blocking on purpose.** The HTTP
     * response cannot be written until the outcome is known — it *is* the
     * outcome — so this does not hand off to a coroutine and return early. That
     * also serialises imports, which is what we want: [PackageReceiver] takes one
     * connection at a time, so two packages can never be in the store at once.
     */
    fun receive(bytes: ByteArray): PackageReceiver.Reply {
        val outcome =
            try {
                store.import(bytes)
            } catch (e: Exception) {
                // A malformed package comes back as Outcome.Refused rather than
                // as an exception, so reaching here means something worse: no
                // room on the device, or an archive whose contents will not fit
                // in memory. Two things must still happen. The sender is owed an
                // answer — it is holding a connection open waiting for one — and
                // the screen must stop saying a package is on its way.
                val why = e.message ?: "the package could not be read"
                _state.value = _state.value.copy(arriving = null, readError = why, lastOutcome = null)
                return PackageReceiver.Reply.Refused(PackageReceiver.Codes.IMPORT_FAILED, why)
            }
        _state.value = _state.value.copy(arriving = null, lastOutcome = outcome, readError = null)
        refresh()
        return outcome.asReply()
    }

    /** Nothing arrived and the screen was left. */
    fun stopReceiving() {
        if (_state.value.arriving != null) _state.value = _state.value.copy(arriving = null)
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

    companion object {
        private const val READ_CHUNK = 64 * 1024

        /**
         * SPEC.md 2.1 puts a package's practical ceiling at 50 MB and is explicit
         * that this is not a conformance limit. This is the app's own refusal to
         * read something absurd, set well above it.
         *
         * Public, and handed to [PackageReceiver] rather than restated there. A
         * package is not allowed to be bigger because it came over the wire, and
         * two constants that were meant to agree are two constants that will one
         * day disagree.
         */
        const val MAX_PACKAGE_BYTES = 128 * 1024 * 1024
    }
}

data class ImportUiState(
    val stored: List<PackageStore.Entry> = emptyList(),
    val lastOutcome: PackageStore.Outcome? = null,
    val readError: String? = null,
    val busy: Boolean = false,
    /**
     * How many bytes a package arriving over the network says it is, or null
     * when none is. Separate from [busy], which is the file picker reading a
     * file: these happen on two different screens and say two different
     * sentences.
     */
    val arriving: Long? = null,
)

/**
 * The outcome, in the words the wire contract uses.
 *
 * Here rather than in [PackageReceiver] because the receiver deliberately knows
 * nothing about packages — it moves bytes and reports what it was told. And here
 * rather than in the screen because it is not a matter of presentation: these
 * four strings are a contract with the editor and changing one is a change to
 * the protocol, not to a label.
 */
private fun PackageStore.Outcome.asReply(): PackageReceiver.Reply =
    when (this) {
        is PackageStore.Outcome.Installed -> {
            PackageReceiver.Reply.Stored("installed", entry.boardPackage.name)
        }

        is PackageStore.Outcome.Replaced -> {
            PackageReceiver.Reply.Stored("replaced", entry.boardPackage.name)
        }

        is PackageStore.Outcome.AlreadyCurrent -> {
            PackageReceiver.Reply.Stored("already_current", incoming.name)
        }

        // The code and the prose stay apart. The sender writes its own German
        // sentence and shows the code as a bare token beside it, so a sentence
        // fragment in `reason` would surface as English inside a German dialog.
        is PackageStore.Outcome.Refused -> {
            PackageReceiver.Reply.Refused(rejection.code.wireName, rejection.detail)
        }
    }

/** Only used so the view model can name what it is holding. */
val PackageStore.Entry.archiveName: String get() = File(archive.path).name
