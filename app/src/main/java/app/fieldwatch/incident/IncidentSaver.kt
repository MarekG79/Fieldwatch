package app.fieldwatch.incident

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.domain.Incident
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.domain.Sighting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Antidron: writes a saved event or surroundings to Downloads/Antidron/Udalosti as a PDF
 * for people and a JSON file for tools. Nothing leaves the phone.
 */
object IncidentSaver {
    private const val TAG = "AntidronIncident"
    const val FOLDER = "Antidron/Udalosti"

    data class Saved(val name: String, val pdf: Uri?, val json: Uri?, val radios: Int)

    /** From an alarm: every radio heard in the last 5 minutes. A sound alarm also keeps the last 10 s. */
    suspend fun saveEvent(app: FieldwatchApp, alarm: Incident.Alarm?): Saved? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val audio = if (alarm?.type == TYPE_SOUND) runCatching { app.acoustic.saveClip() }.getOrNull() else null
        val name = Incident.defaultName(LanguageText.text("incident.event"), now, alarm?.title, LanguageText.dateLocale())
        val fix = runCatching { app.currentFix(now) }.getOrNull()
        val snap = Incident.build(
            kind = Incident.Kind.EVENT,
            name = name,
            rows = app.devices.devices.value,
            fleets = app.config.fleets,
            now = now,
            phoneLat = fix?.first,
            phoneLon = fix?.second,
            alarm = alarm,
            audioFile = audio,
        )
        write(app, snap)
    }

    /** From Filters: the radios in the current filter heard in the last 10 minutes. */
    suspend fun saveSurroundings(app: FieldwatchApp, name: String, filtered: List<Sighting>): Saved? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val fix = runCatching { app.currentFix(now) }.getOrNull()
            val snap = Incident.build(
                kind = Incident.Kind.SURROUNDINGS,
                name = name,
                rows = filtered,
                fleets = app.config.fleets,
                now = now,
                phoneLat = fix?.first,
                phoneLon = fix?.second,
            )
            write(app, snap)
        }

    fun defaultSurroundingsName(): String =
        Incident.defaultName(LanguageText.text("incident.surroundings"), System.currentTimeMillis(), null, LanguageText.dateLocale())

    /** Gives both files a new name. Only files this app wrote can be renamed this way. */
    suspend fun rename(app: FieldwatchApp, saved: Saved, newName: String): Saved = withContext(Dispatchers.IO) {
        val clean = Incident.cleanName(newName)
        fun move(uri: Uri?, ext: String) {
            if (uri == null) return
            runCatching {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "$clean.$ext") }
                app.contentResolver.update(uri, values, null, null)
            }.onFailure { Log.w(TAG, "rename failed", it) }
        }
        move(saved.pdf, "pdf")
        move(saved.json, "json")
        saved.copy(name = clean)
    }

    private fun write(app: FieldwatchApp, s: Incident.Snapshot): Saved? {
        val stem = Incident.fileStem(s)
        val json = insert(app, "$stem.json", "application/json") { out ->
            out.write(Incident.toJson(s).toByteArray(Charsets.UTF_8))
        }
        val pdf = insert(app, "$stem.pdf", "application/pdf") { out -> IncidentPdf.write(s, out) }
        if (json == null && pdf == null) return null
        return Saved(s.name, pdf, json, s.radios.size)
    }

    private fun insert(app: FieldwatchApp, name: String, mime: String, body: (java.io.OutputStream) -> Unit): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
        }
        val uri = runCatching { app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
            .getOrNull() ?: return null
        val ok = runCatching { app.contentResolver.openOutputStream(uri)?.use(body) != null }
            .onFailure { Log.w(TAG, "write $name failed", it) }
            .getOrDefault(false)
        if (!ok) {
            runCatching { app.contentResolver.delete(uri, null, null) }
            return null
        }
        return uri
    }

    const val TYPE_AIRCRAFT = "aircraft"
    const val TYPE_PILOT = "pilot"
    const val TYPE_SOUND = "sound"
    const val TYPE_DEVICE = "device"
}
