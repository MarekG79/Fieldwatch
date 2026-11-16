package app.fieldwatch.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Acoustic drone detector. Pure Kotlin, no Android calls, JVM-tested.
 *
 * A multirotor hums at its blade-pass frequency (motor rpm × blades / 60), roughly
 * 80–500 Hz, with a ladder of harmonics above it. Each frame looks for that ladder:
 * spectral peaks above the local noise floor that sit at k × f0. A drone keeps f0
 * steady for seconds; speech and passing noises do not. So an alarm needs the ladder
 * in most frames of the last [WINDOW_S] seconds, at one steady f0.
 *
 * It says "likely a drone", not which drone. Mowers, two-stroke engines and some
 * fans also hum with harmonics and can trip it.
 */
class AcousticDrone(val sampleRate: Int) {

    /** Samples per frame: largest power of two within 150 ms (16 kHz → 2048, 44.1/48 kHz → 4096). */
    val frameSize: Int = run {
        var n = 1
        while (n * 2 <= sampleRate * 0.15) n = n shl 1
        n
    }

    /** New samples between frames: half a frame. */
    val hop: Int = frameSize / 2

    val binHz: Double = sampleRate.toDouble() / frameSize

    data class Frame(
        /** Whether this frame holds a harmonic ladder. */
        val tonal: Boolean,
        /** Fundamental of the best ladder, Hz, or null. */
        val f0: Double?,
        /** Harmonics matched among the first [MAX_HARMONIC]. */
        val harmonics: Int,
        /** Mean height of the matched peaks above the noise floor, dB. */
        val snrDb: Double,
        /** Display rows: level 0..1 per band, low to high, [DISPLAY_ROWS] long. */
        val rows: FloatArray,
    )

    data class Verdict(
        val drone: Boolean,
        /** 0..1: share of recent frames with a steady ladder. */
        val confidence: Double,
        /** Median f0 over the window when [drone] or close to it. */
        val f0: Double?,
        val snrDb: Double,
    )

