package pk.techtonic.jarvis.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import pk.techtonic.jarvis.JarvisApp
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.data.JarvisPrefs
import pk.techtonic.jarvis.speech.TtsManager
import pk.techtonic.jarvis.util.LocaleHelper
import java.util.Locale

/**
 * Voice picker: choose the device TTS voice JARVIS speaks with, preview it,
 * and set speech rate + pitch. Launched from Settings.
 *
 * - List of all TtsManager.voices() (name + locale display name); tap a row
 *   to select it (highlight + save prefs.ttsVoiceName + applySettings()).
 * - "Default voice" button clears the selection back to the engine default.
 * - Preview speaks a short sample in the selected voice.
 * - Rate / pitch SeekBars: 50–200 -> 0.5x–2.0x, written to prefs live.
 * - No Urdu voice installed -> plain notice + button to system TTS settings.
 * - Empty voice list (engine not ready yet) -> loading text, one refresh
 *   after 1.5 s; never loops forever.
 * - Stop halts any preview speech immediately.
 */
class VoicePickerActivity : Activity() {

    private lateinit var prefs: JarvisPrefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var voiceList: ListView
    private lateinit var loadingText: TextView
    private lateinit var selectedText: TextView
    private lateinit var noUrduText: TextView
    private lateinit var openTtsButton: Button
    private lateinit var rateLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var rateSeek: SeekBar
    private lateinit var pitchSeek: SeekBar

    private val voices = ArrayList<Voice>()
    private lateinit var adapter: VoiceAdapter
    private var refreshScheduled = false

