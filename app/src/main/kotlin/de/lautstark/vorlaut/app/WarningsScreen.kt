package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lautstark.vorlaut.app.design.AppBar
import de.lautstark.vorlaut.app.design.Btn
import de.lautstark.vorlaut.app.design.BtnTier
import de.lautstark.vorlaut.app.design.EmptyState
import de.lautstark.vorlaut.app.design.Notice
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut
import de.lautstark.vorlaut.boardpackage.ImportWarning
import de.lautstark.vorlaut.boardpackage.WarningCode

/**
 * The warning list for one Sammlung.
 *
 * SPEC.md 9.3 requires it to exist and to be reachable after the import: the
 * person importing is usually not the person who later notices a button has
 * gone quiet, and by then a toast is long gone.
 *
 * The order is the importer's and is not re-sorted here. SPEC.md 9.5 makes the
 * sequence part of the format precisely so a caregiver can compare this list
 * against what they saw last week; a screen that sorted it by its own
 * preference would undo that at the last step.
 */
@Composable
fun WarningsScreen(
    packageName: String,
    warnings: List<ImportWarning>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vorlaut.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AppBar(where = packageName)

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = Vorlaut.metrics.screenMargin),
        ) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    if (warnings.isEmpty()) {
                        EmptyState(
                            headline = stringResource(R.string.no_warnings),
                            body = stringResource(R.string.nothing_missing_body),
                        )
                    } else {
                        Notice(stringResource(R.string.warnings_intro))
                    }
                    Box(Modifier.size(16.dp))
                }
            }
            items(warnings) { warning ->
                Box(Modifier.fillMaxWidth()) { WarningRow(warning) }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.End,
                ) { Btn(stringResource(R.string.back), onBack, tier = BtnTier.Normal) }
            }
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
            .background(c.surface)
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

private fun where(w: ImportWarning): String =
    when {
        w.boardId == null -> "ganze Sammlung · ${w.code.wireName}"
        w.buttonId == null -> "Tafel ${w.boardId} · ${w.code.wireName}"
        else -> "Tafel ${w.boardId} · Taste ${w.buttonId} · ${w.code.wireName}"
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
 * is precisely what shipped until it was run in English and looked at.
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