    private val window = DoubleArray(frameSize) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (frameSize - 1)) }
    private val re = DoubleArray(frameSize)
    private val im = DoubleArray(frameSize)
    private val history = ArrayDeque<Frame>()
    private val framesPerWindow: Int = max(8, (WINDOW_S * sampleRate / hop).roundToInt())
    private var active = false

    /** Analyse one frame of [frameSize] samples (−1..1). */
    fun analyse(samples: FloatArray, offset: Int = 0): Frame {
        for (i in 0 until frameSize) {
            re[i] = samples[offset + i] * window[i]
            im[i] = 0.0
        }
        fft(re, im)
        val bins = frameSize / 2
        val db = DoubleArray(bins)
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k]
            db[k] = 10.0 * log10(p + 1e-12)
        }
        val rows = displayRows(db)
        val lo = max(1, (MIN_HZ / binHz).toInt())
        val hi = min(bins - 2, (MAX_HZ / binHz).toInt())
        val floor = noiseFloor(db, lo, hi)
        val peaks = peaks(db, floor, lo, hi)
        val best = bestLadder(peaks)
        val tonal = best != null && best.count >= MIN_HARMONICS && best.snr >= MIN_SNR_DB
        return Frame(
            tonal = tonal,
            f0 = best?.f0,
            harmonics = best?.count ?: 0,
            snrDb = best?.snr ?: 0.0,
            rows = rows,
        )
    }

    /** Add a frame to the window and decide. Hysteresis: on at [ON_SHARE], off below [OFF_SHARE]. */
    fun push(frame: Frame): Verdict {
        history.addLast(frame)
        while (history.size > framesPerWindow) history.removeFirst()
        val tonal = history.filter { it.tonal && it.f0 != null }
        if (tonal.isEmpty() || history.size < framesPerWindow / 2) {
            active = false
            return Verdict(false, 0.0, null, 0.0)
        }
        val med = median(tonal.map { it.f0!! })
        val steady = tonal.filter { abs(it.f0!! - med) <= med * F0_TOLERANCE }
        val share = steady.size.toDouble() / framesPerWindow
        active = if (active) share >= OFF_SHARE else share >= ON_SHARE
        val snr = if (steady.isEmpty()) 0.0 else steady.sumOf { it.snrDb } / steady.size
        return Verdict(active, share.coerceIn(0.0, 1.0), if (steady.isNotEmpty()) med else null, snr)
    }

    fun reset() {
        history.clear()
        active = false
    }

    private data class Peak(val hz: Double, val height: Double)

    private data class Ladder(val f0: Double, val count: Int, val snr: Double)

    /** Median of the dB spectrum over a sliding ±[FLOOR_HALF] bins: robust to the peaks themselves. */
    private fun noiseFloor(db: DoubleArray, lo: Int, hi: Int): DoubleArray {
        val out = DoubleArray(db.size)
        val half = max(FLOOR_HALF, (FLOOR_HALF_HZ / binHz).roundToInt())
        val buf = DoubleArray(2 * half + 1)
        for (k in lo..hi) {
            val a = max(0, k - half)
            val b = min(db.size - 1, k + half)
            var n = 0
            for (j in a..b) buf[n++] = db[j]
            java.util.Arrays.sort(buf, 0, n)
            out[k] = buf[n / 2]
        }
        return out
    }

    /** Local maxima at least [PEAK_DB] above the floor, with parabolic frequency refinement. */
    private fun peaks(db: DoubleArray, floor: DoubleArray, lo: Int, hi: Int): List<Peak> {
        val out = ArrayList<Peak>()
        for (k in max(lo, 1)..hi) {
            val h = db[k] - floor[k]
            if (h < PEAK_DB) continue
            if (db[k] < db[k - 1] || db[k] < db[k + 1]) continue
            val a = db[k - 1]
            val b = db[k]
            val c = db[k + 1]
            val den = a - 2 * b + c
            val delta = if (den != 0.0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
            out += Peak((k + delta) * binHz, h)
        }
        return out.sortedByDescending { it.height }.take(MAX_PEAKS)
    }

    /**
     * Try each strong peak, and its half and third, as the fundamental. Count how many
     * of harmonics 1..[MAX_HARMONIC] land on a peak. Most harmonics wins; ties go to
     * the louder ladder, then the higher f0 (so f0/2 does not steal a clean ladder).
     */
    private fun bestLadder(peaks: List<Peak>): Ladder? {
        if (peaks.size < MIN_HARMONICS) return null
        var best: Ladder? = null
        val candidates = HashSet<Long>()
        for (p in peaks) {
            for (div in 1..3) {
                val f = p.hz / div
                if (f < MIN_F0 || f > MAX_F0) continue
                candidates += (f * 10).roundToInt().toLong()
            }
        }
        for (c in candidates) {
            val f0 = c / 10.0
            var count = 0
            var sum = 0.0
            var lowHit = false
            for (h in 1..MAX_HARMONIC) {
                val target = f0 * h
                val tol = max(binHz * 1.2, target * 0.03)
                val hit = peaks.filter { abs(it.hz - target) <= tol }.maxByOrNull { it.height } ?: continue
                count++
                sum += hit.height
                if (h <= 3) lowHit = true
            }
            if (!lowHit || count == 0) continue
            val ladder = Ladder(f0, count, sum / count)
            val b = best
            if (b == null || ladder.count > b.count ||
                (ladder.count == b.count && ladder.snr > b.snr + 0.5) ||
                (ladder.count == b.count && abs(ladder.snr - b.snr) <= 0.5 && ladder.f0 > b.f0)
            ) {
                best = ladder
            }
        }
        return best
    }

    /** Log-spaced bands 50 Hz – 5 kHz, levels mapped to 0..1 for the spectrogram. */
    private fun displayRows(db: DoubleArray): FloatArray {
        val out = FloatArray(DISPLAY_ROWS)
        val lo = log10(50.0)
        val hi = log10(min(5_000.0, sampleRate / 2.0 - binHz))
        for (r in 0 until DISPLAY_ROWS) {
            val f1 = Math.pow(10.0, lo + (hi - lo) * r / DISPLAY_ROWS)
            val f2 = Math.pow(10.0, lo + (hi - lo) * (r + 1) / DISPLAY_ROWS)
            val k1 = (f1 / binHz).toInt().coerceIn(0, db.size - 1)
            val k2 = (f2 / binHz).toInt().coerceIn(k1, db.size - 1)
            var m = -200.0
            for (k in k1..k2) m = max(m, db[k])
            // Roughly −40 dB (quiet room) to +20 dB (loud) after the window gain.
            out[r] = ((m + 40.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    companion object {
        const val MIN_HZ = 60.0
        const val MAX_HZ = 4_500.0
        const val MIN_F0 = 80.0
        const val MAX_F0 = 520.0
        const val MAX_HARMONIC = 10
        const val MIN_HARMONICS = 4
        const val MIN_SNR_DB = 9.0
        const val PEAK_DB = 7.0
        const val MAX_PEAKS = 24
        const val FLOOR_HALF = 6
        const val FLOOR_HALF_HZ = 120.0
        const val WINDOW_S = 3.0
        const val F0_TOLERANCE = 0.10
        const val ON_SHARE = 0.6
        const val OFF_SHARE = 0.3
        const val DISPLAY_ROWS = 64

        /** In-place iterative radix-2 FFT. Size must be a power of two. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wr = cos(ang)
                val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar
                        im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar
                        im[i + k] += ai
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }

        /** RMS of a buffer in dBFS, for the level meter. */
        fun rmsDb(samples: FloatArray, from: Int, to: Int): Double {
            var s = 0.0
            for (i in from until to) s += samples[i] * samples[i]
            val r = sqrt(s / max(1, to - from))
            return 20.0 * log10(r + 1e-9)
        }
    }
}
