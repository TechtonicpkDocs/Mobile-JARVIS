package pk.techtonic.jarvis.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.data.JarvisPrefs

/**
 * Seeds the 9 pre-loaded books from assets/books/ into the books table.
 * Runs on a background thread; never blocks the UI.
 *
 * Seeding is VERSIONED (v1.0.1): [CURRENT_SEED_VERSION] is the seed
 * version this build requires. When the stored version is lower, the
 * seed runs again — but it is idempotent per title (a title already in
 * the library is skipped), so a re-seed only restores MISSING pre-loaded
 * titles. Existing books are never re-inserted or touched, so bookmarks
 * and user-added books survive untouched. A deleted pre-loaded book
 * therefore comes back on the next upgrade that bumps the version;
 * within one version a deliberate delete stays deleted.
 *
 * v1.0.2: the existing-titles check reads titles ONLY ([JarvisDb.allBookTitles])
 * — v1.0.1 read the whole library through the 2 MB CursorWindow, which
 * could not see every title and so re-inserted duplicates. Each v3 seed
 * run first removes those duplicates ([JarvisDb.deduplicateBooks]).
 *
 * The manifest is parsed with a small regex — the file is a flat array of
 * {"file","id","title"} objects, and no JSON library is needed for it.
 */
object BookSeeder {

    /** Seed version this build requires (v1.0.0 = 1, v1.0.1 = 2, v1.0.2 = 3). */
    const val CURRENT_SEED_VERSION = 3

    @Volatile private var seeding = false
    private val pendingDone = ArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val OBJECT_RE = Regex("\\{[^}]*\\}")
    private val FILE_RE = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
    private val TITLE_RE = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * Seed if needed: runs whenever the stored seed version is below
     * [CURRENT_SEED_VERSION] (fresh install, or an upgrade that bumped
     * the version). [onDone] runs on the main thread when seeding
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
        if (prefs.booksSeedVersion >= CURRENT_SEED_VERSION) {
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
                // v1.0.2: clear any duplicates the v1.0.1 seeder created
                // (its existing-check could not see rows past the 2 MB
                // CursorWindow, so it re-inserted titles that were
                // already there). Idempotent: later runs find nothing.
                try {
                    db.deduplicateBooks()
                } catch (e: Exception) {
                    // Dedup failure must never block seeding.
                }
                val manifest = readAsset(appContext, "books/books.json")
                var allOk = manifest != null
                if (manifest != null) {
                    val existing = db.allBookTitles().toHashSet()
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
                if (allOk) {
                    prefs.booksSeedVersion = CURRENT_SEED_VERSION
                    prefs.booksSeeded = true
                }
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
