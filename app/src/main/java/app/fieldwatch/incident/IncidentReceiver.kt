package app.fieldwatch.incident

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.domain.Incident
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Antidron: the "Save event" button inside an alarm notification. Works from the lock
 * screen and with the app in the background; posts a short "saved" notice when done.
 */
class IncidentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SAVE) return
        val app = context.applicationContext as? FieldwatchApp ?: return
        val alarm = alarmFrom(intent)
        val pending = goAsync()
        scope.launch {
            try {
                val saved = IncidentSaver.saveEvent(app, alarm)
                runCatching { app.alerter.incidentSavedNote(saved?.name) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SAVE = "app.fieldwatch.ANTIDRON_SAVE_EVENT"
        private const val EXTRA_TYPE = "antidron.incident.type"
        private const val EXTRA_TITLE = "antidron.incident.title"
        private const val EXTRA_BODY = "antidron.incident.body"
        private const val EXTRA_KEY = "antidron.incident.key"
        private const val EXTRA_TONE = "antidron.incident.tone"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun putAlarm(intent: Intent, a: Incident.Alarm): Intent = intent
            .putExtra(EXTRA_TYPE, a.type)
            .putExtra(EXTRA_TITLE, a.title)
            .putExtra(EXTRA_BODY, a.body)
            .putExtra(EXTRA_KEY, a.deviceKey)
            .putExtra(EXTRA_TONE, a.toneHz ?: Double.NaN)

        fun alarmFrom(intent: Intent): Incident.Alarm? {
            val type = intent.getStringExtra(EXTRA_TYPE) ?: return null
            val tone = intent.getDoubleExtra(EXTRA_TONE, Double.NaN)
            return Incident.Alarm(
                type = type,
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
                deviceKey = intent.getStringExtra(EXTRA_KEY),
                toneHz = tone.takeUnless { it.isNaN() },
            )
        }

        /** PendingIntent for a notification action. [code] keeps different alarms apart. */
        fun pending(context: Context, a: Incident.Alarm, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                code,
                putAlarm(Intent(context, IncidentReceiver::class.java).setAction(ACTION_SAVE), a),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
