# JARVIS Native — Worker Contract (READ FIRST, FOLLOW EXACTLY)

Work dir: `~/workspace/deliver/jarvis-native/`
Package: `pk.techtonic.jarvis`. Spec: HTML v1.0.0
(`~/workspace/deliver/mobile-jarvis/jarvis-mobile-pre-embed.html`,
`~/workspace/deliver/mobile-jarvis/BUILD-REPORT.md`, worker sources in
`~/workspace/deliver/mobile-jarvis/build/`).

## Hard rules (all workers)

- **NO androidx imports anywhere.** Only `android.*`, `kotlin.*`,
  `java.*`. Platform `android.app.Activity` / `android.app.Fragment` /
  `android.widget.ListView` + `BaseAdapter`. No Material, no Room, no
  KSP, no third-party dependency of any kind.
- **minSdk 24.** NO `java.time` (API 26+) — use `java.util.Calendar` /
  `SimpleDateFormat`. No API 26+ method without a fallback.
- **Kotlin hides `java.lang.Number.longValue()`** — calling `.longValue()`
  on BigDecimal/BigInteger is an unresolved reference. Use `.toLong()`.
- **Strings:** your strings live in YOUR OWN files only:
  `res/values/strings_<area>.xml` (Urdu = default) and
  `res/values-en/strings_<area>.xml` (English) with the IDENTICAL key set.
  Escape every apostrophe in a value as `\'`. NO emoji in any string.
  Never edit another worker's strings file. `app_name`, `footer_text`,
  `exit_confirm`, `yes`, `no` are owned by the coordinator (strings.xml).
- **Layouts:** your layouts only, named as listed in your brief.
  Reference strings with `@string/...`, never hardcode user-visible text.
  Urdu TextViews: `style="@style/JarvisTextUrdu"`. Numbers/times/Latin:
  `style="@style/JarvisTextLatin"` (Jameel renders Latin at half size).
- **Stop contract (user requirement):** EVERY long/ongoing operation gets
  a visible STOP button (`style="@style/JarvisButton.Stop"`, text
  `@string/stop_button` — owned by W2, key `stop_button`). Stop halts
  NOW (cancel the call, stop TTS, stop listening), shows a plain
  "stopped" toast, and NEVER auto-resumes or retries.
- **Markdown/TTS defect (user requirement, "sab se bara masla"):**
  Gemini's `**bold**` markers were read aloud as "star star". Three
  layers, all required (see TextCleaner + GeminiClient contracts below).
- **No hardcoded API key anywhere.** The key comes only from JarvisPrefs.
- **Every button must do its stated job.** No dead buttons, no TODOs,
  no placeholder text.
- **Footer** (already in the shell): `powered by TECHTONICPK (03200989317)`.

## Already written by the coordinator (DO NOT rewrite, use as-is)

- `JarvisApp` (Application; owns `prefs`, inits TtsManager)
- `data/JarvisPrefs` — `geminiKey`, `model` (default `gemini-3.5-flash`),
  `language` ("ur"/"en"), `voiceEnabled`, `ttsVoiceName`, `ttsRate`,
  `ttsPitch`, `hasKey()`
- `data/JarvisDb` — SQLite, tables `chat(id,role,text,ts)`,
  `notes(id,text,date,pinned,created)`, `milk(id,date,litres_milli,
  rate_paisa,total_paisa,ts)`; methods `addChat/allChat/clearChat`,
  `addNote/allNotes/setNotePinned/deleteNote/clearNotes`,
  `addMilk/allMilk/clearMilk`; row classes `ChatRow/NoteRow/MilkRow`
- `util/LocaleHelper` — `wrap(context, language)`
- `ui/MainActivity` — 6-tab shell, back-nav standard, `showTab(tab)`,
  `applyLanguageAndRecreate()`; exposes `prefs` and `db`
