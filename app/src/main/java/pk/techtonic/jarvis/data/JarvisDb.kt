package pk.techtonic.jarvis.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single SQLite database for the whole app (his storage standard).
 *
 * Tables mirror the HTML v1.0.0 localStorage stores, plus the native
 * additions (book library, tasbih daily totals):
 * - chat  <- jvs_chat   (role: "user" | "ai" | "error", text, ts)
 * - notes <- jvs_notes  (text, date "YYYY-MM-DD" or "", time "HH:MM" or "",
 *   pinned 0/1, created). date+time together drive reminder alerts.
 * - milk  <- jvs_milk   (date "YYYY-MM-DD", litres_milli, rate_paisa,
 *   total_paisa — integer-paisa math, same as HTML)
 * - books (title, text, position = char offset bookmark, updated,
 *   words = stored word count — v1.0.2: the library list must never load
 *   full book texts, see [allBooks])
 * - tasbih_daily (date "YYYY-MM-DD", count) — one row per day
 */
class JarvisDb(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE chat (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role TEXT NOT NULL, " +
                "text TEXT NOT NULL, " +
                "ts INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE notes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "text TEXT NOT NULL, " +
                "date TEXT NOT NULL DEFAULT '', " +
                "time TEXT NOT NULL DEFAULT '', " +
                "pinned INTEGER NOT NULL DEFAULT 0, " +
                "created INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE milk (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "date TEXT NOT NULL, " +
                "litres_milli INTEGER NOT NULL, " +
                "rate_paisa INTEGER NOT NULL, " +
                "total_paisa INTEGER NOT NULL, " +
                "ts INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE books (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "text TEXT NOT NULL, " +
                "position INTEGER NOT NULL DEFAULT 0, " +
                "updated INTEGER NOT NULL, " +
                "words INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE tasbih_daily (" +
                "date TEXT PRIMARY KEY, " +
                "count INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2: stored word count for the Kitab library. The library
        // list must never load full book texts (CursorWindow 2 MB limit),
        // so the count is stored per book and backfilled here, one row at
        // a time — a single book row is <= ~1.3 MB and fits the window.
        // `updated` is deliberately NOT touched: the library order stays.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE books ADD COLUMN words INTEGER NOT NULL DEFAULT 0")
            val ids = ArrayList<Long>()
            db.rawQuery("SELECT id FROM books", null).use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
            for (id in ids) {
                var text: String? = null
                db.rawQuery(
                    "SELECT text FROM books WHERE id=?", arrayOf(id.toString())
                ).use { c ->
                    if (c.moveToFirst()) text = c.getString(0)
                }
                val cv = ContentValues().apply { put("words", countWords(text ?: "")) }
                db.update("books", cv, "id=?", arrayOf(id.toString()))
            }
        }
    }

    // ---- Chat ----

    fun addChat(role: String, text: String): Long {
        val cv = ContentValues().apply {
            put("role", role)
            put("text", text)
            put("ts", System.currentTimeMillis())
        }
        return writableDatabase.insert("chat", null, cv)
    }

    /** All chat rows, oldest first. */
    fun allChat(): List<ChatRow> {
        val out = ArrayList<ChatRow>()
        readableDatabase.query(
            "chat", null, null, null, null, null, "ts ASC, id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChatRow(
                        id = c.getLong(0),
                        role = c.getString(1),
                        text = c.getString(2),
                        ts = c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    fun clearChat() {
        writableDatabase.delete("chat", null, null)
    }

    // ---- Notes ----

    fun addNote(text: String, date: String, time: String = ""): Long {
        val cv = ContentValues().apply {
            put("text", text)
            put("date", date)
            put("time", time)
            put("pinned", 0)
            put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("notes", null, cv)
    }

    /** Pinned first, then newest first (same order as HTML). */
    fun allNotes(): List<NoteRow> {
        val out = ArrayList<NoteRow>()
        readableDatabase.query(
            "notes", null, null, null, null, null, "pinned DESC, created DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    NoteRow(
                        id = c.getLong(0),
                        text = c.getString(1),
                        date = c.getString(2),
                        time = c.getString(3),
                        pinned = c.getInt(4) == 1,
                        created = c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    fun setNotePinned(id: Long, pinned: Boolean) {
        val cv = ContentValues().apply { put("pinned", if (pinned) 1 else 0) }
        writableDatabase.update("notes", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteNote(id: Long) {
        writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
    }

    fun clearNotes() {
        writableDatabase.delete("notes", null, null)
    }

    // ---- Milk entries ----

    fun addMilk(date: String, litresMilli: Long, ratePaisa: Long, totalPaisa: Long): Long {
        val cv = ContentValues().apply {
            put("date", date)
            put("litres_milli", litresMilli)
            put("rate_paisa", ratePaisa)
            put("total_paisa", totalPaisa)
            put("ts", System.currentTimeMillis())
        }
        return writableDatabase.insert("milk", null, cv)
    }

    /** Newest first. */
    fun allMilk(): List<MilkRow> {
        val out = ArrayList<MilkRow>()
        readableDatabase.query(
            "milk", null, null, null, null, null, "ts DESC, id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    MilkRow(
                        id = c.getLong(0),
                        date = c.getString(1),
                        litresMilli = c.getLong(2),
                        ratePaisa = c.getLong(3),
                        totalPaisa = c.getLong(4),
                        ts = c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    fun clearMilk() {
        writableDatabase.delete("milk", null, null)
    }

    // ---- Books (Kitab library) ----

    fun addBook(title: String, text: String): Long {
        val cv = ContentValues().apply {
            put("title", title)
            put("text", text)
            put("position", 0)
            put("updated", System.currentTimeMillis())
            put("words", countWords(text))
        }
        return writableDatabase.insert("books", null, cv)
    }

    /**
     * Library list: metadata ONLY (id, title, position, updated, words,
     * LENGTH(text)). Full book texts are NEVER loaded here — the 10
     * pre-loaded books total 3.8 MB, past Android's 2 MB CursorWindow
     * limit, so a full-text query silently dropped rows from the list
     * (v1.0.1 defect: books "missing" from the library). Returned rows
     * have text = "" — use [bookById] for full text, [bookPreview] for
     * the reader's first-page preview.
     */
    fun allBooks(): List<BookRow> {
        val out = ArrayList<BookRow>()
        readableDatabase.query(
            "books",
            arrayOf("id", "title", "position", "updated", "words", "LENGTH(text) AS text_len"),
            null, null, null, null, "updated DESC"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iTitle = c.getColumnIndexOrThrow("title")
            val iPos = c.getColumnIndexOrThrow("position")
            val iUpd = c.getColumnIndexOrThrow("updated")
            val iWords = c.getColumnIndexOrThrow("words")
            val iLen = c.getColumnIndexOrThrow("text_len")
            while (c.moveToNext()) {
                out.add(
                    BookRow(
                        id = c.getLong(iId),
                        title = c.getString(iTitle),
                        text = "",
                        position = c.getInt(iPos),
                        updated = c.getLong(iUpd),
                        words = c.getInt(iWords),
                        textLength = c.getInt(iLen)
                    )
                )
            }
        }
        return out
    }

    /** All book titles (no text) — the seeder's existing check and backup import dedup. */
    fun allBookTitles(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query(
            "books", arrayOf("title"), null, null, null, null, null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /** All book ids, most recently read first — backup export loads each text by id. */
    fun allBookIds(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.query(
            "books", arrayOf("id"), null, null, null, null, "updated DESC"
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /** First [maxChars] chars of one book (SUBSTR — never loads the whole text). */
    fun bookPreview(id: Long, maxChars: Int): String {
        readableDatabase.rawQuery(
            "SELECT SUBSTR(text, 1, ?) FROM books WHERE id=?",
            arrayOf(maxChars.toString(), id.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) ?: "" else ""
        }
    }

    /**
     * One-time dedup (v1.0.2 upgrade): v1.0.1's seeder check read the
     * library through the 2 MB CursorWindow and could re-insert titles
     * it could not see. For each duplicated title keep the row with the
     * furthest bookmark (tie: latest updated), delete the rest.
     * Returns the number of rows deleted (0 when already clean).
     */
    fun deduplicateBooks(): Int {
        var deleted = 0
        val dupTitles = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT title FROM books GROUP BY title HAVING COUNT(*) > 1", null
        ).use { c ->
            while (c.moveToNext()) dupTitles.add(c.getString(0))
        }
        for (title in dupTitles) {
            val ids = ArrayList<Long>()
            readableDatabase.query(
                "books", arrayOf("id"), "title=?", arrayOf(title), null, null,
                "position DESC, updated DESC, id DESC"
            ).use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
            // Keep the first (furthest bookmark); delete the rest.
            for (i in 1 until ids.size) {
                deleted += deleteBook(ids[i])
            }
        }
        return deleted
    }

    fun bookById(id: Long): BookRow? {
        readableDatabase.query(
            "books",
            arrayOf("id", "title", "text", "position", "updated", "words", "LENGTH(text) AS text_len"),
            "id=?", arrayOf(id.toString()), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return BookRow(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                title = c.getString(c.getColumnIndexOrThrow("title")),
                text = c.getString(c.getColumnIndexOrThrow("text")),
                position = c.getInt(c.getColumnIndexOrThrow("position")),
                updated = c.getLong(c.getColumnIndexOrThrow("updated")),
                words = c.getInt(c.getColumnIndexOrThrow("words")),
                textLength = c.getInt(c.getColumnIndexOrThrow("text_len"))
            )
        }
    }

    /** Save the reading bookmark (char offset) + touch updated. */
    fun setBookPosition(id: Long, position: Int) {
        val cv = ContentValues().apply {
            put("position", position)
            put("updated", System.currentTimeMillis())
        }
        writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
    }

    /** Deletes one book; returns the number of rows deleted. */
    fun deleteBook(id: Long): Int {
        return writableDatabase.delete("books", "id=?", arrayOf(id.toString()))
    }

    fun clearBooks() {
        writableDatabase.delete("books", null, null)
    }

    // ---- Tasbih daily totals ----

    fun tasbihToday(date: String): Int {
        readableDatabase.query(
            "tasbih_daily", arrayOf("count"), "date=?",
            arrayOf(date), null, null, null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun setTasbihToday(date: String, count: Int) {
        val cv = ContentValues().apply {
            put("date", date)
            put("count", count)
        }
        writableDatabase.insertWithOnConflict(
            "tasbih_daily", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    companion object {
        const val DB_NAME = "jarvis.db"
        const val DB_VERSION = 2

        /** Whitespace-delimited word count (the rule the library used in v1.0.1). */
        fun countWords(text: String): Int {
            var count = 0
            var inWord = false
            for (c in text) {
                if (c.isWhitespace()) {
                    inWord = false
                } else if (!inWord) {
                    inWord = true
                    count++
                }
            }
            return count
        }
    }
}

data class ChatRow(val id: Long, val role: String, val text: String, val ts: Long)

data class NoteRow(
    val id: Long,
    val text: String,
    val date: String,
    val time: String,
    val pinned: Boolean,
    val created: Long
)

data class MilkRow(
    val id: Long,
    val date: String,
    val litresMilli: Long,
    val ratePaisa: Long,
    val totalPaisa: Long,
    val ts: Long
)

data class BookRow(
    val id: Long,
    val title: String,
    /** Full text ONLY from [JarvisDb.bookById]; "" on [JarvisDb.allBooks] metadata rows. */
    val text: String,
    val position: Int,
    val updated: Long,
    /** Stored word count (set at insert; backfilled on the v1→v2 upgrade). */
    val words: Int = 0,
    /** LENGTH(text) in chars — the progress denominator (position is a char offset). */
    val textLength: Int = 0
)
