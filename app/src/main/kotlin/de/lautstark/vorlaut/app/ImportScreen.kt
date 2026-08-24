package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lautstark.vorlaut.boardpackage.Board
import de.lautstark.vorlaut.boardpackage.ImportWarning
import de.lautstark.vorlaut.boardpackage.SymbolSource

/**
 * Everything the importer found, listed.
 *
 * This is deliberately the whole of the user interface: the viewer's rendering
 * is not built yet, and a screen that shows the parse result without pretending
 * to be a board is the honest placeholder. Two things here are not placeholders
 * and must survive the real UI — the warning list and the redistribution notice.
 */
@Composable
fun ImportScreen(
    state: ImportUiState,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Vorlaut", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Imports a board package and lists what it contains.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            Button(onClick = onPickFile, enabled = !state.busy) {
                Text(if (state.stored.isEmpty()) "Choose a board package" else "Import another")
            }
        }

        if (state.busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

        state.readError?.let { message ->
            item { Notice("The file could not be read", message) }
        }

        state.lastOutcome?.let { outcome ->
            item { OutcomeNotice(outcome) }
        }

        if (state.stored.isEmpty() && !state.busy) {
            item {
                Text(
                    "Nothing imported yet. Open a .obz file, share one to this app, " +
                        "or pick one above.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(state.stored, key = { it.boardPackage.id }) { entry ->
            PackageCard(entry)
        }
    }
}

@Composable
private fun OutcomeNotice(outcome: PackageStore.Outcome) {
    when (outcome) {
        is PackageStore.Outcome.Refused -> {
            // SPEC.md 9.1: a rejection must be reported to the person importing,
            // naming the package and the reason. It does not go to the persistent
            // warning list, because nothing was imported to attach it to.
            Notice(
                "That package was refused",
                "${outcome.rejection.code.wireName}: ${outcome.rejection.detail}\n\n" +
                    "Nothing was imported, and anything already here is untouched.",
            )
        }

        is PackageStore.Outcome.Installed -> {
            Notice("Imported", "\"${outcome.entry.boardPackage.name}\" was added.")
        }

        is PackageStore.Outcome.Replaced -> {
            Notice(
                "Replaced",
                "\"${outcome.entry.boardPackage.name}\" replaced the copy stored on " +
                    "${outcome.previous.modified.readable()}.",
            )
        }

        is PackageStore.Outcome.AlreadyCurrent -> {
            Notice(
                "Not imported",
                "The copy already here was modified ${outcome.stored.modified.readable()}, " +
                    "which is not older than the one you opened " +
                    "(${outcome.incoming.modified.readable()}). It was left alone rather " +
                    "than rolled back.",
            )
        }
    }
}

@Composable
private fun Notice(
    title: String,
    body: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PackageCard(entry: PackageStore.Entry) {
    val pkg = entry.boardPackage
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pkg.name, style = MaterialTheme.typography.titleMedium)
            // Two packages may share a name, so the id is shown rather than hidden:
            // it is the only thing that tells them apart.
            Field("Package id", pkg.id)
            Field("Modified", pkg.modified.readable())
            Field("Symbol source", pkg.symbolSource.wireName)
            Field("Spec version", pkg.specVersion.toString())
            pkg.ttsVoice?.let { Field("Preferred voice", it) }
            Field("Root board", pkg.rootBoardId)

            if (!pkg.redistributable) {
                // SPEC.md 5.2: the viewer MUST show this where the person managing
                // the package can see it. A constraint nobody can see is one nobody
                // can honour — and for a METACOM package the constraint is what the
                // licence rests on.
                Notice(
                    "Not for passing on",
                    "This package is marked non-redistributable" +
                        if (pkg.symbolSource == SymbolSource.METACOM) {
                            ", and its symbols are METACOM. It was prepared for one person " +
                                "under that person's licence and must not be shared, published, " +
                                "or handed to another licensee."
                        } else {
                            ". It must not be shared or uploaded."
                        },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            pkg.boards.forEach { BoardSummary(it, isRoot = it.id == pkg.rootBoardId) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            WarningSummary(entry.warnings)
        }
    }
}

@Composable
private fun BoardSummary(
    board: Board,
    isRoot: Boolean,
) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                board.name + if (isRoot) "  (root)" else "",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "${board.rows}x${board.columns}, ${board.buttons.size} buttons" +
                (board.locale?.let { ", $it" } ?: "") +
                (board.color?.let { ", $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        board.buttons.forEach { button ->
            val marks =
                buildList {
                    add(button.onActivate.wireName)
                    button.imagePath?.let { add("image") }
                    button.audio?.let { add(it.wireName) }
                    // The degraded and disabled marks are the ones that must stay
                    // visible when this screen is replaced by a real board: a
                    // caregiver needs to see at a glance which buttons are
                    // incomplete, and the person importing is rarely the person who
                    // later notices a button has gone quiet.
                    if (button.state != de.lautstark.vorlaut.boardpackage.ButtonState.NORMAL) {
                        add(button.state.wireName.uppercase())
                    }
                }
            Text(
                "  ${button.label ?: button.id} — ${marks.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun WarningSummary(warnings: List<ImportWarning>) {
    if (warnings.isEmpty()) {
        Text("No warnings.", style = MaterialTheme.typography.bodySmall)
        return
    }
    // SPEC.md 9.3: warnings are persisted with the package and reachable later.
    // A toast at import time is not enough — the person importing is often not the
    // person who finds out something is missing, and by then the toast is gone.
    Text(
        "${warnings.size} warning${if (warnings.size == 1) "" else "s"}",
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodyMedium,
    )
    warnings.forEach { warning ->
        val place =
            listOfNotNull(warning.boardId, warning.buttonId)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("/") ?: "package"
        Text(
            "  ${warning.code.wireName} [$place] ${warning.detail}",
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall)
}
