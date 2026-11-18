package pk.techtonic.jarvis.net

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import pk.techtonic.jarvis.data.JarvisPrefs

/**
 * Minimal Gemini client: HttpURLConnection on a plain Thread, results
 * posted back via [mainPoster] (main thread by default). No third-party
 * dependency.
 *
 * No key -> onError("chat_no_key") with ZERO network calls.
 * Cancel = disconnect() + a cancelled flag checked before delivering
 * any result AND between retry attempts, so a stopped request never
 * touches the UI afterwards — including a Stop pressed during backoff.
 *
 * Response parsing joins ALL parts of candidates[0].content.parts,
 * skipping any part with `"thought":true`.
 *
 * 503 / overload handling (user hit a live 503 on 2026-11-18 — Google-side
 * overload, NOT the key, NOT quota, NOT the app):
 * - Retryable codes 429/500/502/503/504: `generate` retries the
 *   configured model up to 3 attempts total (delays 2 s then 4 s).
 * - If the primary still fails, one fallback attempt with FALLBACK_MODEL
 *   (skipped when the configured model already IS the lite model).
 *   Fallback success -> onResult(text, usedFallback = true).
 * - Final failure -> onError("chat_server_busy"). The user NEVER sees a
 *   bare "503" or any raw code.
 * - 400/401/403 -> immediate onError("chat_error"), no retry, no fallback.
 * - Network IOException (DNS blip, momentary drop on a slow mobile
 *   connection) -> retried exactly like a retryable code (2 s, then 4 s);
 *   onError("chat_offline") only after the retries are exhausted. One
 *   transient hiccup must never fail the request.
 * - `testKey`: one retry after 2 s on retryable codes, then
 *   onError("chat_server_busy") — the key is probably fine. No fallback.
 */
class GeminiClient(private val prefs: JarvisPrefs) {

    interface Call {
        fun cancel()
    }

    /**
     * Test hook: post callbacks via this. Default posts to the main
     * thread; the coordinator's gate overrides it to run immediately on
     * the JVM. The default lambda constructs no Handler until invoked.
     */
    internal var mainPoster: (Runnable) -> Unit =
        { r -> Handler(Looper.getMainLooper()).post(r) }

    /**
     * One-shot generation. Callbacks run on the main thread (via
     * mainPoster). onResult's second param is true when the FALLBACK
     * model answered.
     */
    fun generate(
        prompt: String,
        onResult: (String, Boolean) -> Unit,
        onError: (String) -> Unit
    ): Call {
        val key = prefs.geminiKey.trim()
        if (key.isEmpty()) {
            // No key: plain error, ZERO network calls.
            mainPoster(Runnable { onError("chat_no_key") })
            return NoOpCall
        }
        val call = HttpCall()
        val worker = Thread {
            val model = prefs.model
            val result = attemptWithRetries(call, model, key, prompt, MAX_ATTEMPTS)
            when (result) {
                is AttemptResult.Success ->
                    postResult(call, onResult, result.text, false)
                is AttemptResult.Fatal ->
                    postError(call, onError, result.key)
                is AttemptResult.Offline ->
                    postError(call, onError, "chat_offline")
                is AttemptResult.Cancelled -> {
                    // Silent: a cancelled call never reports.
                }
                is AttemptResult.Retryable -> {
                    // Primary exhausted after retries: one fallback try.
                    if (model == FALLBACK_MODEL) {
                        postError(call, onError, "chat_server_busy")
                    } else {
                        val fallback = doAttempt(call, FALLBACK_MODEL, key, prompt)
                        when (fallback) {
                            is AttemptResult.Success ->
                                postResult(call, onResult, fallback.text, true)
                            is AttemptResult.Cancelled -> {
                                // Silent.
                            }
                            else ->
                                postError(call, onError, "chat_server_busy")
                        }
                    }
                }
            }
        }
        worker.start()
        return call
    }

