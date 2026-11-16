@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.fieldwatch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fieldwatch.domain.AppSettings
import app.fieldwatch.domain.DroneFence
import app.fieldwatch.domain.Geo
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.ui.FieldwatchViewModel
import app.fieldwatch.ui.component.FieldwatchFilterChip
import app.fieldwatch.ui.component.FieldwatchSwitch
import app.fieldwatch.ui.component.SectionCard

/** Settings → Drone geofence. Kept out of SettingsScreen so upstream merges stay small. */
@Composable
internal fun DroneFenceSettings(vm: FieldwatchViewModel, settings: AppSettings) {
    SectionCard(LanguageText.text("settings.topic.dronefence"), showTitle = false) {
        FenceSwitchRow(LanguageText.text("dronefence.on"), settings.droneFence) { on ->
            vm.updateSettings { it.copy(droneFence = on) }
        }
        FenceNote(LanguageText.text("dronefence.note"))
        if (settings.droneFence && !settings.tagLocation) {
            Text(
                LanguageText.text("dronefence.needs.gps"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (!settings.droneFence) return
    SectionCard(LanguageText.text("dronefence.radius")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DroneFence.RADII.forEach { m ->
                FieldwatchFilterChip(
                    selected = settings.droneFenceM == m,
                    onClick = { vm.updateSettings { it.copy(droneFenceM = m) } },
                    label = { Text(Geo.distanceText(m.toDouble())) },
                )
            }
        }
        FenceSwitchRow(LanguageText.text("dronefence.pilot"), settings.droneFencePilot) { on ->
            vm.updateSettings { it.copy(droneFencePilot = on) }
        }
        FenceNote(LanguageText.text("dronefence.pilot.note"))
    }
    SectionCard(LanguageText.text("settings.topic.alerts")) {
        FenceSwitchRow(LanguageText.text("dronefence.beep"), settings.droneFenceBeep) { on ->
            vm.updateSettings { it.copy(droneFenceBeep = on) }
        }
        FenceSwitchRow(LanguageText.text("dronefence.voice"), settings.droneFenceVoice) { on ->
            vm.updateSettings { it.copy(droneFenceVoice = on) }
        }
        FenceSwitchRow(LanguageText.text("dronefence.shade"), settings.droneFenceShade) { on ->
            vm.updateSettings { it.copy(droneFenceShade = on) }
        }
    }
}

/** Summary on the Settings topic list. */
@Composable
internal fun droneFenceSummary(settings: AppSettings): String =
    if (settings.droneFence) {
        "${LanguageText.text("ui.on.label")} · ${Geo.distanceText(settings.droneFenceM.toDouble())}"
    } else {
        LanguageText.text("ui.off.label")
    }

@Composable
private fun FenceSwitchRow(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        FieldwatchSwitch(on, set)
    }
}

@Composable
private fun FenceNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
