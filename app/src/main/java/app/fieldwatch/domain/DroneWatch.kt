package app.fieldwatch.domain

/**
 * Plain-language drone picture for the simple screen. Pure logic, JVM-tested.
 *
 * A row is a drone when it carries Remote ID (position, operator or UAS ID decoded,
 * or the ASTM BLE/Wi-Fi payload) or matches a catalog signature of class DRONE.
 */
object DroneWatch {

    enum class Level { CLEAR, NEARBY, ALERT }

    data class Drone(
        val key: String,
        val label: String,
        val remoteId: Boolean,
        val distanceM: Double?,
        val bearingDeg: Double?,
        /** Height above the pilot, when both altitudes were sent. */
        val aboveOperatorM: Double?,
        val pilotDistanceM: Double?,
        val pilotBearingDeg: Double?,
        val lastSeen: Long,
    )

    /** Rows heard within [maxAgeMs] that look like drones, nearest first, unknown range last. */
    fun drones(
        rows: List<Sighting>,
        fleets: List<Fleet>,
        phoneLat: Double?,
        phoneLon: Double?,
        now: Long,
        maxAgeMs: Long = MAX_AGE_MS,
    ): List<Drone> {
        val droneFleets = fleets.filter { it.kind == SignatureClass.DRONE }.associateBy { it.id }
        val phoneOk = PayloadLocation.validCoord(phoneLat, phoneLon)
        val out = ArrayList<Drone>()
        for (row in rows) {
            if (now - row.lastSeen > maxAgeMs) continue
            val rid = hasRemoteId(row)
            val fleetName = row.fleetIds.firstNotNullOfOrNull { droneFleets[it]?.name }
            if (!rid && fleetName == null) continue
            // Position older than the window is not where the drone is now.
            val posFresh = (row.payloadAt ?: row.lastSeen).let { now - it <= maxAgeMs }
            val hasPos = posFresh && PayloadLocation.validCoord(row.payloadLat, row.payloadLon)
            val opFresh = (row.payloadOpAt ?: row.lastSeen).let { now - it <= OPERATOR_AGE_MS }
            val hasOp = opFresh && PayloadLocation.validCoord(row.payloadOpLat, row.payloadOpLon)
            val above = if (row.payloadAlt != null && row.payloadOpAlt != null) {
                row.payloadAlt - row.payloadOpAlt
            } else {
                null
            }
            out += Drone(
                key = row.key,
                label = labelOf(row, fleetName),
                remoteId = rid,
                distanceM = if (phoneOk && hasPos) Geo.meters(phoneLat!!, phoneLon!!, row.payloadLat!!, row.payloadLon!!) else null,
                bearingDeg = if (phoneOk && hasPos) Geo.bearingDegrees(phoneLat!!, phoneLon!!, row.payloadLat!!, row.payloadLon!!) else null,
                aboveOperatorM = above,
                pilotDistanceM = if (phoneOk && hasOp) Geo.meters(phoneLat!!, phoneLon!!, row.payloadOpLat!!, row.payloadOpLon!!) else null,
                pilotBearingDeg = if (phoneOk && hasOp) Geo.bearingDegrees(phoneLat!!, phoneLon!!, row.payloadOpLat!!, row.payloadOpLon!!) else null,
                lastSeen = row.lastSeen,
            )
        }
        return out.sortedWith(compareBy<Drone> { it.nearestM() ?: Double.MAX_VALUE }.thenByDescending { it.lastSeen })
    }

    /** ALERT when a drone or its pilot is inside [radiusM]; NEARBY for any other drone heard. */
    fun level(drones: List<Drone>, radiusM: Int, watchPilot: Boolean): Level {
        if (drones.isEmpty()) return Level.CLEAR
        val inside = drones.any { d ->
            (d.distanceM != null && d.distanceM <= radiusM) ||
                (watchPilot && d.pilotDistanceM != null && d.pilotDistanceM <= radiusM)
        }
        return if (inside) Level.ALERT else Level.NEARBY
    }

    fun hasRemoteId(row: Sighting): Boolean =
        PayloadLocation.validCoord(row.payloadLat, row.payloadLon) ||
            PayloadLocation.validCoord(row.payloadOpLat, row.payloadOpLon) ||
            !row.payloadUasId.isNullOrBlank() ||
            row.facts.serviceData.any { uuid16(it.uuid) == ASTM_UUID } ||
            row.facts.vendorIes.any { it.oui.equals(OpenDroneId.WIFI_OUI, ignoreCase = true) }

    /** Eight-point compass index: 0 north, 1 north-east … 7 north-west. */
    fun compassIndex(bearingDeg: Double): Int {
        val wrapped = ((bearingDeg % 360.0) + 360.0) % 360.0
        return ((wrapped + 22.5) / 45.0).toInt() % 8
    }

    private fun labelOf(row: Sighting, fleetName: String?): String =
        row.payloadUasId?.trim()?.takeIf { it.isNotEmpty() }
            ?: fleetName
            ?: row.name.trim().takeIf { it.isNotEmpty() }
            ?: row.mac

    /** 16-bit UUID from "FFFA" or "0000FFFA-0000-1000-8000-00805F9B34FB". */
    private fun uuid16(uuid: String): Int? {
        val hex = uuid.filter { it.isLetterOrDigit() }
        return when {
            hex.length == 4 -> hex.toIntOrNull(16)
            hex.length == 32 && hex.startsWith("0000", ignoreCase = true) &&
                hex.endsWith("00001000800000805F9B34FB", ignoreCase = true) -> hex.substring(4, 8).toIntOrNull(16)
            else -> null
        }
    }

    private const val ASTM_UUID = 0xFFFA

    private fun Drone.nearestM(): Double? = listOfNotNull(distanceM, pilotDistanceM).minOrNull()

    const val MAX_AGE_MS = 60_000L
    /** The pilot or takeoff spot moves little; keep it a bit longer than the aircraft. */
    const val OPERATOR_AGE_MS = 180_000L
}
