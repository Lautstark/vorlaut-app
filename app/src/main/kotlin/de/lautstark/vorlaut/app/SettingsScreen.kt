package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.lautstark.vorlaut.app.design.AppBar
import de.lautstark.vorlaut.app.design.Btn
import de.lautstark.vorlaut.app.design.BtnTier
import de.lautstark.vorlaut.app.design.Txt
import de.lautstark.vorlaut.app.design.Vorlaut

/**
 * Einstellungen.
 *
 * It exists because the word was already at the foot of the Sammlungen screen,
 * as plain text, wired to nothing — the family convention says Einstellungen
 * lives there and the label was placed without the screen behind it.
 *
 * **What belongs here, and why the press timings do.** This screen used to say
 * it had one setting, the PIN, and that everything else was the package's to
 * decide. That was right about vocabularies and wrong about hands. Colours,
 * grids and gaps describe the board; a hold time describes the person holding
 * the tablet, and the same child needs the same one across every Sammlung on the
 * device. exchange/SPEC.md 4.1 says as much from the other side — a package
 * carries the author's default, and a viewer with its own setting SHOULD let it
 * win.
 *
 * So each timing has three states rather than two, and the third is the default:
 * take whatever the Sammlung asked for. [PressSettings] is where the reason an
 * override is stored apart from the package is written down.
 */
@Composable
fun SettingsScreen(
    pinIsSet: Boolean,
    pinningAvailable: Boolean,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
    onFixPinning: () -> Unit,
    packageTimings: PressTimings,
    holdOverrideMs: Int?,
    releaseOverrideMs: Int?,
    onHoldOverride: (Int?) -> Unit,
    onReleaseOverride: (Int?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vorlaut.colors
    Column(
        modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Zurück in the bar, where it is already in view on arrival. It sat
        // below two cards at the foot, which is behind the content rather than
        // beside it — and on a screen the device's own Back button did not
        // leave, it closed the app instead (MainActivity now handles that too).
        AppBar(
            where = stringResource(R.string.settings),
            trailing = { Btn(stringResource(R.string.back), onBack, tier = BtnTier.Normal) },
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Vorlaut.metrics.screenMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 860.dp).fillMaxWidth()) {
                Setting(
                    title = stringResource(R.string.pin_setting_title),
                    body =
                        stringResource(
                            if (pinIsSet) R.string.pin_setting_on else R.string.pin_setting_off,
                        ),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Btn(
                            stringResource(if (pinIsSet) R.string.pin_change else R.string.pin_set_up),
                            onSetPin,
                            tier = if (pinIsSet) BtnTier.Normal else BtnTier.Primary,
                        )
                        if (pinIsSet) {
                            Btn(stringResource(R.string.pin_remove), onRemovePin, tier = BtnTier.Quiet, destructive = true)
                        }
                    }
                }

                Box(Modifier.size(12.dp))

                /* The two timings, first, because they are the two somebody
                 * opens this screen for on a bad afternoon — the PIN is set once
                 * and never looked at again.
                 *
                 * Steps rather than a slider or a number field, and the same
                 * steps the editor offers: nobody tuning this knows the answer
                 * in advance, it is found by trying one and watching, and a
                 * slider invites the belief that 340 differs from 300, which it
                 * does not for any hand this is for. */
                Setting(
                    title = stringResource(R.string.press_hold_title),
                    body = stringResource(R.string.press_hold_body),
                ) {
                    PressSteps(
                        steps = HOLD_STEPS,
                        chosen = holdOverrideMs,
                        fromPackage = packageTimings.holdMs,
                        onChoose = onHoldOverride,
                    )
                }

                Box(Modifier.size(12.dp))

                Setting(
                    title = stringResource(R.string.press_release_title),
                    body = stringResource(R.string.press_release_body),
                ) {
                    PressSteps(
                        steps = RELEASE_STEPS,
                        chosen = releaseOverrideMs,
                        fromPackage = packageTimings.releaseMs,
                        onChoose = onReleaseOverride,
                    )
                }

                Box(Modifier.size(12.dp))

                // Stated here rather than shouted at the caregiver mid-task. It
                // is a fact about the tablet they may want before they hand it
                // over, not an error: Android's app pinning is off until
                // somebody turns it on, and no app can turn it on for them.
                Setting(
                    title = stringResource(R.string.pinning_setting_title),
                    body =
                        stringResource(
                            if (pinningAvailable) R.string.pinning_on else R.string.pinning_off,
                        ),
                ) {
                    if (!pinningAvailable) {
                        Btn(stringResource(R.string.open_security_settings), onFixPinning, tier = BtnTier.Normal)
                    }
                }
            }
        }

        Box(Modifier.size(20.dp))
    }
}

/** The steps each timing is offered in. The editor's lists, deliberately — a
 *  caregiver tuning the same child from both ends should not find two different
 *  sets of numbers. See accessPanel in `vorlaut-editor`. */
private val HOLD_STEPS = listOf(0, 100, 300, 500, 800)
private val RELEASE_STEPS = listOf(0, 300, 500, 1000, 1500)

/**
 * One timing, as "Aus der Sammlung" plus the steps.
 *
 * The first chip is the third state and the default, and it names the number it
 * currently resolves to. Saying only "Aus der Sammlung" would leave the one
 * question a caregiver actually has — *what is it doing right now* — answerable
 * only by exporting the Sammlung again and reading the manifest.
 */
@Composable
private fun PressSteps(
    steps: List<Int>,
    chosen: Int?,
    fromPackage: Int,
    onChoose: (Int?) -> Unit,
) {
    val c = Vorlaut.colors
    val spoken: @Composable (Int) -> String = { ms ->
        if (ms == 0) stringResource(R.string.press_off) else stringResource(R.string.press_ms, ms)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Step(
            label = stringResource(R.string.press_follow_package_is, spoken(fromPackage)),
            selected = chosen == null,
            onClick = { onChoose(null) },
        )
        for (ms in steps) {
            Step(
                label = spoken(ms),
                selected = chosen == ms,
                onClick = { onChoose(ms) },
            )
        }
    }
    // Said once under both, rather than twice.
    Box(Modifier.size(6.dp))
    Txt(stringResource(R.string.press_note), style = Vorlaut.type.sub, color = c.textDim)
}

/** One chip. The bordered box that lights up when chosen, which is the shape
 *  the editor's own step picker takes and the one this family draws a choice
 *  in. */
@Composable
private fun Step(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = Vorlaut.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(if (selected) c.accentSoft else c.surface)
            .border(
                1.dp,
                if (selected) c.accent else c.line,
                RoundedCornerShape(Vorlaut.metrics.radiusSm),
            ).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Txt(label, style = Vorlaut.type.sub, color = if (selected) c.text else c.textDim)
    }
}

@Composable
private fun Setting(
    title: String,
    body: String,
    control: @Composable () -> Unit,
) {
    val c = Vorlaut.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Vorlaut.metrics.radius))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(Vorlaut.metrics.radius))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(title, style = Vorlaut.type.rowName, color = c.text)
        Txt(body, style = Vorlaut.type.sub, color = c.textDim)
        Box(Modifier.size(6.dp))
        control()
    }
}
