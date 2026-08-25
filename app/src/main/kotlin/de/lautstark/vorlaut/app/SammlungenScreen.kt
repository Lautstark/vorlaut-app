package de.lautstark.vorlaut.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
 */
@Composable
fun SammlungenScreen(
    state: ImportUiState,
    onAdd: () -> Unit,
    onOpen: (PackageStore.Entry) -> Unit,
    onWarnings: (PackageStore.Entry) -> Unit,
    onRemove: (PackageStore.Entry) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vorlaut.colors
    val context = LocalContext.current

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AppBar()

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.readError?.let { message ->
                item {
                    Column(Modifier.widthIn(max = 860.dp)) {
                        Notice(message, bad = true)
                        Gap()
                    }
                }
            }
            state.lastOutcome?.let { outcome ->
                item {
                    Column(Modifier.widthIn(max = 860.dp)) {
                        Notice(outcomeText(outcome), bad = outcome is PackageStore.Outcome.Refused)
                        Gap()
                    }
                }
            }
            if (state.stored.isEmpty() && !state.busy) {
                item {
                    Column(Modifier.widthIn(max = 860.dp)) {
                        EmptyState(
                            headline = stringResource(R.string.empty_headline),
                            body = stringResource(R.string.empty_body),
                            footnote = stringResource(R.string.empty_footnote),
                        )
                    }
                }
            }

            items(state.stored, key = { it.boardPackage.id }) { entry ->
                Box(Modifier.widthIn(max = 860.dp)) {
                    SammlungRow(
                        entry,
                        onOpen = { onOpen(entry) },
                        onWarnings = { onWarnings(entry) },
                        onRemove = { onRemove(entry) },
                    )
                }
            }

            item {
                Row(
                    Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(top = 10.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Btn(stringResource(R.string.add_collection), onAdd, tier = BtnTier.Primary)
                }
            }
        }

        // Einstellungen at the foot, where the family keeps it (design.md §3.6).
        // It was this same line of text for a while with nothing behind it —
        // the convention put the word there and the screen was never built.
        Box(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt(
                stringResource(R.string.settings),
                style = Vorlaut.type.sub,
                color = c.textDim,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
                        .clickable { onSettings() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
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
 */
@Composable
private fun SammlungRow(
    entry: PackageStore.Entry,
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
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // A Sammlung is a set of pictures, so a picture introduces it.
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
                .background(Color.White)
                .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radiusSm)),
            contentAlignment = Alignment.Center,
        ) {
            rememberFace(entry)?.let {
                Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            }
        }

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

        if (!pkg.redistributable) Flag(stringResource(R.string.not_redistributable), quiet = true)
        if (entry.warnings.isNotEmpty()) {
            Box(Modifier.clickable { onWarnings() }) {
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
                MenuItem(stringResource(R.string.menu_open)) {
                    menuOpen = false
                    onOpen()
                }
                MenuItem(stringResource(R.string.menu_warnings)) {
                    menuOpen = false
                    onWarnings()
                }
                // Destructive last, and the only coloured row in the menu.
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

/**
 * The first symbol the Sammlung actually draws, as its face.
 *
 * Decoded off the stored archive on a worker and remembered per package: a
 * Sammlung is a set of pictures and a row of names tells an adult less than a
 * row of pictures does, but neither is worth a stutter on the list.
 */
@Composable
private fun RemoveConfirmation(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDestructive(
        title = stringResource(R.string.remove_title, name),
        body = stringResource(R.string.remove_body),
        confirmLabel = stringResource(R.string.remove_confirm),
        cancelLabel = stringResource(R.string.cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun rememberFace(entry: PackageStore.Entry): androidx.compose.ui.graphics.ImageBitmap? {
    var face by androidx.compose.runtime.remember(entry.boardPackage.id) {
        androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(entry.boardPackage.id) {
        face =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val path =
                    entry.boardPackage.boards
                        .firstOrNull { it.id == entry.boardPackage.rootBoardId }
                        ?.buttons
                        ?.firstNotNullOfOrNull { it.imagePath }
                        ?: entry.boardPackage.boards
                            .flatMap { it.buttons }
                            .firstNotNullOfOrNull { it.imagePath }
                path?.let {
                    de.lautstark.vorlaut.boardpackage.PackageArchive
                        .open(entry.archive.readBytes())
                        ?.let { archive -> BoardMedia(archive).image(it, 128) }
                }
            }
    }
    return face
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
