package pk.techtonic.jarvis.reader

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import pk.techtonic.jarvis.JarvisApp
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.data.JarvisPrefs
import pk.techtonic.jarvis.speech.TtsManager
import pk.techtonic.jarvis.ui.MainActivity
import pk.techtonic.jarvis.util.TextCleaner

/**
 * Foreground service that reads a book aloud — the core of the Kitab
 * vision: headphones on, screen off, app in the background, hands free
 * to keep working. Playback owns TTS here (never in the fragment) so it
 * survives the screen going off and the app leaving the foreground.
 *
 * Commands arrive as startService intents: ACTION_PLAY (extra
 * [EXTRA_BOOK_ID]), ACTION_PAUSE, ACTION_RESUME, ACTION_STOP.
 *
 * Behaviour:
 * - The full text is cleaned ONCE with TextCleaner.stripForSpeech, then
 *   split into ~900-char chunks at sentence boundaries (Urdu ۔ ؟ count
 *   as sentence ends). Chunks never split mid-word except as a last
 *   resort when a word itself is longer than the chunk.
 * - The bookmark is the char offset in the CLEANED text, saved to the
 *   books table after every chunk and on pause/stop/destroy — it
 *   survives service restarts because the cleaned text is re-derived
 *   deterministically on every play.
 * - Audio focus (TRANSIENT_MAY_DUCK): LOSS_TRANSIENT pauses and
 *   remembers, GAIN resumes only if it was playing, LOSS stops.
 * - ACTION_AUDIO_BECOMING_NOISY (headset/BT disconnect) pauses at once
 *   — the book must never blare on the speaker.
 * - A new PLAY always stops the current playback first; a generation
 *   counter guards every async callback (cleaning thread, TTS onDone)
 *   so a stale callback can never advance a newer session.
 */
class ReadingService : Service() {