    override fun attachBaseContext(newBase: Context) {
        val lang = JarvisPrefs(newBase).language
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = (application as JarvisApp).prefs
        setContentView(R.layout.activity_voice_picker)

        voiceList = findViewById(R.id.voice_list)
        loadingText = findViewById(R.id.voice_loading_text)
        selectedText = findViewById(R.id.voice_selected_text)
        noUrduText = findViewById(R.id.voice_no_urdu_text)
        openTtsButton = findViewById(R.id.voice_open_tts_button)
        rateLabel = findViewById(R.id.voice_rate_label)
        pitchLabel = findViewById(R.id.voice_pitch_label)
        rateSeek = findViewById(R.id.voice_rate_seek)
        pitchSeek = findViewById(R.id.voice_pitch_seek)

        findViewById<TextView>(R.id.voice_title_text).text =
            getString(R.string.voice_title)
        findViewById<TextView>(R.id.voice_hint_text).text =
            getString(R.string.voice_pick_hint)
        findViewById<TextView>(R.id.voice_section_label).text =
            getString(R.string.set_ttsvoice_label)
        loadingText.text = getString(R.string.set_ttsvoice_loading)
        noUrduText.text = getString(R.string.voice_no_urdu)

        adapter = VoiceAdapter()
        voiceList.adapter = adapter
        voiceList.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
            val voice = voices[position]
            prefs.ttsVoiceName = voice.name
            TtsManager.applySettings()
            updateSelectedLabel()
            adapter.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.voice_default_button).setOnClickListener {
            prefs.ttsVoiceName = ""
            TtsManager.applySettings()
            updateSelectedLabel()
            adapter.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.voice_preview_button).setOnClickListener {
            if (!prefs.voiceEnabled) {
                Toast.makeText(
                    this,
                    getString(R.string.voice_disabled_hint),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            TtsManager.speak(getString(R.string.voice_preview_text))
        }

        findViewById<Button>(R.id.voice_stop_button).setOnClickListener {
            TtsManager.stop()
            Toast.makeText(this, getString(R.string.stopped), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.voice_back_button).setOnClickListener { finish() }

        openTtsButton.setOnClickListener { openSystemTtsSettings() }

        // SeekBars: max 150, progress 0..150 maps to 0.5x..2.0x.
        rateSeek.max = SEEK_RANGE
        pitchSeek.max = SEEK_RANGE
        rateSeek.progress = rateToProgress(prefs.ttsRate)
        pitchSeek.progress = rateToProgress(prefs.ttsPitch)
        updateRateLabel()
        updatePitchLabel()
        rateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.ttsRate = progressToRate(progress)
                TtsManager.applySettings()
                updateRateLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        pitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.ttsPitch = progressToRate(progress)
                TtsManager.applySettings()
                updatePitchLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        updateSelectedLabel()
        refreshVoices()
    }

    override fun onResume() {
        super.onResume()
        // The user may have installed a voice in system settings and come back.
        refreshVoices()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        TtsManager.stop()
        super.onDestroy()
    }

    private fun refreshVoices() {
        val all = TtsManager.voices()
        voices.clear()
        voices.addAll(all)
        adapter.notifyDataSetChanged()
        if (all.isEmpty()) {
            voiceList.visibility = View.GONE
            loadingText.visibility = View.VISIBLE
            noUrduText.visibility = View.GONE
            openTtsButton.visibility = View.GONE
            if (!refreshScheduled) {
                // Engine not ready yet: one retry after 1.5 s, never a loop.
                refreshScheduled = true
                handler.postDelayed({ refreshVoicesOnceMore() }, REFRESH_DELAY_MS)
            } else {
                loadingText.text = getString(R.string.set_ttsvoice_none)
            }
        } else {
            loadingText.visibility = View.GONE
            voiceList.visibility = View.VISIBLE
            // v1.0.2: also show the notice when the engine reports Urdu
            // unavailable even though a voice row exists (odd engines
            // list a voice setLanguage() still rejects).
            if (TtsManager.hasUrduVoice() && !TtsManager.urduVoiceMissing) {
                noUrduText.visibility = View.GONE
                openTtsButton.visibility = View.GONE
            } else {
                noUrduText.visibility = View.VISIBLE
                openTtsButton.visibility = View.VISIBLE
            }
        }
    }

    private fun refreshVoicesOnceMore() {
        // Second (and last) attempt: refreshVoices shows "none" if still empty.
        refreshVoices()
    }

    private fun updateSelectedLabel() {
        val saved = prefs.ttsVoiceName
        val label = if (saved.isBlank()) {
            getString(R.string.set_ttsvoice_default)
        } else {
            saved
        }
        selectedText.text = getString(R.string.voice_selected, label)
    }

    private fun updateRateLabel() {
        rateLabel.text = getString(R.string.voice_rate, formatRate(prefs.ttsRate))
    }

    private fun updatePitchLabel() {
        pitchLabel.text = getString(R.string.voice_pitch, formatRate(prefs.ttsPitch))
    }

    private fun openSystemTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: ActivityNotFoundException) {
                // No settings app at all; nothing to open.
            }
        }
    }

    private fun rateToProgress(rate: Float): Int {
        val clamped = rate.coerceIn(0.5f, 2.0f)
        return ((clamped * 100f).toInt() - 50).coerceIn(0, SEEK_RANGE)
    }

    private fun progressToRate(progress: Int): Float = (progress + 50) / 100f

    private fun formatRate(rate: Float): String =
        String.format(Locale.US, "%.1f", rate)

    private inner class VoiceAdapter : BaseAdapter() {
        override fun getCount(): Int = voices.size
        override fun getItem(position: Int): Any = voices[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(this@VoicePickerActivity)
                .inflate(R.layout.item_voice, parent, false)
            val voice = voices[position]
            val nameView = row.findViewById<TextView>(R.id.voice_name)
            val localeView = row.findViewById<TextView>(R.id.voice_locale)
            nameView.text = voice.name
            val locale = voice.locale
            localeView.text = if (locale != null) {
                locale.getDisplayName(locale)
            } else {
                ""
            }
            // Highlight the selected voice.
            val selected = prefs.ttsVoiceName.isNotBlank() &&
                voice.name == prefs.ttsVoiceName
            row.setBackgroundColor(
                if (selected) {
                    resources.getColor(R.color.gold_light)
                } else {
                    resources.getColor(android.R.color.transparent)
                }
            )
            return row
        }
    }

    companion object {
        /** SeekBar range: progress 0..150 maps to rate 0.5x..2.0x. */
        const val SEEK_RANGE = 150
        const val REFRESH_DELAY_MS = 1500L
    }
}
