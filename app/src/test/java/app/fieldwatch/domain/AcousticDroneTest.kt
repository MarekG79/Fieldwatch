package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class AcousticDroneTest {
    private val sr = 16_000

    /** Four motors near [f0], each with a harmonic ladder, slow rpm wobble, plus white noise. */
    private fun drone(seconds: Double, f0: Double, level: Double, noise: Double, seed: Long = 1): FloatArray {
        val rnd = java.util.Random(seed)
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        val motors = doubleArrayOf(f0 * 0.97, f0 * 0.99, f0 * 1.01, f0 * 1.03)
        val phase = DoubleArray(motors.size * 12)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var s = 0.0
            motors.forEachIndexed { m, base ->
                val f = base * (1.0 + 0.01 * sin(2 * PI * (0.3 + 0.1 * m) * t))
                for (h in 1..12) {
                    val idx = m * 12 + h - 1
                    phase[idx] += 2 * PI * f * h / sr
                    s += level / h.toDouble().pow(0.7) * sin(phase[idx])
                }
            }
            out[i] = (s / motors.size + noise * rnd.nextGaussian()).toFloat()
        }
        return out
    }

    private fun noise(seconds: Double, level: Double, seed: Long = 7): FloatArray {
        val rnd = java.util.Random(seed)
        return FloatArray((seconds * sr).toInt()) { (level * rnd.nextGaussian()).toFloat() }
    }

    private fun sine(seconds: Double, hz: Double, level: Double): FloatArray =
        FloatArray((seconds * sr).toInt()) { i -> (level * sin(2 * PI * hz * i / sr)).toFloat() }

    /** Voiced speech stand-in: f0 gliding 110–220 Hz, rich harmonics, syllable bursts. */
    private fun speech(seconds: Double): FloatArray {
        val rnd = java.util.Random(3)
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f0 = 165.0 + 55.0 * sin(2 * PI * 0.7 * t)
            ph += 2 * PI * f0 / sr
            val voiced = (t % 0.45) < 0.25
            var s = 0.0
            if (voiced) for (h in 1..15) s += 0.3 / h * sin(h * ph)
            out[i] = (s + 0.01 * rnd.nextGaussian()).toFloat()
        }
        return out
    }

    /** Runs the detector over [x]; returns the share of verdicts after warm-up that were "drone" and the last verdict. */
    private fun run(x: FloatArray): Pair<Double, AcousticDrone.Verdict> {
        val det = AcousticDrone(sr)
        var last = AcousticDrone.Verdict(false, 0.0, null, 0.0)
        var on = 0
        var total = 0
        var i = 0
        val warm = (AcousticDrone.WINDOW_S * sr).toInt()
        while (i + det.frameSize <= x.size) {
            last = det.push(det.analyse(x, i))
            if (i >= warm) {
                total++
                if (last.drone) on++
            }
            i += det.hop
        }
        return (if (total == 0) 0.0 else on.toDouble() / total) to last
    }

    @Test
    fun frameAndBinSizes() {
        val d = AcousticDrone(16_000)
        assertEquals(2048, d.frameSize)
        assertEquals(1024, d.hop)
        assertEquals(7.8125, d.binHz, 1e-9)
        assertEquals(4096, AcousticDrone(44_100).frameSize)
    }

    @Test
    fun fftFindsASine() {
        val n = 1024
        val re = DoubleArray(n) { sin(2 * PI * 37 * it / n) }
        val im = DoubleArray(n)
        AcousticDrone.fft(re, im)
        val mags = (0 until n / 2).map { re[it] * re[it] + im[it] * im[it] }
        assertEquals(37, mags.indices.maxByOrNull { mags[it] })
    }

    @Test
    fun quadcopterIsHeardWithItsBladeFrequency() {
        val (share, last) = run(drone(8.0, f0 = 190.0, level = 0.05, noise = 0.01))
        assertTrue("share=$share", share > 0.9)
        assertTrue(last.drone)
        assertEquals(190.0, last.f0!!, 190.0 * 0.06)
    }

    @Test
    fun higherPitchedSmallDroneIsHeard() {
        val (share, last) = run(drone(8.0, f0 = 360.0, level = 0.04, noise = 0.01, seed = 5))
        assertTrue("share=$share", share > 0.9)
        assertEquals(360.0, last.f0!!, 360.0 * 0.06)
    }

    @Test
    fun faintDroneInNoiseIsStillHeard() {
        val (share, _) = run(drone(10.0, f0 = 220.0, level = 0.02, noise = 0.02, seed = 9))
        assertTrue("share=$share", share > 0.6)
    }

    @Test
    fun whiteNoiseIsQuiet() {
        val (share, _) = run(noise(8.0, 0.1))
        assertEquals(0.0, share, 0.0)
    }

    @Test
    fun whistleIsQuiet() {
        val x = sine(8.0, 1_000.0, 0.3)
        val rnd = java.util.Random(2)
        for (i in x.indices) x[i] += (0.005 * rnd.nextGaussian()).toFloat()
        assertEquals(0.0, run(x).first, 0.0)
    }

    /** Fridge or transformer: 50 Hz grid with harmonics, perfectly steady. */
    private fun mainsHum(base: Double): FloatArray {
        val rnd = java.util.Random(4)
        return FloatArray(10 * sr) { i ->
            var s = 0.0
            var h = 1
            while (base * h < 2_000) {
                s += 0.02 / h * sin(2 * PI * base * h * i / sr)
                h++
            }
            (s + 0.003 * rnd.nextGaussian()).toFloat()
        }
    }

    @Test
    fun mainsHumIsQuiet() {
        assertEquals(0.0, run(mainsHum(50.0)).first, 0.0)
        assertEquals(0.0, run(mainsHum(100.0)).first, 0.0)
    }

    @Test
    fun droneOnAFiftyHertzMultipleStillCounts() {
        val (share, _) = run(drone(8.0, f0 = 300.0, level = 0.05, noise = 0.01, seed = 8))
        assertTrue("share=$share", share > 0.9)
    }

    @Test
    fun bladePassOctaveFlipsStayOneSource() {
        // Shaft-rate ladder at 195 Hz under a stronger blade-pass ladder at 390 Hz, as in field recordings.
        val x = drone(8.0, 195.0, 0.02, 0.01, seed = 12)
        val y = drone(8.0, 390.0, 0.05, 0.0, seed = 13)
        for (i in x.indices) x[i] += y[i]
        val (share, last) = run(x)
        assertTrue("share=$share", share > 0.9)
        assertTrue(abs(last.f0!! - 195.0) < 15 || abs(last.f0!! - 390.0) < 25)
    }

    @Test
    fun speechIsQuiet() {
        val (share, _) = run(speech(10.0))
        assertTrue("share=$share", share < 0.05)
    }

    @Test
    fun alarmReleasesAfterTheDroneLeaves() {
        val x = drone(6.0, 200.0, 0.05, 0.01) + noise(6.0, 0.01)
        val det = AcousticDrone(sr)
        val verdicts = ArrayList<Boolean>()
        var i = 0
        while (i + det.frameSize <= x.size) {
            verdicts += det.push(det.analyse(x, i)).drone
            i += det.hop
        }
        val perSecond = sr.toDouble() / det.hop
        val midDrone = (4.5 * perSecond).toInt()
        val endQuiet = verdicts.size - 1
        assertTrue(verdicts[midDrone])
        assertFalse(verdicts[endQuiet])
        // Released within about one window after the hum stops.
        val released = (verdicts.indices.firstOrNull { it > 6 * perSecond && !verdicts[it] } ?: endQuiet) / perSecond
        assertTrue("released at $released s", abs(released - 6.0) < AcousticDrone.WINDOW_S + 0.5)
    }

    @Test
    fun displayRowsAreUnitRange() {
        val det = AcousticDrone(sr)
        val f = det.analyse(drone(0.2, 200.0, 0.05, 0.01))
        assertEquals(AcousticDrone.DISPLAY_ROWS, f.rows.size)
        assertTrue(f.rows.all { it in 0f..1f })
    }
}
