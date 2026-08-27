package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lautstark.vorlaut.app.design.AppBar
import de.lautstark.vorlaut.app.design.Btn
import de.lautstark.vorlaut.app.design.BtnTier
import de.lautstark.vorlaut.app.design.ConfirmDestructive
import de.lautstark.vorlaut.app.design.EmptyState
import de.lautstark.vorlaut.app.design.Flag
import de.lautstark.vorlaut.app.design.MenuItem
import de.lautstark.vorlaut.app.design.Notice
import de.lautstark.vorlaut.app.design.OverflowMenu
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut

/**
 * The adult's screen: which Sammlungen are on this device, and what is wrong
 * with them.
 *
 * The board gives up everything it can to the grid. This gives up nothing,
 * because reading is the whole task here.
 *
 * It is also the only screen the adult has to find their way around, and it
 * used to offer three doors to two places. A row opens by being tapped and its
 * warnings open by their chip; the `⋯` therefore carries only what is nowhere
 * else, and the warnings arrive as a sheet over this list rather than as a
 * screen of their own.
 */
@Composable
fun SammlungenScreen(
    state: ImportUiState,
    currentId: String?,
    onAdd: () -> Unit,
    onOpen: (PackageStore.Entry) -> Unit,
    onRemove: (PackageStore.Entry) -> Unit,
    onDismissNotice: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vorlaut.colors

    // Which Sammlung's warnings are open, by id rather than by entry: the list
    // is rebuilt on every refresh and a held entry would be a stale copy.
    var warningsFor by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AppBar()

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Vorlaut.metrics.screenMargin),
        ) {
            // Reading a 40 MB .obz takes long enough that a screen which says
            // nothing looks like a screen that did nothing, and the caregiver
            // picks the file a second time.
            if (state.busy) {
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Notice(stringResource(R.string.reading_file), busy = true)
                        Gap()
                    }
                }
            }
            state.readError?.let { message ->
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Notice(
                            message,
                            bad = true,
                            dismissLabel = stringResource(R.string.dismiss_notice),
                            onDismiss = onDismissNotice,
                        )
                        Gap()
                    }
                }
            }
            state.lastOutcome?.let { outcome ->
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Notice(
                            outcomeText(outcome),
                            bad = outcome is PackageStore.Outcome.Refused,
                            dismissLabel = stringResource(R.string.dismiss_notice),
                            onDismiss = onDismissNotice,
                        )
                        Gap()
                    }
                }
            }
            if (state.stored.isEmpty() && !state.busy) {
                item {
                    Column(Modifier.fillMaxWidth()) {
                        EmptyState(
                            headline = stringResource(R.string.empty_headline),
                            body = stringResource(R.string.empty_body),
                            footnote = stringResource(R.string.empty_footnote),
                        )
                    }
                }
            }

            items(state.stored, key = { it.boardPackage.id }) { entry ->
                Box(Modifier.fillMaxWidth()) {
                    SammlungRow(
                        entry,
                        opensNext = entry.boardPackage.id == currentId,
                        onOpen = { onOpen(entry) },
                        onWarnings = { warningsFor = entry.boardPackage.id },
                        onRemove = { onRemove(entry) },
                    )
                }
            }

            item { Box(Modifier.size(16.dp)) }
        }

        /*
         * The foot, and it does not scroll.
         *
         * Both of these were reachable only by scrolling past the list, or —
         * for Einstellungen — as dim text at 13.5sp with 6dp of padding under
         * it, which is below any target size worth the name and the lowest
         * contrast on the screen. Einstellungen stays at the foot, where the
         * family keeps it (design.md §3.6); it is a button now rather than a
         * word. Quiet on the left, the one primary action on the right, which
         * is the shape ConfirmDestructive's own footer already has.
         */
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Vorlaut.metrics.screenMargin, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Btn(stringResource(R.string.settings), onSettings, tier = BtnTier.Quiet)
            Box(Modifier.weight(1f))
            Btn(stringResource(R.string.add_collection), onAdd, tier = BtnTier.Primary)
        }
    }

    warningsFor?.let { id ->
        state.stored.firstOrNull { it.boardPackage.id == id }?.let { entry ->
            WarningsSheet(
                packageName = entry.boardPackage.name,
                warnings = entry.warnings,
                onDismiss = { warningsFor = null },
            )
        }
    }
}

