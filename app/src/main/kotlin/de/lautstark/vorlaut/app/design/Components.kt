package de.lautstark.vorlaut.app.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/*
 * The family's components, as Compose.
 *
 * A port of `docs/components.css`, which exists because the products were
 * retyping each other's rules — so this file is that same agreement arriving in
 * a fourth product rather than a fourth set of lookalikes. Where the CSS has a
 * comment explaining a value, the value is here and the reasoning stayed there.
 */

/**
 * The one text primitive. BasicText rather than Material's Text on purpose: the
 * moment a screen calls Text() it inherits Material's type ramp and colour
 * roles, which is a second design system arriving through the back door.
 */
@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = Vorlaut.type.body,
    color: Color = Vorlaut.colors.text,
    maxLines: Int = Int.MAX_VALUE,
    align: androidx.compose.ui.text.style.TextAlign? = null,
) = androidx.compose.foundation.text.BasicText(
    text = text,
    modifier = modifier,
    style = style.copy(color = color, textAlign = align ?: androidx.compose.ui.text.style.TextAlign.Unspecified),
    maxLines = maxLines,
    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
)

/** Three tiers, no more (design.md §4.3). */
enum class BtnTier { Primary, Normal, Quiet }

/**
 * [BtnTier.Primary] takes the accent fill and there is **one per view**.
 * Destructive is not a fourth tier but a colour on a quiet button.
 */
@Composable
fun Btn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tier: BtnTier = BtnTier.Normal,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Vorlaut.colors
    val fill =
        when {
            destructive -> Color.Transparent
            tier == BtnTier.Primary -> c.accent
            tier == BtnTier.Quiet -> Color.Transparent
            else -> c.surface2
        }
    val ink =
        when {
            destructive -> c.danger
            tier == BtnTier.Primary -> c.accentInk
            tier == BtnTier.Quiet -> c.textDim
            else -> c.text
        }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(fill)
            .then(
                if (tier == BtnTier.Normal && !destructive) {
                    Modifier.border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radiusSm))
                } else {
                    Modifier
                },
            ).clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, style = Vorlaut.type.small.copy(fontWeight = FontWeight.SemiBold), color = ink)
    }
}

/**
 * The outcome line: it says what happened and stays until something replaces
 * it. Never a toast — a toast is the treatment for an aside, and a package
 * that could not be read is not an aside (design.md §2).
 *
 * [busy] puts the same line to work while something is still happening, which
 * is the one case the notice could not cover: reading a large `.obz` took long
 * enough to look like nothing had happened at all.
 *
 * [onDismiss] adds the way out. "Stays until something replaces it" was written
 * about a line that reports a refusal, and it is right about that one; applied
 * to "„Alltag zu Hause“ hinzugefügt." it left a sentence about last Tuesday at
 * the top of the list.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    bad: Boolean = false,
    busy: Boolean = false,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val c = Vorlaut.colors
    val ink = if (bad) c.danger else c.accentStrong
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(if (bad) c.dangerSoft else c.accentSoft)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) Ticker(ink)
        Txt(text, style = Vorlaut.type.sub, color = ink, modifier = Modifier.weight(1f))
        if (onDismiss != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClickLabel = dismissLabel) { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Txt("✕", style = Vorlaut.type.small, color = ink)
            }
        }
    }
}

/**
 * The one indeterminate thing in the app.
 *
 * A bar rather than a spinner: it sits inside a line of text and a spinner at
 * that size is a smudge. There is no percentage to show — the reader is a
 * stream and the total is not known until it ends — so it says *working*, not
 * *how far*, and never pretends to the second.
 */
