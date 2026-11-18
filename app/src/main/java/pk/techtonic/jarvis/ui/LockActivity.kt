package pk.techtonic.jarvis.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import pk.techtonic.jarvis.JarvisApp
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.data.JarvisPrefs
import pk.techtonic.jarvis.util.LocaleHelper
import pk.techtonic.jarvis.util.LockHelper

/**
 * App lock screen (W4) — the LAUNCHER activity. When the lock is off (or
 * no PIN was ever set) it forwards straight to MainActivity. Otherwise
 * it asks for the PIN (4–8 digits) and, when enabled and available,
 * offers the fingerprint button. Back on this screen moves the task to
 * the background — it never bypasses the lock.
 */
class LockActivity : Activity() {

    private lateinit var prefs: JarvisPrefs
    private lateinit var pinInput: EditText
    private lateinit var errorText: TextView
    private var biometricSignal: CancellationSignal? = null

    override fun attachBaseContext(newBase: Context) {
        val lang = JarvisPrefs(newBase).language
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = (application as JarvisApp).prefs
        // Lock off, or lock on but no PIN stored (must not happen — Settings
        // only enables the lock after a PIN is set): never brick the app.
        if (!prefs.lockEnabled || prefs.lockPinHash.isBlank()) {
            goMain()
            return
        }
        setContentView(R.layout.activity_lock)

        pinInput = findViewById(R.id.lock_pin)
        errorText = findViewById(R.id.lock_error)
        val unlockBtn = findViewById<Button>(R.id.lock_unlock)
        val fingerprintBtn = findViewById<Button>(R.id.lock_fingerprint)

        unlockBtn.setOnClickListener { checkPin() }
        if (prefs.lockBiometric && LockHelper.hasBiometric(this)) {
            fingerprintBtn.visibility = View.VISIBLE
            fingerprintBtn.setOnClickListener { showBiometric() }
        } else {
            fingerprintBtn.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        biometricSignal?.cancel()
        biometricSignal = null
    }

    override fun onBackPressed() {
        // Never let Back bypass the lock — background the task instead.
        moveTaskToBack(true)
    }

    private fun checkPin() {
        val entered = pinInput.text.toString().trim()
        if (entered.length !in PIN_MIN..PIN_MAX || !entered.all { it.isDigit() }) {
            showError(R.string.lock_pin_short)
            pinInput.setText("")
            return
        }
        if (LockHelper.sha256Hex(entered) == prefs.lockPinHash) {
            goMain()
        } else {
            showError(R.string.lock_wrong_pin)
            pinInput.setText("")
        }
    }

    private fun showBiometric() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val signal = CancellationSignal()
        biometricSignal = signal
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.lock_title))
            .setDescription(getString(R.string.lock_biometric))
            .setNegativeButton(
                getString(android.R.string.cancel),
                mainExecutor
            ) { _, _ -> /* user chose the PIN instead */ }
            .build()
        prompt.authenticate(
            signal,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    goMain()
                }

                override fun onAuthenticationFailed() {
                    showError(R.string.lock_wrong_pin)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User-cancelled / system-cancelled: stay silent, the
                    // PIN field is right there. Anything else (e.g. too
                    // many attempts) gets the system's own plain message.
                    // NOTE: the platform BiometricPrompt exposes no public
                    // constant for the negative-button error; 13 is its
                    // AOSP value (BiometricConstants.ERROR_NEGATIVE_BUTTON).
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                        errorCode != ERROR_NEGATIVE_BUTTON
                    ) {
                        errorText.text = errString
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
        )
    }

    private fun showError(resId: Int) {
        errorText.setText(resId)
        errorText.visibility = View.VISIBLE
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val PIN_MIN = 4
        const val PIN_MAX = 8
        // Negative-button ("Use PIN") error code — AOSP value; the
        // platform BiometricPrompt exposes no public constant for it.
        const val ERROR_NEGATIVE_BUTTON = 13
    }
}
