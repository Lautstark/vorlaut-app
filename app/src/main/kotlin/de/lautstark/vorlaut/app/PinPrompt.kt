package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.lautstark.vorlaut.app.design.Btn
import de.lautstark.vorlaut.app.design.BtnTier
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut

/** Why the PIN is being asked for, which decides the wording and the rules. */
enum class PinPurpose { Choose, Unlock }

/**
 * The handover sheet.
 *
 * The family's sheet silhouette — head, body, foot — rather than Material's
 * dialog, which brings its own type ramp and colour roles with it. Cancelling
 * is always possible and always costs nothing: a sheet that traps somebody who
 * opened it by accident is a worse problem than the one the PIN solves.
 */
@Composable
fun PinPrompt(
    purpose: PinPurpose,
    busy: Boolean,
    wrong: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val c = Vorlaut.colors
    var entry by remember { mutableStateOf("") }

    // A rejected PIN clears the field. Leaving the wrong digits sitting there
    // makes the next attempt wrong too unless the caregiver notices and deletes
    // them, which turns one mistyped PIN into a locked-out feeling.
    LaunchedEffect(wrong) { if (wrong) entry = "" }

    val tooShort = entry.length < PinHash.MIN_LENGTH

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(Vorlaut.metrics.radius))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radius))
                .padding(22.dp),
        ) {
            Txt(
                stringResource(
                    if (purpose == PinPurpose.Choose) R.string.pin_choose_title else R.string.pin_enter_title,
                ),
                style = Vorlaut.type.rowName,
                color = c.text,
            )
            Txt(
                stringResource(
                    if (purpose == PinPurpose.Choose) R.string.pin_choose_body else R.string.pin_enter_body,
                ),
                style = Vorlaut.type.sub,
                color = c.textDim,
                modifier = Modifier.padding(top = 8.dp),
            )

            // A field is a fill, not a hole (design.md §4.3): --surface-2 at
            // rest, lifting to --surface with an accent border on focus.
            var focused by remember { mutableStateOf(false) }
            BasicTextField(
                value = entry,
                onValueChange = { entry = it.filter(Char::isDigit) },
                enabled = !busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle =
                    Vorlaut.type.body.copy(
                        fontSize = 30.sp,
                        letterSpacing = 0.5.em,
                        textAlign = TextAlign.Center,
                        color = c.text,
                    ),
                cursorBrush = SolidColor(c.accent),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
                        .background(if (focused) c.surface else c.surface2)
                        .border(
                            1.dp,
                            if (focused) c.accent else Color.Transparent,
                            RoundedCornerShape(Vorlaut.metrics.radiusSm),
                        ).padding(vertical = 12.dp, horizontal = 13.dp)
                        .onFocusChanged { focused = it.isFocused },
            )

            if (wrong) {
                Txt(
                    stringResource(R.string.pin_wrong),
                    style = Vorlaut.type.sub,
                    color = c.danger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Btn(stringResource(R.string.cancel), onDismiss, tier = BtnTier.Quiet, enabled = !busy)
                Box(Modifier.size(9.dp))
                Btn(
                    stringResource(
                        if (purpose == PinPurpose.Choose) R.string.pin_set else R.string.pin_unlock,
                    ),
                    { onSubmit(entry) },
                    tier = BtnTier.Primary,
                    enabled = !tooShort && !busy,
                )
            }
        }
    }
}

private val Double.em get() =
    androidx.compose.ui.unit
        .TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