@Composable
private fun Ticker(ink: Color) {
    val cycle = rememberInfiniteTransition(label = "ticker")
    val at by cycle.animateFloat(
        initialValue = -0.45f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "ticker",
    )
    Box(
        Modifier
            .width(96.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(ink.copy(alpha = 0.22f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.45f)
                .fillMaxHeight()
                .offset { IntOffset((at * 96.dp.toPx()).toInt(), 0) }
                .clip(RoundedCornerShape(999.dp))
                .background(ink),
        )
    }
}

/**
 * An empty state teaches the one thing the user does not yet know. A
 * filtered-empty is a different, shorter sentence and must never be confused
 * with it — this app has no filter, so it has only the first kind.
 */
@Composable
fun EmptyState(
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    val c = Vorlaut.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .padding(horizontal = 20.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Txt(headline, style = Vorlaut.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Txt(body, style = Vorlaut.type.sub, color = c.textDim)
        footnote?.let { Txt(it, style = Vorlaut.type.sub, color = c.textFaint) }
    }
}

/**
 * A fact about a row — not a filter, so not a chip.
 *
 * Three weights, and they are the three things a fact can be: [accent] for one
 * that is simply true and worth knowing, [quiet] for one that is a caveat, and
 * the default for one that wants somebody to look.
 */
@Composable
fun Flag(
    text: String,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
    accent: Boolean = false,
) {
    val c = Vorlaut.colors
    val fill =
        when {
            accent -> c.accentSoft
            quiet -> c.surface2
            else -> c.dangerSoft
        }
    val ink =
        when {
            accent -> c.accentStrong
            quiet -> c.textDim
            else -> c.danger
        }
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .padding(horizontal = 11.dp, vertical = 4.dp),
    ) {
        Txt(text, style = Vorlaut.type.small, color = ink)
    }
}

/** The app bar: the mark, the wordmark, and where you are. Nothing else. */
@Composable
fun AppBar(
    modifier: Modifier = Modifier,
    where: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = Vorlaut.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = Vorlaut.metrics.screenMargin, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VorlautMark(Modifier.size(30.dp))
        Txt("vorlaut", style = Vorlaut.type.title, color = c.text)
        where?.let {
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .width(1.dp)
                    .height(18.dp)
                    .background(c.line),
            )
            Txt(it, style = Vorlaut.type.body, color = c.textDim)
        }
        Box(Modifier.weight(1f))
        trailing()
    }
}

/**
 * The overflow menu.
 *
 * A row shows its content and one `⋯`; everything you can *do* to it lives
 * behind that one trigger, so a row never grows a new control each time the
 * product grows a feature (design.md §4.3). Anchored under its trigger with a
 * 6dp gap and right-aligned to it, on a `--surface` plane.
 *
 * Destructive items sit last and are the only coloured thing in it. Nothing is
 * hidden behind hover — this is a touch screen and there is no hover to hide
 * behind.
 */
@Composable
fun OverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Vorlaut.colors
    if (!expanded) return
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, with(LocalDensity.current) { 6.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            // Intrinsic, not fill: inside a Popup the incoming constraint is the
            // whole window, and the rows fill whatever they are given — without
            // this the menu spans the screen.
            Modifier
                .width(IntrinsicSize.Max)
                .widthIn(min = 196.dp)
                .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radiusSm))
                .padding(6.dp),
            content = content,
        )
    }
}

/** One row of an [OverflowMenu]. */
@Composable
fun MenuItem(
    label: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Vorlaut.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusItem))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Txt(
            label,
            style = Vorlaut.type.sub,
            color = if (destructive) c.danger else c.text,
            maxLines = 1,
        )
    }
}

/**
 * A destructive confirmation.
 *
 * It names what is lost and labels the button with the act rather than with
 * "OK" (design.md §4.3), because "OK" on a dialog somebody half-read is how a
 * vocabulary disappears.
 */
@Composable
fun ConfirmDestructive(
    title: String,
    body: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Vorlaut.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(Vorlaut.metrics.radius))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radius))
                .padding(22.dp),
        ) {
            Txt(title, style = Vorlaut.type.rowName, color = c.text)
            Txt(body, style = Vorlaut.type.sub, color = c.textDim, modifier = Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Btn(cancelLabel, onDismiss, tier = BtnTier.Quiet)
                Box(Modifier.width(9.dp))
                Btn(confirmLabel, onConfirm, tier = BtnTier.Normal, destructive = true)
            }
        }
    }
}
