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
 * So there are four answers and the default is the first: take whatever the
 * Sammlung asked for, or one of [PressMode]'s three. [PressSettings] is where
 * the reason an override is stored apart from the package is written down, and
 * [PressMode] is where the reason this screen names modes while the editor
 * offers milliseconds is.
 */
@Composable
fun SettingsScreen(
    pinIsSet: Boolean,
    pinningAvailable: Boolean,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
    onFixPinning: () -> Unit,
    packageTimings: PressTimings,
    mode: PressMode?,
    onMode: (PressMode?) -> Unit,
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

                /* First, because it is what somebody opens this screen for on a
                 * bad afternoon — the PIN is set once and never looked at again.
                 *
                 * One question rather than the two millisecond pickers this
                 * replaces. Those were honest and unusable: a parent knows their
                 * child pressed once and got three words, not that they want a
                 * 600 ms post-activation cooldown. The numbers still exist and
                 * the editor still offers all of them, because somebody
                 * authoring a board sets a considered default once; this is the
                 * screen for adjusting from what you just watched happen. */
                Setting(
                    title = stringResource(R.string.press_title),
                    body = stringResource(R.string.press_body),
                ) {
                    PressModes(
                        chosen = mode,
                        fromPackage = packageTimings,
                        onChoose = onMode,
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

/**
 * The four answers: follow the Sammlung, or one of [PressMode]'s three.
 *
 * Each carries a line saying what it does, because the names alone are honest
 * but thin — "Einmal pro Druck" is checkable against what somebody just watched
 * happen only once they know a press is what is being counted. Stacked rather
 * than laid out as chips in a row for the same reason: with a sentence under
 * each, four of them side by side would wrap into a wall.
 *
 * The first names what the Sammlung currently asks for. Saying only "Aus der
 * Sammlung" would leave the question a caregiver actually has — *what is it
 * doing right now* — answerable only by exporting the Sammlung and reading its
 * manifest.
 */
@Composable
private fun PressModes(
    chosen: PressMode?,
    fromPackage: PressTimings,
    onChoose: (PressMode?) -> Unit,
) {
    val c = Vorlaut.colors
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Choice(
            label = stringResource(R.string.press_follow_package),
            note = stringResource(R.string.press_follow_package_is, describe(fromPackage)),
            selected = chosen == null,
            onClick = { onChoose(null) },
        )
        for (mode in PressMode.entries) {
            Choice(
                label = stringResource(nameOf(mode)),
                note = stringResource(noteOf(mode)),
                selected = chosen == mode,
                onClick = { onChoose(mode) },
            )
        }
    }
    Box(Modifier.size(6.dp))
    Txt(stringResource(R.string.press_note), style = Vorlaut.type.sub, color = c.textDim)
}

/* Written out one mode at a time rather than built from the enum's name,
 * so that every string id in this app is greppable from the source. */
private fun nameOf(mode: PressMode) =
    when (mode) {
        PressMode.AtOnce -> R.string.press_mode_at_once
        PressMode.Once -> R.string.press_mode_once
        PressMode.Held -> R.string.press_mode_held
    }

private fun noteOf(mode: PressMode) =
    when (mode) {
        PressMode.AtOnce -> R.string.press_mode_at_once_note
        PressMode.Once -> R.string.press_mode_once_note
        PressMode.Held -> R.string.press_mode_held_note
    }

/** What a Sammlung is asking for, in the words of the mode nearest to it. */
@Composable
private fun describe(timings: PressTimings): String = stringResource(nameOf(PressMode.nearest(timings)))

/** One choice: a name, a line about it, and the box that lights up when it is
 *  the one in force. The shape the word-colour options take in the editor. */
@Composable
private fun Choice(
    label: String,
    note: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = Vorlaut.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Vorlaut.metrics.radiusSm))
            .background(if (selected) c.accentSoft else c.surface)
            .border(
                1.dp,
                if (selected) c.accent else c.line,
                RoundedCornerShape(Vorlaut.metrics.radiusSm),
            ).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt(label, style = Vorlaut.type.rowName, color = if (selected) c.text else c.textDim)
        Txt(note, style = Vorlaut.type.sub, color = c.textDim)
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
