package pk.techtonic.jarvis.ui

import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.net.BriefingClient
import pk.techtonic.jarvis.speech.TtsManager
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Morning Briefing tab (W3): today's date (+ Hijri when Aladhan provides
 * it), Layyah weather, prayer times, notes, scholar-of-the-day shortcut,
 * and a spoken briefing.
 *
 * Weather and prayers fetch in parallel and fail independently — one
 * failing never blocks the other. A visible Stop button halts both
 * fetches; a second Stop halts the spoken briefing.
 */
class BriefingFragment : Fragment() {

    private lateinit var btnGenerate: Button
    private lateinit var btnStopFetch: Button
    private lateinit var briefStatus: TextView
    private lateinit var dateText: TextView
    private lateinit var hijriText: TextView
    private lateinit var weatherLabel: TextView
    private lateinit var tempValue: TextView
    private lateinit var humidityValue: TextView
    private lateinit var windValue: TextView
    private lateinit var prayerList: LinearLayout
    private lateinit var notesCount: TextView
    private lateinit var notesList: LinearLayout
    private lateinit var btnBayan: Button
    private lateinit var btnSpeak: Button
    private lateinit var btnStopSpeak: Button
    private lateinit var speakingStatus: TextView

    private lateinit var db: JarvisDb
    private lateinit var briefingClient: BriefingClient

    // Last generated briefing (in-memory only, for the spoken summary).
    private var generated = false
    private var dateSummary = ""
    private var hijriSummary = ""
    private var weather: BriefingClient.Weather? = null
    private var prayers: BriefingClient.Prayers? = null
    private var notesTotal = 0

    // Fetch state.
    private var fetching = false
    private var stopped = false
    private var weatherCall: BriefingClient.Call? = null
    private var prayersCall: BriefingClient.Call? = null
    private var weatherDone = false
    private var prayersDone = false
    private var weatherOk = false
    private var prayersOk = false

