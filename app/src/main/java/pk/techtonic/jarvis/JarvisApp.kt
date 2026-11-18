package pk.techtonic.jarvis

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import pk.techtonic.jarvis.data.JarvisPrefs
import pk.techtonic.jarvis.speech.TtsManager

/**
 * Application class. Owns the single [JarvisPrefs] and the single
 * [TtsManager] for the whole app. TTS is initialised lazily on first use
 * (init is async on the engine side) — never block app start on it.
 * Also creates the notification channels for reminder + prayer alerts.
 */
class JarvisApp : Application() {

    lateinit var prefs: JarvisPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = JarvisPrefs(applicationContext)
        TtsManager.init(applicationContext, prefs)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                getString(R.string.channel_reminders),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PRAYERS,
                getString(R.string.channel_prayers),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLAYBACK,
                getString(R.string.channel_playback),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onTerminate() {
        TtsManager.shutdown()
        super.onTerminate()
    }

    companion object {
        const val CHANNEL_REMINDERS = "jarvis_reminders"
        const val CHANNEL_PRAYERS = "jarvis_prayers"
        const val CHANNEL_PLAYBACK = "jarvis_playback"
    }
}
