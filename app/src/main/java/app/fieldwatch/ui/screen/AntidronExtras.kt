package app.fieldwatch.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.domain.AppSettings
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.radio.NanRadio
import app.fieldwatch.ui.FieldwatchViewModel
import app.fieldwatch.ui.component.FieldwatchSwitch
import app.fieldwatch.ui.theme.Amber

/** Antidron: full-screen alarm switch, with the Android 14+ permission shortcut. */
@Composable
internal fun FullScreenAlarmRow(vm: FieldwatchViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val app = context.applicationContext as FieldwatchApp
    // Re-read the permission when the user comes back from Android settings.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val allowed = remember(tick) { runCatching { app.alerter.canFullScreen() }.getOrDefault(true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(LanguageText.text("dronefence.fullscreen"))
            ExtraNote(LanguageText.text("dronefence.fullscreen.note"))
        }
        FieldwatchSwitch(settings.droneFenceFullScreen, onCheckedChange = { on ->
            vm.updateSettings { it.copy(droneFenceFullScreen = on) }
        })
    }
    if (settings.droneFenceFullScreen && !allowed && Build.VERSION.SDK_INT >= 34) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                LanguageText.text("dronefence.fullscreen.denied"),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text(LanguageText.text("dronefence.fullscreen.allow")) }
        }
    }
}

/** Antidron: Wi-Fi NAN Remote ID switch and what the radio is doing. */
@Composable
internal fun NanRow(vm: FieldwatchViewModel, settings: AppSettings) {
    val state by NanRadio.state.collectAsStateWithLifecycle()
    val supported = NanRadio.supported(LocalContext.current)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(LanguageText.text("nan.title"))
            ExtraNote(LanguageText.text("nan.note"))
        }
        FieldwatchSwitch(settings.nanRemoteId && supported, onCheckedChange = { on ->
            vm.updateSettings { it.copy(nanRemoteId = on) }
        }, enabled = supported)
    }
    ExtraNote(nanStatusLine(state, supported, settings.nanRemoteId))
}

/** One line for the simple screen and settings. */
internal fun nanStatusLine(state: NanRadio.State, supported: Boolean, on: Boolean): String {
    val key = when {
        !supported -> "nan.state.unsupported"
        !on -> "nan.state.off"
        state == NanRadio.State.RUNNING -> "nan.state.running"
        state == NanRadio.State.UNAVAILABLE -> "nan.state.unavailable"
        state == NanRadio.State.FAILED -> "nan.state.failed"
        state == NanRadio.State.STARTING -> "nan.state.starting"
        else -> "nan.state.waiting"
    }
    return LanguageText.text("nan.status", mapOf("state" to LanguageText.text(key)))
}

@Composable
private fun ExtraNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
