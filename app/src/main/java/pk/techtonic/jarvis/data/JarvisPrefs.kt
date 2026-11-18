package pk.techtonic.jarvis.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Single SharedPreferences store for the whole app.
 *
 * Keys mirror the HTML v1.0.0 `jvs_settings` object so a JSON backup made
 * by the HTML app can be imported (see BackupManager).
 *
 * The Gemini API key lives ONLY here (entered by the user in Settings).
 * It is never hardcoded anywhere in the source.
 */
class JarvisPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var geminiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = sp.edit().putString(KEY_MODEL, value).apply()

    /** "ur" (default) or "en". */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, "ur") ?: "ur"
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    /** Master switch for spoken replies. */
    var voiceEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_VOICE_ENABLED, value).apply()

    /** Selected TTS voice name (Android Voice.name), "" = engine default. */
    var ttsVoiceName: String
        get() = sp.getString(KEY_TTS_VOICE, "") ?: ""
        set(value) = sp.edit().putString(KEY_TTS_VOICE, value).apply()

    /** Speech rate 0.5f..2.0f, default 1.0f. */
    var ttsRate: Float
        get() = sp.getFloat(KEY_TTS_RATE, 1.0f)
        set(value) = sp.edit().putFloat(KEY_TTS_RATE, value).apply()

    /** Pitch 0.5f..2.0f, default 1.0f. */
    var ttsPitch: Float
        get() = sp.getFloat(KEY_TTS_PITCH, 1.0f)
        set(value) = sp.edit().putFloat(KEY_TTS_PITCH, value).apply()

    // ---- App lock (default OFF; PIN stored as SHA-256 hex, never plain) ----

    var lockEnabled: Boolean
        get() = sp.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()

    var lockPinHash: String
        get() = sp.getString(KEY_LOCK_PIN_HASH, "") ?: ""
        set(value) = sp.edit().putString(KEY_LOCK_PIN_HASH, value).apply()

    var lockBiometric: Boolean
        get() = sp.getBoolean(KEY_LOCK_BIOMETRIC, true)
        set(value) = sp.edit().putBoolean(KEY_LOCK_BIOMETRIC, value).apply()

    // ---- Prayer-time alerts (default ALL OFF — he enables what he wants) ----

    fun prayerAlert(prayerApiName: String): Boolean =
        sp.getBoolean(KEY_PRAYER_ALERT_PREFIX + prayerApiName, false)

    fun setPrayerAlert(prayerApiName: String, enabled: Boolean) {
        sp.edit().putBoolean(KEY_PRAYER_ALERT_PREFIX + prayerApiName, enabled).apply()
    }

    // ---- Auto-backup (SAF; one visible file, written on app close) ----

    var autoBackupEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTOBACKUP_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_AUTOBACKUP_ENABLED, value).apply()

    /** Persisted SAF document Uri string, "" = not chosen yet. */
    var autoBackupUri: String
        get() = sp.getString(KEY_AUTOBACKUP_URI, "") ?: ""
        set(value) = sp.edit().putString(KEY_AUTOBACKUP_URI, value).apply()

    // ---- Tasbih ----

    /** Count target: 33, 100, 500… default 100. */
    var tasbihTarget: Int
        get() = sp.getInt(KEY_TASBIH_TARGET, 100)
        set(value) = sp.edit().putInt(KEY_TASBIH_TARGET, value).apply()

    /** Battery-optimization hint shown once, at first book start. */
    var batteryHintShown: Boolean
        get() = sp.getBoolean(KEY_BATTERY_HINT_SHOWN, false)
        set(value) = sp.edit().putBoolean(KEY_BATTERY_HINT_SHOWN, value).apply()

    /** First-run seeding of the pre-loaded book library done. */
    var booksSeeded: Boolean
        get() = sp.getBoolean(KEY_BOOKS_SEEDED, false)
        set(value) = sp.edit().putBoolean(KEY_BOOKS_SEEDED, value).apply()

    fun hasKey(): Boolean = geminiKey.isNotBlank()

    companion object {
        const val PREFS_NAME = "jarvis_prefs"
        const val DEFAULT_MODEL = "gemini-3.5-flash"

        const val KEY_API_KEY = "apiKey"
        const val KEY_MODEL = "model"
        const val KEY_LANGUAGE = "language"
        const val KEY_VOICE_ENABLED = "voiceEnabled"
        const val KEY_TTS_VOICE = "ttsVoice"
        const val KEY_TTS_RATE = "ttsRate"
        const val KEY_TTS_PITCH = "ttsPitch"
        const val KEY_LOCK_ENABLED = "lockEnabled"
        const val KEY_LOCK_PIN_HASH = "lockPinHash"
        const val KEY_LOCK_BIOMETRIC = "lockBiometric"
        const val KEY_PRAYER_ALERT_PREFIX = "prayerAlert_"
        const val KEY_AUTOBACKUP_ENABLED = "autoBackupEnabled"
        const val KEY_AUTOBACKUP_URI = "autoBackupUri"
        const val KEY_TASBIH_TARGET = "tasbihTarget"
        const val KEY_BATTERY_HINT_SHOWN = "batteryHintShown"
        const val KEY_BOOKS_SEEDED = "booksSeeded"
    }
}