    private var speaking = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_briefing, container, false)
        val main = activity as MainActivity
        db = main.db
        briefingClient = BriefingClient(getString(R.string.brief_hijri_suffix))

        btnGenerate = view.findViewById(R.id.brief_generate)
        btnStopFetch = view.findViewById(R.id.brief_stop_fetch)
        briefStatus = view.findViewById(R.id.brief_status)
        dateText = view.findViewById(R.id.brief_date_text)
        hijriText = view.findViewById(R.id.brief_hijri_text)
        weatherLabel = view.findViewById(R.id.brief_weather_label)
        tempValue = view.findViewById(R.id.brief_temp_value)
        humidityValue = view.findViewById(R.id.brief_humidity_value)
        windValue = view.findViewById(R.id.brief_wind_value)
        prayerList = view.findViewById(R.id.brief_prayer_list)
        notesCount = view.findViewById(R.id.brief_notes_count)
        notesList = view.findViewById(R.id.brief_notes_list)
        btnBayan = view.findViewById(R.id.brief_bayan_btn)
        btnSpeak = view.findViewById(R.id.brief_speak)
        btnStopSpeak = view.findViewById(R.id.brief_stop_speak)
        speakingStatus = view.findViewById(R.id.brief_speaking_status)

        btnGenerate.setOnClickListener { generateBriefing() }
        btnStopFetch.setOnClickListener { stopFetching() }
        btnSpeak.setOnClickListener { speakBriefing() }
        btnStopSpeak.setOnClickListener { stopSpeaking() }
        btnBayan.setOnClickListener { openScholarSearch(scholarOfTheDay().first) }

        renderDate()
        loadNotes()
        renderBayanShortcut()
        return view
    }

    override fun onDestroyView() {
        weatherCall?.cancel()
        prayersCall?.cancel()
        weatherCall = null
        prayersCall = null
        if (speaking) {
            TtsManager.stop()
            speaking = false
        }
        super.onDestroyView()
    }

    // ---------- Generate ----------

    private fun generateBriefing() {
        if (fetching) return
        renderDate()
        loadNotes()
        renderBayanShortcut()

        if (!isOnline()) {
            briefStatus.setText(R.string.brief_err_offline)
            return
        }

        fetching = true
        stopped = false
        generated = false
        hijriSummary = ""
        weather = null
        prayers = null
        weatherDone = false
        prayersDone = false
        weatherOk = false
        prayersOk = false
        btnGenerate.isEnabled = false
        btnStopFetch.visibility = View.VISIBLE
        briefStatus.setText(R.string.brief_loading)
        hijriText.visibility = View.GONE
        hijriText.text = ""

        weatherCall = briefingClient.fetchWeather(
            onResult = { result ->
                if (!isAdded || stopped) return@fetchWeather
                weather = result
                weatherOk = true
                weatherDone = true
                renderWeather(result)
                checkBothDone()
            },
            onError = {
                if (!isAdded || stopped) return@fetchWeather
                weather = null
                weatherOk = false
                weatherDone = true
                renderWeatherError()
                checkBothDone()
            }
        )
        prayersCall = briefingClient.fetchPrayers(
            onResult = { result ->
                if (!isAdded || stopped) return@fetchPrayers
                prayers = result
                prayersOk = true
                prayersDone = true
                hijriSummary = result.hijriText
                renderPrayers(result)
                renderDate()
                checkBothDone()
            },
            onError = {
                if (!isAdded || stopped) return@fetchPrayers
                prayers = null
                prayersOk = false
                prayersDone = true
                renderPrayersError()
                checkBothDone()
            }
        )
    }

    private fun checkBothDone() {
        if (!weatherDone || !prayersDone) return
        fetching = false
        generated = true
        btnGenerate.isEnabled = true
        btnStopFetch.visibility = View.GONE
        briefStatus.setText(
            when {
                weatherOk && prayersOk -> R.string.brief_done
                !weatherOk && !prayersOk -> R.string.brief_err_offline
                else -> R.string.brief_partial
            }
        )
    }

    /** Stop halts NOW: both calls cancelled, no retry, no resume. */
    private fun stopFetching() {
        stopped = true
        fetching = false
        weatherCall?.cancel()
        prayersCall?.cancel()
        weatherCall = null
        prayersCall = null
        btnGenerate.isEnabled = true
        btnStopFetch.visibility = View.GONE
        briefStatus.setText(R.string.brief_stopped)
        Toast.makeText(activity, R.string.brief_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun isOnline(): Boolean {
        val cm = activity?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        return info != null && info.isConnected
    }

    // ---------- Date ----------

    private fun renderDate() {
        val now = Calendar.getInstance().time
        val greg = SimpleDateFormat("EEEE d MMMM yyyy", Locale("ur")).format(now)
        dateSummary = greg
        dateText.text = greg
        if (hijriSummary.isNotEmpty()) {
            hijriText.text = hijriSummary
            hijriText.visibility = View.VISIBLE
        } else {
            hijriText.visibility = View.GONE
        }
    }

    // ---------- Weather ----------

    private fun renderWeather(result: BriefingClient.Weather) {
        weatherLabel.setText(wxLabelRes(result.labelKey))
        tempValue.text = fmtTemp(result.tempC) + " °C"
        humidityValue.text = result.humidity.toString() + " %"
        windValue.text = fmtTemp(result.windKmh) + " km/h"
    }

    private fun renderWeatherError() {
        weatherLabel.setText(R.string.brief_err_weather)
        tempValue.text = ""
        humidityValue.text = ""
        windValue.text = ""
    }

    private fun fmtTemp(value: Double): String {
        // One decimal, trailing ".0" trimmed ("24" not "24.0").
        val s = String.format(Locale.US, "%.1f", value)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun wxLabelRes(key: String): Int = when (key) {
        "brief_wx_clear" -> R.string.brief_wx_clear
        "brief_wx_partly" -> R.string.brief_wx_partly
        "brief_wx_fog" -> R.string.brief_wx_fog
        "brief_wx_rain" -> R.string.brief_wx_rain
        "brief_wx_snow" -> R.string.brief_wx_snow
        "brief_wx_storm" -> R.string.brief_wx_storm
        else -> R.string.brief_wx_unknown
    }

    // ---------- Prayers ----------

    private fun renderPrayers(result: BriefingClient.Prayers) {
        prayerList.removeAllViews()
        for (prayer in result.list) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val label = TextView(activity).apply {
                text = getString(prayerLabelRes(prayer.labelKey))
                setTextAppearance(android.R.style.TextAppearance_Medium)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val time = TextView(activity).apply {
                text = prayer.time
                setTextAppearance(android.R.style.TextAppearance_Medium)
                typeface = Typeface.MONOSPACE
            }
            if (result.next != null && prayer.apiName == result.next.apiName) {
                row.setBackgroundColor(activity.getColor(R.color.gold_light))
                label.setTypeface(null, Typeface.BOLD)
                time.setTypeface(null, Typeface.BOLD)
            }
            row.addView(label)
            row.addView(time)
            prayerList.addView(row)
        }
    }

    private fun renderPrayersError() {
        prayerList.removeAllViews()
        val error = TextView(activity).apply {
            setText(R.string.brief_err_prayers)
            setTextAppearance(android.R.style.TextAppearance_Medium)
        }
        prayerList.addView(error)
    }

    private fun prayerLabelRes(key: String): Int = when (key) {
        "brief_prayer_fajr" -> R.string.brief_prayer_fajr
        "brief_prayer_sunrise" -> R.string.brief_prayer_sunrise
        "brief_prayer_dhuhr" -> R.string.brief_prayer_dhuhr
        "brief_prayer_asr" -> R.string.brief_prayer_asr
        "brief_prayer_maghrib" -> R.string.brief_prayer_maghrib
        "brief_prayer_isha" -> R.string.brief_prayer_isha
        else -> R.string.brief_prayer_fajr
    }

    // ---------- Notes ----------

    private fun loadNotes() {
        val notes = try {
            db.allNotes()
        } catch (ignored: Exception) {
            emptyList()
        }
        notesTotal = notes.size
        notesCount.text = getString(R.string.brief_notes_count)
            .replace("{n}", notesTotal.toString())
        notesList.removeAllViews()
        if (notes.isEmpty()) {
            notesList.addView(
                TextView(activity).apply {
                    setText(R.string.brief_no_notes)
                    setTextAppearance(android.R.style.TextAppearance_Medium)
                }
            )
            return
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        val todays = notes.filter { it.date == today }.take(5)
        if (todays.isNotEmpty()) {
            notesList.addView(
                TextView(activity).apply {
                    setText(R.string.brief_today_notes)
                    setTextAppearance(android.R.style.TextAppearance_Medium)
                    setTypeface(null, Typeface.BOLD)
                }
            )
            for (note in todays) {
                notesList.addView(noteRow(note.text, isToday = true, pinned = note.pinned))
            }
        }
        // Featured note (pinned first, else newest) unless already shown.
        val featured = notes.firstOrNull { it.pinned } ?: notes.firstOrNull()
        if (featured != null && todays.none { it.id == featured.id }) {
            notesList.addView(
                noteRow(
                    featured.text,
                    isToday = featured.date == today,
                    pinned = featured.pinned
                )
            )
        }
    }

    private fun noteRow(text: String, isToday: Boolean, pinned: Boolean): TextView {
        val badge = StringBuilder()
        if (isToday) badge.append(" · ").append(getString(R.string.brief_today))
        if (pinned) badge.append(" · ").append(getString(R.string.brief_pinned))
        return TextView(activity).apply {
            this.text = text + badge.toString()
            setTextAppearance(android.R.style.TextAppearance_Medium)
            setPadding(0, 8, 0, 8)
        }
    }

    // ---------- Bayan shortcut ----------

    private fun renderBayanShortcut() {
        val scholar = scholarOfTheDay()
        btnBayan.text = getString(R.string.brief_bayan_today)
            .replace("{scholar}", getString(scholar.second))
    }

    /** Deterministic rotation: (dayOfYear - 1) % 6. Returns (English search
        name, display string res). */
    private fun scholarOfTheDay(): Pair<String, Int> {
        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return SCHOLARS[(dayOfYear - 1) % SCHOLARS.size]
    }

    private fun openScholarSearch(englishName: String) {
        val url = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode("$englishName latest bayan", "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (intent.resolveActivity(activity.packageManager) != null) {
            startActivity(intent)
        }
    }

    // ---------- Spoken briefing ----------

    private fun speakBriefing() {
        if (!generated) {
            Toast.makeText(activity, R.string.brief_generate_first, Toast.LENGTH_SHORT).show()
            return
        }
        val main = activity as? MainActivity ?: return
        if (!main.prefs.voiceEnabled) {
            Toast.makeText(activity, R.string.voice_disabled_hint, Toast.LENGTH_SHORT).show()
            return
        }
        if (!TtsManager.isReady()) {
            Toast.makeText(activity, R.string.brief_speak_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val parts = ArrayList<String>()
        if (dateSummary.isNotEmpty()) parts.add(dateSummary)
        weather?.let { w ->
            parts.add(
                getString(R.string.brief_speak_weather)
                    .replace("{label}", getString(wxLabelRes(w.labelKey)))
                    .replace("{temp}", fmtTemp(w.tempC))
            )
        }
        prayers?.next?.let { next ->
            parts.add(
                getString(R.string.brief_speak_prayer)
                    .replace("{name}", getString(prayerLabelRes(next.labelKey)))
                    .replace("{time}", next.time)
            )
        }
        if (notesTotal > 0) {
            parts.add(
                getString(R.string.brief_speak_notes)
                    .replace("{n}", notesTotal.toString())
            )
        } else {
            parts.add(getString(R.string.brief_speak_no_notes))
        }
        val separator = if (main.prefs.language == "ur") "۔ " else ". "
        val end = if (main.prefs.language == "ur") "۔" else "."
        val summary = parts.joinToString(separator) + end

        speaking = true
        btnSpeak.visibility = View.GONE
        btnStopSpeak.visibility = View.VISIBLE
        speakingStatus.visibility = View.VISIBLE
        TtsManager.speak(
            summary,
            onDone = {
                if (!isAdded) return@speak
                speaking = false
                btnSpeak.visibility = View.VISIBLE
                btnStopSpeak.visibility = View.GONE
                speakingStatus.visibility = View.GONE
            },
            onError = {
                if (!isAdded) return@speak
                speaking = false
                btnSpeak.visibility = View.VISIBLE
                btnStopSpeak.visibility = View.GONE
                speakingStatus.visibility = View.GONE
                Toast.makeText(activity, R.string.brief_speak_unavailable, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun stopSpeaking() {
        TtsManager.stop()
        speaking = false
        btnSpeak.visibility = View.VISIBLE
        btnStopSpeak.visibility = View.GONE
        speakingStatus.visibility = View.GONE
        Toast.makeText(activity, R.string.stopped, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** (English YouTube search name, display string res) — contract order. */
        private val SCHOLARS = listOf(
            "Imran Attari" to R.string.bayan_scholar_imran,
            "Abdul Habib Attari" to R.string.bayan_scholar_habib,
            "Mufti Samar Abbas Attari" to R.string.bayan_scholar_samar,
            "Muhammad Raza Saqib Mustafai" to R.string.bayan_scholar_saqib,
            "Mufti Salman Azhari" to R.string.bayan_scholar_salman,
            "Ajmal Raza Qadri" to R.string.bayan_scholar_ajmal
        )
    }
}