    /** Tiny probe call for Test Key. Same no-key behaviour as generate. */
    fun testKey(onOk: () -> Unit, onError: (String) -> Unit): Call {
        val key = prefs.geminiKey.trim()
        if (key.isEmpty()) {
            mainPoster(Runnable { onError("chat_no_key") })
            return NoOpCall
        }
        val call = HttpCall()
        val worker = Thread {
            val result = attemptWithRetries(
                call, prefs.model, key, PROBE_PROMPT, TEST_KEY_ATTEMPTS
            )
            when (result) {
                is AttemptResult.Success ->
                    mainPoster(Runnable { if (!call.cancelled) onOk() })
                is AttemptResult.Fatal ->
                    postError(call, onError, result.key)
                is AttemptResult.Offline ->
                    postError(call, onError, "chat_offline")
                is AttemptResult.Cancelled -> {
                    // Silent.
                }
                is AttemptResult.Retryable ->
                    // Key is probably fine — report overload, not a key error.
                    postError(call, onError, "chat_server_busy")
            }
        }
        worker.start()
        return call
    }

    /**
     * Run up to [maxAttempts] attempts of [model], sleeping 2 s after the
     * first retryable failure and 4 s after the second. The cancelled flag
     * is checked before every attempt and during every sleep, so Stop
     * during backoff halts immediately.
     *
     * A transient network failure ([AttemptResult.Offline]) is retried
     * exactly like a retryable HTTP code — a DNS blip or a momentary
     * drop on a slow mobile connection must not fail the request on the
     * first hiccup. Only when the attempts are exhausted with the last
     * failure still offline does the caller report "offline".
     */
    private fun attemptWithRetries(
        call: HttpCall,
        model: String,
        key: String,
        prompt: String,
        maxAttempts: Int
    ): AttemptResult {
        var last: AttemptResult = AttemptResult.Retryable(-1)
        var attempt = 0
        while (attempt < maxAttempts) {
            if (call.cancelled) return AttemptResult.Cancelled
            val result = doAttempt(call, model, key, prompt)
            when (result) {
                is AttemptResult.Success,
                is AttemptResult.Fatal,
                is AttemptResult.Cancelled -> return result
                is AttemptResult.Retryable,
                is AttemptResult.Offline -> {
                    last = result
                    attempt++
                    if (attempt < maxAttempts) {
                        val delay = if (attempt == 1) RETRY_DELAY_1_MS else RETRY_DELAY_2_MS
                        if (!sleepCancellable(call, delay)) {
                            return AttemptResult.Cancelled
                        }
                    }
                }
            }
        }
        return last
    }

