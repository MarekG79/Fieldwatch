package app.fieldwatch.domain

/**
 * Drone geofence: a Remote ID aircraft or its pilot comes within a radius of this phone.
 *
 * Pure logic with no Android calls, so it is unit-tested on the JVM.
 * Hysteresis: a target enters at [radiusM] and leaves only past radiusM * [EXIT_FACTOR],
 * so GPS jitter at the edge does not flap the alarm. One alert per entry, then a quiet
 * [REARM_MS] even if the target leaves and comes back.
 */
class DroneFence {

    enum class Target { AIRCRAFT, PILOT }

    data class Hit(
        val deviceKey: String,
        val target: Target,
        /** UAS ID when the drone sent one, else the radio name, else the key. */
        val label: String,
        val distanceM: Double,
        val radiusM: Int,
    )

    private data class State(
        var inside: Boolean = false,
        var lastAlertAt: Long? = null,
        var lastHeardAt: Long = 0L,
    )

    private val states = HashMap<String, State>()

    /**
     * Returns the new entries for this batch. [phoneLat]/[phoneLon] null means no fix:
     * nothing is raised and nothing changes state.
     */
    fun hear(
        rows: List<Sighting>,
        phoneLat: Double?,
        phoneLon: Double?,
        radiusM: Int,
        watchPilot: Boolean,
        now: Long,
    ): List<Hit> {
        forget(now)
        if (phoneLat == null || phoneLon == null || radiusM <= 0) return emptyList()
        if (!PayloadLocation.validCoord(phoneLat, phoneLon)) return emptyList()
        val out = ArrayList<Hit>(2)
        for (row in rows) {
            val label = labelOf(row)
            check(row, Target.AIRCRAFT, row.payloadLat, row.payloadLon, row.payloadAt, label,
                phoneLat, phoneLon, radiusM, now)?.let { out += it }
            if (watchPilot) {
                check(row, Target.PILOT, row.payloadOpLat, row.payloadOpLon, row.payloadOpAt, label,
                    phoneLat, phoneLon, radiusM, now)?.let { out += it }
            }
        }
        return out
    }

    /** Aircraft and pilots inside the radius now. */
    fun insideCount(): Int = states.values.count { it.inside }

    fun reset() {
        states.clear()
    }

    private fun check(
        row: Sighting,
        target: Target,
        lat: Double?,
        lon: Double?,
        at: Long?,
        label: String,
        phoneLat: Double,
        phoneLon: Double,
        radiusM: Int,
        now: Long,
    ): Hit? {
        if (!PayloadLocation.validCoord(lat, lon)) return null
        // A position older than STALE_MS is history, not a drone overhead now.
        val stamp = at ?: row.lastSeen
        if (now - stamp > STALE_MS) return null
        val d = Geo.meters(phoneLat, phoneLon, lat!!, lon!!)
        val key = "${row.key}|${target.name}"
        val st = states.getOrPut(key) { State() }
        st.lastHeardAt = now
        return if (!st.inside) {
            if (d > radiusM) return null
            st.inside = true
            st.lastAlertAt?.let { if (now - it < REARM_MS) return null }
            st.lastAlertAt = now
            Hit(row.key, target, label, d, radiusM)
        } else {
            if (d > radiusM * EXIT_FACTOR) st.inside = false
            null
        }
    }

    private fun forget(now: Long) {
        val iter = states.values.iterator()
        while (iter.hasNext()) {
            val st = iter.next()
            val rearmed = st.lastAlertAt?.let { now - it > REARM_MS } ?: true
            if (now - st.lastHeardAt > FORGET_MS && rearmed) iter.remove()
        }
    }

    companion object {
        const val EXIT_FACTOR = 1.25
        const val STALE_MS = 30_000L
        const val REARM_MS = 120_000L
        const val FORGET_MS = 300_000L
        /** Radius choices in Settings, meters. */
        val RADII = listOf(100, 200, 300, 500, 1000)

        fun labelOf(row: Sighting): String =
            row.payloadUasId?.trim()?.takeIf { it.isNotEmpty() }
                ?: row.name.trim().takeIf { it.isNotEmpty() }
                ?: row.mac
    }
}