- `res/values/{colors,dimens,themes}.xml`, drawables, launcher icon,
  `res/xml/{backup_rules,data_extraction_rules}.xml`, font,
  `activity_main.xml`, manifest, gradle files, CI workflow

## Shared APIs (exact signatures — code against these)

### util/TextCleaner (W1 writes it, W2 + W3 use it)
```kotlin
object TextCleaner {
    /** Pure Kotlin (no Android deps): strip ALL Markdown/symbols for TTS.
        Removes ** * # _ ` [] () link URLs, HTML tags, collapses whitespace.
        The result must contain no '*', no '#', no '`', no '_' used as markup. */
    fun stripForSpeech(text: String): String
    /** Plain text with markers removed but words kept (for copy/share). */
    fun markdownToPlain(text: String): String
    /** Display form: **bold** -> real bold spans, other markers removed.
        Returns android.text.Spanned for TextView.setText(). */
    fun toDisplaySpanned(text: String): android.text.Spanned
}
```

### speech/TtsManager (W2 writes it)
```kotlin
object TtsManager {
    fun init(context: Context, prefs: JarvisPrefs)
    fun shutdown()
    fun isReady(): Boolean
    /** Speak. ALWAYS passes text through TextCleaner.stripForSpeech first.
        Honors prefs: voiceEnabled, ttsVoiceName, ttsRate, ttsPitch.
        onDone/onError run on the calling thread via Handler(mainLooper). */
    fun speak(text: String, onDone: (() -> Unit)? = null, onError: (() -> Unit)? = null)
    /** Halt speech immediately. */
    fun stop()
    fun isSpeaking(): Boolean
    /** All voices the engine reports (may be empty before init completes). */
    fun voices(): List<android.speech.tts.Voice>
    /** Apply the saved voice/rate/pitch now (call after a settings change). */
    fun applySettings()
    /** True when at least one Urdu (ur) voice is installed. */
    fun hasUrduVoice(): Boolean
}
```

### speech/SpeechInput (W2 writes it)
```kotlin
class SpeechInput(context: Context) {
    /** Start listening. Tries ur-PK first, falls back to en-US on error.
        onResult receives the best transcript. onError receives a plain
        message key ("chat_voice_failed" / "chat_voice_unsupported"). */
    fun start(onResult: (String) -> Unit, onError: (String) -> Unit)
    fun stop()   // stopListening: deliver partial results
    fun cancel() // cancel: discard everything
    fun isListening(): Boolean
    fun destroy()
    companion object { fun isAvailable(context: Context): Boolean }
}
```

### net/GeminiClient (W1 writes it)
```kotlin
class GeminiClient(private val prefs: JarvisPrefs) {
    interface Call { fun cancel() }
    /** One-shot generation. No key -> onError("chat_no_key"), ZERO network
        calls. Callbacks run on the main thread (via mainPoster).
        onResult's second param is true when the FALLBACK model answered. */
    fun generate(prompt: String, onResult: (String, Boolean) -> Unit, onError: (String) -> Unit): Call
    /** Tiny probe call for Test Key. Same no-key behaviour. */
    fun testKey(onOk: () -> Unit, onError: (String) -> Unit): Call
    companion object {
        const val SYSTEM_PROMPT: String // see below — exact text required
        const val FALLBACK_MODEL: String = "gemini-3.5-flash-lite"
        /** Test hook: the coordinator's gate points this at a local mock
            server. Production default is the real host. */
        @Volatile var baseUrl: String = "https://generativelanguage.googleapis.com"
    }
    /** Test hook: post callbacks via this. Default posts to the main
        thread; the gate overrides it to run immediately on the JVM. */
    internal var mainPoster: (Runnable) -> Unit =
        { r -> Handler(Looper.getMainLooper()).post(r) }
}
```
Endpoint: `{baseUrl}/v1beta/models/{model}:generateContent?key={key}`
(URLEncoder-encode model + key). POST JSON
`{"contents":[{"parts":[{"text": SYSTEM_PROMPT + "\n\n" + prompt}]}]}`,
`Content-Type: application/json`. Parse
`candidates[0].content.parts[].text` (join ALL parts, skip any part with
`"thought":true`). Timeouts: connect 15000 ms, read 60000 ms. Cancel =
`disconnect()` + cancelled flag checked before delivering any result AND
between retry attempts. Use `HttpURLConnection` on a plain `Thread` (no
coroutines dependency); post results via `mainPoster`.

**503 / overload handling (user hit a live 503 on 2026-11-18 — Google-side
overload, NOT the key, NOT quota, NOT the app):**
- RETRYABLE codes: 429, 500, 502, 503, 504. On a retryable code,
  `generate` retries the configured model up to 3 attempts total
  (initial + 2 retries, delays 2 s then 4 s, exponential backoff).
  Retry sleeps run on the worker thread and MUST check the cancelled
  flag before each attempt (a Stop during backoff halts immediately).
- FALLBACK: if the primary model still fails after retries, `generate`
  tries FALLBACK_MODEL once for that same request. If the configured
  model IS already the fallback model, skip the fallback step. On a
  fallback success, onResult(text, usedFallback = true) — ChatFragment
  shows the plain `chat_fallback_used` note (toast; NOT stored in the
  message history — the stored answer stays clean).
- FINAL FAILURE: if the fallback also fails (any code), onError with
  `chat_server_busy` — "Gemini ka server busy hai, thodi der baad dobara
  koshish karein". The user must NEVER see a bare "503" or any raw code.
- NON-RETRYABLE: 400/401/403 -> immediate onError("chat_error") (no
  retry, no fallback — the key or the request is wrong, retrying
  cannot help). Network IOException (no connectivity) -> retried
  exactly like a retryable code (v1.0.1 — one transient hiccup must not
  fail the request); onError("chat_offline") only after the retries
  are exhausted.
- `testKey`: retry ONCE after 2 s on retryable codes; still failing ->
  onError("chat_server_busy") (the key is probably fine — say so, do
  NOT report it as a key error). 400/401/403 -> onError("chat_error").
  No fallback for testKey (it tests the configured model).
- New string keys (W1 owns, strings_chat.xml): `chat_server_busy`,
  `chat_fallback_used`, `chat_offline`.

**SYSTEM_PROMPT (exact — includes the no-Markdown rule):**
"You are TECHTONICPK AI, built by TECHTONICPK (Muhammad Riyaz). Answer in
the user's language (Urdu, English or Saraiki as they write). Be direct,
practical and honest; never invent facts. Reply in plain natural prose
with NO Markdown symbols at all: no asterisks, no hash signs, no
underscores, no backticks, no bullet stars. Write continuous human-style
speech as if speaking aloud; use plain numbered lines (1. 2. 3.) only when
a list is truly needed."

### net/BriefingClient (W3 writes it)
```kotlin
class BriefingClient {
    interface Call { fun cancel() }
    data class Weather(val labelKey: String, val tempC: Double, val humidity: Int, val windKmh: Double)
    data class Prayer(val apiName: String, val labelKey: String, val time: String) // time "HH:MM"
    data class Prayers(val list: List<Prayer>, val hijriText: String, val next: Prayer?)
    fun fetchWeather(onResult: (Weather) -> Unit, onError: () -> Unit): Call
    fun fetchPrayers(onResult: (Prayers) -> Unit, onError: () -> Unit): Call
}
```
- Weather: `https://api.open-meteo.com/v1/forecast?latitude=30.96&longitude=70.94&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&timezone=Asia%2FKarachi`
  Parse `current.{temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m}`.
  WMO code -> label key: 0 clear; 1,2,3 partly; 45,48 fog; 51,53,55,56,57,
  61,63,65,66,67,80,81,82 rain; 71,73,75,77,85,86 snow; 95,96,99 storm;
  else unknown. Keys: `brief_wx_clear/partly/fog/rain/snow/storm/unknown`.
