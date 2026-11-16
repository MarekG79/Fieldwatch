package app.fieldwatch.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.domain.AcousticDrone
import app.fieldwatch.domain.LanguageText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Microphone side of the acoustic drone detector. Listens only while asked, on its own
 * thread; nothing is stored or sent unless the user taps Save, which writes the last
 * [KEEP_S] seconds as a WAV file to Downloads/Antidron.
 *
 * Foreground only: Android gives background apps silence, so the screen stops it on
 * leaving and starts it again on return.
 */
class AcousticListener(private val app: FieldwatchApp) {

    data class State(
        val listening: Boolean = false,
        /** Language key of the last problem, or null. */
        val errorKey: String? = null,
        val sampleRate: Int = 0,
        val verdict: AcousticDrone.Verdict = AcousticDrone.Verdict(false, 0.0, null, 0.0),
        val levelDb: Double = -120.0,
        /** Spectrogram columns, oldest first, each [AcousticDrone.DISPLAY_ROWS] long. */
        val columns: List<FloatArray> = emptyList(),
        /** File name of the last saved clip. */
        val savedName: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    private val ringLock = Any()
    private var ring = ShortArray(0)
    private var ringPos = 0
    private var ringFull = false
    private var lastAlertAt = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start() {
        if (running) return
        if (!hasPermission()) {
            _state.value = _state.value.copy(listening = false, errorKey = "acoustic.error.permission")
            return
        }
        running = true
        _state.value = _state.value.copy(listening = true, errorKey = null)
        worker = Thread({ loop() }, "antidron-acoustic").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        _state.value = _state.value.copy(
            listening = false,
            verdict = AcousticDrone.Verdict(false, 0.0, null, 0.0),
            levelDb = -120.0,
        )
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val rec = open()
        if (rec == null) {
            running = false
            _state.value = _state.value.copy(listening = false, errorKey = "acoustic.error.mic")
            return
        }
        val sr = rec.sampleRate
        val det = AcousticDrone(sr)
        synchronized(ringLock) {
            ring = ShortArray(sr * KEEP_S)
            ringPos = 0
            ringFull = false
        }
        val frame = FloatArray(det.frameSize)
        val chunk = ShortArray(det.hop)
        val columns = ArrayDeque<FloatArray>()
        var filled = 0
        try {
            rec.startRecording()
            _state.value = _state.value.copy(sampleRate = sr, columns = emptyList())
            while (running && !Thread.currentThread().isInterrupted) {
                var got = 0
                while (got < chunk.size && running) {
                    val n = rec.read(chunk, got, chunk.size - got)
                    if (n < 0) throw IllegalStateException("AudioRecord.read $n")
                    got += n
                }
                if (!running) break
                remember(chunk)
                // Slide the frame left by one hop and append the new samples.
                System.arraycopy(frame, det.hop, frame, 0, det.frameSize - det.hop)
                for (i in chunk.indices) frame[det.frameSize - det.hop + i] = chunk[i] / 32768f
                filled = minOf(det.frameSize, filled + det.hop)
                if (filled < det.frameSize) continue
                val f = det.analyse(frame)
                val v = det.push(f)
                columns.addLast(f.rows)
                while (columns.size > COLUMNS) columns.removeFirst()
                val wasDrone = _state.value.verdict.drone
                _state.value = _state.value.copy(
                    verdict = v,
                    levelDb = AcousticDrone.rmsDb(frame, det.frameSize - det.hop, det.frameSize),
                    columns = columns.toList(),
                )
                if (v.drone && !wasDrone) alert(v)
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            _state.value = _state.value.copy(errorKey = "acoustic.error.mic")
        } finally {
            runCatching { rec.stop() }
            rec.release()
            running = false
            _state.value = _state.value.copy(listening = false)
        }
    }

    /** Prefer the raw source so the phone's noise suppression does not eat the hum. */
    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = buildList {
            if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        for (src in sources) {
            for (sr in intArrayOf(16_000, 48_000, 44_100)) {
                val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val rec = runCatching {
                    AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sr))
                }.getOrNull() ?: continue
                if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
                rec.release()
            }
        }
        return null
    }

    private fun remember(chunk: ShortArray) {
        synchronized(ringLock) {
            if (ring.isEmpty()) return
            for (s in chunk) {
                ring[ringPos] = s
                ringPos = (ringPos + 1) % ring.size
                if (ringPos == 0) ringFull = true
            }
        }
    }

    private fun alert(v: AcousticDrone.Verdict) {
        val now = System.currentTimeMillis()
        if (now - lastAlertAt < REARM_MS) return
        lastAlertAt = now
        val settings = app.config.settings
        val hz = (v.f0 ?: 0.0).roundToInt().toString()
        runCatching {
            app.alerter.fenceAlert(
                title = LanguageText.text("acoustic.alert"),
                body = LanguageText.text("acoustic.alert.body", mapOf("hz" to hz)),
                phrase = if (settings.droneFenceVoice) LanguageText.text("acoustic.alert") else null,
                beep = settings.droneFenceBeep,
                shade = settings.droneFenceShade,
            )
        }
    }

    /** Saves the last [KEEP_S] seconds to Downloads/Antidron. Returns the file name or null. */
    fun saveClip(): String? {
        val (pcm, sr) = synchronized(ringLock) {
            if (ring.isEmpty()) return null
            val n = if (ringFull) ring.size else ringPos
            if (n == 0) return null
            val out = ShortArray(n)
            if (ringFull) {
                val tail = ring.size - ringPos
                System.arraycopy(ring, ringPos, out, 0, tail)
                System.arraycopy(ring, 0, out, tail, ringPos)
            } else {
                System.arraycopy(ring, 0, out, 0, n)
            }
            out to _state.value.sampleRate
        }
        if (sr <= 0) return null
        val name = "antidron-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Antidron")
        }
        val uri = runCatching {
            app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        val ok = runCatching {
            app.contentResolver.openOutputStream(uri)?.use { writeWav(it, pcm, sr) } != null
        }.getOrDefault(false)
        if (!ok) {
            runCatching { app.contentResolver.delete(uri, null, null) }
            return null
        }
        _state.value = _state.value.copy(savedName = name)
        return name
    }

    private fun writeWav(out: OutputStream, pcm: ShortArray, sr: Int) {
        val data = pcm.size * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data)
        out.write(h.array())
        val body = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) body.putShort(s)
        out.write(body.array())
    }

    companion object {
        const val KEEP_S = 10
        /** About 7.5 s of spectrogram at 16 kHz. */
        const val COLUMNS = 120
        const val REARM_MS = 120_000L
    }
}
