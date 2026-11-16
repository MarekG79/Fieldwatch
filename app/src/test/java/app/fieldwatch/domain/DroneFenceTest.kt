package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneFenceTest {
    // Phone near Varín. 0.001° latitude ≈ 111 m.
    private val pLat = 49.2000
    private val pLon = 18.8700

    private fun drone(
        dLat: Double?,
        at: Long,
        opLat: Double? = null,
        uas: String? = "1581F5FKD229400AB123",
    ): Sighting = Sighting(
        key = "BLE:AA:BB:CC:DD:EE:01",
        kind = RadioKind.BLE,
        mac = "AA:BB:CC:DD:EE:01",
        name = "",
        rssi = -60,
        rssiMin = -60,
        rssiMax = -60,
        channel = 0,
        frequencyMhz = 0,
        vendor = null,
        randomized = false,
        hiddenSsid = false,
        serviceUuids = emptyList(),
        manufacturerId = null,
        manufacturerDataHex = "",
        rawHex = "",
        extras = "",
        firstSeen = 1L,
        lastSeen = at,
        hitCount = 1,
        fleetIds = emptySet(),
        rssiHistory = emptyList(),
        presence = emptyList(),
        payloadLat = dLat?.let { pLat + it },
        payloadLon = dLat?.let { pLon },
        payloadAt = at,
        payloadOpLat = opLat?.let { pLat + it },
        payloadOpLon = opLat?.let { pLon },
        payloadOpAt = opLat?.let { at },
        payloadUasId = uas,
    )

    private fun hear(f: DroneFence, row: Sighting, now: Long, pilot: Boolean = true) =
        f.hear(listOf(row), pLat, pLon, radiusM = 300, watchPilot = pilot, now = now)

    @Test
    fun outsideRadiusIsQuiet() {
        val f = DroneFence()
        assertTrue(hear(f, drone(0.005, 1_000L), 1_000L).isEmpty()) // ~555 m
    }

    @Test
    fun enteringRaisesOnceWithLabelAndDistance() {
        val f = DroneFence()
        val hits = hear(f, drone(0.002, 1_000L), 1_000L) // ~222 m
        assertEquals(1, hits.size)
        assertEquals(DroneFence.Target.AIRCRAFT, hits[0].target)
        assertEquals("1581F5FKD229400AB123", hits[0].label)
        assertEquals(222.0, hits[0].distanceM, 2.0)
        // Still inside: no repeat.
        assertTrue(hear(f, drone(0.001, 2_000L), 2_000L).isEmpty())
    }

    @Test
    fun edgeJitterDoesNotFlap() {
        val f = DroneFence()
        assertEquals(1, hear(f, drone(0.0026, 1_000L), 1_000L).size) // ~289 m in
        assertTrue(hear(f, drone(0.0030, 2_000L), 2_000L).isEmpty()) // ~333 m, inside exit band
        assertTrue(hear(f, drone(0.0026, 3_000L), 3_000L).isEmpty())
    }

    @Test
    fun reentryWaitsForRearm() {
        val f = DroneFence()
        assertEquals(1, hear(f, drone(0.002, 0L), 0L).size)
        assertTrue(hear(f, drone(0.006, 10_000L), 10_000L).isEmpty()) // left
        assertTrue(hear(f, drone(0.002, 20_000L), 20_000L).isEmpty()) // back too soon
        assertTrue(hear(f, drone(0.006, 30_000L), 30_000L).isEmpty()) // left again
        val later = DroneFence.REARM_MS + 40_000L
        assertEquals(1, hear(f, drone(0.002, later), later).size)
    }

    @Test
    fun stalePositionIsIgnored() {
        val f = DroneFence()
        val now = 100_000L
        assertTrue(hear(f, drone(0.001, now - DroneFence.STALE_MS - 1), now).isEmpty())
    }

    @Test
    fun pilotAlarmFollowsSwitch() {
        val near = drone(0.009, 1_000L, opLat = 0.001) // aircraft ~1 km, pilot ~111 m
        assertTrue(hear(DroneFence(), near, 1_000L, pilot = false).isEmpty())
        val hits = hear(DroneFence(), near, 1_000L, pilot = true)
        assertEquals(1, hits.size)
        assertEquals(DroneFence.Target.PILOT, hits[0].target)
    }

    @Test
    fun noPhoneFixRaisesNothing() {
        val f = DroneFence()
        val hits = f.hear(listOf(drone(0.0, 1_000L)), null, null, 300, true, 1_000L)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun labelFallsBackToMac() {
        val f = DroneFence()
        val hits = hear(f, drone(0.001, 1_000L, uas = null), 1_000L)
        assertEquals("AA:BB:CC:DD:EE:01", hits.single().label)
    }

    @Test
    fun englishAndSlovakLinesExist() {
        listOf(
            "settings.topic.dronefence",
            "dronefence.alert.aircraft",
            "dronefence.alert.pilot",
            "dronefence.alert.body",
        ).forEach { assertTrue(it, EnglishCopy.byKey.containsKey(it)) }
        assertEquals(setOf("id", "d", "m"), EnglishCopy.placeholders["dronefence.alert.body"])
    }
}
