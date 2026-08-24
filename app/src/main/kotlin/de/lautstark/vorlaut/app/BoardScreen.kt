package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lautstark.vorlaut.boardpackage.Board
import de.lautstark.vorlaut.boardpackage.Button
import de.lautstark.vorlaut.boardpackage.ButtonState
import de.lautstark.vorlaut.boardpackage.OnActivate

/**
 * A board, drawn.
 *
 * The grid is built from nested weights rather than a lazy grid with a fixed cell
 * size, and that is the whole reason it has no maximum: every row takes an equal
 * share of the height and every cell an equal share of its row, so a 6x11 board
 * divides the same space as a 3x5 one and simply gets smaller cells. Nothing
 * scrolls — a board is a page a user learns the shape of, and a button that moves
 * off-screen is a button somebody navigating by position cannot find.
 */
@Composable
fun BoardScreen(
    board: Board,
    state: BoardUiState,
    media: BoardMedia,
    onPress: (Button) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byId = board.buttons.associateBy { it.id }
    val background = board.color?.let { parseHex(it) } ?: MaterialTheme.colorScheme.surface

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(background)) {
        val cellWidth = maxWidth / board.columns.coerceAtLeast(1)
        val cellHeight = maxHeight / board.rows.coerceAtLeast(1)
        val cell = minOf(cellWidth, cellHeight)
        val targetPx = with(LocalDensity.current) { cell.toPx().toInt() }

        Column(Modifier.fillMaxSize()) {
            board.cells.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    row.forEach { cellId ->
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            // A cell may be empty three ways: the grid holds null,
                            // the id names no button, or the button is hidden. All
                            // three draw as an empty cell rather than as a gap that
                            // shifts everything after it.
                            val button = cellId?.let { byId[it] }
                            if (button != null) {
                                ButtonCell(
                                    button = button,
                                    state = state,
                                    media = media,
                                    size = cell,
                                    targetPx = targetPx,
                                    onPress = onPress,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonCell(
    button: Button,
    state: BoardUiState,
    media: BoardMedia,
    size: Dp,
    targetPx: Int,
    onPress: (Button) -> Unit,
) {
    val disabled = button.onActivate == OnActivate.Disabled
    val degraded = button.state == ButtonState.DEGRADED
    val synthesising = state.isSynthesising(button)
    val playingClip = state.isPlayingClip(button)

    val face = button.backgroundColor?.let { parseHex(it) } ?: MaterialTheme.colorScheme.surfaceVariant
    val edge =
        when {
            // The speaking marks are the loudest thing on the button while they
            // last, because they answer "did it hear me" — the question a user
            // asks first and cannot ask out loud.
            playingClip -> MaterialTheme.colorScheme.primary

            synthesising -> MaterialTheme.colorScheme.tertiary

            button.borderColor?.let { parseHex(it) } != null -> parseHex(button.borderColor!!)!!

            else -> MaterialTheme.colorScheme.outlineVariant
        }

    val padding = (size * PADDING_FRACTION).coerceAtMost(MAX_PADDING)
    val image = media.image(button.imagePath, targetPx)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .clip(RoundedCornerShape(CORNER))
                .background(face)
                .then(
                    if (playingClip || synthesising) {
                        Modifier.border(SPEAKING_EDGE, edge, RoundedCornerShape(CORNER))
                    } else {
                        Modifier.border(RESTING_EDGE, edge, RoundedCornerShape(CORNER))
                    },
                )
                // A disabled button is not `enabled = false` on a clickable: it must
                // still take the press and visibly refuse it, rather than behaving like
                // a gap in the board.
                .clickable { onPress(button) }
                .semantics {
                    contentDescription = describe(button, degraded, disabled)
                },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).alpha(if (disabled) DISABLED_ALPHA else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            button.label?.let { label ->
                Text(
                    text = label,
                    textAlign = TextAlign.Center,
                    maxLines = if (image == null) LABEL_LINES_ALONE else 1,
                    overflow = TextOverflow.Ellipsis,
                    // Type scales with the cell, so a dense board stays legible
                    // instead of clipping every label to an ellipsis.
                    fontSize = labelSize(size, hasImage = image != null),
                    lineHeight = labelSize(size, hasImage = image != null) * LINE_HEIGHT,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // SPEC.md 9.2: the marking MUST be visible in the UI, not merely logged.
        // A caregiver needs to see at a glance which buttons are incomplete, and
        // the person importing is rarely the person who later notices.
        if (degraded) DegradedMark(Modifier.align(Alignment.TopEnd).padding(padding))
        if (disabled) DisabledMark(Modifier.align(Alignment.Center))
    }
}

/**
 * A corner wedge for a button that lost something it was promised.
 *
 * Deliberately not a placeholder picture: SPEC.md 9.2 leaves the label, colour
 * and action standing on a degraded button, and drawing a stand-in symbol would
 * tell a user who cannot read that the button has a picture, which it does not.
 */
@Composable
private fun DegradedMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(MARK)
            .clip(RoundedCornerShape(MARK / 2))
            .background(MaterialTheme.colorScheme.error),
        contentAlignment = Alignment.Center,
    ) {
        Text("!", color = MaterialTheme.colorScheme.onError, fontSize = MARK_TEXT)
    }
}

/** A cross over the whole face: this button is not going to do anything. */
@Composable
private fun DisabledMark(modifier: Modifier = Modifier) {
    val colour = MaterialTheme.colorScheme.outline
    Box(
        modifier.fillMaxSize().drawBehind {
            val inset = size.minDimension * CROSS_INSET
            drawLine(
                color = colour,
                start =
                    androidx.compose.ui.geometry
                        .Offset(inset, inset),
                end =
                    androidx.compose.ui.geometry
                        .Offset(size.width - inset, size.height - inset),
                strokeWidth = CROSS_STROKE,
            )
            drawLine(
                color = colour,
                start =
                    androidx.compose.ui.geometry
                        .Offset(size.width - inset, inset),
                end =
                    androidx.compose.ui.geometry
                        .Offset(inset, size.height - inset),
                strokeWidth = CROSS_STROKE,
            )
        },
    )
}

private fun describe(
    button: Button,
    degraded: Boolean,
    disabled: Boolean,
): String =
    buildString {
        append(button.label ?: button.id)
        if (disabled) append(", unavailable")
        if (degraded) append(", incomplete")
    }

/** `#RRGGBB` only — the importer already normalised `rgb(...)` and dropped the rest. */
private fun parseHex(value: String?): Color? {
    val text = value ?: return null
    if (!text.startsWith("#") || text.length != 7) return null
    val rgb = text.substring(1).toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}

private fun labelSize(
    cell: Dp,
    hasImage: Boolean,
) = (cell.value * if (hasImage) LABEL_WITH_IMAGE else LABEL_ALONE)
    .coerceIn(MIN_LABEL, MAX_LABEL)
    .sp

private const val PADDING_FRACTION = 0.04f
private val MAX_PADDING = 8.dp
private val CORNER = 10.dp
private val RESTING_EDGE = 1.dp
private val SPEAKING_EDGE = 4.dp
private val MARK = 18.dp
private val MARK_TEXT = 13.sp
private const val DISABLED_ALPHA = 0.45f
private const val CROSS_INSET = 0.28f
private const val CROSS_STROKE = 3f
private const val LABEL_WITH_IMAGE = 0.11f
private const val LABEL_ALONE = 0.17f
private const val MIN_LABEL = 9f
private const val MAX_LABEL = 26f
private const val LINE_HEIGHT = 1.15f
private const val LABEL_LINES_ALONE = 3
