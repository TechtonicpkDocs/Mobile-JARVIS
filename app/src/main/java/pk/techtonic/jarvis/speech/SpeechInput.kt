package pk.techtonic.jarvis.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.ArrayList

/**
 * Voice input via the platform SpeechRecognizer. Tries ur-PK first; on
 * NO_MATCH / SPEECH_TIMEOUT it retries ONCE with en-US, then reports
 * "chat_voice_failed". Never loops.
 *
 * Callers MUST run MicPermission.ensure first (permission is assumed
 * granted here). [start] itself checks for a recognizer and calls
 * onError("mic_no_recognizer") when none exists — never a dead button,
 * never a crash.
 *
 * onError receives a STRING KEY, not raw text:
 * "mic_no_recognizer" | "chat_voice_unsupported" | "chat_voice_failed".
 */
class SpeechInput(context: Context) {

    private val appContext: Context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var retriedWithEnglish = false
    private var onResultCb: ((String) -> Unit)? = null
    private var onErrorCb: ((String) -> Unit)? = null

    fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("mic_no_recognizer")
            return
        }
        // A fresh start replaces any in-flight listen.
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (ignored: Exception) {
            // Old recognizer already dead; continue with a new one.
        }
        recognizer = null
        listening = false
        retriedWithEnglish = false
        onResultCb = onResult
        onErrorCb = onError
        val rec = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (ignored: Exception) {
            onError("chat_voice_unsupported")
            return
        }
        recognizer = rec
        rec.setRecognitionListener(listener)
        startListeningWith(rec, LANG_URDU)
    }

    /** stopListening: deliver partial results as a final result. */
    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (ignored: Exception) {
            listening = false
        }
    }

    /** cancel: discard everything, no result delivered. */
    fun cancel() {
        listening = false
        try {
            recognizer?.cancel()
        } catch (ignored: Exception) {
            // Already stopped; nothing to cancel.
        }
    }

    fun isListening(): Boolean = listening

    fun destroy() {
        listening = false
        onResultCb = null
        onErrorCb = null
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (ignored: Exception) {
            // Already released.
        }
        recognizer = null
    }

    private fun startListeningWith(rec: SpeechRecognizer, language: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            rec.startListening(intent)
        } catch (ignored: Exception) {
            listening = false
            fail("chat_voice_unsupported")
        }
    }

    private fun fail(key: String) {
        listening = false
        val cb = onErrorCb
        onErrorCb = null
        onResultCb = null
        cb?.invoke(key)
    }

    private fun deliver(text: String) {
        listening = false
        val cb = onResultCb
        onResultCb = null
        onErrorCb = null
        cb?.invoke(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle) {
            listening = true
        }

        override fun onBeginningOfSpeech() {
            // Listening state already set in onReadyForSpeech.
        }

        override fun onRmsChanged(rmsdB: Float) {
            // No level meter in this build.
        }

        override fun onBufferReceived(buffer: ByteArray) {
            // Raw audio not needed.
        }

        override fun onEndOfSpeech() {
            // Results (or an error) follow; keep the listening flag until then.
        }

        override fun onError(error: Int) {
            listening = false
            if ((error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                !retriedWithEnglish
            ) {
                // One retry in English, then give up — never loop.
                retriedWithEnglish = true
                val rec = recognizer
                if (rec != null) {
                    startListeningWith(rec, LANG_ENGLISH)
                    return
                }
            }
            fail("chat_voice_failed")
        }

        override fun onResults(results: Bundle) {
            listening = false
            val matches: ArrayList<String>? =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull()?.trim().orEmpty()
            if (best.isNotEmpty()) {
                deliver(best)
            } else if (!retriedWithEnglish) {
                // Empty result after ur-PK: one English retry, then fail.
                retriedWithEnglish = true
                val rec = recognizer
                if (rec != null) {
                    startListeningWith(rec, LANG_ENGLISH)
                    return
                }
                fail("chat_voice_failed")
            } else {
                fail("chat_voice_failed")
            }
        }

        override fun onPartialResults(partialResults: Bundle) {
            // Partials are shown only via stop() -> onResults; ignore live ones.
        }

        override fun onEvent(eventType: Int, params: Bundle) {
            // No vendor events used.
        }
    }

    companion object {
        const val LANG_URDU = "ur-PK"
        const val LANG_ENGLISH = "en-US"

        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)
    }
}
