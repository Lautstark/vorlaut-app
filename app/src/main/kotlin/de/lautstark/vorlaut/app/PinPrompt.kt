package de.lautstark.vorlaut.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Why the PIN is being asked for, which decides the wording and the rules. */
enum class PinPurpose {
    /** No PIN yet. Choosing one, before handing the tablet over for the first time. */
    Choose,

    /** Leaving the board. */
    Unlock,
}

/**
 * The PIN dialog.
 *
 * Cancelling is always possible and always costs nothing — it returns to the
 * board with everything as it was. A dialog that traps somebody who opened it by
 * accident is a worse problem than the one the PIN solves.
 */
@Composable
fun PinPrompt(
    purpose: PinPurpose,
    busy: Boolean,
    wrong: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var entry by remember { mutableStateOf("") }

    // A rejected PIN clears the field. Leaving the wrong digits sitting there
    // makes the next attempt wrong too unless the caregiver notices and deletes
    // them, which turns one mistyped PIN into a locked-out feeling.
    LaunchedEffect(wrong) { if (wrong) entry = "" }
    // Checking a PIN is deliberately slow, so it happens on a worker and the
    // dialog says so. Without that the caregiver gets a dead button and taps it
    // again, which is how a slow check becomes a hung one.
    val tooShort = entry.length < PinHash.MIN_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (purpose) {
                    PinPurpose.Choose -> "Choose a PIN"
                    PinPurpose.Unlock -> "Enter the PIN"
                },
            )
        },
        text = {
            Column {
                Text(
                    when (purpose) {
                        PinPurpose.Choose -> {
                            "At least ${PinHash.MIN_LENGTH} digits. It is what gets you back " +
                                "out of the board once the tablet is handed over, so pick " +
                                "something you will not have to think about."
                        }

                        PinPurpose.Unlock -> {
                            "This leaves the board and unpins the app."
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it.filter(Char::isDigit) },
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = wrong,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (wrong) {
                    Text(
                        "That is not the PIN.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !tooShort && !busy,
                onClick = { onSubmit(entry) },
            ) {
                Text(if (purpose == PinPurpose.Choose) "Set PIN" else "Unlock")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
