package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lautstark.vorlaut.boardpackage.ImportWarning
import de.lautstark.vorlaut.boardpackage.WarningCode

/**
 * The warning list, for a package that is already on the device.
 *
 * SPEC.md 9.3 requires this to exist and to be reachable after the import: the
 * person importing a package is usually not the person who later notices a button
 * has gone quiet, and by then a toast is long gone. So this is a screen somebody
 * can come back to, reached from settings and from the board itself.
 *
 * The order is the importer's and is not re-sorted here. SPEC.md 9.5 makes the
 * sequence part of the format precisely so a caregiver can compare this list
 * against what they saw last week; a screen that sorted it by its own preference
 * would undo that at the last step.
 */
@Composable
fun WarningsScreen(
    packageName: String,
    warnings: List<ImportWarning>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(packageName, style = MaterialTheme.typography.titleLarge)
        Text(
            if (warnings.isEmpty()) {
                "Nothing is missing from this package."
            } else {
                "${warnings.size} thing${if (warnings.size == 1) "" else "s"} the builder should know about. " +
                    "The buttons still work as far as they can."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(warnings) { warning -> WarningRow(warning) }
        }
    }
}

@Composable
private fun WarningRow(warning: ImportWarning) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (warning.code.degrades) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ).padding(10.dp),
    ) {
        Text(explain(warning.code), fontWeight = FontWeight.SemiBold)
        Text(
            where(warning),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(warning.detail, style = MaterialTheme.typography.bodySmall)
    }
}

private fun where(warning: ImportWarning): String =
    when {
        warning.boardId == null -> "whole package · ${warning.code.wireName}"
        warning.buttonId == null -> "board ${warning.boardId} · ${warning.code.wireName}"
        else -> "board ${warning.boardId}, button ${warning.buttonId} · ${warning.code.wireName}"
    }

/**
 * Plain language for each code.
 *
 * The codes are for a machine and the list is for a person — usually a parent or
 * a carer rather than whoever built the package. `sound_missing` says nothing on
 * its own about what a child will experience, which is that the button speaks in
 * the device voice instead of the recorded one.
 */
private fun explain(code: WarningCode): String =
    when (code) {
        WarningCode.IMAGE_MISSING -> "A picture is missing. The button shows its label only."
        WarningCode.IMAGE_OVERSIZED -> "A picture is too large to show, so the button has none."
        WarningCode.IMAGE_UNDECODABLE -> "A picture could not be read, so the button has none."
        WarningCode.SOUND_MISSING -> "A recording is missing. The button speaks in the device voice."
        WarningCode.SOUND_UNDECODABLE -> "A recording could not be read. The button speaks in the device voice."
        WarningCode.SOUND_TOO_LONG -> "A recording is longer than 30 seconds. The button speaks in the device voice."
        WarningCode.ACTION_UNSUPPORTED -> "This button asks for something this viewer cannot do, so it is switched off."
        WarningCode.BUTTON_MISSING -> "The board has a gap where a button was meant to be."
        WarningCode.PATH_NORMALIZATION -> "A file inside the package is named unusually. It was found anyway."
        WarningCode.PATH_CONFLICT -> "The package disagrees with itself about where a file lives."
        WarningCode.IMAGE_REFERENCE_IGNORED -> "A picture also pointed somewhere online. The one in the package was used."
        WarningCode.COLOR_UNPARSEABLE -> "A colour could not be read, so the standard one was used."
    }
