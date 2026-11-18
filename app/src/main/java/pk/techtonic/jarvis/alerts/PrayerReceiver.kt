package pk.techtonic.jarvis.alerts

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import pk.techtonic.jarvis.JarvisApp
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.ui.LockActivity

/**
 * Shows the prayer-time notification (W4) on CHANNEL_PRAYERS, with a
 * Dismiss action that cancels just this notification. Tapping the
 * notification opens the app through LockActivity (the launcher entry,
 * which forwards to MainActivity when unlocked).
 */
class PrayerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (intent.action == ACTION_DISMISS) {
            nm.cancel(intent.getIntExtra(PrayerScheduler.EXTRA_NOTIF_ID, 0))
            return
        }
        if (intent.action != PrayerScheduler.ACTION_PRAYER_ALARM) return

        val apiName = intent.getStringExtra(PrayerScheduler.EXTRA_PRAYER_API) ?: return
        val labelKey = intent.getStringExtra(PrayerScheduler.EXTRA_PRAYER_LABEL_KEY)
        val time = intent.getStringExtra(PrayerScheduler.EXTRA_PRAYER_TIME) ?: ""
        val notifId = intent.getIntExtra(
            PrayerScheduler.EXTRA_NOTIF_ID, PrayerScheduler.REQUEST_BASE
        )

        val prayerName = prayerName(context, apiName, labelKey)
        val title = context.getString(R.string.prayer_alert_now, prayerName)

        val openIntent = Intent(context, LockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openPi = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, PrayerReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(PrayerScheduler.EXTRA_NOTIF_ID, notifId)
        }
        val dismissPi = PendingIntent.getBroadcast(
            context, notifId, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, JarvisApp.CHANNEL_PRAYERS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(time)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.prayer_alert_dismiss),
                    dismissPi
                ).build()
            )
            .build()
        nm.notify(notifId, notification)
    }

    /** Prayer display name: fixed map first, label-key lookup as fallback. */
    private fun prayerName(context: Context, apiName: String, labelKey: String?): String {
        val resId = when (apiName) {
            "Fajr" -> R.string.brief_prayer_fajr
            "Sunrise" -> R.string.brief_prayer_sunrise
            "Dhuhr" -> R.string.brief_prayer_dhuhr
            "Asr" -> R.string.brief_prayer_asr
            "Maghrib" -> R.string.brief_prayer_maghrib
            "Isha" -> R.string.brief_prayer_isha
            else -> 0
        }
        if (resId != 0) return context.getString(resId)
        if (!labelKey.isNullOrBlank()) {
            val byKey = context.resources.getIdentifier(labelKey, "string", context.packageName)
            if (byKey != 0) return context.getString(byKey)
        }
        return apiName
    }

    companion object {
        const val ACTION_DISMISS = "pk.techtonic.jarvis.PRAYER_DISMISS"
    }
}
