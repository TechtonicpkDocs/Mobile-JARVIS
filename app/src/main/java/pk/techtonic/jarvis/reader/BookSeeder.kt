package pk.techtonic.jarvis.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.data.JarvisPrefs

/**
 * Seeds the 9 pre-loaded books from assets/books/ into the books table,
 * exactly once. Runs on a background thread; never blocks the UI.
 *
 * Never re-seeds: once [JarvisPrefs.booksSeeded] is true this is a no-op,
 * so a deleted pre-loaded book stays deleted and user bookmarks survive.
 * Seeding is also idempotent per title (a title already in the library is
 * skipped), so a retry after a partial failure cannot duplicate a book.
 *
 * The manifest is parsed with a small regex — the file is a flat array of
 * {"file","id","title"} objects, and no JSON library is needed for it.
 */
object BookSeeder {

    @Volatile private var seeding = false
    private val pendingDone = ArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val OBJECT_RE = Regex("\\{[^}]*\\}")
    private val FILE_RE = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
    private val TITLE_RE = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * Seed if needed. [onDone] runs on the main thread when seeding
     * finishes (or immediately, on the main thread, when already seeded).
     * Safe to call again while a seed is running: the callback is queued
     * and runs when the in-flight seed finishes.
     */
    fun seedIfNeeded(
        context: Context,
        db: JarvisDb,
        prefs: JarvisPrefs,
        onDone: (() -> Unit)? = null
    ) {
        if (prefs.booksSeeded) {
            if (onDone != null) mainHandler.post(onDone)
            return
        }
        synchronized(this) {
            if (onDone != null) pendingDone.add(onDone)
            if (seeding) return
            seeding = true
        }
        val appContext = context.applicationContext
        Thread {
            try {
                val manifest = readAsset(appContext, "books/books.json")
                var allOk = manifest != null
                if (manifest != null) {
                    val existing = db.allBooks().map { it.title }.toHashSet()
                    for (obj in OBJECT_RE.findAll(manifest)) {
                        val file = FILE_RE.find(obj.value)?.groupValues?.getOrNull(1)
                            ?: continue
                        val title = TITLE_RE.find(obj.value)?.groupValues?.getOrNull(1)
                            ?: continue
                        if (existing.contains(title)) continue
                        val text = readAsset(appContext, "books/$file")
                        if (text == null) {
                            allOk = false
                            continue
                        }
                        db.addBook(title, text)
                        existing.add(title)
                    }
                }
                // Only mark seeded when every book landed; otherwise the
                // next open retries (existing titles are skipped, so no
                // duplicates can appear).
                if (allOk) prefs.booksSeeded = true
            } catch (e: Exception) {
                // Leave booksSeeded false so the next open retries.
            } finally {
                seeding = false
                val callbacks = synchronized(this) {
                    pendingDone.toList().also { pendingDone.clear() }
                }
                for (callback in callbacks) mainHandler.post(callback)
            }
        }.start()
    }

    private fun readAsset(context: Context, path: String): String? {
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
