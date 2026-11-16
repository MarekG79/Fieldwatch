package app.fieldwatch.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.audio.AcousticListener
import app.fieldwatch.domain.AcousticDrone
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.ui.component.FieldwatchSwitch
import app.fieldwatch.ui.component.SectionCard
import app.fieldwatch.ui.theme.Amber
import app.fieldwatch.ui.theme.SignalRed
import kotlin.math.roundToInt

/**
 * Simple screen → "Propeller sound". Starts the microphone only while the switch is on
 * and this screen is in front; stops it otherwise.
 */
@Composable
internal fun AcousticCard() {
    val app = LocalContext.current.applicationContext as FieldwatchApp
    val listener = app.acoustic
    val st by listener.state.collectAsStateWithLifecycle()
    var wanted by rememberSaveable { mutableStateOf(false) }
    var saveNote by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        wanted = granted
    }

    // Run only while wanted and the screen is started; Android gives background apps silence.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, wanted) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (wanted) listener.start()
                Lifecycle.Event.ON_STOP -> listener.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        if (wanted && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) listener.start()
        if (!wanted) listener.stop()
        onDispose {
            owner.lifecycle.removeObserver(obs)
            listener.stop()
        }
    }
    // Keep the display on while listening, like the hunt screen does.
    val view = LocalView.current
    DisposableEffect(wanted) {
        val prev = view.keepScreenOn
        if (wanted) view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }

    SectionCard(LanguageText.text("acoustic.title")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(LanguageText.text("acoustic.listen"), Modifier.weight(1f))
            FieldwatchSwitch(wanted, onCheckedChange = { on ->
                if (!on) {
                    wanted = false
                } else if (listener.hasPermission()) {
                    wanted = true
                } else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            })
        }
        if (!wanted) {
            Note(LanguageText.text("acoustic.note"))
            Note(LanguageText.text("acoustic.foreground"))
        }
        st.errorKey?.let { key ->
            Text(LanguageText.text(key), color = SignalRed, style = MaterialTheme.typography.bodyMedium)
        }
        if (wanted && st.listening) {
            val v = st.verdict
            val hz = v.f0?.roundToInt()?.toString().orEmpty()
            val (line, color) = when {
                v.drone -> LanguageText.text("acoustic.heard", mapOf("hz" to hz)) to SignalRed
                v.confidence >= 0.25 && v.f0 != null ->
                    LanguageText.text("acoustic.maybe", mapOf("hz" to hz)) to Amber
                st.columns.size < 8 -> LanguageText.text("acoustic.listening") to MaterialTheme.colorScheme.onSurfaceVariant
                else -> LanguageText.text("acoustic.quiet") to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(line, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(
                progress = { v.confidence.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = if (v.drone) SignalRed else Amber,
            )
            Spectrogram(st.columns, v.f0, st.sampleRate)
            Text(
                LanguageText.text("acoustic.level", mapOf("db" to st.levelDb.coerceAtLeast(-99.0).roundToInt().toString())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    val name = listener.saveClip()
                    saveNote = if (name != null) {
                        LanguageText.text("acoustic.saved", mapOf("name" to name))
                    } else {
                        LanguageText.text("acoustic.save.failed")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(LanguageText.text("acoustic.save")) }
            saveNote?.let { Note(it) }
        }
    }
}

/** Time left to right, 50 Hz – 5 kHz bottom to top (log scale). Dashed marks at k × f0. */
@Composable
private fun Spectrogram(columns: List<FloatArray>, f0: Double?, sampleRate: Int) {
    val hot = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg),
    ) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            if (columns.isEmpty()) return@Canvas
            val rows = AcousticDrone.DISPLAY_ROWS
            val cw = size.width / AcousticListener.COLUMNS
            val rh = size.height / rows
            val x0 = size.width - columns.size * cw
            columns.forEachIndexed { c, col ->
                for (r in 0 until rows) {
                    val v = col[r]
                    if (v < 0.08f) continue
                    drawRect(
                        color = hot.copy(alpha = v),
                        topLeft = Offset(x0 + c * cw, size.height - (r + 1) * rh),
                        size = Size(cw + 0.5f, rh + 0.5f),
                    )
                }
            }
            if (f0 != null && sampleRate > 0) {
                val lo = Math.log10(50.0)
                val hi = Math.log10(minOf(5_000.0, sampleRate / 2.0))
                for (k in 1..6) {
                    val f = f0 * k
                    if (f < 50.0 || f > 5_000.0) continue
                    val y = size.height - ((Math.log10(f) - lo) / (hi - lo) * size.height).toFloat()
                    drawLine(SignalRed.copy(alpha = 0.7f), Offset(size.width - 10.dp.toPx(), y), Offset(size.width, y), 2.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
