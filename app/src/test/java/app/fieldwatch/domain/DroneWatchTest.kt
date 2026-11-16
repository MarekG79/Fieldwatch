package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneWatchTest {
    private val pLat = 49.2000
    private val pLon = 18.8700
    private val now = 1_000_000L

    private fun row(
        key: String = "BLE:AA:BB:CC:DD:EE:01",
        name: String = "",
        seen: Long = now,
        dLat: Double? = null,
        opLat: Double? = null,
        uas: String? = null,
        alt: Double? = null,
        opAlt: Double? = null,
        fleetIds: Set<String> = emptySet(),
        facts: RadioFacts = RadioFacts(),
    ): Sighting = Sighting(
        key = key,
        kind = RadioKind.BLE,
        mac = key.substringAfter(':'),
        name = name,
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
        lastSeen = seen,
        hitCount = 1,
        fleetIds = fleetIds,
        rssiHistory = emptyList(),
        presence = emptyList(),
        facts = facts,
        payloadLat = dLat?.let { pLat + it },
        payloadLon = dLat?.let { pLon },
        payloadAt = dLat?.let { seen },
        payloadAlt = alt,
        payloadOpLat = opLat?.let { pLat + it },
        payloadOpLon = opLat?.let { pLon },
        payloadOpAt = opLat?.let { seen },
        payloadOpAlt = opAlt,
        payloadUasId = uas,
    )

    private val droneFleet = Fleet(id = "dji", name = "DJI drone Wi-Fi", kind = SignatureClass.DRONE)
    private val tagFleet = Fleet(id = "tag", name = "Finder tag", kind = SignatureClass.FINDER)

    private fun drones(vararg rows: Sighting) =
        DroneWatch.drones(rows.toList(), listOf(droneFleet, tagFleet), pLat, pLon, now)

    @Test
    fun emptyIsClear() {
        val list = drones(row(name = "Headphones"), row(key = "BLE:2", fleetIds = setOf("tag")))
        assertTrue(list.isEmpty())
        assertEquals(DroneWatch.Level.CLEAR, DroneWatch.level(list, 300, true))
    }

    @Test
    fun remoteIdDroneInsideZoneIsAlertWithNorthBearing() {
        val list = drones(row(dLat = 0.002, uas = "1581F5FKD229400AB123", alt = 480.0, opAlt = 400.0))
        val d = list.single()
        assertTrue(d.remoteId)
        assertEquals("1581F5FKD229400AB123", d.label)
        assertEquals(222.0, d.distanceM!!, 2.0)
        assertEquals(0, DroneWatch.compassIndex(d.bearingDeg!!))
        assertEquals(80.0, d.aboveOperatorM!!, 0.01)
        assertEquals(DroneWatch.Level.ALERT, DroneWatch.level(list, 300, true))
    }

    @Test
    fun farDroneIsNearbyNotAlert() {
        val list = drones(row(dLat = 0.01, uas = "X")) // ~1.1 km
        assertEquals(DroneWatch.Level.NEARBY, DroneWatch.level(list, 300, true))
    }

    @Test
    fun pilotInsideZoneFollowsPilotSwitch() {
        val list = drones(row(dLat = 0.01, opLat = 0.001, uas = "X"))
        assertEquals(DroneWatch.Level.ALERT, DroneWatch.level(list, 300, true))
        assertEquals(DroneWatch.Level.NEARBY, DroneWatch.level(list, 300, false))
    }

    @Test
    fun catalogDroneWithoutPositionIsNearby() {
        val list = drones(row(key = "WIFI:60:60:1F:00:00:01", name = "TELLO-ABC", fleetIds = setOf("dji")))
        val d = list.single()
        assertFalse(d.remoteId)
        assertEquals("DJI drone Wi-Fi", d.label)
        assertNull(d.distanceM)
        assertEquals(DroneWatch.Level.NEARBY, DroneWatch.level(list, 300, true))
    }

    @Test
    fun astmServiceDataCountsAsRemoteId() {
        val facts = RadioFacts(serviceData = listOf(ServiceDataRecord("0000FFFA-0000-1000-8000-00805F9B34FB", "0D00")))
        assertTrue(DroneWatch.hasRemoteId(row(facts = facts)))
        val other = RadioFacts(serviceData = listOf(ServiceDataRecord("1234FFFA-0000-1000-8000-00805F9B34FB", "00")))
        assertFalse(DroneWatch.hasRemoteId(row(facts = other)))
    }

    @Test
    fun oldRowsAreDropped() {
        assertTrue(drones(row(dLat = 0.001, uas = "X", seen = now - DroneWatch.MAX_AGE_MS - 1)).isEmpty())
    }

    @Test
    fun nearestFirstUnknownLast() {
        val list = drones(
            row(key = "BLE:far", dLat = 0.005, uas = "FAR"),
            row(key = "BLE:nopos", name = "x", fleetIds = setOf("dji")),
            row(key = "BLE:near", dLat = 0.001, uas = "NEAR"),
        )
        assertEquals(listOf("NEAR", "FAR", "DJI drone Wi-Fi"), list.map { it.label })
    }

    @Test
    fun noPhoneFixKeepsDroneWithoutRange() {
        val list = DroneWatch.drones(listOf(row(dLat = 0.001, uas = "X")), emptyList(), null, null, now)
        assertNull(list.single().distanceM)
        assertEquals(DroneWatch.Level.NEARBY, DroneWatch.level(list, 300, true))
    }

    @Test
    fun compassPoints() {
        assertEquals(0, DroneWatch.compassIndex(350.0))
        assertEquals(1, DroneWatch.compassIndex(45.0))
        assertEquals(2, DroneWatch.compassIndex(90.0))
        assertEquals(4, DroneWatch.compassIndex(-180.0))
        assertEquals(7, DroneWatch.compassIndex(315.0))
    }

    @Test
    fun everyDirectionHasALine() {
        (0..7).forEach { assertTrue(EnglishCopy.byKey.containsKey("simple.dir.$it")) }
    }
}
