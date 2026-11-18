package pk.techtonic.jarvis.util

import android.content.Context
import android.net.Uri
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.data.JarvisPrefs

/**
 * Auto-backup on app close (W4). Called from MainActivity.onStop.
 *
 * Only runs when the user enabled it in Settings AND chose a file.
 * Writes the full BackupManager export to the persisted SAF document
 * (truncate mode, so the file always holds the latest backup).
 * Runs on a plain background thread. Silent on success; ANY failure is
 * swallowed (the app must never crash on close) but recorded in the
 * "lastAutoBackupError" pref string, which Settings shows plainly.
 */
object AutoBackup {

    private const val KEY_LAST_ERROR = "lastAutoBackupError"

    fun maybeBackupOnClose(context: Context) {
        val appContext = context.applicationContext
        val prefs = JarvisPrefs(appContext)
        if (!prefs.autoBackupEnabled) return
        val uriString = prefs.autoBackupUri
        if (uriString.isBlank()) return

        Thread {
            try {
                val db = JarvisDb(appContext)
                val json = try {
                    BackupManager.buildExportJson(db, prefs)
                } finally {
                    db.close()
                }
                val uri = Uri.parse(uriString)
                val out = appContext.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("openOutputStream returned null")
                out.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                setLastError(appContext, "")
            } catch (e: Exception) {
                // Swallowed by design (never crash on close), but recorded
                // so Settings can show what went wrong.
                setLastError(appContext, e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    /** Last auto-backup failure message, "" when the last run succeeded. */
    fun getLastError(context: Context): String {
        return context.getSharedPreferences(JarvisPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ERROR, "") ?: ""
    }

    private fun setLastError(context: Context, message: String) {
        context.getSharedPreferences(JarvisPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_ERROR, message).apply()
    }
}
