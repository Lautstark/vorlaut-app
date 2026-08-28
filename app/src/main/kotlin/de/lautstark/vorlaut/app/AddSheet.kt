package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.lautstark.vorlaut.app.design.Btn
import de.lautstark.vorlaut.app.design.BtnTier
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut

/**
 * Which way a Sammlung comes in, asked before the file picker rather than
 * instead of it.
 *
 * „Sammlung hinzufügen" used to be the file picker, and one of the two ways in
 * is now not a file. Rows rather than two buttons in a foot, because each way
 * needs a second line to be choosable at all: the difference between them is
 * *where the package is*, not what will happen to it, and „Vom Tablet holen"
 * against „Vom Rechner empfangen" without those second lines is a riddle.
 *
 * The silhouette is [de.lautstark.vorlaut.app.design.ConfirmDestructive]'s and
 * [WarningsSheet]'s, value for value — same plane, same radius, same 22dp —
 * rather than a third thing that looks nearly like them.
 */
@Composable
fun AddSheet(
    onFromFile: () -> Unit,
    onFromNetwork: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Vorlaut.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(Vorlaut.metrics.radius))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radius))
                .padding(22.dp),
        ) {
            Txt(stringResource(R.string.add_collection), style = Vorlaut.type.rowName, color = c.text)
            Box(Modifier.size(4.dp))
            Txt(stringResource(R.string.add_where_from), style = Vorlaut.type.sub, color = c.textDim)
            Box(Modifier.size(16.dp))

            Way(
                what = stringResource(R.string.add_from_file),
                where = stringResource(R.string.add_from_file_sub),
                onClick = onFromFile,
            )
            Box(Modifier.size(10.dp))
            Way(
                what = stringResource(R.string.add_from_network),
                where = stringResource(R.string.add_from_network_sub),
                onClick = onFromNetwork,
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) { Btn(stringResource(R.string.cancel), onDismiss, tier = BtnTier.Quiet) }
        }
    }
}

@Composable
private fun Way(
    what: String,
    where: String,
    onClick: () -> Unit,
) {
    val c = Vorlaut.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(c.surface2)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Mark(c.surface, c.textDim)
        Column {
            Txt(what, style = Vorlaut.type.small, color = c.text)
            Txt(where, style = Vorlaut.type.sub, color = c.textDim, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * The plate a mark would sit on.
 *
 * `senden.css` draws a folder and a wifi arc in it. They are not here, and that
 * is a deliberate stop rather than an omission left half-done: the app has one
 * piece of vector art, [de.lautstark.vorlaut.app.design.VorlautMark], and a
 * screen inventing two more icons in Compose path syntax is exactly the drift
 * `Lautstark/design`'s `docs/components.css` exists to stop. The two lines of
 * text tell the ways apart on their own; the plate keeps the row's rhythm so
 * that dropping the marks in from the design repo later changes nothing else.
 */
@Composable
private fun Mark(
    plane: Color,
    ink: Color,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(plane),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ink),
        )
    }
}
