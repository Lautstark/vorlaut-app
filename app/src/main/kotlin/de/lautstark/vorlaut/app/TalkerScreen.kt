package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The talking screen: the message bar above, the board below.
 *
 * The bar is at the top and always present, even when empty. It is the record of
 * what has been said so far, and a bar that appeared only once it had contents
 * would move the whole board down on the first press — shifting every button
 * under the finger of someone who navigates by position.
 */
@Composable
fun TalkerScreen(
    state: BoardUiState,
    media: BoardMedia,
    onPress: (de.lautstark.vorlaut.boardpackage.Button) -> Unit,
    onOpenWarnings: () -> Unit,
    onClosePackage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val board = state.board ?: return
    Column(modifier.fillMaxSize()) {
        MessageBarView(
            entries = state.entries,
            packageName = state.boardPackage?.name.orEmpty(),
            warningCount = state.warnings.size,
            onOpenWarnings = onOpenWarnings,
            onClosePackage = onClosePackage,
        )
        Box(Modifier.fillMaxSize().weight(1f)) {
            BoardScreen(board = board, state = state, media = media, onPress = onPress)
        }
    }
}

@Composable
private fun MessageBarView(
    entries: List<String>,
    packageName: String,
    warningCount: Int,
    onOpenWarnings: () -> Unit,
    onClosePackage: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClosePackage) { Text("‹ Packages") }
            Text(
                packageName,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            // SPEC.md 9.3: the warning list has to be reachable later, not only at
            // import. This is the way in from the board itself; settings is the
            // other. The count is shown rather than hidden behind the tap, because
            // a list nobody knows is there is one nobody opens.
            if (warningCount > 0) {
                WarningChip(warningCount, onOpenWarnings)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = BAR_MIN_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (entries.isEmpty()) {
                Text(
                    "…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = BAR_TEXT,
                )
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // One chip per entry, not per word. SPEC.md 7.3: a press
                    // contributes one entry whatever its length, and :backspace
                    // takes the whole of it back — showing them separately is what
                    // makes that visible before the button is pressed.
                    entries.forEach { entry ->
                        Text(
                            entry,
                            fontSize = BAR_TEXT,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningChip(
    count: Int,
    onClick: () -> Unit,
) {
    Text(
        text = "$count ⚠",
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.labelMedium,
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable(onClickLabel = "Show warnings") { onClick() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private val BAR_MIN_HEIGHT = 52.dp
private val BAR_TEXT = 20.sp
