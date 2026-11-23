package pk.techtonic.jarvis.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import pk.techtonic.jarvis.data.JarvisPrefs
import pk.techtonic.jarvis.util.TextCleaner
import java.util.Locale
import java.util.UUID

/**
 * Single TextToSpeech engine for the whole app, owned by [pk.techtonic.jarvis.JarvisApp].
 *
 * CRITICAL (user requirement, "sab se bara masla"): [speak] ALWAYS passes the
 * text through [TextCleaner.stripForSpeech] first — no `**`, no `#`, no symbols
 * ever reach the engine, so TTS never reads "star star" aloud.
 *
 * Honours prefs: [JarvisPrefs.voiceEnabled] (master switch),
 * [JarvisPrefs.ttsVoiceName] (saved Voice.name, "" = engine default),
 * [JarvisPrefs.ttsRate] / [JarvisPrefs.ttsPitch] (clamped 0.5–2.0).
 *
 * v1.0.2: [applySettings] sets the engine language to Urdu
 * ([TextToSpeech.setLanguage]) — before this the engine read Urdu text
 * in its default language (English on the user's Vivo), which is why
 * the voice sounded robotic and broken. When no voice is saved and an
 * Urdu voice is installed, the first Urdu voice is auto-selected and
 * saved; a manual picker choice always wins. When Urdu language data
 * is missing or unsupported, [urduVoiceMissing] is set so Settings can
 * show the install-voice hint.
 *
 * Every public method is safe to call before init completes or after
 * shutdown: nothing throws, nothing queues silently — speak-before-init
 * calls onError.
 */
object TtsManager {

    private var tts: TextToSpeech? = null
    private var prefs: JarvisPrefs? = null
    private var ready = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * True when the last setLanguage(Urdu) reported the Urdu language
     * data missing or unsupported — the engine is reading Urdu text in
     * its default language instead. Recomputed on every applySettings();
     * Settings shows the install-voice hint while this is true.
     */
    @Volatile
    var urduVoiceMissing: Boolean = false
        private set

    /** utteranceId -> (onDone, onError). Only the latest utterance is kept:
        speak() uses QUEUE_FLUSH, so an older utterance never completes. */
    private val pending = HashMap<String, Pair<(() -> Unit)?, (() -> Unit)?>>()

