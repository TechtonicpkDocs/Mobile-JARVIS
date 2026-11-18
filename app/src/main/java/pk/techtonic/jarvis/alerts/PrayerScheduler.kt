package pk.techtonic.jarvis.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import pk.techtonic.jarvis.data.JarvisPrefs
import pk.techtonic.jarvis.net.BriefingClient
import java.util.Calendar

/**
 * Prayer-time alarms (W4). Fetches today's times from BriefingClient
 * (W3) and sets one exact alarm per prayer the user enabled in Settings
 * (default ALL OFF). Times already past today are skipped.
 *
 * Called from MainActivity.onCreate, from BootReceiver, and whenever a
 * prayer toggle changes. A failed fetch keeps whatever is already
 * scheduled (the old alarms are only replaced after a successful fetch).
 * Request codes are 9000 + the prayer's fixed index (Fajr=0 … Isha=5),
 * so they never collide with reminder request codes (note ids).
 */
object PrayerScheduler {

    const val REQUEST_BASE = 9000
    const val ACTION_PRAYER_ALARM = "pk.techtonic.jarvis.PRAYER_ALARM"
    const val EXTRA_PRAYER_API = "prayer_api"
    const val EXTRA_PRAYER_LABEL_KEY = "prayer_label_key"
    const val EXTRA_PRAYER_TIME = "prayer_time"
    const val EXTRA_NOTIF_ID = "notif_id"

    /** Fixed order — matches BriefingClient and the Settings toggles. */
    val PRAYER_ORDER = arrayOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")

    fun rescheduleToday(context: Context) {
        val appContext = context.applicationContext
        BriefingClient().fetchPrayers(
            onResult = { prayers ->
                // Replace the whole day's set only after a good fetch.
                cancelAll(appContext)
                val prefs = JarvisPrefs(appContext)
                val now = System.currentTimeMillis()
                for (prayer in prayers.list) {
                    val index = PRAYER_ORDER.indexOf(prayer.apiName)
                    if (index < 0) continue
                    if (!prefs.prayerAlert(prayer.apiName)) continue
                    val trigger = todayMillis(prayer.time) ?: continue
                    if (trigger <= now) continue
                    scheduleOne(
                        appContext,
                        REQUEST_BASE + index,
                        prayer.apiName,
                        prayer.labelKey,
                        prayer.time,
                        trigger
                    )
                }
            },
            onError = {
                // Fetch failed (offline / server down): keep the alarms
                // already scheduled; the next app start retries.
            }
        )
    }

    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in PRAYER_ORDER.indices) {
            val pi = pendingIntent(appContext, REQUEST_BASE + i, null, null, null, noCreate = true)
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
    }

    /** False on API 31+ when the user revoked exact-alarm permission. */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun scheduleOne(
        context: Context,
        requestCode: Int,
        apiName: String,
        labelKey: String,
        time: String,
        triggerAtMillis: Long
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, requestCode, apiName, labelKey, time, noCreate = false)
            ?: return
        if (canScheduleExact(context)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } catch (e: SecurityException) {
                // Exact permission revoked between the check and the call.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun pendingIntent(
        context: Context,
        requestCode: Int,
        apiName: String?,
        labelKey: String?,
        time: String?,
        noCreate: Boolean
    ): PendingIntent? {
        val intent = Intent(context, PrayerReceiver::class.java).apply {
            action = ACTION_PRAYER_ALARM
            if (apiName != null) putExtra(EXTRA_PRAYER_API, apiName)
            if (labelKey != null) putExtra(EXTRA_PRAYER_LABEL_KEY, labelKey)
            if (time != null) putExtra(EXTRA_PRAYER_TIME, time)
            putExtra(EXTRA_NOTIF_ID, requestCode)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (noCreate) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /** "HH:MM" today as epoch millis, or null when unparseable. */
    private fun todayMillis(time: String): Long? {
        val clean = time.trim().substringBefore(" ")
        val parts = clean.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
