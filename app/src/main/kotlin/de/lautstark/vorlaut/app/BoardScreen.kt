package de.lautstark.vorlaut.app

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut
import de.lautstark.vorlaut.app.design.VorlautBoard
import de.lautstark.vorlaut.boardpackage.AudioSource
import de.lautstark.vorlaut.boardpackage.Board
import de.lautstark.vorlaut.boardpackage.Button
import de.lautstark.vorlaut.boardpackage.ButtonState
import de.lautstark.vorlaut.boardpackage.OnActivate
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * Filling the space is not the same as using it, which is what
 * [MIN_CELL_ASPECT] and [MAX_CELL_ASPECT] are for. A grid of pure weights takes
 * whatever shape it is given: stretched far enough, a cell becomes a slot with
 * the symbol a strip across the middle and the rest of the tile empty. So a
 * cell may stretch, but only so far; past that the grid stops growing, centres
 * itself, and leaves the remainder as ground.
 *
 * The case that made this visible — a 3x5 board in a portrait window — is
 * [AlwaysLandscape]'s now, and it turns the board rather than squeezing it.
 * What is left here is every other window that is not the shape the package was
 * drawn for: a wide desktop window, a split screen, a foldable half-open. The
 * band is wide enough that a landscape tablet never reaches it, so the shape
 * these boards are drawn for is untouched.
 */
private const val MIN_CELL_ASPECT = 0.8f
private const val MAX_CELL_ASPECT = 1.5f

