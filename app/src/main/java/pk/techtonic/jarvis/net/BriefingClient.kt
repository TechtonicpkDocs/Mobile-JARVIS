package pk.techtonic.jarvis.net

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Calendar

/**
 * Morning-briefing network client (W3): Open-Meteo weather + Aladhan
 * prayer times for Layyah. Same pattern as GeminiClient (W1):
 * HttpURLConnection on a plain Thread, results posted to the main thread,
 * cancel = disconnect() + a flag checked before delivering anything.
 *
 * [hijriSuffix] is the localised "AH" suffix (R.string.brief_hijri_suffix)
 * appended to the Hijri date text; "" keeps the date without a suffix
 * (used by callers that do not show the Hijri date, e.g. PrayerScheduler).
 */
class BriefingClient(private val hijriSuffix: String = "") {

    interface Call {
        fun cancel()
    }

    data class Weather(
        val labelKey: String,
        val tempC: Double,
        val humidity: Int,
        val windKmh: Double
    )

    /** [time] is "HH:MM". [labelKey] is a brief_prayer_* string key. */
    data class Prayer(val apiName: String, val labelKey: String, val time: String)

    data class Prayers(val list: List<Prayer>, val hijriText: String, val next: Prayer?)

    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetchWeather(onResult: (Weather) -> Unit, onError: () -> Unit): Call {
        return fetch(WEATHER_URL, onError) { body ->
            val current = JSONObject(body).getJSONObject("current")
            val code = current.getInt("weather_code")
            Weather(
                labelKey = wmoLabelKey(code),
                tempC = current.getDouble("temperature_2m"),
                humidity = current.getInt("relative_humidity_2m"),
                windKmh = current.getDouble("wind_speed_10m")
            ).let { weather ->
                mainHandler.post { onResult(weather) }
            }
        }
    }

    fun fetchPrayers(onResult: (Prayers) -> Unit, onError: () -> Unit): Call {
        return fetch(PRAYERS_URL, onError) { body ->
            val data = JSONObject(body).getJSONObject("data")
            val timings = data.getJSONObject("timings")
            val list = ArrayList<Prayer>()
            for ((apiName, labelKey) in PRAYER_ORDER) {
                val raw = timings.optString(apiName, "")
                val time = cleanTime(raw)
                if (time.isNotEmpty()) {
                    list.add(Prayer(apiName, labelKey, time))
                }
            }
            if (list.isEmpty()) throw IllegalStateException("no prayer times")

            // Next prayer: first time still ahead today, else Fajr (tomorrow).
            val now = Calendar.getInstance()
            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            var next: Prayer? = null
            for (prayer in list) {
                if (timeToMinutes(prayer.time) > nowMin) {
                    next = prayer
                    break
                }
            }
            if (next == null) next = list[0]

            // Hijri date, only as provided by Aladhan — never invented.
            var hijriText = ""
            val hijri = data.optJSONObject("date")?.optJSONObject("hijri")
            if (hijri != null) {
                val day = hijri.optString("day", "")
                val monthObj = hijri.optJSONObject("month")
                val month = monthObj?.optString("ar", "")?.takeIf { it.isNotEmpty() }
                    ?: monthObj?.optString("en", "") ?: ""
                val year = hijri.optString("year", "")
                if (day.isNotEmpty() && month.isNotEmpty() && year.isNotEmpty()) {
                    hijriText = if (hijriSuffix.isNotEmpty()) {
                        "$day $month $year $hijriSuffix"
                    } else {
                        "$day $month $year"
                    }
                }
            }

            val prayers = Prayers(list, hijriText, next)
            mainHandler.post { onResult(prayers) }
        }
    }

    /**
     * Shared GET helper. [parse] runs on the worker thread and must post
     * its own result to [mainHandler] (or throw, which posts [onError]).
     * A cancelled call delivers nothing — never a stale result, never a
     * spurious error after the user pressed Stop.
     */
    private fun fetch(
        urlString: String,
        onError: () -> Unit,
        parse: (String) -> Unit
    ): Call {
        val handle = FetchHandle()
        val thread = Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(urlString).openConnection() as HttpURLConnection
                handle.connection = connection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP " + connection.responseCode)
                }
                val body = StringBuilder()
                BufferedReader(
                    InputStreamReader(connection.inputStream, Charset.forName("UTF-8"))
                ).use { reader ->
                    val buf = CharArray(4096)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1) {
                        body.append(buf, 0, read)
                    }
                }
                if (handle.cancelled) return@Thread
                parse(body.toString())
            } catch (e: Exception) {
                if (!handle.cancelled) {
                    mainHandler.post { onError() }
                }
            } finally {
                try {
                    connection?.disconnect()
                } catch (ignored: Exception) {
                    // Already closed; nothing to do.
                }
            }
        }
        handle.thread = thread
        thread.start()
        return handle
    }

    /** Strip suffixes like " (PKT)" from an Aladhan time string. */
    private fun cleanTime(raw: String): String =
        raw.replace("\\s*\\(.*\\)\\s*$".toRegex(), "").trim()

    private fun timeToMinutes(hhmm: String): Int {
        val parts = hhmm.split(":")
        if (parts.size < 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        return h * 60 + m
    }

    /** WMO weather-code -> brief_wx_* string key (HTML v1.0.0 mapping). */
    private fun wmoLabelKey(code: Int): String = when (code) {
        0 -> "brief_wx_clear"
        1, 2, 3 -> "brief_wx_partly"
        45, 48 -> "brief_wx_fog"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "brief_wx_rain"
        71, 73, 75, 77, 85, 86 -> "brief_wx_snow"
        95, 96, 99 -> "brief_wx_storm"
        else -> "brief_wx_unknown"
    }

    private class FetchHandle : Call {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
        var thread: Thread? = null

        override fun cancel() {
            cancelled = true
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
                // Already closed; nothing to do.
            }
        }
    }

    companion object {
        private const val WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=30.96&longitude=70.94" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&timezone=Asia%2FKarachi"
        private const val PRAYERS_URL =
            "https://api.aladhan.com/v1/timingsByCity?city=Layyah&country=Pakistan&method=1"

        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 30000

        private val PRAYER_ORDER = listOf(
            "Fajr" to "brief_prayer_fajr",
            "Sunrise" to "brief_prayer_sunrise",
            "Dhuhr" to "brief_prayer_dhuhr",
            "Asr" to "brief_prayer_asr",
            "Maghrib" to "brief_prayer_maghrib",
            "Isha" to "brief_prayer_isha"
        )
    }
}
