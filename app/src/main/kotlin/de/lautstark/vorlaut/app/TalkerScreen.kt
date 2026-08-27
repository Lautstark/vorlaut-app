package de.lautstark.vorlaut.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut
import de.lautstark.vorlaut.app.design.VorlautBoard
import de.lautstark.vorlaut.app.design.VorlautMark
import de.lautstark.vorlaut.boardpackage.MessageBar
import kotlinx.coroutines.launch

private val HOLD_MILLIS = 1200L

/**
 * The bar's proportions, and why they are these numbers.
 *
 * The controls used to take 546dp of a 940dp bar — 58% of it — which left room
 * for three tiles of sentence. They are the adult's three buttons; the sentence
 * is what the screen is for. At 418dp they take 45%, and four tiles fit at the
 * same size rather than being shrunk to make room.
 *
 * The screen's edge is [Vorlaut.metrics.screenMargin] — the same number the
 * list screens hold off by, so the board does not read as a different
 * application — and it is larger than [Vorlaut.metrics.gap], as is
 * [BAR_TO_GRID]. The rule that every gutter is one number was about the gutters
 * *inside* the grid — the build before this one set a padding on the grid and
 * another inside each cell, so the outer columns silently carried both. Giving
 * the screen's edge and the seam between two different surfaces their own
 * values is not that mistake; every cell-to-cell gap is still identical, and so
 * is every edge.
 */
private val ARROW_WIDTH = 54.dp
private val SPEAK_WIDTH = 106.dp
private val CONTROL_WIDTH = 76.dp
private val CONTROL_ICON = 34.dp

/**
 * The talking screen: the sentence bar above, the board below.
 *
 * The bar is a fixed height and always present, even empty. The grid must never
 * shift — a board is a page whose shape a child learns, and a button that moves
 * because the sentence grew is a button they have to find again mid-thought.
 */
@Composable
fun TalkerScreen(
    state: BoardUiState,
    media: BoardMedia,
    onPress: (de.lautstark.vorlaut.boardpackage.Button) -> Unit,
    onSpeak: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val board = state.board ?: return
    val gap = Vorlaut.metrics.gap
    KeepScreenOn(enabled = true)

    // The board is full bleed and therefore runs under the status bar and the
    // gesture pill unless it is told not to. A talker whose top row is half
    // under the clock is a talker with a row of unreachable buttons. Holding
    // that back is [AlwaysLandscape]'s job, along with the quarter turn that
    // keeps the board landscape in a window that is not.
    AlwaysLandscape(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(VorlautBoard.edge),
            verticalArrangement = Arrangement.spacedBy(VorlautBoard.barGap),
        ) {
            SentenceBar(
                modifier = Modifier.weight(VorlautBoard.BAR_FRACTION),
                entries = state.entries,
                media = media,
                onSpeak = onSpeak,
                onUndo = onUndo,
                onClear = onClear,
                onLeave = onLeave,
            )
            Box(Modifier.fillMaxSize().weight(1f - VorlautBoard.BAR_FRACTION)) {
                BoardScreen(board = board, state = state, media = media, onPress = onPress)
            }
        }
    }
}

@Composable
private fun SentenceBar(
    modifier: Modifier,
    entries: List<MessageBar.Entry>,
    media: BoardMedia,
    onSpeak: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onLeave: () -> Unit,
) {
    val c = Vorlaut.colors
    val gap = Vorlaut.metrics.gap
    val leaveLabel = stringResource(R.string.leave_board)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Always follow the newest entry. A press has to show its own result, or
    // the bar has stopped being the confirmation the button was pressed for.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) list.animateScrollToItem(entries.lastIndex)
    }
    val canBack by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    val canFwd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && (last.index < info.totalItemsCount - 1 || last.offset + last.size > info.viewportEndOffset)
        }
    }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // The way out, and the only control here that is not the child's.
        //
        // A 6dp handle in an 18dp column, replacing the 62dp tile that carried
        // the mark. The test the tile failed was that the person who asked for
        // it read the commit and still had to ask what the icon was for: it
        // looked like the wordmark on the other two screens, sat in a tile
        // styled exactly like the page arrows, and gave no hint that it wanted
        // holding rather than tapping.
        //
        // The ring is the affordance. It starts filling the instant a finger
        // lands, so "keep holding" is learned rather than written somewhere
        // nobody reads — and a stray tap does nothing at all, which is the
        // whole point of putting the way out behind a hold.
        ExitHandle(onLeave = onLeave, label = leaveLabel)

        // The arrows are always here and never move. Reserving the space costs
        // a little width that is usually idle and buys that the entries never
        // jump sideways at the moment a sentence gets long enough to need them.
        PageArrow(back = true, enabled = canBack) {
            scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex - 4).coerceAtLeast(0)) }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
                .background(VorlautBoard.barPlate),
        ) {
            // An empty bar is the plate and nothing else. A placeholder would be
            // either text — the thing this screen exists for people who cannot
            // use — or a shape that has to be learnt as meaning nothing.
            LazyRow(
                state = list,
                modifier = Modifier.fillMaxSize().padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(entries) { _, entry -> BarEntry(entry, media) }
            }
        }

        PageArrow(back = false, enabled = canFwd) {
            scope.launch { list.animateScrollToItem(list.firstVisibleItemIndex + 4) }
        }

        // One primary per view (design.md §4.3). On this screen it is Speak —
        // and it is primary by being wider and by having a filled glyph, not by
        // being the accent. An accent fill was the loudest thing on a screen
        // whose only colour is supposed to mean word class.
        //
        // No labels under the glyphs. Everything the words were carrying has to
        // be in the content description now, or these are three unnamed buttons
        // to TalkBack.
        BarControl(
            stringResource(R.string.speak),
            primary = true,
            enabled = entries.isNotEmpty(),
            onClick = onSpeak,
        ) { SpeakIcon(it) }
        BarControl(stringResource(R.string.undo), enabled = entries.isNotEmpty(), onClick = onUndo) { UndoIcon(it) }
        BarControl(stringResource(R.string.clear), enabled = entries.isNotEmpty(), onClick = onClear) { ClearIcon(it) }
    }
}

