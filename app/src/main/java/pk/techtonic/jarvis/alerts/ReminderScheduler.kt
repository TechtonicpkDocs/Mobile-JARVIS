package pk.techtonic.jarvis.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import pk.techtonic.jarvis.data.JarvisDb
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reminder alarms for notes (W3). One exact alarm per note, request code =
 * the note id. The alarm fires once; nothing re-fires.
 *
 * [schedule] returns false when the date/time cannot be parsed or is
 * already in the past (the caller tells the user plainly).
 */
object ReminderScheduler {

    const val ACTION_REMINDER = "pk.techtonic.jarvis.action.REMINDER"
    const val EXTRA_NOTE_ID = "note_id"
    const val EXTRA_TEXT = "text"

    /**
     * Schedule a one-shot exact alarm for [date] ("YYYY-MM-DD") +
     * [time] ("HH:MM"). Returns true when the alarm was set.
     */
    fun schedule(context: Context, noteId: Long, date: String, time: String): Boolean {
        val triggerAt = parseDateTime(date, time) ?: return false
        if (triggerAt <= System.currentTimeMillis()) return false
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = alarmIntent(context, noteId, noteText(context, noteId))
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAt, pending
            )
        } catch (e: SecurityException) {
            // Exact alarms not allowed on this device/ROM: fall back to an
            // inexact alarm rather than failing the reminder outright.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAt, pending
            )
        }
        return true
    }

    /** Cancel the alarm for one note (also used when the note is deleted). */
    fun cancel(context: Context, noteId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmIntent(context, noteId, ""))
    }

    /**
     * Re-schedule every note whose date+time is still in the future.
     * Called from BootReceiver (alarms do not survive a reboot).
     */
    fun rescheduleAll(context: Context) {
        val db = JarvisDb(context)
        try {
            val now = System.currentTimeMillis()
            for (note in db.allNotes()) {
                if (note.date.isBlank() || note.time.isBlank()) continue
                val triggerAt = parseDateTime(note.date, note.time) ?: continue
                if (triggerAt <= now) continue
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pending = alarmIntent(context, note.id, note.text)
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pending
                    )
                } catch (e: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pending
                    )
                }
            }
        } catch (ignored: Exception) {
            // A corrupt DB row must never break the boot flow.
        } finally {
            try {
                db.close()
            } catch (ignored: Exception) {
                // Already closed.
            }
        }
    }

    /** "YYYY-MM-DD" + "HH:MM" -> epoch millis, or null when unparseable. */
    fun parseDateTime(date: String, time: String): Long? {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            format.isLenient = false
            format.parse("$date $time")?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun noteText(context: Context, noteId: Long): String {
        val db = JarvisDb(context)
        try {
            return db.allNotes().firstOrNull { it.id == noteId }?.text ?: ""
        } catch (ignored: Exception) {
            return ""
        } finally {
            try {
                db.close()
            } catch (ignored: Exception) {
                // Already closed.
            }
        }
    }

    /** The alarm PendingIntent. Extras are ignored for identity, so the
        same builder serves schedule() and cancel(). */
    private fun alarmIntent(context: Context, noteId: Long, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_TEXT, text)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, noteId.toInt(), intent, flags)
    }
}
