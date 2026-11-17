@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.fieldwatch.ui.screen

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.domain.DroneFence
import app.fieldwatch.domain.DroneWatch
import app.fieldwatch.domain.Geo
import app.fieldwatch.domain.Incident
import app.fieldwatch.incident.IncidentSaver
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.ui.FieldwatchUi
import app.fieldwatch.ui.FieldwatchViewModel
import app.fieldwatch.ui.component.FieldwatchFilterChip
import app.fieldwatch.ui.component.FieldwatchSwitch
import app.fieldwatch.ui.component.SectionCard
import app.fieldwatch.ui.theme.Amber
import app.fieldwatch.ui.theme.Phosphor
import app.fieldwatch.ui.theme.SignalRed
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Antidron simple mode: one screen, one answer — is there a drone near me?
 * Green: none. Amber: a drone is heard outside the zone. Red: inside the zone.
 * Separate file so the upstream screens stay untouched.
 */
@Composable
internal fun SimpleScreen(state: FieldwatchUi, vm: FieldwatchViewModel) {
    val settings = state.settings
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    val drones = DroneWatch.drones(
        rows = state.devices,
        fleets = state.fleets,
        phoneLat = state.phoneLat,
        phoneLon = state.phoneLon,
        now = now,
    )
    val radius = settings.droneFenceM
    val level = DroneWatch.level(drones, radius, settings.droneFencePilot)
    val acoustic by (LocalContext.current.applicationContext as app.fieldwatch.FieldwatchApp)
        .acoustic.state.collectAsStateWithLifecycle()
    val heardHz = if (acoustic.listening && acoustic.verdict.drone) acoustic.verdict.f0 else null
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state.scanning, onStart = vm::startScan)
        if (!state.locationOn) LocationHint()
        StatusCard(level, drones.firstOrNull(), radius, heardHz)
        // While an alarm lasts: one tap saves the last 5 minutes of radios (and sound).
        val nearest = drones.firstOrNull()
        val alarmNow = when {
            level == DroneWatch.Level.ALERT && nearest != null -> Incident.Alarm(
                type = if ((nearest.distanceM ?: Double.MAX_VALUE) <= radius) IncidentSaver.TYPE_AIRCRAFT else IncidentSaver.TYPE_PILOT,
                title = LanguageText.text("simple.alert"),
                body = nearest.label,
                deviceKey = nearest.key,
            )
            heardHz != null -> Incident.Alarm(
                type = IncidentSaver.TYPE_SOUND,
                title = LanguageText.text("simple.heard"),
                body = LanguageText.text("simple.heard.note", mapOf("hz" to heardHz.roundToInt().toString())),
                toneHz = heardHz,
            )
            else -> null
        }
        if (alarmNow != null) SaveEventBar(alarmNow, onRed = false)
        if (drones.isNotEmpty()) {
            Text(
                LanguageText.text("simple.drones"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            drones.forEach { DroneRow(it, radius, now) }
        }
        AcousticCard(state, vm)
        ZoneCard(
            vm = vm,
            settings = settings,
            on = settings.droneFence,
            radius = radius,
            pilot = settings.droneFencePilot,
            onToggle = { on -> vm.updateSettings { it.copy(droneFence = on) } },
            onRadius = { m -> vm.updateSettings { it.copy(droneFenceM = m) } },
            onPilot = { on -> vm.updateSettings { it.copy(droneFencePilot = on) } },
        )
        Text(
            LanguageText.text("simple.limits"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { vm.updateSettings { it.copy(simpleMode = false) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Tune, null)
            Spacer(Modifier.width(8.dp))
            Text(LanguageText.text("simple.advanced"))
        }
    }
}

/** Settings list entry in technical mode: back to the one-screen view. */
@Composable
internal fun SimpleModeEntry(vm: FieldwatchViewModel) {
    SectionCard(LanguageText.text("simple.mode"), showTitle = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(LanguageText.text("simple.mode"), fontWeight = FontWeight.SemiBold)
                Text(
                    LanguageText.text("simple.mode.note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { vm.updateSettings { it.copy(simpleMode = true) } }) {
                Text(LanguageText.text("simple.start"))
            }
        }
    }
}

@Composable
private fun Header(scanning: Boolean, onStart: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                LanguageText.text("simple.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(if (scanning) Phosphor else MaterialTheme.colorScheme.onSurfaceVariant, scanning)
                Spacer(Modifier.width(6.dp))
                Text(
                    LanguageText.text(if (scanning) "simple.watching" else "simple.stopped"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!scanning) {
            TextButton(onClick = onStart) { Text(LanguageText.text("simple.start")) }
        }
    }
}

@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(
        Modifier
            .size(10.dp)
            .alpha(if (pulsing) a else 1f)
            .background(color, CircleShape),
    )
}

@Composable
private fun LocationHint() {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Amber.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                LanguageText.text("simple.location.off"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text(LanguageText.text("ui.open.android.settings")) }
        }
    }
}

