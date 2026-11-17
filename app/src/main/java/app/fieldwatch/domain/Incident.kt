package app.fieldwatch.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Antidron: a saved moment. An EVENT is saved from an alarm (all radios heard in the
 * last [EVENT_WINDOW_MS]); SURROUNDINGS is saved from Filters (radios in the current
 * filter heard in the last [SURROUNDINGS_WINDOW_MS]). Pure logic, JVM-tested.
 */
object Incident {
    const val FORMAT = "antidron-incident"
    const val VERSION = 1
    const val EVENT_WINDOW_MS = 5 * 60_000L
    const val SURROUNDINGS_WINDOW_MS = 10 * 60_000L
    const val NAME_MAX = 60

    enum class Kind { EVENT, SURROUNDINGS }

    /** What raised the alarm, when the save came from one. */
    @Serializable
    data class Alarm(
        /** aircraft, pilot, sound or device. */
        val type: String,
        val title: String,
        val body: String,
        val deviceKey: String? = null,
        val toneHz: Double? = null,
    )

    @Serializable
    data class Radio(
        val key: String,
        val kind: String,
        val mac: String,
        val name: String,
        val vendor: String? = null,
        val signatures: List<String> = emptyList(),
        val classes: List<String> = emptyList(),
        val rssi: Int,
        val rssiMax: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val remoteId: Boolean = false,
        val uasId: String? = null,
        val droneLat: Double? = null,
        val droneLon: Double? = null,
        val droneAlt: Double? = null,
        val pilotLat: Double? = null,
        val pilotLon: Double? = null,
        /** True for the radio that raised the alarm. */
        val trigger: Boolean = false,
    )

    @Serializable
    data class Snapshot(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val kind: String,
        val name: String,
        val savedAt: Long,
        val windowMinutes: Int,
        val phoneLat: Double? = null,
        val phoneLon: Double? = null,
        val alarm: Alarm? = null,
        /** Name of the sound clip saved next to this file, if any. */
        val audioFile: String? = null,
        val radios: List<Radio>,
    )

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Radios heard in the window, the trigger first, then Remote ID drones, then by the
     * strongest signal. [rows] is everything for an event, the filtered list for surroundings.
     */
    fun build(
        kind: Kind,
        name: String,
        rows: List<Sighting>,
        fleets: List<Fleet>,
        now: Long,
        phoneLat: Double? = null,
        phoneLon: Double? = null,
        alarm: Alarm? = null,
        audioFile: String? = null,
    ): Snapshot {
        val window = if (kind == Kind.EVENT) EVENT_WINDOW_MS else SURROUNDINGS_WINDOW_MS
        val byId = fleets.associateBy { it.id }
        val radios = rows
            .filter { now - it.lastSeen <= window }
            .map { row ->
                val matched = row.fleetIds.mapNotNull { byId[it] }
                Radio(
                    key = row.key,
                    kind = row.kind.name,
                    mac = row.mac,
                    name = row.name,
                    vendor = row.vendor,
                    signatures = matched.map { it.name }.distinct(),
                    classes = matched.map { it.kind.name }.distinct(),
                    rssi = row.rssi,
                    rssiMax = row.rssiMax,
                    firstSeen = row.firstSeen,
                    lastSeen = row.lastSeen,
                    remoteId = DroneWatch.hasRemoteId(row),
                    uasId = row.payloadUasId?.takeIf { it.isNotBlank() },
                    droneLat = row.payloadLat,
                    droneLon = row.payloadLon,
                    droneAlt = row.payloadAlt,
                    pilotLat = row.payloadOpLat,
                    pilotLon = row.payloadOpLon,
                    trigger = alarm?.deviceKey != null && alarm.deviceKey == row.key,
                )
            }
            .sortedWith(
                compareByDescending<Radio> { it.trigger }
                    .thenByDescending { it.remoteId }
                    .thenByDescending { it.rssiMax },
            )
        return Snapshot(
            kind = kind.name,
            name = cleanName(name),
            savedAt = now,
            windowMinutes = (window / 60_000L).toInt(),
            phoneLat = phoneLat?.takeIf { PayloadLocation.validCoord(phoneLat, phoneLon) },
            phoneLon = phoneLon?.takeIf { PayloadLocation.validCoord(phoneLat, phoneLon) },
            alarm = alarm,
            audioFile = audioFile,
            radios = radios,
        )
    }

    fun toJson(s: Snapshot): String = json.encodeToString(Snapshot.serializer(), s)

    fun fromJson(text: String): Snapshot = json.decodeFromString(Snapshot.serializer(), text)

    /** "Udalosť 17. 11. 09:47 – Dron do 300 m" style default name. */
    fun defaultName(prefix: String, now: Long, detail: String?, locale: Locale): String {
        val stamp = SimpleDateFormat("d. M. HH:mm", locale).format(Date(now))
        val base = "$prefix $stamp"
        return cleanName(if (detail.isNullOrBlank()) base else "$base – $detail")
    }

    /** Safe for a file name and the 60-character cap; never empty. */
    fun cleanName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(NAME_MAX)
            .trim()
        return cleaned.ifEmpty { "Antidron" }
    }

    /** File stem: name plus a sortable time so two saves never clash. */
    fun fileStem(s: Snapshot): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(s.savedAt))
        return "$stamp ${cleanName(s.name)}"
    }
}
