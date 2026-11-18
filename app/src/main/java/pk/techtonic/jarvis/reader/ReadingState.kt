package pk.techtonic.jarvis.reader

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live playback state shared between [ReadingService] (writer) and
 * [pk.techtonic.jarvis.ui.BookReaderFragment] (reader).
 *
 * All fields are volatile: the service writes them from the main thread
 * and from TTS callbacks, the fragment reads them on the main thread.
 * [chunkIndex] is 0-based; display it as chunkIndex + 1.
 * [position] / [totalChars] are char offsets into the CLEANED text
 * (TextCleaner.stripForSpeech output) — the same text the service
 * re-derives on every play, so the bookmark stays exact across restarts.
 *
 * NOTE: the notify method is called [notifyListeners], not `notify()` —
 * java.lang.Object.notify() is final and cannot be overridden.
 */
object ReadingState {

    /** Row id of the current book in the books table (-1 = none). */
    @Volatile var bookId: Long = -1L

    @Volatile var bookTitle: String = ""

    @Volatile var isPlaying: Boolean = false

    /** 0-based index of the chunk now playing (or paused on). */
    @Volatile var chunkIndex: Int = 0

    /** Total chunks in the current book (0 = no live session). */
    @Volatile var chunkCount: Int = 0

    /** Char offset bookmark in the cleaned text. */
    @Volatile var position: Int = 0

    /** Length of the cleaned text. */
    @Volatile var totalChars: Int = 0

    /** Text of the chunk now playing (cleaned, for display). */
    @Volatile var currentChunkText: String = ""

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Notify all listeners on the main thread. */
    fun notifyListeners() {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener()
                } catch (e: Exception) {
                    // One bad listener must not break the rest.
                }
            }
        }
    }
}
