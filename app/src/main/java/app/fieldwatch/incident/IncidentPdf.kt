package app.fieldwatch.incident

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.fieldwatch.domain.Geo
import app.fieldwatch.domain.Incident
import app.fieldwatch.domain.LanguageText
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Antidron: one plain A4 report of a saved event or surroundings. */
internal object IncidentPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 36f

    fun write(s: Incident.Snapshot, out: OutputStream) {
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 16f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val head = Paint().apply { textSize = 10f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val body = Paint().apply { textSize = 9f; isAntiAlias = true }
        val small = Paint().apply { textSize = 8f; isAntiAlias = true; color = 0xFF555555.toInt() }
        val rule = Paint().apply { strokeWidth = 0.5f; color = 0xFF999999.toInt() }
        val time = SimpleDateFormat("d. M. yyyy HH:mm:ss", LanguageText.dateLocale())
        val clock = SimpleDateFormat("HH:mm:ss", LanguageText.dateLocale())

        var pageNo = 0
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
        var c = page.canvas
        var y = M + 10f

        fun newPage() {
            doc.finishPage(page)
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
            c = page.canvas
            y = M + 10f
        }
        fun line(text: String, p: Paint, gap: Float = 13f) {
            if (y > H - M) newPage()
            c.drawText(text, M, y, p)
            y += gap
        }
        fun wrap(text: String, p: Paint, gap: Float = 12f) {
            val max = W - 2 * M
            var rest = text
            while (rest.isNotEmpty()) {
                var n = p.breakText(rest, true, max, null)
                if (n < rest.length) {
                    val sp = rest.lastIndexOf(' ', n)
                    if (sp > 0) n = sp
                }
                line(rest.substring(0, n).trim(), p, gap)
                rest = rest.substring(n).trimStart()
            }
        }

        val kind = LanguageText.text(if (s.kind == Incident.Kind.EVENT.name) "incident.event" else "incident.surroundings")
        line(s.name, title, 20f)
        line("$kind · ${time.format(Date(s.savedAt))}", body)
        line(LanguageText.text("incident.window", mapOf("n" to s.windowMinutes.toString(), "count" to s.radios.size.toString())), body)
        if (s.phoneLat != null && s.phoneLon != null) {
            line(LanguageText.text("incident.phone", mapOf("pos" to "%.6f, %.6f".format(s.phoneLat, s.phoneLon))), body)
        }
        s.alarm?.let { a ->
            y += 4f
            line(LanguageText.text("incident.alarm"), head)
            wrap(a.title, body)
            if (a.body.isNotBlank()) wrap(a.body, body)
            a.toneHz?.let { line(LanguageText.text("incident.tone", mapOf("hz" to it.roundToInt().toString())), body) }
        }
        s.audioFile?.let { line(LanguageText.text("incident.audio", mapOf("name" to it)), body) }
        y += 6f
        c.drawLine(M, y - 8f, W - M, y - 8f, rule)
        line(LanguageText.text("incident.radios"), head, 15f)

        s.radios.forEachIndexed { i, r ->
            if (y > H - M - 40f) newPage()
            val label = listOfNotNull(
                r.uasId,
                r.signatures.takeIf { it.isNotEmpty() }?.joinToString(", "),
                r.name.takeIf { it.isNotBlank() },
                r.vendor,
            ).firstOrNull() ?: "—"
            val flags = buildString {
                if (r.trigger) append("★ ")
                if (r.remoteId) append("Remote ID · ")
            }
            line("${i + 1}. $flags$label", head, 11f)
            line(
                "${r.kind} ${r.mac} · ${r.rssi} dBm (max ${r.rssiMax}) · " +
                    "${clock.format(Date(r.firstSeen))}–${clock.format(Date(r.lastSeen))}",
                small,
                11f,
            )
            if (r.droneLat != null && r.droneLon != null) {
                val dist = if (s.phoneLat != null && s.phoneLon != null) {
                    " · " + Geo.distanceText(Geo.meters(s.phoneLat, s.phoneLon, r.droneLat, r.droneLon))
                } else {
                    ""
                }
                val alt = r.droneAlt?.let { " · ${it.roundToInt()} m" }.orEmpty()
                line(LanguageText.text("incident.drone.at", mapOf("pos" to "%.6f, %.6f".format(r.droneLat, r.droneLon) + alt + dist)), small, 11f)
            }
            if (r.pilotLat != null && r.pilotLon != null) {
                line(LanguageText.text("incident.pilot.at", mapOf("pos" to "%.6f, %.6f".format(r.pilotLat, r.pilotLon))), small, 11f)
            }
            if (r.classes.isNotEmpty()) line(r.classes.joinToString(", "), small, 11f)
            y += 3f
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}