@Composable
fun BoardScreen(
    board: Board,
    state: BoardUiState,
    media: BoardMedia,
    onPress: (Button) -> Unit,
    modifier: Modifier = Modifier,
    timings: PressTimings = PressTimings.Off,
) {
    // One per board on screen, and deliberately not carried across boards: a
    // navigation ends whatever was being held, and a cooldown started by the
    // press that turned the page has nothing to say about the page it turned to.
    val presses = remember(board.id) { BoardPresses() }
    val gap = Vorlaut.metrics.gap
    // Only where there is a second column for the space to be between.
    val apart = state.firstColumnGap && board.columns > 1
    val byId = board.buttons.associateBy { it.id }
    // The ground stays neutral. ext_lautstark_board_color is the builder's set
    // colour and it is not a word class — painting the whole screen with it
    // makes the loudest thing on the board the one thing that carries no
    // meaning, and drowns the Fitzgerald tints that do. On a sparse board it
    // is most of the screen. The approved mock does not use it at all.
    BoxWithConstraints(modifier.fillMaxSize().background(VorlautBoard.ground)) {
        val columns = board.columns.coerceAtLeast(1)
        val rows = board.rows.coerceAtLeast(1)
        // The gutters are not the cells' to divide. The seam after the first
        // column takes two gaps' worth on top of that, so the size the images
        // and the type scale off has to know about it.
        val seam = if (apart) gap * 2 else 0.dp
        val forCells = (maxWidth - gap * (columns - 1) - seam).coerceAtLeast(0.dp)
        val forRows = (maxHeight - gap * (rows - 1)).coerceAtLeast(0.dp)
        var cellWidth = forCells / columns
        var cellHeight = forRows / rows
        val shape = if (cellHeight > 0.dp) cellWidth / cellHeight else 1f
        if (shape > MAX_CELL_ASPECT) cellWidth = cellHeight * MAX_CELL_ASPECT
        if (shape < MIN_CELL_ASPECT) cellHeight = cellWidth / MIN_CELL_ASPECT
        // A symbol is square, so it is the shorter side that governs how large
        // it can be drawn and how large the label under it may be set.
        val cell = minOf(cellWidth, cellHeight)
        val targetPx = with(LocalDensity.current) { cell.toPx().toInt() }

        Column(
            Modifier
                .width(cellWidth * columns + gap * (columns - 1) + seam)
                .height(cellHeight * rows + gap * (rows - 1))
                .align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            board.cells.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { column, cellId ->
                        if (apart && column == 1) Spacer(Modifier.width(gap))
                        // A cell is empty three ways — the grid holds null, the
                        // id names no button, or the button is hidden — and all
                        // three keep their place rather than shifting
                        // everything after them.
                        val held = byId[cellId]
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                // Only where there is nothing. The hole used to
                                // be painted under every slot, filled ones
                                // included, where it was invisible under the
                                // tile — until a tile grew a notch and the hole
                                // was what showed through it, a shade darker
                                // than the gutter either side. What a notch
                                // has to show is the board, and the board is
                                // the ground.
                                .then(
                                    if (held == null) {
                                        Modifier
                                            .clip(RoundedCornerShape(Vorlaut.metrics.radius))
                                            .background(VorlautBoard.hole)
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            held?.let {
                                ButtonCell(it, state, media, cell, targetPx, onPress, timings, presses)
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
    timings: PressTimings,
    presses: BoardPresses,
) {
    val c = Vorlaut.colors
    val disabled = button.onActivate == OnActivate.Disabled
    val degraded = button.state == ButtonState.DEGRADED
    val held = presses.holding == button.id

    /* A word button with no recording to say it with.
     *
     * The device voice is not a fallback here (see Speech), so this button is
     * silent, and silence on press is the one failure a board must not keep to
     * itself: it looks exactly like a board that is working. It carries the
     * importer's own marker rather than a second one of its own, because to the
     * person holding the tablet it is the same fault — this button does not do
     * what a button on this board does.
     *
     * SPEC.md 9.2 says a button with no clip is *not* degraded, and the importer
     * still says so; this is the viewer drawing what it can actually do with
     * what it was given, and nothing the fixtures pin.
     */
    val voiceless = speaks(button) && button.audio !is AudioSource.Recorded
    val marked = degraded || voiceless
    val speaking = state.isPlayingClip(button)

    // The tint is the word class, written into background_color by the builder.
    // It is the only colour on this screen that carries meaning, which is why
    // a resting cell has nothing else on it — no border, no shadow, no gradient.
    // SPEC.md 10.2. `background_color` and `border_color` are the two ways a
    // word class arrives, and which one the package carries is the setting the
    // Sammlung was exported with — "Als Fläche" or "Als Rahmen". Neither is
    // "Aus". The viewer has no switch of its own; it draws what it was given.
    val fill = parseHex(button.backgroundColor)
    val edge = parseHex(button.borderColor)
    val image = media.image(button.imagePath, targetPx)
    val radius = RoundedCornerShape(Vorlaut.metrics.radius)

    /* A way back to the start page is furniture, not a word, and is drawn as
     * the furniture it is: the sentence bar's own plate, with the picture in
     * the bar's own ink and no label under it.
     *
     * The colours are not a new pair. barPlate and icon are what Speak, Undo
     * and Clear are already drawn in one band down — see BarControl in
     * TalkerScreen.kt — and that is the whole argument for this treatment
     * rather than merely its implementation. Everything a resting cell has
     * says "this is a word": paper under it because pictograms are drawn for
     * white, the Fitzgerald tint that says which kind of word, the label
     * spelling it. `:home` is none of those. Drawn as a word it reads as one,
     * and on a first board it is the only button that is not.
     *
     * Only a bare `:home`. AppendThenNavigate(Home) is a word that also goes
     * home — "bitte", said and then back to the start — and it keeps its
     * label, its tint and its paper, because it really is one.
     *
     * And only where the package left the colour to us. The note above says
     * this viewer draws what it was given and has no switch of its own; a
     * hand-authored `:home` carrying a background_color has been given a
     * colour on purpose, and taking it away would make that sentence false for
     * the sake of a default. Every start key the builder writes carries none.
     */
    val chrome = button.onActivate == OnActivate.Home && fill == null && edge == null

    /* What this press does, where it is not what a press ordinarily does.
     *
     * Appending is the common case and carries no mark: marking every ordinary
     * cell would make the marks worth nothing. That is the editor's rule and it
     * governs here too, so exactly two facts are left to say.
     *
     * The seats are the editor's as well — left is the sound, right is the way
     * onward — so a page marked in one tool reads the same in the other.
     *
     * A navigation drawn as [chrome] is left alone. The plate already says the
     * press changes the page, and better than a corner does; a wedge on top
     * would be two marks for one fact, which is the mistake the editor has
     * already had to undo once. AppendThenNavigate(Home) is not chrome — it
     * keeps its paper and its word — so it does take the wedge, because on that
     * cell nothing else says the page is about to change.
     */
    val wedge: Wedge? =
        when (button.onActivate) {
            OnActivate.Home, is OnActivate.Navigate -> {
                if (chrome) null else Wedge.Onward
            }

            // A carrier phrase is a word that also turns the page, so it is a
            // way onward and wears the wedge. It is deliberately not an
            // `OnActivate.Navigation` — see the note on AppendThenNavigate,
            // which exists so that sites which must not treat it as plain
            // navigation are asked. This `when` is exhaustive for the same
            // reason: the first version of it tested `is Navigation`, which
            // silently skipped every carrier phrase on the board.
            is OnActivate.AppendThenNavigate -> {
                Wedge.Onward
            }

            OnActivate.SpeakImmediately -> {
                Wedge.Sound
            }

            // An `actions` array, whose meaning SPEC.md 7.4 does not define and
            // which no fixture exercises. Marked by what it contains rather
            // than by what it is: if one of its members turns the page, the
            // press turns the page, and that is the fact the wedge states.
            is OnActivate.Sequence -> {
                when {
                    (button.onActivate as OnActivate.Sequence).actions.any {
                        it is OnActivate.Navigation || it is OnActivate.AppendThenNavigate
                    } -> Wedge.Onward

                    else -> null
                }
            }

            OnActivate.Append, OnActivate.SpeakBar, OnActivate.Clear,
            OnActivate.Backspace, OnActivate.Disabled,
            -> {
                null
            }
        }

    // The tile is the notched shape where there is a notch, so nothing is ever
    // drawn in the corner to be painted over afterwards — see [NotchedTile].
    val tile: Shape =
        wedge?.let { NotchedTile(Vorlaut.metrics.radius, wedgeSide(cell), it == Wedge.Sound) }
            ?: radius

    Box(
        Modifier
            .fillMaxSize()
            // The clip takes the notch too, and it has to. A pictogram carries
            // its own white ground — AAC line art is drawn for white and needs
            // it whatever the screen is doing — so the picture reaches into the
            // corner behind the tile's own background. Cutting only the
            // background leaves that white sitting in the notch.
            .clip(tile)
            .background(
                color = if (chrome) VorlautBoard.barPlate else fill ?: VorlautBoard.paper,
                shape = tile,
            )
            // The plate is 1.41:1 against the ground and cannot be made to
            // clear 1.4.11's 3:1 by any darker shade — see chromeEdge, which
            // carries the whole argument and the measurement. The bar's four
            // controls wear the same hairline for the same reason.
            .then(if (chrome) Modifier.border(1.5.dp, VorlautBoard.chromeEdge, radius) else Modifier)
            .then(if (edge != null) Modifier.border(4.dp, edge, tile) else Modifier)
            .then(
                // The speaking mark is the loudest thing on the button while it
                // lasts, because it answers "did it hear me" — the question a
                // user asks first and cannot ask out loud.
                if (speaking) Modifier.border(4.dp, c.accent, tile) else Modifier,
            )
            /* Under the finger, right now.
             *
             * SPEC.md 7.5 requires this while a hold counts, and it is drawn for
             * every press rather than only for held ones — before this there was
             * no press feedback at all on a board. The only thing that answered
             * "did it hear me" was the speaking border above, which needs a
             * recording to play, so a button with no clip took a press and
             * showed nothing whatsoever. That is its own reason to press again,
             * which is one of the two faults the release time is here to absorb.
             *
             * Ink rather than the accent, because the accent already means
             * something one line up and the two would be told apart only by
             * width. This is a shadow under a finger; that is a word being
             * spoken.
             */
            .then(if (held) Modifier.border(3.dp, VorlautBoard.ink, tile) else Modifier)
            // A disabled button still takes the press and visibly refuses it,
            // rather than behaving like a gap in the board.
            .pressGesture(button, timings, presses, onPress)
            .semantics {
                contentDescription = describe(button, degraded, voiceless, disabled)
                /* The screen reader's own way in, and it deliberately skips the
                 * timings above.
                 *
                 * TalkBack activates with an explicit double tap on a focused
                 * element, which is already a deliberate, confirmed act — the
                 * thing a hold time exists to establish. Putting the hold in
                 * front of it would ask somebody to hold a gesture that is not
                 * a hold, and the single-pointer rule is meaningless for an
                 * activation that arrives one at a time by construction.
                 *
                 * Losing this was the real cost of dropping `clickable`, and it
                 * would have been a silent one: the board looks identical and
                 * simply stops answering a screen reader. */
                role = Role.Button
                onClick(label = null) {
                    onPress(button)
                    true
                }
            }.padding(8.dp),
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
                    // On the plate the drawing is the button, so the two tones
                    // replace it rather than sitting on a square of white -
                    // see HOME_TONES, and the note on `chrome` for why this one
                    // button is not a word on paper.
                    colorFilter = if (chrome) ColorFilter.colorMatrix(HOME_TONES) else null,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
                            // A pictogram is a canvas, not a plane: AAC symbols are
                            // drawn for white and need it in either scheme. The
                            // start key is the exception and is the reason the
                            // sentence above says "pictogram": that white square
                            // is exactly what makes a button read as a word.
                            .then(if (chrome) Modifier else Modifier.background(Color.White)),
                )
            } else {
                Box(Modifier.weight(1f))
            }
            // Not on the plate. The word is still in the package and still
            // reaches a screen reader through describe() below - what goes is
            // the drawing of it, because a key that navigates is read by where
            // it sits and what it shows.
            button.label?.takeIf { !chrome }?.let {
                Txt(
                    it,
                    style =
                        Vorlaut.type.body.copy(
                            fontSize = labelSize(cell, image != null),
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.01).sp,
                        ),
                    // Always the dark ink. A cell is paper or a pale
                    // Fitzgerald tint and never anything else, so the label
                    // does not follow a scheme.
                    color = VorlautBoard.ink,
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
        if (marked) {
            Box(
                Modifier
                    // The corner the wedge left free.
                    //
                    // Both marks wanted the top right and the wedge takes its
                    // corner out of the tile's shape, so the badge was clipped
                    // by it: the notch removes everything within `side` of the
                    // corner and the badge's inner corner sits 16dp from it.
                    //
                    // Moving the badge rather than dropping the wedge, because
                    // the two are addressed to different people. The wedge
                    // tells the child what the press does; the badge tells the
                    // adult the package is broken. The adult is the one who can
                    // look somewhere else.
                    //
                    // Never both corners: a button has one `onActivate`, so it
                    // has at most one wedge, and the other corner is always
                    // free.
                    .align(if (wedge == Wedge.Onward) Alignment.TopStart else Alignment.TopEnd)
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

/**
 * How a start key's picture is recoloured: a plain linear map from the
 * drawing's luminance onto the two tones the sentence bar is drawn in,
 * `out = light - (light - plate) * in`.
 *
 * Black in the pictogram becomes [VorlautBoard.icon] and white becomes
 * [VorlautBoard.barPlate], so the drawing turns into strokes of light on the
 * key rather than a black drawing on a white square. The builder puts the
 * black-and-white variant of the symbol on this key for exactly this reason -
 * ARASAAC's greyscale rendering, or METACOM's `SW` file - because a two-tone
 * map only holds on a greyscale source and a coloured pictogram comes out
 * tinted.
 *
 * **Not `invert()`.** Inverting takes the white interior of the house to pure
 * black, which on a dark key reads as a hole cut through it rather than as a
 * drawing on it. This map takes that same white to the key's own colour, so
 * the interior simply is the key.
 *
 * Derived from the two tokens rather than written out as six numbers, so that
 * a key and the bar it matches cannot drift apart: the offsets *are* the light
 * tone, which is what `in = 0` has to come out as. The one thing that is not
 * shared with the builder's own copy of this map is the units - Compose keeps
 * Android's convention and applies the fifth column in 0..255, while the
 * editor's feColorMatrix works in 0..1. The scale factors are ratios and are
 * the same number in both; only the offsets differ, and only by 255.
 */
internal val HOME_TONES =
    ColorMatrix(
        floatArrayOf(
            -(VorlautBoard.icon.red - VorlautBoard.barPlate.red),
            0f,
            0f,
            0f,
            VorlautBoard.icon.red * 255f,
            0f,
            -(VorlautBoard.icon.green - VorlautBoard.barPlate.green),
            0f,
            0f,
            VorlautBoard.icon.green * 255f,
            0f,
            0f,
            -(VorlautBoard.icon.blue - VorlautBoard.barPlate.blue),
            0f,
            VorlautBoard.icon.blue * 255f,
            // Alpha untouched: the symbol's transparent ground stays
            // transparent, and the key shows through it.
            0f,
            0f,
            0f,
            1f,
            0f,
        ),
    )

/**
 * SPEC.md 7.5, on one cell: when a press on this button counts.
 *
 * Four things happen here, and only the first is what most of the code is about.
 *
 * **The board is taken by one pointer at a time.** [BoardPresses] says why; the
 * consequence here is that a second finger landing on a second cell finds the
 * board already claimed and is dropped without ever being drawn as a press.
 *
 * **A press that arrives during the cooldown is dropped, not queued** — SPEC.md
 * 7.5 is explicit, and the reason is that a queued press lands after the user has
 * given up on it and reads as the board acting by itself. It is swallowed
 * silently: showing a pressed state for a press that will not count would say
 * the opposite of what is happening.
 *
 * **With a hold time, activation happens at the end of the hold and not on
 * release.** This is what every talker with this setting does, and it is the
 * kinder half: the word arrives while the finger is still down, so the child
 * learns the length rather than discovering it on lift. With no hold time the
 * button activates on release exactly as it always has — that path has to stay
 * byte-for-byte the old behaviour, because it is the one every existing board is
 * used with.
 *
 * **A hold shows itself the whole time it is counting**, which SPEC.md 7.5 makes
 * a MUST rather than a suggestion. A hold with no feedback is indistinguishable
 * from a dead button for as long as it lasts, and 7.4 already says what a button
 * that takes a press and does nothing teaches the person pressing it.
 */
private fun Modifier.pressGesture(
    button: Button,
    timings: PressTimings,
    presses: BoardPresses,
    onPress: (Button) -> Unit,
): Modifier =
    pointerInput(button.id, timings) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (presses.deaf(SystemClock.uptimeMillis()) || !presses.claim(down.id)) {
                // Let the finger finish its journey so the next one starts
                // clean, and say nothing while it does.
                waitForUpOrCancellation()
                return@awaitEachGesture
            }
            try {
                presses.show(button.id)
                if (timings.holdMs <= 0) {
                    // waitForUpOrCancellation() answers null when the pointer is
                    // consumed elsewhere or leaves the cell, which is the same
                    // "slid off, do nothing" clickable has always had.
                    if (waitForUpOrCancellation() != null) {
                        onPress(button)
                        presses.activated(SystemClock.uptimeMillis(), timings.releaseMs)
                    }
                    return@awaitEachGesture
                }

                /* Whether the gesture ended before the hold was up.
                 *
                 * The two outcomes of withTimeoutOrNull have to be told apart
                 * and both of them are null on their own: the block returns null
                 * when the press was cancelled, and the wrapper returns null when
                 * it timed out. Returning `true` from inside the block is what
                 * separates them - reaching that line at all means the gesture
                 * finished first, whichever way it finished. */
                val endedEarly =
                    withTimeoutOrNull(timings.holdMs.toLong()) {
                        waitForUpOrCancellation()
                        true
                    } ?: false

                if (!endedEarly) {
                    // The finger neither lifted nor left for the whole window,
                    // which is the definition of a press that counts.
                    onPress(button)
                    presses.activated(SystemClock.uptimeMillis(), timings.releaseMs)
                    // The mark comes off at the moment the word happens rather
                    // than on lift: what it was reporting was the wait, and the
                    // wait is over.
                    presses.clear()
                    waitForUpOrCancellation()
                }
            } finally {
                // Whatever happened - activated, cancelled, or the coroutine
                // torn down by a navigation mid-hold - the board goes back to
                // nobody's. Leaving it claimed would deafen the whole page.
                presses.release(down.id)
            }
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

/** Which corner a wedge sits in, and therefore which fact it states. */
private enum class Wedge { Sound, Onward }

/**
 * How large a wedge is: with the cell, like the label, and bounded at both
 * ends. A fixed size is a speck on a 3x5 board and half the tile on a 6x11.
 */
private fun wedgeSide(cell: Dp) = (cell.value * 0.17f).coerceIn(14f, 30f).dp

/**
 * The tile with one corner taken off: a rounded rectangle whose top corner is a
 * straight diagonal instead.
 *
 * **A shape rather than a triangle painted on afterwards, and that is the whole
 * point.** Painting worked until you looked closely: the cell's rounded corner
 * is drawn with antialiasing, the clip trims the paint at exactly that corner,
 * and the half-covered pixels underneath survive as a pale arc traced through
 * the notch. No amount of overshoot reaches them, because the clip removes the
 * overshoot first. Not drawing the corner at all is the only version with
 * nothing left to cover up.
 *
 * In [VorlautBoard.ground]'s place, so to speak: what shows through the missing
 * corner is the board behind the tile, which is what a key that leads somewhere
 * else actually means. It also keeps the mark out of the ink, so nothing on the
 * cell competes with the word.
 *
 * The contrast is the cell's own and needs no separate argument. A cell already
 * has to clear 3:1 against the ground to be a visible cell at all, so a corner
 * of ground taken out of it is legible by the same measurement. Worst of the
 * ten Fitzgerald fills is `place` at 4.64:1.
 *
 * The cut corner is left square while the other three keep their radius. A
 * rounded cut reads as a bite; a straight one reads as a clip, which is the
 * thing being said.
 */
private data class NotchedTile(
    private val corner: Dp,
    private val notch: Dp,
    private val atStart: Boolean,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { corner.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val s = with(density) { notch.toPx() }.coerceAtMost(minOf(size.width, size.height))
        val w = size.width
        val h = size.height
        val path =
            Path().apply {
                if (atStart) {
                    moveTo(s, 0f)
                    lineTo(w - r, 0f)
                    arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
                    lineTo(w, h - r)
                    arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
                    lineTo(r, h)
                    arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
                    lineTo(0f, s)
                } else {
                    moveTo(r, 0f)
                    lineTo(w - s, 0f)
                    lineTo(w, s)
                    lineTo(w, h - r)
                    arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
                    lineTo(r, h)
                    arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
                    lineTo(0f, r)
                    arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                }
                close()
            }
        return Outline.Generic(path)
    }
}

private fun describe(
    button: Button,
    degraded: Boolean,
    voiceless: Boolean,
    disabled: Boolean,
) = buildString {
    append(button.label ?: button.id)
    // Left in German with the rest of the default locale; these reach a screen
    // reader rather than the screen, and follow the same resources when the
    // English half is wired through.
    if (disabled) append(", nicht verfügbar")
    if (degraded) append(", unvollständig")
    if (voiceless) append(", ohne Aufnahme")
    // The same two facts the wedges carry. A mark that only exists in the
    // drawing is a mark that half the people this app is for cannot have.
    when (button.onActivate) {
        OnActivate.Home -> append(", zur Startseite")
        is OnActivate.Navigate -> append(", öffnet eine Seite")
        is OnActivate.AppendThenNavigate -> append(", und öffnet eine Seite")
        OnActivate.SpeakImmediately -> append(", spricht sofort")
        else -> Unit
    }
}

/** Whether a press on this button is meant to make a sound at all. */
private fun speaks(button: Button) =
    when (button.onActivate) {
        OnActivate.Append, OnActivate.SpeakImmediately, is OnActivate.AppendThenNavigate -> true
        else -> false
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
