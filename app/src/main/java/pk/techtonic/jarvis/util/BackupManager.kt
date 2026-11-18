package pk.techtonic.jarvis.util

import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.data.JarvisPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * Backup export / import (W4).
 *
 * Native export shape:
 *   {"version":"1.0.0","exportedAt":ISO8601,
 *    "notes":[{"text","date","time","pinned","created"}],
 *    "milk":[{"date","litres","rate","amount"}],
 *    "chat":[{"role","text","ts"}],
 *    "books":[{"title","text","position","updated"}],
 *    "settings":{"apiKey","model","language","voiceEnabled",
 *               "ttsVoice","ttsRate","ttsPitch"}}
 *
 * Notes carry the native "time" field too (reminder time) so a restored
 * note keeps its alert; readers that do not know the field ignore it.
 * Books are USER-ADDED books only — a book whose title matches a
 * pre-loaded asset title (assets/books/books.json) is skipped on export,
 * because pre-loaded books re-seed from assets.
 *
 * Import accepts the native shape AND the HTML v1.0.0 shape
 * {"version","exportedAt","data":{"jvs_notes":"<json string>", ...}}.
 * HTML notes are {id,text,date,pinned,created}; HTML milk is
 * {date,litres,rate,amount}; HTML chat is {role,text,ts} with the AI
 * role spelled "assistant" (normalised to "ai" here); HTML settings are
 * {apiKey,model,lang,voiceOn,ttsVoice} — the "lang"/"voiceOn" spellings
 * are accepted alongside "language"/"voiceEnabled".
 *
 * Import validates EVERYTHING before writing anything, then writes the
 * whole restore inside one SQLite transaction: a bad file touches
 * nothing, and a failed write rolls back instead of half-importing.
 * On success notes/milk/chat are replaced (clear + re-insert), settings
 * are applied, and books are MERGED (a title already in the library is
 * skipped, so a re-import never duplicates a book).
 */
object BackupManager {

    const val VERSION = "1.0.0"

    /**
     * Exact titles of the 9 pre-loaded books (assets/books/books.json).
     * These are never exported — they re-seed from assets on first run.
     */
    private val PRELOADED_TITLES = setOf(
        "علماء کا امتحان",
        "ٹیکنالوجی اور سافٹ ویئر کا مکمل انسائیکلوپیڈیا",
        "آخری دس سورتیں — درست تلفظ، تجوید اور لفظ کی گہرائی",
        "اذان اور نماز کی درستگی",
        "علماء کی محفل",
        "ایمان اور محنت",
        "اعلیٰ حضرت اور پروفیسر ضیاء الدین",
        "اے آئی کیا ہے؟",
        "بغیر انٹرنیٹ کے ایپ کیسے بنائیں؟"
    )

    fun buildExportJson(db: JarvisDb, prefs: JarvisPrefs): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exportedAt", isoNow())

        val notes = JSONArray()
        for (row in db.allNotes()) {
            val o = JSONObject()
            o.put("text", row.text)
            o.put("date", row.date)
            o.put("time", row.time)
            o.put("pinned", row.pinned)
            o.put("created", row.created)
            notes.put(o)
        }
        root.put("notes", notes)

        val milk = JSONArray()
        for (row in db.allMilk()) {
            val o = JSONObject()
            o.put("date", row.date)
            o.put("litres", row.litresMilli / 1000.0)
            o.put("rate", row.ratePaisa / 100.0)
            o.put("amount", row.totalPaisa / 100.0)
            milk.put(o)
        }
        root.put("milk", milk)

        val chat = JSONArray()
        for (row in db.allChat()) {
            val o = JSONObject()
            o.put("role", row.role)
            o.put("text", row.text)
            o.put("ts", row.ts)
            chat.put(o)
        }
        root.put("chat", chat)

        val books = JSONArray()
        for (row in db.allBooks()) {
            if (PRELOADED_TITLES.contains(row.title.trim())) continue
            val o = JSONObject()
            o.put("title", row.title)
            o.put("text", row.text)
            o.put("position", row.position)
            o.put("updated", row.updated)
            books.put(o)
        }
        root.put("books", books)