- Prayers: `https://api.aladhan.com/v1/timingsByCity?city=Layyah&country=Pakistan&method=1`
  Parse `data.timings.{Fajr,Sunrise,Dhuhr,Asr,Maghrib,Isha}`; strip suffixes
  like " (PKT)" (regex `\s*\(.*\)\s*$`). Hijri:
  `data.date.hijri.{day, month.ar (fallback month.en), year}` ->
  `"{day} {month} {year} {brief_hijri_suffix}"`, only when all parts exist.
  Next prayer = first time still ahead today, else Fajr (tomorrow).
  Order: Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha.
- Same HttpURLConnection + Thread + main-thread callback + cancel pattern
  as GeminiClient. Timeouts 15000/30000 ms.

### util/Calculator (W3 writes it)
```kotlin
object Calculator {
    /** Tokenise + evaluate with correct precedence (+ - * /, parentheses).
        Throws CalculatorException("divzero") on divide-by-zero or non-finite,
        CalculatorException("error") on malformed input. Result formatting:
        12 significant digits, trailing zeros stripped ("2+3*4" -> "14"). */
    fun evaluate(expr: String): String
    class CalculatorException(val code: String) : Exception(code)
}
```

### util/Units (W3 writes it)
```kotlin
object Units {
    /** Units: kg, maund (mass; 1 maund = 40 kg), metre, yard, gaz (length;
        1 yard = 1 gaz = 0.9144 m). Mass<->length -> null (mismatch). */
    fun convert(value: Double, from: String, to: String): Double?
}
```

