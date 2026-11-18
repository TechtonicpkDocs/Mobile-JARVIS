package pk.techtonic.jarvis.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Urdu-first locale handling. Default language is Urdu ("ur"); the user
 * can switch to English in Settings. Applied in attachBaseContext of
 * every Activity (same pattern as Medical Center Pro).
 *
 * API 24+ only (minSdk 24): Configuration.setLocale + setLayoutDirection.
 */
object LocaleHelper {

    fun wrap(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLayoutDirection(locale)
        }
        return context.createConfigurationContext(config)
    }

    /** "ur" -> Locale("ur"), anything else -> Locale.ENGLISH. */
    fun localeFor(language: String): Locale =
        if (language == "ur") Locale("ur") else Locale.ENGLISH
}
