package de.lautstark.vorlaut.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut
import de.lautstark.vorlaut.boardpackage.Board
import de.lautstark.vorlaut.boardpackage.Button
import de.lautstark.vorlaut.boardpackage.ButtonState
import de.lautstark.vorlaut.boardpackage.OnActivate

/**
 * A board, drawn.
 *
 * The grid is nested weights rather than a lazy grid with a fixed cell size,
 * and that is why it has no maximum: every row takes an equal share of the
 * height and every cell an equal share of its row, so a 6x11 board divides the
 * same space as a 3x5 and simply gets smaller cells. Nothing scrolls — a board
 * is a page whose shape a user learns, and a button that moves off-screen is a
 * button somebody navigating by position cannot find.
 *
 * One gap governs every gutter: the outer edges, between any two cells, and
 * between the sentence bar and the grid. The build this replaces set a padding
 * on the grid *and* inside each cell, so the outer columns carried both.
 *
 * The one place that gutter widens is after the first column, and only where the
 * package asked for it (SPEC.md 4.1). It is still the same number — a spacer one
 * gap wide, between two gaps, so the seam is three gaps and every other seam is
 * one. What it says is that those buttons stay reachable while the pages behind
 * them change; nothing here makes them stay, and nothing in the format does. The
 * builder wrote that column onto every board, and this is the gap that tells a
 * reader so.
 */
@Composable
fun BoardScreen(
    board: Board,
    state: BoardUiState,
    media: BoardMedia,
    onPress: (Button) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = Vorlaut.metrics.gap
    // Only where there is a second column for the space to be between.
    val apart = state.firstColumnGap && board.columns > 1
    val byId = board.buttons.associateBy { it.id }
    // The ground stays neutral. ext_lautstark_board_color is the builder's set
    // colour and it is not a word class — painting the whole screen with it
    // makes the loudest thing on the board the one thing that carries no
    // meaning, and drowns the Fitzgerald tints that do. On a sparse board it
    // is most of the screen. The approved mock does not use it at all.
    BoxWithConstraints(modifier.fillMaxSize().background(Vorlaut.colors.bg)) {
        // The seam takes two gaps' worth of width off the row, so the cell size
        // the images and the type scale off has to know about it.
        val across = maxWidth - if (apart) gap * 2 else 0.dp
        val cell = minOf(across / board.columns.coerceAtLeast(1), maxHeight / board.rows.coerceAtLeast(1))
        val targetPx = with(LocalDensity.current) { cell.toPx().toInt() }

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            board.cells.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { column, cellId ->
                        if (apart && column == 1) Spacer(Modifier.width(gap))
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            // A cell is empty three ways — the grid holds null,
                            // the id names no button, or the button is hidden —
                            // and all three keep their place rather than
                            // shifting everything after them.
                            byId[cellId]?.let {
                                ButtonCell(it, state, media, cell, targetPx, onPress)
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
    cell: Dp,
    targetPx: Int,
    onPress: (Button) -> Unit,
) {
    val c = Vorlaut.colors
    val disabled = button.onActivate == OnActivate.Disabled
    val degraded = button.state == ButtonState.DEGRADED
    val speaking = state.isSynthesising(button) || state.isPlayingClip(button)

    // The tint is the word class, written into background_color by the builder.
    // It is the only colour on this screen that carries meaning, which is why
    // a resting cell has nothing else on it — no border, no shadow, no gradient.
    val tint = parseHex(button.backgroundColor) ?: c.surface2
    val image = media.image(button.imagePath, targetPx)
    val radius = RoundedCornerShape(Vorlaut.metrics.radius)

    Box(
        Modifier
            .fillMaxSize()
            .clip(radius)
            .background(tint)
            .then(
                // The speaking mark is the loudest thing on the button while it
                // lasts, because it answers "did it hear me" — the question a
                // user asks first and cannot ask out loud. Clip and device
                // voice are told apart by hue.
                when {
                    state.isPlayingClip(button) -> Modifier.border(4.dp, c.accent, radius)
                    state.isSynthesising(button) -> Modifier.border(4.dp, c.accentStrong, radius)
                    else -> Modifier
                },
            )
            // A disabled button still takes the press and visibly refuses it,
            // rather than behaving like a gap in the board.
            .clickable { onPress(button) }
            .semantics { contentDescription = describe(button, degraded, disabled) }
            .padding(8.dp),
    ) {
        Column(
            Modifier.fillMaxSize().alpha(if (disabled) 0.45f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
                            // A pictogram is a canvas, not a plane: AAC symbols are
                            // drawn for white and need it in either scheme.
                            .background(Color.White),
                )
            } else {
                Box(Modifier.weight(1f))
            }
            button.label?.let {
                Txt(
                    it,
                    style =
                        Vorlaut.type.body.copy(
                            fontSize = labelSize(cell, image != null),
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.01).sp,
                        ),
                    // Ink on a word-class tint is always the dark ink: the
                    // tints are pale by definition and do not change with the
                    // scheme, so neither may the text on them.
                    color = Color(0xFF1A1A1D),
                    maxLines = if (image == null) 3 else 1,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // SPEC.md 9.2: the marking MUST be visible in the UI, not merely
        // logged. Deliberately not a stand-in pictogram — drawing one would
        // tell a child who cannot read that the button has a picture, which is
        // the thing that has gone missing.
        if (degraded) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(c.danger),
                contentAlignment = Alignment.Center,
            ) { Txt("!", style = Vorlaut.type.small, color = c.dangerInk) }
        }
        if (disabled) DisabledCross(Modifier.fillMaxSize())
        if (speaking) Unit
    }
}

/** A cross over the whole face: this button is not going to do anything. */
@Composable
private fun DisabledCross(modifier: Modifier) {
    val colour = Vorlaut.colors.textDim
    Box(
        modifier.drawBehind {
            val inset = size.minDimension * 0.28f
            drawLine(colour, Offset(inset, inset), Offset(size.width - inset, size.height - inset), 3f)
            drawLine(colour, Offset(size.width - inset, inset), Offset(inset, size.height - inset), 3f)
        },
    )
}

private fun describe(
    button: Button,
    degraded: Boolean,
    disabled: Boolean,
) = buildString {
    append(button.label ?: button.id)
    // Left in German with the rest of the default locale; these reach a screen
    // reader rather than the screen, and follow the same resources when the
    // English half is wired through.
    if (disabled) append(", nicht verfügbar")
    if (degraded) append(", unvollständig")
}

/** Type scales with the cell, so a dense board stays legible instead of clipping. */
private fun labelSize(
    cell: Dp,
    hasImage: Boolean,
) = (cell.value * if (hasImage) 0.11f else 0.17f).coerceIn(9f, 26f).sp

/** `#RRGGBB` only — the importer already normalised `rgb(...)` and dropped the rest. */
internal fun parseHex(value: String?): Color? {
    val text = value ?: return null
    if (!text.startsWith("#") || text.length != 7) return null
    val rgb = text.substring(1).toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}