### util/BackupManager (W4 writes it)
```kotlin
object BackupManager {
    /** Export payload: {"version":"1.0.0","exportedAt":ISO8601,
        "notes":[...], "milk":[...], "chat":[...], "settings":{...}}.
        Notes: {text,date,pinned,created}; milk: {date,litres,rate,amount}
        (litres/rate/amount as decimal numbers, HTML-compatible);
        chat: {role,text,ts}; settings: {apiKey,model,language,voiceEnabled,
        ttsVoice,ttsRate,ttsPitch}. */
    fun buildExportJson(db: JarvisDb, prefs: JarvisPrefs): String
    /** Import: validate FIRST (version present, arrays well-formed; reject
        anything else with false). Accepts BOTH the native shape above AND
        the HTML v1.0.0 shape {"version","exportedAt","data":{"jvs_notes":
        "<json string>", "jvs_milk": "...", "jvs_chat": "...",
        "jvs_settings": "..."}}. On success replaces notes/milk/chat and
        applies settings; returns true. Never half-imports. */
    fun importJson(json: String, db: JarvisDb, prefs: JarvisPrefs): Boolean
}
```

## Scholars (W3 — exact, from HTML)
Order: Imran Attari / مولانا محمد عمران عطاری; Abdul Habib Attari /
مولانا عبد الحبیب عطاری; Mufti Samar Abbas Attari / مفتی محمد سمر عباس
عطاری; Muhammad Raza Saqib Mustafai / محمد رضا ثاقب مصطفائی; Mufti Salman
Azhari / مفتی سلمان اظہری; Ajmal Raza Qadri / پیر اجمل رضا قادری.
YouTube search URL: `https://www.youtube.com/results?search_query=` +
URLEncoder(`<English name> + " latest bayan"`). Scholar-of-the-day:
`(dayOfYear - 1) % 6` (dayOfYear 1-based). Search links ONLY — never invent
a bayan title or video link.

## Milk math (W3 — exact, from HTML)
Litres rounded to 2 decimals first: `amount = round(litres*100)*rate/100`,
then `amount = round(amount*100)/100`. Store integer paisa:
`total_paisa = round(amount*100)`, `litres_milli = round(litres*1000)`,
`rate_paisa = round(rate*100)`. Running total = sum of total_paisa.
Display money with 2 decimals + `hisab_currency` (روپے / Rs).

