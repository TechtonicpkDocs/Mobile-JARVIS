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
 * - chat  <- jvs_chat   (role: "user" | "ai", text, ts)
 * - notes <- jvs_notes  (text, date "YYYY-MM-DD" or "", time "HH:MM" or "",
 *   pinned 0/1, created). date+time together drive reminder alerts.
 * - milk  <- jvs_milk   (date "YYYY-MM-DD", litres_milli, rate_paisa,
 *   total_paisa — integer-paisa math, same as HTML)
 * - books (title, text, position = char offset bookmark, updated)
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
                "updated INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE tasbih_daily (" +
                "date TEXT PRIMARY KEY, " +
                "count INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1: no migrations yet. Future versions add ALTER TABLE steps here.
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
        }
        return writableDatabase.insert("books", null, cv)
    }

    /** Most recently read first. */
    fun allBooks(): List<BookRow> {
        val out = ArrayList<BookRow>()
        readableDatabase.query(
            "books", null, null, null, null, null, "updated DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    BookRow(
                        id = c.getLong(0),
                        title = c.getString(1),
                        text = c.getString(2),
                        position = c.getInt(3),
                        updated = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    fun bookById(id: Long): BookRow? {
        readableDatabase.query(
            "books", null, "id=?", arrayOf(id.toString()), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return BookRow(
                id = c.getLong(0),
                title = c.getString(1),
                text = c.getString(2),
                position = c.getInt(3),
                updated = c.getLong(4)
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

    fun deleteBook(id: Long) {
        writableDatabase.delete("books", "id=?", arrayOf(id.toString()))
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
        const val DB_VERSION = 1
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
    val text: String,
    val position: Int,
    val updated: Long
)
