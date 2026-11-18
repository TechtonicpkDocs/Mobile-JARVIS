package pk.techtonic.jarvis.util

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.SpeechRecognizer
import pk.techtonic.jarvis.R

/**
 * Shared RECORD_AUDIO permission flow (W1 chat mic + W3 voice-notes mic
 * use this identically — same behaviour everywhere).
 *
 * [ensure]: if granted -> onGranted. Else a plain-Urdu rationale dialog
 * FIRST (why the mic is needed: bol kar baat karne ke liye), then the
 * system permission request. Denial -> onDenied (the caller shows the
 * denied message with the Settings button; never fail silently).
 *
 * The host Activity/Fragment MUST forward onRequestPermissionsResult to
 * [onRequestPermissionsResult]. Only ONE pending callback pair is kept;
 * a new [ensure] call replaces it.
 */
object MicPermission {

    const val REQUEST_CODE = 1001

    private var pendingGranted: (() -> Unit)? = null
    private var pendingDenied: (() -> Unit)? = null

    /** True when RECORD_AUDIO is currently granted. */
    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** True when a speech recognizer exists on the device. */
    fun isRecognizerAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    fun ensure(activity: Activity, onGranted: () -> Unit, onDenied: () -> Unit) {
        if (isGranted(activity)) {
            onGranted()
            return
        }
        // One pending pair; a new call replaces the old one.
        pendingGranted = onGranted
        pendingDenied = onDenied
        AlertDialog.Builder(activity)
            .setTitle(R.string.mic_why_title)
            .setMessage(R.string.mic_why_body)
            .setPositiveButton(R.string.mic_allow) { _, _ ->
                activity.requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_CODE
                )
            }
            .setNegativeButton(R.string.mic_cancel) { _, _ ->
                val denied = pendingDenied
                pendingGranted = null
                pendingDenied = null
                denied?.invoke()
            }
            .setOnCancelListener {
                val denied = pendingDenied
                pendingGranted = null
                pendingDenied = null
                denied?.invoke()
            }
            .show()
    }

    /**
     * Forward from the host Activity/Fragment's onRequestPermissionsResult.
     * Returns true if the result was ours.
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        val onGranted = pendingGranted
        val onDenied = pendingDenied
        pendingGranted = null
        pendingDenied = null
        if (granted) onGranted?.invoke() else onDenied?.invoke()
        return true
    }

    /** One-tap path to this app's system Settings page (for a denied user). */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName)
        )
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
