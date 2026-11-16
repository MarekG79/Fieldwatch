package app.fieldwatch.domain

/**
 * Remote ID over Wi-Fi NAN (Wi-Fi Aware), ASTM F3411 / ASD-STAN prEN 4709-002.
 *
 * The drone publishes the NAN service "org.opendroneid.remoteid". Its service-specific
 * info is the same bytes a Wi-Fi Beacon carries after the FA:0B:BC type 0x0D header:
 * a counter byte, then one message or a message pack. So a NAN frame is wrapped as that
 * vendor element and goes through the same [OpenDroneId] decoder and catalog match.
 *
 * NAN hides the sender's MAC; the peer handle is all there is. A stable locally
 * administered address is made from it so one drone stays one row for the session.
 */
object NanRemoteId {
    const val SERVICE_NAME = "org.opendroneid.remoteid"
    const val NAME = "Wi-Fi NAN Remote ID"

    /** NAN discovery runs on channel 6. */
    const val CHANNEL = 6
    const val FREQUENCY_MHZ = 2437

    /** NAN discovery does not report signal strength; a neutral middle value. */
    const val RSSI_UNKNOWN = -80

    private const val MESSAGE_SIZE = 25

    /** Counter + at least one 25-byte message whose type is a known ASTM type or a pack. */
    fun looksLikeRemoteId(payload: ByteArray?): Boolean {
        if (payload == null || payload.size < 1 + MESSAGE_SIZE) return false
        val type = (payload[1].toInt() shr 4) and 0x0F
        return type in 0..5 || type == 0x0F
    }

    /** 02:4E:41:xx:xx:xx — local bit set, "NA" for NAN, three bytes from the peer id. */
    fun macFor(peerId: Int): String =
        "02:4E:41:%02X:%02X:%02X".format(
            (peerId shr 16) and 0xFF,
            (peerId shr 8) and 0xFF,
            peerId and 0xFF,
        )

    fun observation(payload: ByteArray, peerId: Int, now: Long): Observation? {
        if (!looksLikeRemoteId(payload)) return null
        val ie = VendorIeRecord(OpenDroneId.WIFI_OUI, OpenDroneId.WIFI_TYPE, payload.toHex())
        return Observation(
            kind = RadioKind.WIFI,
            mac = macFor(peerId),
            name = NAME,
            rssi = RSSI_UNKNOWN,
            channel = CHANNEL,
            frequencyMhz = FREQUENCY_MHZ,
            hiddenSsid = false,
            serviceUuids = emptyList(),
            manufacturerId = null,
            manufacturerDataHex = "",
            rawHex = "",
            extras = "Wi-Fi NAN",
            at = now,
            fresh = true,
            vendorIeOuis = listOf(OpenDroneId.WIFI_OUI),
            facts = RadioFacts(vendorIes = listOf(ie)),
        )
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02X".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