        val settings = JSONObject()
        settings.put("apiKey", prefs.geminiKey)
        settings.put("model", prefs.model)
        settings.put("language", prefs.language)
        settings.put("voiceEnabled", prefs.voiceEnabled)
        settings.put("ttsVoice", prefs.ttsVoiceName)
        settings.put("ttsRate", prefs.ttsRate.toDouble())
        settings.put("ttsPitch", prefs.ttsPitch.toDouble())
        root.put("settings", settings)

        return root.toString(2)
    }

    fun importJson(json: String, db: JarvisDb, prefs: JarvisPrefs): Boolean {
        val parsed = try {
            parse(json)
        } catch (e: JSONException) {
            return false
        } catch (e: Exception) {
            return false
        } ?: return false

        val wdb = db.writableDatabase
        wdb.beginTransaction()
        try {
            wdb.delete("notes", null, null)
            wdb.delete("milk", null, null)
            wdb.delete("chat", null, null)

            for (note in parsed.notes) {
                val cv = ContentValues().apply {
                    put("text", note.text)
                    put("date", note.date)
                    put("time", note.time)
                    put("pinned", if (note.pinned) 1 else 0)
                    put("created", note.created)
                }
                wdb.insert("notes", null, cv)
            }
            val now = System.currentTimeMillis()
            for (entry in parsed.milk) {
                val cv = ContentValues().apply {
                    put("date", entry.date)
                    put("litres_milli", entry.litresMilli)
                    put("rate_paisa", entry.ratePaisa)
                    put("total_paisa", entry.totalPaisa)
                    put("ts", now)
                }
                wdb.insert("milk", null, cv)
            }
            for (msg in parsed.chat) {
                val cv = ContentValues().apply {
                    put("role", msg.role)
                    put("text", msg.text)
                    put("ts", msg.ts)
                }
                wdb.insert("chat", null, cv)
            }
            // Books merge: skip any title already in the library.
            val existingTitles = HashSet<String>()
            for (book in db.allBooks()) existingTitles.add(book.title.trim())
            for (book in parsed.books) {
                if (existingTitles.contains(book.title.trim())) continue
                val cv = ContentValues().apply {
                    put("title", book.title)
                    put("text", book.text)
                    put("position", book.position)
                    put("updated", book.updated)
                }
                wdb.insert("books", null, cv)
                existingTitles.add(book.title.trim())
            }
            wdb.setTransactionSuccessful()
        } catch (e: Exception) {
            return false
        } finally {
            wdb.endTransaction()
        }

        // Settings only after the database restore committed.
        parsed.settings?.applyTo(prefs)
        return true
    }

    // ---- Parsing + validation (nothing is written from here) ----

    private data class ParsedNote(
        val text: String,
        val date: String,
        val time: String,
        val pinned: Boolean,
        val created: Long
    )

    private data class ParsedMilk(
        val date: String,
        val litresMilli: Long,
        val ratePaisa: Long,
        val totalPaisa: Long
    )

    private data class ParsedChat(val role: String, val text: String, val ts: Long)

    private data class ParsedBook(
        val title: String,
        val text: String,
        val position: Int,
        val updated: Long
    )

    private data class ParsedSettings(
        val apiKey: String?,
        val model: String?,
        val language: String?,
        val voiceEnabled: Boolean?,
        val ttsVoice: String?,
        val ttsRate: Float?,
        val ttsPitch: Float?
    ) {
        fun applyTo(prefs: JarvisPrefs) {
            if (apiKey != null) prefs.geminiKey = apiKey
            if (model != null && model.isNotBlank()) prefs.model = model
            if (language == "ur" || language == "en") prefs.language = language
            if (voiceEnabled != null) prefs.voiceEnabled = voiceEnabled
            if (ttsVoice != null) prefs.ttsVoiceName = ttsVoice
            if (ttsRate != null) prefs.ttsRate = ttsRate.coerceIn(0.5f, 2.0f)
            if (ttsPitch != null) prefs.ttsPitch = ttsPitch.coerceIn(0.5f, 2.0f)
        }
    }

    private data class ParsedBackup(
        val notes: List<ParsedNote>,
        val milk: List<ParsedMilk>,
        val chat: List<ParsedChat>,
        val books: List<ParsedBook>,
        val settings: ParsedSettings?
    )

    /**
     * Parses + fully validates a backup file. Returns null (or throws
     * JSONException) for anything malformed — the caller writes nothing.
     */
    @Throws(JSONException::class)
    private fun parse(json: String): ParsedBackup? {
        val root = JSONObject(json)
        val version = root.optString("version", "")
        if (version.isBlank()) return null

        if (root.has("data") && root.get("data") is JSONObject) {
            return parseHtmlShape(root.getJSONObject("data"))
        }
        return parseNativeShape(root)
    }

    @Throws(JSONException::class)
    private fun parseNativeShape(root: JSONObject): ParsedBackup? {
        val hasAny = root.has("notes") || root.has("milk") || root.has("chat") ||
            root.has("books") || root.has("settings")
        if (!hasAny) return null
        val notes = if (root.has("notes")) parseNotes(reqArray(root, "notes")) else emptyList()
        val milk = if (root.has("milk")) parseMilk(reqArray(root, "milk")) else emptyList()
        val chat = if (root.has("chat")) parseChat(reqArray(root, "chat")) else emptyList()
        val books = if (root.has("books")) parseBooks(reqArray(root, "books")) else emptyList()
        val settings = if (root.has("settings")) {
            parseSettings(reqObject(root, "settings"))
        } else {
            null
        }
        return ParsedBackup(notes, milk, chat, books, settings)
    }

    @Throws(JSONException::class)
    private fun parseHtmlShape(data: JSONObject): ParsedBackup? {
        val notes = parseNotes(optHtmlArray(data, "jvs_notes"))
        val milk = parseMilk(optHtmlArray(data, "jvs_milk"))
        val chat = parseChat(optHtmlArray(data, "jvs_chat"))
        val settingsObj = optHtmlObject(data, "jvs_settings")
        val settings = if (settingsObj != null) parseSettings(settingsObj) else null
        // HTML backups carry no books.
        return ParsedBackup(notes, milk, chat, emptyList(), settings)
    }

    /**
     * An HTML data value is a JSON-encoded STRING (e.g. "[{...}]"); a
     * hand-edited file may hold the array directly — accept both.
     * A missing key means an empty list (HTML exports only keys that exist).
     */
    @Throws(JSONException::class)
    private fun optHtmlArray(data: JSONObject, key: String): JSONArray {
        if (!data.has(key) || data.isNull(key)) return JSONArray()
        val raw = data.get(key)
        return when (raw) {
            is JSONArray -> raw
            is String -> JSONArray(raw)
            else -> throw JSONException("bad $key")
        }
    }

    @Throws(JSONException::class)
    private fun optHtmlObject(data: JSONObject, key: String): JSONObject? {
        if (!data.has(key) || data.isNull(key)) return null
        val raw = data.get(key)
        return when (raw) {
            is JSONObject -> raw
            is String -> JSONObject(raw)
            else -> throw JSONException("bad $key")
        }
    }

    @Throws(JSONException::class)
    private fun parseNotes(array: JSONArray): List<ParsedNote> {
        val out = ArrayList<ParsedNote>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val text = reqString(o, "text")
            val date = optString(o, "date", "")
            val time = optString(o, "time", "")
            val pinned = if (o.has("pinned") && !o.isNull("pinned")) reqBool(o, "pinned") else false
            val created = reqLong(o, "created")
            if (created < 0) throw JSONException("bad created")
            out.add(ParsedNote(text, date, time, pinned, created))
        }
        return out
    }

    @Throws(JSONException::class)
    private fun parseMilk(array: JSONArray): List<ParsedMilk> {
        val out = ArrayList<ParsedMilk>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val date = reqString(o, "date")
            val litres = reqFiniteDouble(o, "litres")
            val rate = reqFiniteDouble(o, "rate")
            val amount = reqFiniteDouble(o, "amount")
            if (litres < 0 || rate < 0 || amount < 0) throw JSONException("negative milk")
            out.add(
                ParsedMilk(
                    date,
                    (litres * 1000.0).roundToLong(),
                    (rate * 100.0).roundToLong(),
                    (amount * 100.0).roundToLong()
                )
            )
        }
        return out
    }

    @Throws(JSONException::class)
    private fun parseChat(array: JSONArray): List<ParsedChat> {
        val out = ArrayList<ParsedChat>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val roleRaw = reqString(o, "role")
            // HTML stores the AI role as "assistant"; native uses "ai".
            val role = when (roleRaw) {
                "user" -> "user"
                "ai", "assistant" -> "ai"
                // v1.0.1: failure rows stored in the chat history.
                "error" -> "error"
                else -> throw JSONException("bad role")
            }
            val text = reqString(o, "text")
            val ts = reqLong(o, "ts")
            if (ts < 0) throw JSONException("bad ts")
            out.add(ParsedChat(role, text, ts))
        }
        return out
    }

    @Throws(JSONException::class)
    private fun parseBooks(array: JSONArray): List<ParsedBook> {
        val out = ArrayList<ParsedBook>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val title = reqString(o, "title")
            if (title.isBlank()) throw JSONException("blank book title")
            val text = reqString(o, "text")
            val position = if (o.has("position") && !o.isNull("position")) {
                reqLong(o, "position").toInt()
            } else {
                0
            }
            if (position < 0) throw JSONException("bad position")
            val updated = if (o.has("updated") && !o.isNull("updated")) {
                reqLong(o, "updated")
            } else {
                System.currentTimeMillis()
            }
            if (updated < 0) throw JSONException("bad updated")
            out.add(ParsedBook(title, text, position, updated))
        }
        return out
    }

    @Throws(JSONException::class)
    private fun parseSettings(o: JSONObject): ParsedSettings {
        val apiKey = if (o.has("apiKey") && !o.isNull("apiKey")) reqString(o, "apiKey") else null
        val model = if (o.has("model") && !o.isNull("model")) reqString(o, "model") else null
        // HTML spells these "lang" / "voiceOn"; native "language" / "voiceEnabled".
        val language = when {
            o.has("language") && !o.isNull("language") -> reqString(o, "language")
            o.has("lang") && !o.isNull("lang") -> reqString(o, "lang")
            else -> null
        }
        val voiceEnabled = when {
            o.has("voiceEnabled") && !o.isNull("voiceEnabled") -> reqBool(o, "voiceEnabled")
            o.has("voiceOn") && !o.isNull("voiceOn") -> reqBool(o, "voiceOn")
            else -> null
        }
        val ttsVoice = if (o.has("ttsVoice") && !o.isNull("ttsVoice")) reqString(o, "ttsVoice") else null
        val ttsRate = if (o.has("ttsRate") && !o.isNull("ttsRate")) {
            reqFiniteDouble(o, "ttsRate").toFloat()
        } else {
            null
        }
        val ttsPitch = if (o.has("ttsPitch") && !o.isNull("ttsPitch")) {
            reqFiniteDouble(o, "ttsPitch").toFloat()
        } else {
            null
        }
        return ParsedSettings(apiKey, model, language, voiceEnabled, ttsVoice, ttsRate, ttsPitch)
    }

    // ---- Strict field readers (throw JSONException on a wrong type) ----

    @Throws(JSONException::class)
    private fun reqArray(o: JSONObject, key: String): JSONArray {
        val v = o.get(key)
        if (v !is JSONArray) throw JSONException("bad $key")
        return v
    }

    @Throws(JSONException::class)
    private fun reqObject(o: JSONObject, key: String): JSONObject {
        val v = o.get(key)
        if (v !is JSONObject) throw JSONException("bad $key")
        return v
    }

    @Throws(JSONException::class)
    private fun reqString(o: JSONObject, key: String): String {
        val v = o.get(key)
        if (v !is String) throw JSONException("bad $key")
        return v
    }

    @Throws(JSONException::class)
    private fun optString(o: JSONObject, key: String, default: String): String {
        if (!o.has(key) || o.isNull(key)) return default
        return reqString(o, key)
    }

    @Throws(JSONException::class)
    private fun reqBool(o: JSONObject, key: String): Boolean {
        val v = o.get(key)
        if (v !is Boolean) throw JSONException("bad $key")
        return v
    }

    @Throws(JSONException::class)
    private fun reqLong(o: JSONObject, key: String): Long {
        val v = o.get(key)
        if (v !is Number) throw JSONException("bad $key")
        return v.toLong()
    }

    @Throws(JSONException::class)
    private fun reqFiniteDouble(o: JSONObject, key: String): Double {
        val v = o.get(key)
        if (v !is Number) throw JSONException("bad $key")
        val d = v.toDouble()
        if (!d.isFinite()) throw JSONException("bad $key")
        return d
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
