package pk.techtonic.jarvis.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import pk.techtonic.jarvis.JarvisApp
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.ui.MainActivity

/**
 * Shows a reminder notification (W3) on CHANNEL_REMINDERS with the note
 * text and a Dismiss action. Dismiss is the stop control: tapping it
 * cancels the notification AND the alarm — nothing re-fires.
 *
 * The alarm itself is one-shot (setExactAndAllowWhileIdle), so a fired
 * reminder never repeats on its own.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val noteId = intent.getLongExtra(ReminderScheduler.EXTRA_NOTE_ID, -1L)
        if (noteId < 0) return

        if (intent.action == ACTION_DISMISS) {
            dismiss(context, noteId)
            return
        }

        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT).orEmpty()
        showNotification(context, noteId, text)
    }

    private fun dismiss(context: Context, noteId: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(noteId.toInt())
        ReminderScheduler.cancel(context, noteId)
    }

    private fun showNotification(context: Context, noteId: Long, text: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Tap the notification -> open the app (MainActivity).
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, noteId.toInt(), openIntent, pendingFlags()
        )

        // Dismiss action -> back here with ACTION_DISMISS.
        val dismissIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(ReminderScheduler.EXTRA_NOTE_ID, noteId)
        }
        val dismissPending = PendingIntent.getBroadcast(
            context, noteId.toInt(), dismissIntent, pendingFlags()
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, JarvisApp.CHANNEL_REMINDERS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.reminder_dismiss),
                dismissPending
            )
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(noteId.toInt(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked after the alarm was set: the
            // reminder cannot be shown, but the receiver must not crash.
        }
    }

    /** The channels are created in JarvisApp.onCreate; create defensively
        here too so a reminder fired before first app start still shows. */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(JarvisApp.CHANNEL_REMINDERS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    JarvisApp.CHANNEL_REMINDERS,
                    context.getString(R.string.channel_reminders),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val ACTION_DISMISS = "pk.techtonic.jarvis.action.REMINDER_DISMISS"
    }
}