/**
 * One entry: the tile that was pressed, at a smaller size, with its word
 * underneath — the same arrangement as in the grid, so the bar reads as a
 * smaller copy of what was just touched.
 */
@Composable
private fun BarEntry(
    entry: MessageBar.Entry,
    media: BoardMedia,
) {
    val bmp = media.image(entry.imagePath, 192)
    val shape = RoundedCornerShape(7.dp)

    /* An entry the sentence will not say.
     *
     * The device voice is not a fallback (see Speech), so a word whose button
     * had no recording is passed over when Speak is pressed. Faded, it is the
     * same "there but not available" the disabled buttons in the grid are drawn
     * with, and the sentence can be read before it is heard.
     */
    val fade = if (entry.soundPath == null) 0.45f else 1f
    val spelled = entry.display.orEmpty() + if (entry.soundPath == null) ", ohne Aufnahme" else ""

    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = spelled,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clip(shape)
                    .background(VorlautBoard.paper)
                    .alpha(fade)
                    .padding(4.dp),
        )
    } else {
        // No picture, so the word is all there is and the card grows to hold it
        // rather than clipping it. SPEC.md 7.3: the word is the vocalization,
        // not the label — MessageBar decides which, this only draws it.
        Box(
            Modifier
                .fillMaxHeight()
                .widthIn(min = 62.dp)
                .clip(shape)
                .background(VorlautBoard.paper)
                .alpha(fade)
                .semantics { contentDescription = spelled }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt(
                entry.display.orEmpty(),
                style = Vorlaut.type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = VorlautBoard.ink,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PageArrow(
    back: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(if (back) R.string.page_back else R.string.page_forward)
    Box(
        Modifier
            .fillMaxHeight()
            .width(ARROW_WIDTH)
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(VorlautBoard.barPlate)
            .clickable(enabled = enabled) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Chevron(back, if (enabled) VorlautBoard.icon else VorlautBoard.iconDead) }
}

@Composable
private fun BarControl(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        Modifier
            .fillMaxHeight()
            .width(if (primary) SPEAK_WIDTH else CONTROL_WIDTH)
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(VorlautBoard.barPlate)
            .clickable(enabled = enabled) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(if (primary) CONTROL_ICON + 8.dp else CONTROL_ICON),
            contentAlignment = Alignment.Center,
        ) { icon(if (enabled) VorlautBoard.icon else VorlautBoard.iconDead) }
    }
}

/* The glyphs. Drawn rather than imported: material-icons-extended is a
   megabyte of vectors for four of them, and these four are the whole set. */

@Composable
private fun SpeakIcon(ink: Color) {
    Canvas(Modifier.size(40.dp)) {
        val u = size.minDimension / 24f
        val body =
            Path().apply {
                moveTo(4 * u, 9 * u)
                lineTo(4 * u, 15 * u)
                lineTo(8 * u, 15 * u)
                lineTo(13 * u, 19 * u)
                lineTo(13 * u, 5 * u)
                lineTo(8 * u, 9 * u)
                close()
            }
        drawPath(body, ink)
        drawArc(
            ink,
            -60f,
            120f,
            false,
            androidx.compose.ui.geometry
                .Offset(11.5f * u, 6.5f * u),
            androidx.compose.ui.geometry
                .Size(10f * u, 11f * u),
            style = Stroke(2.2f * u, cap = StrokeCap.Round),
        )
        drawArc(
            ink,
            -60f,
            120f,
            false,
            androidx.compose.ui.geometry
                .Offset(13f * u, 3.5f * u),
            androidx.compose.ui.geometry
                .Size(13f * u, 17f * u),
            style = Stroke(2.2f * u, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun UndoIcon(ink: Color) {
    Canvas(Modifier.size(40.dp)) {
        val u = size.minDimension / 24f
        val s = Stroke(2.2f * u, cap = StrokeCap.Round)
        val arrow =
            Path().apply {
                moveTo(9 * u, 14 * u)
                lineTo(4 * u, 9 * u)
                lineTo(9 * u, 4 * u)
            }
        drawPath(arrow, ink, style = s)
        val hook =
            Path().apply {
                moveTo(4 * u, 9 * u)
                lineTo(14 * u, 9 * u)
                cubicTo(19 * u, 9 * u, 19 * u, 21 * u, 11.5f * u, 21 * u)
            }
        drawPath(hook, ink, style = s)
    }
}

@Composable
private fun ClearIcon(ink: Color) {
    Canvas(Modifier.size(40.dp)) {
        val u = size.minDimension / 24f
        val s = Stroke(2.2f * u, cap = StrokeCap.Round)
        drawLine(
            ink,
            androidx.compose.ui.geometry
                .Offset(4 * u, 7 * u),
            androidx.compose.ui.geometry
                .Offset(20 * u, 7 * u),
            s.width,
            StrokeCap.Round,
        )
        val lid =
            Path().apply {
                moveTo(9 * u, 7 * u)
                lineTo(9 * u, 5 * u)
                lineTo(15 * u, 5 * u)
                lineTo(15 * u, 7 * u)
            }
        drawPath(lid, ink, style = s)
        val can =
            Path().apply {
                moveTo(6 * u, 7 * u)
                lineTo(7 * u, 19 * u)
                lineTo(17 * u, 19 * u)
                lineTo(18 * u, 7 * u)
            }
        drawPath(can, ink, style = s)
    }
}

@Composable
private fun Chevron(
    back: Boolean,
    colour: Color,
) {
    Canvas(Modifier.size(32.dp)) {
        val u = size.minDimension / 24f
        val p =
            Path().apply {
                if (back) {
                    moveTo(15 * u, 5 * u)
                    lineTo(8 * u, 12 * u)
                    lineTo(15 * u, 19 * u)
                } else {
                    moveTo(9 * u, 5 * u)
                    lineTo(16 * u, 12 * u)
                    lineTo(9 * u, 19 * u)
                }
            }
        drawPath(p, colour, style = Stroke(2.6f * u, cap = StrokeCap.Round))
    }
}

/**
 * Hold to leave the board.
 *
 * The visible mark is 6dp wide and as tall as the bar; the target around it is
 * that height and 18dp across. A 6dp button would be a cruelty — a 6dp mark on
 * a target a hand can find is a handle, which is what it should read as.
 *
 * It ran to a fixed 46dp before, centred, which on a tablet is about half the
 * bar: a short stroke floating beside five full-height controls reads as
 * something left over rather than as the edge of the row. Full height puts it
 * on the same line as everything else up there, and the ring it fills over
 * gets the whole distance to say how long the hold is.
 */
@Composable
private fun ExitHandle(
    onLeave: () -> Unit,
    label: String,
) {
    val c = Vorlaut.colors
    var pressed by remember { mutableStateOf(false) }

    // The ring fills over the hold and falls away quickly when the finger
    // lifts, so an abandoned hold does not leave a half-drawn mark sitting
    // there implying something happened.
    val held by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec =
            tween(
                durationMillis = if (pressed) HOLD_MILLIS.toInt() else 140,
                easing = LinearEasing,
            ),
        label = "exit-hold",
        finishedListener = { value -> if (value == 1f) onLeave() },
    )

    Box(
        Modifier
            .fillMaxHeight()
            .width(18.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        // Returns false when the gesture is cancelled — a scroll
                        // taking over, or a finger sliding off — and either way
                        // the hold is abandoned rather than completed.
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            }.semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pill = 6.dp.toPx()
            val h = size.height
            val left = (size.width - pill) / 2f
            val top = 0f
            val radius = CornerRadius(pill / 2f)

            drawRoundRect(
                color = c.line,
                topLeft = Offset(left, top),
                size = Size(pill, h),
                cornerRadius = radius,
            )

            // The handle fills rather than growing a ring around itself. A ring
            // wide enough to read needs 44dp and this column is 18 — on Android
            // the surplus is simply clipped, so half of it never arrives. The
            // fill also puts the feedback exactly under the finger that caused
            // it, which a ring around the finger does not.
            if (held > 0f) {
                val grown = h * held
                clipRect(left, top, left + pill, top + h) {
                    drawRoundRect(
                        color = c.accent,
                        topLeft = Offset(left, top + (h - grown)),
                        size = Size(pill, grown),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
}
