package de.lautstark.vorlaut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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
 * There is one setting, and it is the PIN. Everything else this app might have
 * had a preference about is decided by the package instead.
 */
@Composable
fun SettingsScreen(
    pinIsSet: Boolean,
    pinningAvailable: Boolean,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
    onFixPinning: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vorlaut.colors
    Column(
        modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AppBar(where = stringResource(R.string.settings))

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
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

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.End,
        ) { Btn(stringResource(R.string.back), onBack, tier = BtnTier.Normal) }
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
