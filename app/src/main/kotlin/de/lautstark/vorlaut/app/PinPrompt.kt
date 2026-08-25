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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay

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

    val incomplete = entry.length < PinHash.LENGTH

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

            PinBoxes(
                value = entry,
                onValueChange = { entry = it },
                enabled = !busy,
                wrong = wrong,
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
                    enabled = !incomplete && !busy,
                )
            }
        }
    }
}

private val Double.em get() =
    androidx.compose.ui.unit
        .TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

/**
 * Four boxes, one digit each.
 *
 * One field with four characters in it asks the person to count what they have
 * typed. Boxes say how many are wanted before a digit is entered and how many
 * are in, and the confirm cannot be reached with three — a short PIN is not a
 * thing this control can express.
 *
 * Masked, with the digit showing for a moment as it lands. The threat model for
 * this PIN is a child in the room, which is the same child it keeps out: long
 * enough to confirm the finger hit the right key, short enough that somebody
 * across the table reads four dots.
 */
@Composable
private fun PinBoxes(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    wrong: Boolean,
) {
    val c = Vorlaut.colors
    val focus = remember { FocusRequester() }
    var revealed by remember { mutableStateOf(-1) }

    LaunchedEffect(value) {
        if (value.isEmpty()) {
            revealed = -1
        } else {
            revealed = value.lastIndex
            delay(700)
            revealed = -1
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(Modifier.padding(top = 18.dp)) {
        // One real field behind four drawn boxes. Four separate inputs would
        // each need their own focus plumbing, and a hardware keyboard or a
        // paste would have to be taught to walk between them; one field gets
        // all of that from the platform and the boxes are only its face.
        BasicTextField(
            value = value,
            onValueChange = { fresh ->
                onValueChange(fresh.filter(Char::isDigit).take(PinHash.LENGTH))
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    // Invisible rather than absent: it has to stay in the layout to
                    // keep focus and the keyboard.
                    .alpha(0f),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            repeat(PinHash.LENGTH) { i ->
                val filled = i < value.length
                val here = i == value.length && enabled
                Box(
                    Modifier
                        .width(66.dp)
                        .height(80.dp)
                        .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
                        .background(
                            when {
                                wrong -> c.dangerSoft
                                filled || here -> c.surface
                                else -> c.surface2
                            },
                        ).border(
                            width = if (here) 2.dp else 1.dp,
                            color =
                                when {
                                    // Wrong marks every box, because the PIN is
                                    // wrong rather than any one digit of it.
                                    wrong -> c.danger

                                    here -> c.accent

                                    filled -> c.line

                                    else -> Color.Transparent
                                },
                            shape = RoundedCornerShape(Vorlaut.metrics.radiusSm),
                        ).clickable(enabled = enabled) { focus.requestFocus() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (filled) {
                        Txt(
                            if (i == revealed) value[i].toString() else "•",
                            style = Vorlaut.type.body.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
                            color = c.text,
                        )
                    }
                }
            }
        }
    }
}