@Composable
private fun StatusCard(level: DroneWatch.Level, nearest: DroneWatch.Drone?, radius: Int, heardHz: Double?) {
    // Sound alone never outranks a drone in the zone; it lifts "clear" to amber.
    val heardOnly = level == DroneWatch.Level.CLEAR && heardHz != null
    val (color, icon, titleKey) = when {
        heardOnly -> Triple(Amber, Icons.Outlined.GraphicEq, "simple.heard")
        level == DroneWatch.Level.CLEAR -> Triple(Phosphor, Icons.Outlined.CheckCircle, "simple.clear")
        level == DroneWatch.Level.NEARBY -> Triple(Amber, Icons.Outlined.Flight, "simple.nearby")
        else -> Triple(SignalRed, Icons.Outlined.Warning, "simple.alert")
    }
    val zone = Geo.distanceText(radius.toDouble())
    val note = if (heardOnly) {
        LanguageText.text("simple.heard.note", mapOf("hz" to heardHz!!.roundToInt().toString()))
    } else when (level) {
        DroneWatch.Level.CLEAR -> LanguageText.text("simple.clear.note")
        DroneWatch.Level.NEARBY -> if (nearest?.distanceM == null) {
            LanguageText.text("simple.nearby.nopos")
        } else {
            LanguageText.text("simple.nearby.note", mapOf("m" to zone))
        }
        DroneWatch.Level.ALERT -> {
            val d = nearest?.let { listOfNotNull(it.distanceM, it.pilotDistanceM).minOrNull() }
            val b = nearest?.let { if (it.distanceM != null && it.distanceM == d) it.bearingDeg else it.pilotBearingDeg }
            if (d != null && b != null) {
                LanguageText.text(
                    "simple.alert.note",
                    mapOf("d" to Geo.distanceText(d), "dir" to direction(b), "m" to zone),
                )
            } else {
                LanguageText.text("simple.nearby.note", mapOf("m" to zone))
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.16f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(64.dp))
            Text(
                LanguageText.text(titleKey),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                note,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DroneRow(drone: DroneWatch.Drone, radius: Int, now: Long) {
    val inside = (drone.distanceM ?: Double.MAX_VALUE) <= radius
    val tint = if (inside) SignalRed else Amber
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Flight, null, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (drone.distanceM != null && drone.bearingDeg != null) {
                        LanguageText.text(
                            "simple.drone.where",
                            mapOf("d" to Geo.distanceText(drone.distanceM), "dir" to direction(drone.bearingDeg)),
                        )
                    } else {
                        LanguageText.text("simple.drone.nowhere")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                drone.aboveOperatorM?.let { h ->
                    Text(
                        LanguageText.text("simple.drone.height", mapOf("h" to Geo.distanceText(h.coerceAtLeast(0.0)))),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (drone.pilotDistanceM != null && drone.pilotBearingDeg != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Person, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            LanguageText.text(
                                "simple.drone.pilot",
                                mapOf("d" to Geo.distanceText(drone.pilotDistanceM), "dir" to direction(drone.pilotBearingDeg)),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    drone.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val secs = ((now - drone.lastSeen) / 1000L).coerceAtLeast(0L)
                Text(
                    LanguageText.text(if (drone.remoteId) "simple.drone.rid" else "simple.drone.radio") +
                        " · " + LanguageText.text("simple.drone.seen", mapOf("s" to secs.toString())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ZoneCard(
    vm: FieldwatchViewModel,
    settings: app.fieldwatch.domain.AppSettings,
    on: Boolean,
    radius: Int,
    pilot: Boolean,
    onToggle: (Boolean) -> Unit,
    onRadius: (Int) -> Unit,
    onPilot: (Boolean) -> Unit,
) {
    SectionCard(LanguageText.text("simple.zone")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(LanguageText.text("dronefence.on"), Modifier.weight(1f))
            FieldwatchSwitch(on, onToggle)
        }
        Text(LanguageText.text("dronefence.radius"), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DroneFence.RADII.forEach { m ->
                FieldwatchFilterChip(
                    selected = radius == m,
                    onClick = { onRadius(m) },
                    label = { Text(Geo.distanceText(m.toDouble())) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(LanguageText.text("dronefence.pilot"), Modifier.weight(1f))
            FieldwatchSwitch(pilot, onPilot)
        }
        FullScreenAlarmRow(vm, settings)
        val nanState by app.fieldwatch.radio.NanRadio.state.collectAsStateWithLifecycle()
        Text(
            nanStatusLine(
                nanState,
                app.fieldwatch.radio.NanRadio.supported(LocalContext.current),
                settings.nanRemoteId,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun direction(bearingDeg: Double): String =
    LanguageText.text("simple.dir.${DroneWatch.compassIndex(bearingDeg)}")
