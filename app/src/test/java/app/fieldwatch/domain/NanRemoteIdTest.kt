package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NanRemoteIdTest {
    // Same DJI message pack as OpenDroneIdTest: counter D9, then pack type F.
    private val packHex = "D9F2190302123135383146335954444A3144303033315A353330000000" +
        "1220820A00864228110CFF80CF0000F508B2083A022E310A00" +
        "420176E42711B5FF81CF010000000000000005088B02900E00"

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun nanPayloadDecodesThroughTheWifiPath() {
        val obs = NanRemoteId.observation(bytes(packHex), peerId = 0x0012AB34, now = 1_000L)!!
        assertEquals(RadioKind.WIFI, obs.kind)
        assertEquals("02:4E:41:12:AB:34", obs.mac)
        assertEquals(listOf("FA:0B:BC"), obs.vendorIeOuis)
        val loc = OpenDroneId.fromFacts(obs.facts)
        assertEquals("1581F3YTDJ1D0031Z530", loc.uasId)
        assertEquals(28.7851142, loc.lat!!, 1e-6)
        assertEquals(-81.3629684, loc.lon!!, 1e-6)
        assertEquals(28.7827062, loc.opLat!!, 1e-6)
    }

    @Test
    fun singleLocationMessageIsAccepted() {
        val single = ByteArray(26)
        single[0] = 0x01
        single[1] = 0x12 // type 1 (Location), version 2
        assertTrue(NanRemoteId.looksLikeRemoteId(single))
    }

    @Test
    fun junkIsRejected() {
        assertNull(NanRemoteId.observation(ByteArray(10), 1, 0L))
        val wrongType = ByteArray(26)
        wrongType[1] = 0x72 // type 7 is not ASTM
        assertFalse(NanRemoteId.looksLikeRemoteId(wrongType))
        assertFalse(NanRemoteId.looksLikeRemoteId(null))
    }

    @Test
    fun peersGetDistinctLocalAddresses() {
        val a = NanRemoteId.macFor(1)
        val b = NanRemoteId.macFor(2)
        assertNotEquals(a, b)
        // Locally administered unicast: second hex digit of the first byte is 2.
        assertTrue(a.startsWith("02:"))
    }
}
