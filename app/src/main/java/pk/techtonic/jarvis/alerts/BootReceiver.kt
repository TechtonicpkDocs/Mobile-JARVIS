package pk.techtonic.jarvis.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-schedules all alerts after a reboot (W4) — AlarmManager alarms do
 * not survive one. Each half is guarded separately so a failure in one
 * never blocks the other, and a boot receiver never crashes the boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        try {
            ReminderScheduler.rescheduleAll(appContext)
        } catch (e: Exception) {
            // Swallowed by design: reminders must not block prayer alarms.
        }
        try {
            PrayerScheduler.rescheduleToday(appContext)
        } catch (e: Exception) {
            // Swallowed by design: a failed fetch retries on next app start.
        }
    }
}
