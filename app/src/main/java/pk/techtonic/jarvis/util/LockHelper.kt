package pk.techtonic.jarvis.util

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import java.security.MessageDigest

/**
 * App-lock helpers (W4). The PIN is stored only as a SHA-256 hex string
 * in prefs (never plain) — see JarvisPrefs.lockPinHash.
 */
object LockHelper {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** Lowercase hex SHA-256 of the PIN (UTF-8 bytes). */
    fun sha256Hex(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v shr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * True when the device can authenticate with a biometric right now
     * (hardware present + at least one biometric enrolled).
     * canAuthenticate(int) exists only from API 30; on API 28–29 the
     * no-arg canAuthenticate() is used instead.
     */
    fun hasBiometric(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        // Platform BiometricManager has no from() (that is androidx-only);
        // obtain it from the system service.
        val manager = context.getSystemService(BiometricManager::class.java)
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        }
    }
}