@Composable private fun Gap() = Box(Modifier.size(16.dp))

/**
 * A row shows its content and one ⋯ (design.md §4.3): the row itself opens, and
 * everything you can *do* to it lives behind the one trigger, so it never grows
 * a new control each time the product grows a feature. Nothing is behind hover
 * — this is a touch screen.
 *
 * The menu held three items and two of them were the row and the chip said
 * over again. What is left is the one act that has nowhere else to be. It keeps
 * the `⋯` rather than promoting Entfernen to a visible control, because the
 * rule is about where the *next* action will go.
 *
 * There is no picture. A Sammlung is a set of pictures and this row used to
 * lead with one, decoded off the archive per row on a worker; but the picture
 * it could offer is whichever button happens to sit first in the root board,
 * not a cover somebody chose, and AAC symbols at 56dp are line drawings on
 * white that tell four rows apart far less well than four names do.
 */
@Composable
private fun SammlungRow(
    entry: PackageStore.Entry,
    opensNext: Boolean,
    onOpen: () -> Unit,
    onWarnings: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val c = Vorlaut.colors
    val pkg = entry.boardPackage
    val boards = pkg.boards.size
    val buttons = pkg.boards.sumOf { it.buttons.size }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(Vorlaut.metrics.radius))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radius))
            .clickable { onOpen() }
            .padding(start = 18.dp, top = 13.dp, bottom = 13.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(pkg.name, style = Vorlaut.type.rowName, color = c.text, maxLines = 1)
            // Counts are everywhere, in tabular numerals, beside the name.
            Txt(
                pluralStringResource(R.plurals.boards, boards, boards) +
                    stringResource(R.string.dot_separator) +
                    pluralStringResource(R.plurals.buttons, buttons, buttons),
                style = Vorlaut.type.sub,
                color = c.textDim,
                maxLines = 1,
            )
        }

        // Which Sammlung the child is handed. It decides what opens on the next
        // start and it was written down nowhere, which left the one question an
        // adult has before passing the tablet over unanswerable from this screen.
        if (opensNext) Flag(stringResource(R.string.opens_next), accent = true)
        if (!pkg.redistributable) Flag(stringResource(R.string.not_redistributable), quiet = true)
        if (entry.warnings.isNotEmpty()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onWarnings() },
            ) {
                Flag(pluralStringResource(R.plurals.warnings_count, entry.warnings.size, entry.warnings.size))
            }
        }
        Box {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) { Txt("⋯", style = Vorlaut.type.rowName, color = c.textDim) }

            OverflowMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                MenuItem(stringResource(R.string.menu_remove), destructive = true) {
                    menuOpen = false
                    confirming = true
                }
            }
        }
    }

    if (confirming) {
        ConfirmDestructive(
            title = stringResource(R.string.remove_title, entry.boardPackage.name),
            body = stringResource(R.string.remove_body),
            confirmLabel = stringResource(R.string.remove_confirm),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirming = false
                onRemove()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun outcomeText(outcome: PackageStore.Outcome): String =
    when (outcome) {
        is PackageStore.Outcome.Refused -> {
            stringResource(R.string.refused, outcome.rejection.code.wireName, outcome.rejection.detail)
        }

        is PackageStore.Outcome.Installed -> {
            stringResource(R.string.added, outcome.entry.boardPackage.name)
        }

        is PackageStore.Outcome.Replaced -> {
            stringResource(R.string.replaced, outcome.entry.boardPackage.name)
        }

        is PackageStore.Outcome.AlreadyCurrent -> {
            stringResource(R.string.not_newer, outcome.incoming.name)
        }
    }