## i18n key ownership (convert from ~/workspace/deliver/mobile-jarvis/build/)
- W1: `w1-i18n.json` (all chat_* keys) + NEW: `stop_button`, `stopped`,
  `chat_stopped`, `chat_speak` (speak this answer), `chat_stop_speaking`.
- W2: from `w4-i18n.json`: `set_ttsvoice_*` (default/label/loading/none)
  + NEW voice-picker keys: `voice_title`, `voice_pick_hint`,
  `voice_preview`, `voice_selected`, `voice_rate`, `voice_pitch`,
  `voice_no_urdu`, `voice_open_tts_settings`, `voice_listening`,
  `voice_stop_listening`, `stop_button`, `stopped` (W2 OWNS these two
  shared keys — W1/W3 reference them, do not redefine).
- W3: `w2-i18n.json` (all brief_* + bayan_*) + `w3-i18n.json` (all
  hisab_*, note_*) + NEW: `brief_stop`, `brief_stopped`,
  `brief_speaking`, `brief_stop_speaking`.
- W4: `w4-i18n.json` MINUS `set_ttsvoice_*` (W2's) + NEW: `set_voice_title`
  (voice settings row), `set_voice_open`, `set_voice_summary`.
- Coordinator owns: `app_name`, `footer_text`, `exit_confirm`, `yes`,
  `no` (strings.xml — already written by coordinator; do not redefine).

## TextCleaner — cleaning rules (W1, exact)

`stripForSpeech` MUST handle the book masters as well as chat answers:
- `@@Arabic@@` spans: REMOVE the `@@` signs but KEEP the Arabic words
  inside (they are real Quranic/Arabic text — never drop the words).
- `**bold**` -> keep the words, drop the stars. Single `*`, `#`, `_`,
  backticks, `[]()` link markup (keep link text, drop the URL), HTML
  tags, `---` rules, `>` quotes: drop the symbols, keep the words.
- `## Heading` -> the heading words as a plain section title (keep them).
- Collapse all whitespace/newlines to single spaces; trim. The result
  must contain NO `*`, NO `#`, NO backtick, NO `@`, NO `_` used as markup.
- `markdownToPlain`: same cleaning, kept as readable plain text.
- `toDisplaySpanned`: `**bold**` -> real bold spans; `@@...@@` -> the
  Arabic words shown plainly (no @ signs); all other markers removed.
  Raw markers must NEVER be visible on screen.

## Alerts (W3 reminders, W4 prayers)

Package `pk.techtonic.jarvis.alerts` (manifest entries already written):
- `ReminderScheduler`: `schedule(context, noteId, date, time)` — AlarmManager
  exact alarm (`setExactAndAllowWhileIdle`; on SecurityException fall back
  to `setAndAllowWhileIdle`); `cancel(context, noteId)`; `rescheduleAll(context)`
  (reads notes with date+time in the future from JarvisDb). Request code =
  note id (int). Intent -> ReminderReceiver with extras note_id + text.
- `ReminderReceiver : BroadcastReceiver` — shows the notification on
  CHANNEL_REMINDERS with the note text + a Dismiss action (the stop control:
  tapping it cancels the notification AND the alarm; nothing re-fires).
- `PrayerScheduler`: `rescheduleToday(context)` — fetches today's prayer
  times (BriefingClient) and sets exact alarms for the prayers enabled in
  prefs (default ALL OFF); `cancelAll(context)`; called from
  MainActivity.onCreate, from BootReceiver, and when a toggle changes.
  Times already past today are skipped. Intent -> PrayerReceiver with
  extras prayer label key + time.
- `PrayerReceiver : BroadcastReceiver` — notification on CHANNEL_PRAYERS
  ("فجر کا وقت ہو گیا ہے" style) + Dismiss action.
- `BootReceiver : BroadcastReceiver` — on BOOT_COMPLETED: ReminderScheduler
  .rescheduleAll + PrayerScheduler.rescheduleToday.
- POST_NOTIFICATIONS (API 33+): ask at runtime the first time the user
  enables any alert; if denied, say so plainly and do not schedule.
- Exact-alarm permission (API 31+): if `canScheduleExactAlarms()` is false,
  fall back to inexact alarms and say so plainly in Settings (one line).

## App lock (W4)

- `ui/LockActivity` (LAUNCHER — manifest already written). On start: if
  `!prefs.lockEnabled` -> start MainActivity + finish immediately. Else show
  PIN entry (numeric, 4-8 digits) + fingerprint button (only when
  `lockBiometric` AND API 28+ AND the device has a biometric).
  Biometric: platform `android.hardware.biometrics.BiometricPrompt`
  (API 28+; no androidx). Success -> MainActivity. Wrong PIN -> plain
  message, no lockout counter games. No PIN set yet + lock enabled must be
  impossible (Settings only enables lock after a PIN is set).
- PIN storage: SHA-256 hex of the PIN in `prefs.lockPinHash` (never plain).
  Settings lock section: enable toggle, set/change PIN (enter twice),
  biometric toggle, disable (asks PIN once to confirm).
- `util/LockHelper`: `fun sha256Hex(pin: String): String`,
  `fun hasBiometric(context: Context): Boolean` (API 28+ + hardware +
  enrolled — use BiometricManager from `android.hardware.biometrics`).

## Auto-backup (W4)

- `util/AutoBackup`: `fun maybeBackupOnClose(context)` — called from
  MainActivity.onStop. Only when `prefs.autoBackupEnabled` AND
  `prefs.autoBackupUri` is non-empty: write `BackupManager.buildExportJson`
  to the persisted SAF document Uri (ContentResolver.openOutputStream,
  truncate mode). Silent success; any failure is swallowed (never crash
  the app on close) but recorded in a `lastAutoBackupError` pref string
  that Settings shows plainly.
- Settings: "auto-backup" row — enable toggle + "choose file" button
  (ACTION_CREATE_DOCUMENT, `Jarvis-Backup.tpkbackup`, persist the Uri with
  takePersistableUriPermission) + last-backup status line.
- BackupManager (W4): export/import as specified in Part 1, PLUS books in
  the payload: `"books":[{"title","text","position","updated"}]` (user-added
  books only — pre-loaded books are assets and re-seed; on import, skip a
  book whose title already exists in the library). Import accepts the
  native shape AND the HTML `{"data":{"jvs_*":...}}` shape.

## Book Reader / Kitab (W5) — the CORE vision

Tab 6 (Kitab). Files: `ui/BookReaderFragment.kt`,
`reader/ReadingService.kt`, `reader/BookSeeder.kt`,
`reader/ReadingState.kt`, layouts `fragment_book_reader.xml`,
`item_book.xml`, `dialog_add_book.xml`, strings `strings_kitab.xml`.

- **Pre-loaded library (assets already copied by the coordinator):**
  `assets/books/books.json` manifest + 9 files (3,829,338 B total):
  book1 علماء کا امتحان (1,268,697 B), book2 ٹیکنالوجی انسائیکلوپیڈیا
  (934,122 B), book3 آخری دس سورتیں (580,335 B), book4 اذان اور نماز
  (682,804 B), book5 علماء کی محفل (99,931 B), book6 ایمان اور محنت
  (92,130 B), book7 اعلیٰ حضرت (15,218 B), book8 اے آئی کیا ہے؟
  (81,750 B), book9 بغیر انٹرنیٹ کے ایپ (74,351 B).
  `BookSeeder.seedIfNeeded(context, db, prefs)`: if the stored seed
  version is below `CURRENT_SEED_VERSION`, read the manifest, insert
  each MISSING book (title + full text) into the books table, set the
  stored version. Runs on a background thread, from BookReaderFragment's
  first open. Versioned (v1.0.1): a version bump re-seeds missing titles
  only — a deleted pre-loaded book comes back on upgrade, while existing
  books (and their bookmarks in `position`) are never touched.
- **Library UI:** list of books (title + word count + progress % +
  last-read date), tap to open the reader; delete button per book (with
  confirm; deleting a pre-loaded book removes it from the library — it
  does NOT re-seed); "Add book" button -> dialog: title field + paste
  text field + "upload .txt/.md" (ACTION_OPEN_DOCUMENT, text/*, read via
  ContentResolver) + plain note that PDF is not supported
  ("PDF کا متن صاف نہیں نکلتا — text paste کریں").
  PDF upload stays DEFERRED — do NOT attempt PDF extraction.
- **ReadingService (foreground service — manifest already written):**
  owns TTS playback so reading continues with the screen OFF and the app
  in the background. Commands via startService intents:
  ACTION_PLAY (extra book_id), ACTION_PAUSE, ACTION_RESUME, ACTION_STOP.
  Chunks the cleaned text (TextCleaner.stripForSpeech first, then split
  into ~900-char chunks at sentence/paragraph boundaries); speaks chunks
  sequentially via TtsManager; saves the bookmark (char offset) to the
  books table after EVERY chunk and on pause/stop/destroy — the bookmark
  must survive service restarts. Media-style notification on
  CHANNEL_PLAYBACK with Play/Pause/Stop actions (PendingIntents back to
  the service) + book title + progress. `startForeground` within the
  service's onStartCommand (call startForegroundService from the UI).
- **Audio focus:** request AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK via
  AudioManager; on LOSS_TRANSIENT (incoming call) pause and remember;
  on GAIN resume only if it was playing; on LOSS stop. Register a
  receiver for ACTION_AUDIO_BECOMING_NOISY (headset/BT disconnect) ->
  pause immediately, never blare on the speaker.
- **Battery hint:** the FIRST time the user starts a book, show a plain
  Urdu dialog explaining that Vivo battery optimization can stop the
  voice, with a button opening the ignore-battery-optimizations settings
  (Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, package Uri;
  fall back to ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) and a
  "later" button. Set `prefs.batteryHintShown = true` after showing once.
- **Reader UI:** current book title, progress bar + "chunk X of Y",
  Play / Pause / Resume / Stop buttons (all visible; Stop halts NOW and
  saves the bookmark), speed SeekBar (0.5x–2.0x, writes prefs.ttsRate),
  the current chunk's text displayed (cleaned, real bold for **).
  `ReadingState` object: volatile `bookId, isPlaying, chunkIndex,
  chunkCount, position` + a listener list the fragment registers to
  refresh the UI.
- **Quranic Arabic note:** Book 3 contains Quranic verses. TTS reads them
  with the selected voice's approximation — that is inherent to TTS and
  must NOT be presented as correct tajweed. No special handling; the
  limitation is reported honestly by the coordinator.

## Qibla + Tasbih (W3, in the Bayan tab)

- Qibla card in BayanFragment: compass needle (an arrow TextView/ImageView
  rotated by `qiblaBearing - azimuth`). Qibla bearing computed from fixed
  Layyah coords (30.96, 70.94) to the Kaaba (21.4225, 39.8262) with the
  standard great-circle formula (~255°, computed in code, never hardcoded
  as a magic display value). Sensors: accelerometer + magnetometer via
  SensorManager.getRotationMatrix/getOrientation; register in onResume,
  unregister in onPause. No sensors -> plain message, no crash. Label
  states the bearing is for Layyah.
- Tasbih card in BayanFragment: big count display (today's total from
  `tasbih_daily`), tap button (+1), target selector (33/100/500 via
  small buttons; writes prefs.tasbihTarget), vibrate 200 ms on reaching
  the target (Vibrator, API-26-safe: use VibrationEffect on API 26+,
  vibrate(long) below), reset-today button with confirm. Count persists
  per day in the DB.

## Voice notes (W3, in NotesFragment)

- Mic button next to the note input: SpeechInput (W2's contract) ->
  transcript auto-saved as a note immediately + toast showing what was
  saved. RECORD_AUDIO permission asked on first mic tap (plain message
  if denied). A visible stop button while listening (SpeechInput.stop()).

## String ownership — additions

- W3 adds to strings_tools.xml: `reminder_*`, `tasbih_*`, `qibla_*`,
  `voice_note_*`, `note_time_label`, `brief_*` additions already listed.
- W4 adds to strings_settings.xml: `lock_*`, `prayer_alert_*`,
  `autobackup_*`, `set_voice_*` additions already listed.
- W5 owns strings_kitab.xml: `nav_kitab`, all `kitab_*` keys.
- W2 still owns the shared `stop_button` + `stopped` keys.

## NOT in this build (do not build, do not stub)

Saraiki UI translation, answering from his own books (RAG), shared memory
with the laptop OpenJarvis. No placeholders for these anywhere.

## Mic — ROCK-SOLID requirement (user's phone test: HTML mic would not turn on at all)

W2 owns the shared helper; W1 (chat mic) and W3 (voice-notes mic) both use
it — identical behaviour everywhere. Typed input is ALWAYS visible next to
the mic (never mic-only).

### util/MicPermission (W2 writes it)
```kotlin
object MicPermission {
    const val REQUEST_CODE = 1001
    /** True when RECORD_AUDIO is currently granted. */
    fun isGranted(context: Context): Boolean
    /** True when a speech recognizer exists on the device. */
    fun isRecognizerAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)
    /**
     * Full flow. If granted -> onGranted(). Else: show a plain-Urdu
     * rationale dialog FIRST (why the mic is needed: "bol kar baat karne
     * ke liye"), then request the system permission. The caller MUST
     * forward onRequestPermissionsResult to [onRequestPermissionsResult].
     * If the user denies -> onDenied() (caller shows the denied message
     * with the Settings button; never fail silently).
     */
    fun ensure(activity: Activity, onGranted: () -> Unit, onDenied: () -> Unit)
    /** Forward from the host Activity/Fragment's onRequestPermissionsResult.
        Returns true if the result was ours. */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean
    /** One-tap path to this app's system Settings page (for a denied user). */
    fun openAppSettings(context: Context)
}
```
Notes: `ensure` keeps ONE pending callback (a new call replaces it).
Rationale text uses keys `mic_why_title` / `mic_why_body` (W2 owns).
Denied message uses `mic_denied` + button `mic_open_settings` (W2 owns).
No-recognizer message uses `mic_no_recognizer` (W2 owns).
Listening indicator uses `mic_listening` ("سن رہا ہوں…") (W2 owns).

### SpeechInput — amended contract (W2)
- `start()` assumes permission is already granted (callers run
  MicPermission.ensure first). `start()` MUST check
  `isRecognizerAvailable` itself and call `onError("mic_no_recognizer")`
  when no recognizer exists — never a dead button, never a crash.
- Listening state: callers show the `mic_listening` indicator + change
  the mic button's appearance + show the Stop button while listening.
  Stop calls `SpeechInput.stop()` (deliver partial results) — a long-press
  or a second "cancel" affordance is NOT required; Stop = stop().

### QA (coordinator gate — mic paths)
The gate verifies by code inspection + a JVM harness where possible:
(1) permission-granted path calls SpeechRecognizer with ur-PK;
(2) denied path shows `mic_denied` + Settings button (no silent fail);
(3) no-recognizer path shows `mic_no_recognizer`. Live mic on his Vivo
remains a phone check and is reported as such — never claimed proven here.
