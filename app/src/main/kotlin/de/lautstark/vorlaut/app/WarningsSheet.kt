package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lautstark.vorlaut.app.design.Btn
import de.lautstark.vorlaut.app.design.BtnTier
import de.lautstark.vorlaut.app.design.EmptyState
import de.lautstark.vorlaut.app.design.Notice
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut
import de.lautstark.vorlaut.boardpackage.ImportWarning
import de.lautstark.vorlaut.boardpackage.WarningCode

/**
 * The warning list for one Sammlung, as a sheet over the list it belongs to.
 *
 * SPEC.md 9.3 requires it to exist and to be reachable after the import: the
 * person importing is usually not the person who later notices a button has
 * gone quiet, and by then a toast is long gone. A sheet is reachable, and this
 * was a screen with its own app bar, its own intro and its own way back — a
 * whole route for an aside nobody answers on the spot.
 *
 * The order is the importer's and is not re-sorted here. SPEC.md 9.5 makes the
 * sequence part of the format precisely so a caregiver can compare this list
 * against what they saw last week; a screen that sorted it by its own
 * preference would undo that at the last step.
 *
 * The silhouette is ConfirmDestructive's, value for value — same plane, same
 * radius, same 22dp — rather than a fourth thing that looks nearly like it.
 */
@Composable
fun WarningsSheet(
    packageName: String,
    warnings: List<ImportWarning>,
    onDismiss: () -> Unit,
) {
    val c = Vorlaut.colors
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(Vorlaut.metrics.radius))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radius))
                .padding(22.dp),
        ) {
            Txt(
                stringResource(R.string.warnings_title, packageName),
                style = Vorlaut.type.rowName,
                color = c.text,
                maxLines = 1,
            )
            Box(Modifier.size(10.dp))

            if (warnings.isEmpty()) {
                EmptyState(
                    headline = stringResource(R.string.no_warnings),
                    body = stringResource(R.string.nothing_missing_body),
                )
            } else {
                Notice(stringResource(R.string.warnings_intro))
                Box(Modifier.size(12.dp))
                // Bounded rather than free: a Sammlung with forty warnings would
                // otherwise grow a sheet taller than the tablet, and a Dialog
                // does not scroll on the window's behalf.
                LazyColumn(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
                    items(warnings) { warning ->
                        Box(Modifier.fillMaxWidth()) { WarningRow(warning) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) { Btn(stringResource(R.string.done), onDismiss, tier = BtnTier.Normal) }
        }
    }
}

@Composable
private fun WarningRow(warning: ImportWarning) {
    val c = Vorlaut.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(Vorlaut.metrics.radius))
            .background(c.bg)
            .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radius))
            .padding(15.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The stripe carries the one distinction a caregiver acts on: whether a
        // button is marked on the board, or whether this is only worth telling
        // whoever built the Sammlung.
        Box(
            Modifier
                .width(10.dp)
                .fillMaxHeight()
                .heightAtLeast()
                .clip(RoundedCornerShape(999.dp))
                .background(if (warning.code.degrades) c.danger else c.line),
        )
        Column {
            Txt(explain(warning.code), style = Vorlaut.type.body, color = c.text)
            Txt(where(warning), style = Vorlaut.type.mono, color = c.textDim, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

private fun Modifier.heightAtLeast() = this.then(Modifier.size(width = 10.dp, height = 42.dp))

/**
 * Where the warning is, as the importer names it.
 *
 * The ids stay verbatim — they are what the person who built the Sammlung will
 * search their editor for — but the words around them are the reader's. They
 * were "Tafel" and "Taste" in the source, which put German nouns in the English
 * build beside explanations that had just been translated out of it.
 */
@Composable
private fun where(w: ImportWarning): String =
    when {
        w.boardId == null -> {
            stringResource(R.string.warning_at_package, w.code.wireName)
        }

        w.buttonId == null -> {
            stringResource(R.string.warning_at_board, w.boardId.orEmpty(), w.code.wireName)
        }

        else -> {
            stringResource(
                R.string.warning_at_button,
                w.boardId.orEmpty(),
                w.buttonId.orEmpty(),
                w.code.wireName,
            )
        }
    }

/**
 * Plain language for each code.
 *
 * The codes are for a machine and this list is for a person — usually a parent
 * or a carer rather than whoever built the Sammlung. `sound_missing` says
 * nothing on its own about what a child will experience, which is that the
 * button makes no sound at all and wears a marker saying so.
 *
 * In resources rather than in this file: a warning list explaining itself in
 * German inside an otherwise English app has told its reader nothing, and that
 * is precisely what shipped until it was run in English and looked at. The
 * mirror image shipped too and lasted longer — all twelve sat in English in the
 * German base file, which is the one every German-speaking family reads.
 */
@Composable
private fun explain(code: WarningCode): String =
    stringResource(
        when (code) {
            WarningCode.IMAGE_MISSING -> R.string.w_image_missing
            WarningCode.IMAGE_OVERSIZED -> R.string.w_image_oversized
            WarningCode.IMAGE_UNDECODABLE -> R.string.w_image_undecodable
            WarningCode.SOUND_MISSING -> R.string.w_sound_missing
            WarningCode.SOUND_UNDECODABLE -> R.string.w_sound_undecodable
            WarningCode.SOUND_TOO_LONG -> R.string.w_sound_too_long
            WarningCode.ACTION_UNSUPPORTED -> R.string.w_action_unsupported
            WarningCode.BUTTON_MISSING -> R.string.w_button_missing
            WarningCode.PATH_NORMALIZATION -> R.string.w_path_normalization
            WarningCode.PATH_CONFLICT -> R.string.w_path_conflict
            WarningCode.IMAGE_REFERENCE_IGNORED -> R.string.w_image_reference_ignored
            WarningCode.COLOR_UNPARSEABLE -> R.string.w_color_unparseable
        },
    )
