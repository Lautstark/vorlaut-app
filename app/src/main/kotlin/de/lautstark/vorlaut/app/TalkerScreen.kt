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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import de.lautstark.vorlaut.app.design.VorlautMark
import de.lautstark.vorlaut.boardpackage.MessageBar
import kotlinx.coroutines.launch

private val BAR_HEIGHT = 132.dp
private val HOLD_MILLIS = 1200L

/**
 * The bar's proportions, and why they are these numbers.
 *
 * The controls used to take 546dp of a 940dp bar — 58% of it — which left room
 * for three tiles of sentence. They are the adult's three buttons; the sentence
 * is what the screen is for. At 418dp they take 45%, and four tiles fit at the
 * same size rather than being shrunk to make room.
 *
 * [SCREEN_MARGIN] is larger than [Vorlaut.metrics.gap] on purpose, and so is
 * [BAR_TO_GRID]. The rule that every gutter is one number was about the gutters
 * *inside* the grid — the build before this one set a padding on the grid and
 * another inside each cell, so the outer columns silently carried both. Giving
 * the screen's edge and the seam between two different surfaces their own
 * values is not that mistake; every cell-to-cell gap is still identical, and so
 * is every edge.
 */
private val SCREEN_MARGIN = 16.dp
private val BAR_TO_GRID = 22.dp
private val ARROW_WIDTH = 48.dp
private val SPEAK_WIDTH = 112.dp
private val CONTROL_WIDTH = 96.dp
private val CONTROL_ICON = 52.dp
private val ENTRY_WIDTH = 96.dp

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
    handedOver: Boolean,
    modifier: Modifier = Modifier,
) {
    val board = state.board ?: return
    val gap = Vorlaut.metrics.gap
    KeepScreenOn(enabled = true)

    Column(
        modifier
            .fillMaxSize()
            .background(Vorlaut.colors.bg)
            // The board is full bleed and therefore runs under the status bar
            // and the gesture pill unless it is told not to. A talker whose top
            // row is half under the clock is a talker with a row of unreachable
            // buttons.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(SCREEN_MARGIN),
        verticalArrangement = Arrangement.spacedBy(BAR_TO_GRID),
    ) {
        SentenceBar(
            entries = state.entries,
            media = media,
            onSpeak = onSpeak,
            onUndo = onUndo,
            onClear = onClear,
            onLeave = onLeave,
            handedOver = handedOver,
        )
        Box(Modifier.fillMaxSize().weight(1f)) {
            BoardScreen(board = board, state = state, media = media, onPress = onPress)
        }
    }
}

@Composable
private fun SentenceBar(
    entries: List<MessageBar.Entry>,
    media: BoardMedia,
    onSpeak: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onLeave: () -> Unit,
    handedOver: Boolean,
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
        Modifier.fillMaxWidth().height(BAR_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(gap),
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
                .clip(RoundedCornerShape(Vorlaut.metrics.radius))
                .background(c.surface),
        ) {
            if (entries.isEmpty()) {
                // An empty bar is a shape, not a word: "…" is text, and text is
                // the thing this screen exists for people who cannot use.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(gap)
                        .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
                        .background(c.surface2.copy(alpha = 0.55f)),
                )
            } else {
                LazyRow(
                    state = list,
                    modifier = Modifier.fillMaxSize().padding(gap),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(entries) { _, entry -> BarEntry(entry, media) }
                }
            }
        }

        PageArrow(back = false, enabled = canFwd) {
            scope.launch { list.animateScrollToItem(list.firstVisibleItemIndex + 4) }
        }

        // One primary per view (design.md §4.3). On this screen it is Speak.
        BarControl(
            stringResource(R.string.speak),
            primary = true,
            enabled = entries.isNotEmpty(),
            onClick = onSpeak,
        ) { SpeakIcon() }
        BarControl(stringResource(R.string.undo), enabled = entries.isNotEmpty(), onClick = onUndo) { UndoIcon() }
        BarControl(stringResource(R.string.clear), enabled = entries.isNotEmpty(), onClick = onClear) { ClearIcon() }
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
    val tint = parseHex(entry.tint) ?: Vorlaut.colors.surface2
    val bmp = media.image(entry.imagePath, 192)
    Column(
        Modifier
            .width(ENTRY_WIDTH)
            .fillMaxHeight()
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(tint)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White),
            )
        }
        entry.display?.let {
            Txt(
                it,
                style = Vorlaut.type.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = Color(0xFF1A1A1D),
                maxLines = 1,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
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
    val c = Vorlaut.colors
    Box(
        Modifier
            .fillMaxHeight()
            .width(ARROW_WIDTH)
            .alpha(if (enabled) 1f else 0.32f)
            .clip(RoundedCornerShape(Vorlaut.metrics.radius))
            .background(c.surface2)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Chevron(back, c.textDim) }
}

@Composable
private fun BarControl(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val c = Vorlaut.colors
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (primary) SPEAK_WIDTH else CONTROL_WIDTH)
            .alpha(if (enabled) 1f else 0.32f)
            .clip(RoundedCornerShape(Vorlaut.metrics.radius))
            .background(if (primary && enabled) c.accent else c.surface2)
            .clickable(enabled = enabled) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(CONTROL_ICON), contentAlignment = Alignment.Center) { icon() }
        Txt(
            label.uppercase(),
            style = Vorlaut.type.caption.copy(fontSize = 11.sp),
            color = if (primary && enabled) c.accentInk else c.textDim,
            maxLines = 1,
        )
    }
}

/* The glyphs. Drawn rather than imported: material-icons-extended is a
   megabyte of vectors for four of them, and these four are the whole set. */

@Composable private fun ctlInk(primary: Boolean = false) = if (primary) Vorlaut.colors.accentInk else Vorlaut.colors.textDim

@Composable
private fun SpeakIcon() {
    val ink = Vorlaut.colors.accentInk
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
private fun UndoIcon() {
    val ink = Vorlaut.colors.textDim
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
private fun ClearIcon() {
    val ink = Vorlaut.colors.textDim
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
 * The visible mark is 6dp wide; the target around it is the full height of the
 * bar and 18dp across. A 6dp button would be a cruelty — a 6dp mark on a target
 * a hand can find is a handle, which is what it should read as.
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
            val h = 46.dp.toPx()
            val left = (size.width - pill) / 2f
            val top = (size.height - h) / 2f
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