    private lateinit var db: JarvisDb
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Current session. Written on the main thread, except the cleaning
    // worker which checks [generation] before touching anything.
    private var generation = 0
    private var utteranceToken = 0
    private var chunks: List<String> = emptyList()
    private var chunkStarts: List<Int> = emptyList()
    private var currentBookId: Long = -1L
    private var currentBookTitle: String = ""
    private var currentIndex = 0
    private var totalChars = 0
    private var hasFocus = false
    private var wasPlaying = false
    private var pausePending = false
    private var noisyRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (ReadingState.isPlaying) {
                    wasPlaying = true
                    pausePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPlaying) {
                    wasPlaying = false
                    resumePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlaying = false
                stopPlayback()
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (ReadingState.isPlaying) pausePlayback()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = JarvisDb(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                if (bookId != -1L) startBook(bookId)
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopPlayback()
            else -> {
                // Null intent (sticky restart after a kill): nothing to
                // resume — the bookmark is already saved, so just stop.
                if (chunks.isEmpty()) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCurrentSession(saveBookmark = true)
        ReadingState.isPlaying = false
        ReadingState.notifyListeners()
        if (noisyRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (e: Exception) {
                // Already unregistered: nothing to do.
            }
            noisyRegistered = false
        }
        super.onDestroy()
    }

    // ---- Playback ----

    private fun startBook(bookId: Long) {
        // Never two playbacks at once: stop the current one first.
        stopCurrentSession(saveBookmark = true)
        pausePending = false
        val gen = ++generation
        val book = try {
            db.bookById(bookId)
        } catch (e: Exception) {
            null
        }
        if (book == null) {
            stopSelf()
            return
        }
        currentBookId = book.id
        currentBookTitle = book.title
        // Go foreground NOW (5-second rule for startForegroundService).
        startForeground(NOTIF_ID, buildNotification(loading = true))
        ReadingState.bookId = book.id
        ReadingState.bookTitle = book.title
        ReadingState.isPlaying = false
        ReadingState.chunkIndex = 0
        ReadingState.chunkCount = 0
        ReadingState.position = book.position
        ReadingState.totalChars = 0
        ReadingState.currentChunkText = ""
        ReadingState.notifyListeners()
        // Cleaning + splitting a whole book is slow: worker thread.
        Thread {
            val cleaned = try {
                TextCleaner.stripForSpeech(book.text)
            } catch (e: Exception) {
                ""
            }
            if (gen != generation) return@Thread
            if (cleaned.isBlank()) {
                mainHandler.post {
                    if (gen != generation) return@post
                    stopPlayback()
                }
                return@Thread
            }
            val split = splitIntoChunks(cleaned, MAX_CHUNK_CHARS)
            mainHandler.post {
                if (gen != generation) return@post
                beginSpeaking(gen, split, cleaned.length, book.position)
            }
        }.start()
    }

    private fun beginSpeaking(
        gen: Int,
        split: List<Chunk>,
        total: Int,
        bookmark: Int
    ) {
        if (gen != generation || split.isEmpty()) {
            stopPlayback()
            return
        }
        chunks = split.map { it.text }
        chunkStarts = split.map { it.startOffset }
        totalChars = total
        // Resume at the saved bookmark (the chunk containing it). A
        // finished book (bookmark at the end) restarts from the start.
        currentIndex = if (bookmark >= total) 0 else indexForPosition(bookmark)
        ReadingState.chunkCount = chunks.size
        ReadingState.totalChars = total
        ReadingState.chunkIndex = currentIndex
        ReadingState.position = chunkStarts[currentIndex]
        ReadingState.currentChunkText = chunks[currentIndex]
        ReadingState.notifyListeners()
        if (pausePending) {
            // Paused while the book was still loading: stay paused with
            // the session ready to resume; do not speak.
            pausePending = false
            ReadingState.isPlaying = false
            updateNotification()
            return
        }
        if (!requestFocus()) {
            // Audio focus denied: do not start speaking.
            stopPlayback()
            return
        }
        if (!isVoiceEnabled()) {
            // Voice is off: TtsManager would answer onDone instantly and
            // the book would "finish" unheard. Stop instead of faking it.
            stopPlayback()
            return
        }
        speakCurrentChunk(gen)
    }

    private fun speakCurrentChunk(gen: Int) {
        if (gen != generation) return
        if (currentIndex < 0 || currentIndex >= chunks.size) {
            finishBook()
            return
        }
        val text = chunks[currentIndex]
        ReadingState.bookId = currentBookId
        ReadingState.bookTitle = currentBookTitle
        ReadingState.isPlaying = true
        ReadingState.chunkIndex = currentIndex
        ReadingState.chunkCount = chunks.size
        ReadingState.position = chunkStarts[currentIndex]
        ReadingState.totalChars = totalChars
        ReadingState.currentChunkText = text
        ReadingState.notifyListeners()
        saveBookmark(chunkStarts[currentIndex])
        updateNotification()
        val token = ++utteranceToken
        TtsManager.speak(
            text,
            onDone = {
                if (gen != generation || token != utteranceToken ||
                    !ReadingState.isPlaying
                ) {
                    return@speak
                }
                val next = currentIndex + 1
                if (next < chunks.size) {
                    saveBookmark(chunkStarts[next])
                    currentIndex = next
                    speakCurrentChunk(gen)
                } else {
                    saveBookmark(totalChars)
                    finishBook()
                }
            },
            onError = {
                if (gen != generation || token != utteranceToken) return@speak
                // TTS failed: pause with the bookmark saved, never spin
                // through the rest of the book.
                pausePlayback()
            }
        )
    }

    private fun pausePlayback() {
        if (chunks.isEmpty()) {
            // Book still loading: remember the pause for when it is ready.
            if (currentBookId != -1L) pausePending = true
            return
        }
        try {
            TtsManager.stop()
        } catch (e: Exception) {
            // TTS already stopped: nothing to do.
        }
        // Invalidate the in-flight utterance's callbacks so a late
        // onDone can never advance the chunk after a resume.
        utteranceToken++
        if (currentIndex in chunks.indices) {
            saveBookmark(chunkStarts[currentIndex])
        }
        ReadingState.isPlaying = false
        ReadingState.notifyListeners()
        // Stay foreground with a paused notification (Play = resume).
        updateNotification()
    }

    private fun resumePlayback() {
        pausePending = false
        if (chunks.isEmpty() || currentBookId == -1L) return
        if (ReadingState.isPlaying) return
        // Voice may have been switched off in Settings while paused:
        // stay paused (never fast-forward through the book unheard).
        if (!isVoiceEnabled()) return
        if (!requestFocus()) return
        // The bookmark already points at the current chunk: replay it.
        speakCurrentChunk(generation)
    }

    private fun stopPlayback(saveBookmark: Boolean = true) {
        stopCurrentSession(saveBookmark = saveBookmark)
        ReadingState.isPlaying = false
        ReadingState.notifyListeners()
        try {
            stopForeground(true)
        } catch (e: Exception) {
            // Not foreground: nothing to do.
        }
        stopSelf()
    }

    private fun finishBook() {
        ReadingState.isPlaying = false
        ReadingState.position = totalChars
        ReadingState.notifyListeners()
        // Bookmark already saved at the end (totalChars) by the caller:
        // do NOT let the stop re-save the last chunk's start, or the
        // next play would resume at the last chunk instead of restarting.
        stopPlayback(saveBookmark = false)
    }

    /**
     * Halt the current session: stop TTS, save the bookmark, drop audio
     * focus, clear the chunk list and invalidate pending callbacks.
     * Does NOT stop the service itself (callers decide that).
     */
    private fun stopCurrentSession(saveBookmark: Boolean) {
        generation++
        utteranceToken++
        try {
            TtsManager.stop()
        } catch (e: Exception) {
            // TTS already stopped: nothing to do.
        }
        if (saveBookmark && currentBookId != -1L &&
            chunks.isNotEmpty() && currentIndex in chunks.indices
        ) {
            saveBookmark(chunkStarts[currentIndex])
        }
        abandonFocus()
        chunks = emptyList()
        chunkStarts = emptyList()
        wasPlaying = false
        pausePending = false
        ReadingState.isPlaying = false
        ReadingState.chunkCount = 0
        ReadingState.chunkIndex = 0
        ReadingState.currentChunkText = ""
    }

    private fun saveBookmark(position: Int) {
        if (currentBookId == -1L) return
        val safe = position.coerceIn(0, totalChars)
        try {
            db.setBookPosition(currentBookId, safe)
        } catch (e: Exception) {
            // A failed write must never kill playback.
        }
        ReadingState.position = safe
    }

    private fun isVoiceEnabled(): Boolean {
        return try {
            JarvisPrefs(applicationContext).voiceEnabled
        } catch (e: Exception) {
            true
        }
    }

    // ---- Audio focus ----

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val result = audioManager.requestAudioFocus(
            focusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (!hasFocus) return
        try {
            audioManager.abandonAudioFocus(focusListener)
        } catch (e: Exception) {
            // Focus already gone: nothing to do.
        }
        hasFocus = false
    }

    // ---- Notification ----

    @Suppress("DEPRECATION")
    private fun buildNotification(loading: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, JarvisApp.CHANNEL_PLAYBACK)
        } else {
            Notification.Builder(this)
        }
        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(
                if (currentBookTitle.isNotBlank()) currentBookTitle
                else getString(R.string.app_name)
            )
            .setContentText(
                if (loading) {
                    getString(R.string.kitab_loading)
                } else {
                    getString(
                        R.string.kitab_chunk_of,
                        ReadingState.chunkIndex + 1,
                        ReadingState.chunkCount
                    )
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // Tap the notification: open the app on the Kitab tab. Single-top
        // so a tap reuses the running MainActivity (onNewIntent) instead
        // of stacking a new one on top.
        val openIntent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_KITAB)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        builder.setContentIntent(
            PendingIntent.getActivity(
                this,
                REQ_OPEN,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        // Play/Pause toggle + Stop, back to this service.
        val playing = ReadingState.isPlaying
        val toggleIcon = if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        val toggleLabel = if (playing) R.string.kitab_pause else R.string.kitab_play
        val toggleAction = if (playing) ACTION_PAUSE else ACTION_RESUME
        val toggleRequest = if (playing) REQ_PAUSE else REQ_RESUME
        builder.addAction(toggleIcon, getString(toggleLabel), actionPending(toggleAction, toggleRequest))
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.kitab_stop),
            actionPending(ACTION_STOP, REQ_STOP)
        )
        return builder.build()
    }

    private fun actionPending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ReadingService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID, buildNotification(loading = false))
        } catch (e: Exception) {
            // Notification failed: playback continues regardless.
        }
    }

    // ---- Chunking ----

    private data class Chunk(val text: String, val startOffset: Int)

    /**
     * Split cleaned text into ~[maxLen]-char chunks at sentence
     * boundaries (Latin . ! ? and Urdu ۔ ؟), never mid-word except when
     * a single word is longer than the chunk. Each chunk records its
     * start offset in the cleaned text, so the bookmark is exact.
     */
    private fun splitIntoChunks(text: String, maxLen: Int): List<Chunk> {
        val out = ArrayList<Chunk>()
        val len = text.length
        var start = 0
        while (start < len) {
            while (start < len && text[start].isWhitespace()) start++
            if (start >= len) break
            val end = minOf(start + maxLen, len)
            if (end >= len) {
                val piece = text.substring(start, len).trim()
                if (piece.isNotEmpty()) out.add(Chunk(piece, start))
                break
            }
            // Last sentence boundary in range (latest = longest chunk).
            var boundary = -1
            var j = end - 1
            while (j > start) {
                val c = text[j]
                if (isSentenceEnd(c) && (j + 1 >= len || text[j + 1].isWhitespace())) {
                    boundary = j + 1
                    break
                }
                j--
            }
            // Last space in range.
            var space = -1
            var k = end - 1
            while (k > start) {
                if (text[k].isWhitespace()) {
                    space = k
                    break
                }
                k--
            }
            val cut = when {
                boundary != -1 && boundary - start >= MIN_CHUNK_CHARS -> boundary
                space != -1 && space - start >= MIN_CHUNK_CHARS -> space
                boundary != -1 -> boundary
                space != -1 -> space
                else -> end
            }
            val piece = text.substring(start, cut).trim()
            if (piece.isNotEmpty()) out.add(Chunk(piece, start))
            start = cut
        }
        return out
    }

    private fun isSentenceEnd(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '۔' || c == '؟'

    /** Index of the chunk containing [position] (offsets are sorted). */
    private fun indexForPosition(position: Int): Int {
        if (chunkStarts.isEmpty()) return 0
        val idx = chunkStarts.binarySearch(position)
        return if (idx >= 0) idx else (-idx - 2).coerceAtLeast(0)
    }

    companion object {
        const val ACTION_PLAY = "pk.techtonic.jarvis.reader.ACTION_PLAY"
        const val ACTION_PAUSE = "pk.techtonic.jarvis.reader.ACTION_PAUSE"
        const val ACTION_RESUME = "pk.techtonic.jarvis.reader.ACTION_RESUME"
        const val ACTION_STOP = "pk.techtonic.jarvis.reader.ACTION_STOP"
        const val EXTRA_BOOK_ID = "book_id"

        private const val NOTIF_ID = 101
        private const val REQ_OPEN = 10
        private const val REQ_PAUSE = 11
        private const val REQ_RESUME = 12
        private const val REQ_STOP = 13
        private const val MAX_CHUNK_CHARS = 900
        private const val MIN_CHUNK_CHARS = 200
    }
}