    /** One HTTP attempt. Never throws; every outcome is an AttemptResult. */
    private fun doAttempt(
        call: HttpCall,
        model: String,
        key: String,
        prompt: String
    ): AttemptResult {
        var connection: HttpURLConnection? = null
        try {
            val encodedModel = URLEncoder.encode(model, "UTF-8")
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL(
                baseUrl + "/v1beta/models/" + encodedModel +
                    ":generateContent?key=" + encodedKey
            )
            connection = url.openConnection() as HttpURLConnection
            call.connection = connection
            if (call.cancelled) {
                connection.disconnect()
                return AttemptResult.Cancelled
            }
            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            val body = buildRequestBody(prompt)
            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val status = connection.responseCode
            if (call.cancelled) return AttemptResult.Cancelled
            if (status == HttpURLConnection.HTTP_OK) {
                val responseText = BufferedReader(
                    InputStreamReader(connection.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                if (call.cancelled) return AttemptResult.Cancelled
                val text = parseResponse(responseText)
                return if (text.isNullOrBlank()) {
                    AttemptResult.Fatal("chat_empty_reply")
                } else {
                    AttemptResult.Success(text)
                }
            }
            if (status in RETRYABLE_CODES) return AttemptResult.Retryable(status)
            if (status == 400 || status == 401 || status == 403) {
                return AttemptResult.Fatal("chat_error")
            }
            return AttemptResult.Fatal("chat_api_error")
        } catch (e: IOException) {
            // disconnect() from cancel() surfaces here; a cancelled call
            // stays silent, never an error toast.
            if (call.cancelled) return AttemptResult.Cancelled
            return AttemptResult.Offline
        } catch (e: Exception) {
            if (call.cancelled) return AttemptResult.Cancelled
            return AttemptResult.Fatal("chat_api_error")
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                // Never throw from cleanup.
            }
        }
    }

    /**
     * Sleep in 100 ms chunks, checking the cancelled flag between chunks
     * so a Stop during backoff halts immediately. Returns false when the
     * sleep was cut short by a cancel (or an interrupt).
     */
    private fun sleepCancellable(call: HttpCall, millis: Long): Boolean {
        var remaining = millis
        while (remaining > 0) {
            if (call.cancelled) return false
            val chunk = minOf(100L, remaining)
            try {
                Thread.sleep(chunk)
            } catch (e: InterruptedException) {
                return false
            }
            remaining -= chunk
        }
        return !call.cancelled
    }

    private fun postResult(
        call: HttpCall,
        onResult: (String, Boolean) -> Unit,
        text: String,
        usedFallback: Boolean
    ) {
        mainPoster(Runnable { if (!call.cancelled) onResult(text, usedFallback) })
    }

    private fun postError(call: HttpCall, onError: (String) -> Unit, key: String) {
        mainPoster(Runnable { if (!call.cancelled) onError(key) })
    }

    private fun buildRequestBody(prompt: String): String {
        val full = SYSTEM_PROMPT + "\n\n" + prompt
        return "{\"contents\":[{\"parts\":[{\"text\":" + jsonEscape(full) + "}]}]}"
    }

    /**
     * Join ALL text parts of candidates[0].content.parts, skipping any
     * part marked `"thought":true`. Returns null when no usable text
     * exists or the JSON is malformed.
     */
    private fun parseResponse(json: String): String? {
        return try {
            val root = JSONObject(json)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content")
                ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text", "")
                if (text.isNotEmpty()) out.append(text)
            }
            val joined = out.toString().trim()
            if (joined.isEmpty()) null else joined
        } catch (e: Exception) {
            null
        }
    }

    /** Minimal JSON string escaper (quotes, backslash, control chars). */
    private fun jsonEscape(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\u0008' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> {
                    if (ch < ' ') {
                        out.append(String.format("\\u%04x", ch.code))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    /** Outcome of one HTTP attempt (or a whole retry sequence). */
    private sealed class AttemptResult {
        data class Success(val text: String) : AttemptResult()
        data class Retryable(val code: Int) : AttemptResult()
        data class Fatal(val key: String) : AttemptResult()
        object Offline : AttemptResult()
        object Cancelled : AttemptResult()
    }

    /** Cancel handle: disconnect() + a flag checked before any delivery. */
    private class HttpCall : Call {
        @Volatile
        var cancelled = false

        @Volatile
        var connection: HttpURLConnection? = null

        override fun cancel() {
            cancelled = true
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                // Never throw from cancel.
            }
        }
    }

    private object NoOpCall : Call {
        override fun cancel() {
            // Nothing was started (no-key path).
        }
    }

    companion object {
        const val SYSTEM_PROMPT: String =
            "You are TECHTONICPK AI, built by TECHTONICPK (Muhammad Riyaz). " +
                "Answer in the user's language (Urdu, English or Saraiki as they write). " +
                "Be direct, practical and honest; never invent facts. " +
                "Reply in plain natural prose with NO Markdown symbols at all: " +
                "no asterisks, no hash signs, no underscores, no backticks, no bullet stars. " +
                "Write continuous human-style speech as if speaking aloud; " +
                "use plain numbered lines (1. 2. 3.) only when a list is truly needed."

        const val FALLBACK_MODEL: String = "gemini-3.5-flash-lite"

        /**
         * Test hook: the coordinator's gate points this at a local mock
         * server. Production default is the real host.
         */
        @Volatile
        var baseUrl: String = "https://generativelanguage.googleapis.com"

        private val RETRYABLE_CODES = intArrayOf(429, 500, 502, 503, 504)
        private const val MAX_ATTEMPTS = 3
        private const val TEST_KEY_ATTEMPTS = 2
        private const val RETRY_DELAY_1_MS = 2000L
        private const val RETRY_DELAY_2_MS = 4000L
        private const val PROBE_PROMPT = "Reply with the single word OK."
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 60000
    }
}