    fun init(context: Context, prefs: JarvisPrefs) {
        if (tts != null) return
        this.prefs = prefs
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    try {
                        tts?.setOnUtteranceProgressListener(utteranceListener)
                    } catch (ignored: Exception) {
                        // Old/odd engines may reject the listener; speech still works.
                    }
                    applySettings()
                } else {
                    ready = false
                }
            }
        } catch (ignored: Exception) {
            tts = null
            ready = false
        }
    }

    fun shutdown() {
        pending.clear()
        ready = false
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (ignored: Exception) {
            // Engine already gone; nothing to do.
        }
        tts = null
    }

    fun isReady(): Boolean = ready && tts != null

    /**
     * Speak [text] after cleaning it for speech. QUEUE_FLUSH: a new speak
     * replaces anything currently playing. Callbacks run on the main thread.
     * If voice is disabled in prefs, does nothing and calls onDone.
     * If the engine is not ready, calls onError (never crashes, never queues).
     */
    fun speak(text: String, onDone: (() -> Unit)? = null, onError: (() -> Unit)? = null) {
        if (prefs?.voiceEnabled == false) {
            mainHandler.post { onDone?.invoke() }
            return
        }
        val engine = tts
        if (engine == null || !ready) {
            mainHandler.post { onError?.invoke() }
            return
        }
        val clean = TextCleaner.stripForSpeech(text)
        if (clean.isBlank()) {
            mainHandler.post { onDone?.invoke() }
            return
        }
        // QUEUE_FLUSH kills the previous utterance: drop its callbacks so a
        // stale onDone can never fire (e.g. start the next book chunk early).
        pending.clear()
        val utteranceId = UUID.randomUUID().toString()
        pending[utteranceId] = Pair(onDone, onError)
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        try {
            val result = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                pending.remove(utteranceId)
                mainHandler.post { onError?.invoke() }
            }
        } catch (ignored: Exception) {
            pending.remove(utteranceId)
            mainHandler.post { onError?.invoke() }
        }
    }

    /** Halt speech immediately. Pending callbacks are dropped, never fired —
        a stopped utterance must not trigger "next chunk" or "done" logic. */
    fun stop() {
        pending.clear()
        try {
            tts?.stop()
        } catch (ignored: Exception) {
            // Engine gone; nothing playing.
        }
    }

    fun isSpeaking(): Boolean {
        return try {
            tts?.isSpeaking ?: false
        } catch (ignored: Exception) {
            false
        }
    }

    /** All voices the engine reports, sorted by (language, name).
        Empty before init completes — callers must handle that. */
    fun voices(): List<Voice> {
        val set = try {
            tts?.voices
        } catch (ignored: Exception) {
            null
        } ?: return emptyList()
        return set.sortedWith(
            compareBy(
                { it.locale?.language ?: "" },
                { it.name }
            )
        )
    }

    /** Apply the saved voice/rate/pitch now. Safe before init (no-op). */
    fun applySettings() {
        val engine = tts
        if (engine == null || !ready) return
        val p = prefs ?: return
        try {
            engine.setSpeechRate(clampRate(p.ttsRate))
            engine.setPitch(clampRate(p.ttsPitch))
        } catch (ignored: Exception) {
            // Engine rejected the values; keep its defaults.
        }
        // v1.0.2: tell the engine the text is URDU. Before this the
        // engine read Urdu text in its default language (English on
        // the user's Vivo) — robotic, mispronounced, broken-sounding
        // speech. A missing/unsupported result falls back to the engine
        // default and records the flag for Settings to show.
        try {
            val langResult = engine.setLanguage(Locale("ur"))
            urduVoiceMissing = langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
        } catch (ignored: Exception) {
            urduVoiceMissing = false
        }
        val savedName = p.ttsVoiceName
        if (savedName.isBlank()) {
            // No manual choice: auto-pick the first installed Urdu voice
            // so the user gets Urdu speech without opening the picker.
            // A manual choice (savedName) always wins over this.
            val urdu = try {
                engine.voices?.firstOrNull { it.locale?.language == "ur" }
            } catch (ignored: Exception) {
                null
            }
            if (urdu != null) {
                try {
                    engine.voice = urdu
                    p.ttsVoiceName = urdu.name
                } catch (ignored: Exception) {
                    // Voice unusable; keep the engine default.
                }
            }
            return
        }
        val match = try {
            engine.voices?.firstOrNull { it.name == savedName }
        } catch (ignored: Exception) {
            null
        }
        if (match != null) {
            try {
                engine.voice = match
            } catch (ignored: Exception) {
                // Saved voice unusable; keep the engine default.
            }
        }
        // Saved voice gone (uninstalled): leave the engine default, never crash.
    }

    /** True when at least one Urdu (ur) voice is installed. */
    fun hasUrduVoice(): Boolean {
        return voices().any { it.locale?.language == "ur" }
    }

    private fun clampRate(value: Float): Float = when {
        value < 0.5f -> 0.5f
        value > 2.0f -> 2.0f
        else -> value
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            // Nothing to do on start.
        }

        override fun onDone(utteranceId: String) {
            val cb = pending.remove(utteranceId) ?: return
            mainHandler.post { cb.first?.invoke() }
        }

        override fun onError(utteranceId: String) {
            val cb = pending.remove(utteranceId) ?: return
            mainHandler.post { cb.second?.invoke() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String, errorCode: Int) {
            val cb = pending.remove(utteranceId) ?: return
            mainHandler.post { cb.second?.invoke() }
        }
    }
}
