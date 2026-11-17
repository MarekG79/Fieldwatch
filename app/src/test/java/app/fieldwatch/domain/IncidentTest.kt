package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class IncidentTest {
    private val now = 10_000_000L

    private fun row(
        key: String,
        seen: Long,
        rssiMax: Int = -70,
        uas: String? = null,
        fleetIds: Set<String> = emptySet(),
    ) = Sighting(
        key = key,
        kind = RadioKind.BLE,
        mac = key.substringAfter(':'),
        name = "",
        rssi = rssiMax,
        rssiMin = rssiMax,
        rssiMax = rssiMax,
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
        firstSeen = seen - 1_000,
        lastSeen = seen,
        hitCount = 1,
        fleetIds = fleetIds,
        rssiHistory = emptyList(),
        presence = emptyList(),
        payloadLat = uas?.let { 49.2 },
        payloadLon = uas?.let { 18.87 },
        payloadAt = uas?.let { seen },
        payloadUasId = uas,
    )

    private val axon = Fleet(id = "fleet-axon", name = "Axon", kind = SignatureClass.LAW_ENFORCEMENT)

    @Test
    fun eventKeepsFiveMinutesAndPutsTriggerAndDronesFirst() {
        val rows = listOf(
            row("BLE:loud", now - 60_000, rssiMax = -40),
            row("BLE:old", now - Incident.EVENT_WINDOW_MS - 1),
            row("BLE:drone", now - 5_000, rssiMax = -90, uas = "1581F5FKD229400AB123"),
            row("BLE:axon", now - 10_000, rssiMax = -60, fleetIds = setOf("fleet-axon")),
        )
        val alarm = Incident.Alarm("device", "Axon", "Bezpečnosť", deviceKey = "BLE:axon")
        val s = Incident.build(Incident.Kind.EVENT, "x", rows, listOf(axon), now, alarm = alarm)
        assertEquals(5, s.windowMinutes)
        assertEquals(listOf("BLE:axon", "BLE:drone", "BLE:loud"), s.radios.map { it.key })
        assertTrue(s.radios[0].trigger)
        assertEquals(listOf("Axon"), s.radios[0].signatures)
        assertTrue(s.radios[1].remoteId)
        assertEquals("1581F5FKD229400AB123", s.radios[1].uasId)
    }

    @Test
    fun surroundingsKeepTenMinutes() {
        val rows = listOf(row("BLE:a", now - 9 * 60_000), row("BLE:b", now - 11 * 60_000))
        val s = Incident.build(Incident.Kind.SURROUNDINGS, "Okolie", rows, emptyList(), now)
        assertEquals(10, s.windowMinutes)
        assertEquals(listOf("BLE:a"), s.radios.map { it.key })
        assertNull(s.alarm)
    }

    @Test
    fun jsonRoundTrip() {
        val s = Incident.build(
            Incident.Kind.EVENT, "Udalosť", listOf(row("BLE:d", now, uas = "X")), emptyList(), now,
            phoneLat = 49.2, phoneLon = 18.87,
            alarm = Incident.Alarm("sound", "Zvuk dronu", "190 Hz", toneHz = 190.0),
            audioFile = "clip.wav",
        )
        val back = Incident.fromJson(Incident.toJson(s))
        assertEquals(s, back)
        assertTrue(Incident.toJson(s).contains("\"format\": \"antidron-incident\""))
    }

    @Test
    fun badPhoneFixIsDropped() {
        val s = Incident.build(Incident.Kind.EVENT, "x", emptyList(), emptyList(), now, phoneLat = 0.0, phoneLon = 0.0)
        assertNull(s.phoneLat)
    }

    @Test
    fun namesAreSafeAndCapped() {
        assertEquals("a b c", Incident.cleanName(" a/b:c "))
        assertEquals("Antidron", Incident.cleanName("  "))
        assertEquals(Incident.NAME_MAX, Incident.cleanName("x".repeat(200)).length)
        val n = Incident.defaultName("Udalosť", 0L, "Dron do 300 m", Locale.US)
        assertTrue(n.startsWith("Udalosť "))
        assertTrue(n.endsWith("– Dron do 300 m"))
        assertFalse(Incident.fileStem(Incident.build(Incident.Kind.EVENT, "a?b", emptyList(), emptyList(), now)).contains("?"))
    }
}
