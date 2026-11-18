# Mobile JARVIS — Native Android Build Report v1.0.1

**Date:** 2026-11-18 · **Version:** 1.0.1 (versionCode 2) · **Package:** `pk.techtonic.jarvis`
**Scope-locked fix round** for the 6 defects Muhammad Riyaz found on his
phone on 2026-11-18 (v1.0.0, commit 7f9868c). No new features; nothing
else changed.

## v1.0.1 — what was fixed

1. **Kitab Play hidden (he deleted «اے آئی کیا ہے؟» by mistake):** every
   book card now carries a clear Play button — tapping it opens the reader
   AND starts playback (same voice-off gate + battery hint as the reader's
   own Play). Delete moved OUT of the card into the reader view only
   (secondary button at the bottom, confirm dialog kept).
2. **Deleted pre-loaded book lost:** seeding is now VERSIONED
   (`BookSeeder.CURRENT_SEED_VERSION = 2`, stored in
   `prefs.booksSeedVersion`). On upgrade, v1.0.1 re-seeds MISSING
   pre-loaded titles only — «اے آئی کیا ہے؟» comes back; existing books
   (and their bookmarks) and user-added books are never touched.
3. **Chat errors invisible:** every failure (no key, offline, server
   busy, API error) is now stored as a visible "error" row in the chat
   history itself (left bubble, marked «خرابی: » / "Error: ", no
   copy/share/speak actions). The toast is kept too; the row is the fix.
   Backup import accepts the new "error" role (else a backup containing
   one would fail the whole import).
4. **Test Key result invisible:** the result now stays visible as a
   status line under the Test Key button (running / ok / the error in
   plain words), plus the toast.
5. **Network gave up on one hiccup (real bug):** `attemptWithRetries()`
   returned `Offline` immediately — one transient IOException (DNS blip,
   momentary drop) instantly failed the request. Offline is now retried
   exactly like a retryable HTTP code (2 s, then 4 s); "offline" is only
   reported after the retries are exhausted, and the message is honest
   ("could not reach Gemini after several tries", not "no internet").
   503/server-busy retry counts unchanged. Test Key gets the same retry.
6. **Version bump:** versionCode 1 → 2, versionName 1.0.0 → 1.0.1
   (build.gradle + About lines, both locales).

**Files changed (16 + 2 docs):** `app/build.gradle`;
`ui/BookReaderFragment.kt`, `ui/ChatFragment.kt`, `ui/SettingsFragment.kt`;
`net/GeminiClient.kt`; `reader/BookSeeder.kt`; `data/JarvisPrefs.kt`,
`data/JarvisDb.kt` (doc line only); `util/BackupManager.kt`;
`res/layout/item_book.xml`, `res/layout/fragment_book_reader.xml`,
`res/layout/fragment_settings.xml`; `res/values/strings_chat.xml`,
`res/values-en/strings_chat.xml`, `res/values/strings_settings.xml`,
`res/values-en/strings_settings.xml`. **No file added, none deleted** —
no `git rm` needed in the push file. New strings (2 keys, both locales):
`chat_error_prefix`, `set_test_running`. `chat_offline` reworded (both
locales). `set_about_line1` 1.0.0 → 1.0.1 (both locales).

**Gate — PASS:** aapt2 compile exit 0; kotlinc full-tree 0 errors
(deprecation warnings only, as in v1.0.0); string parity CLEAN — 288 keys
in values, 288 in values-en (was 286). **JVM gate — 67/67 PASS** (55
original + 12 new offline checks): offline→offline→200 answers with no
fallback (3 requests); offline×3 → `chat_offline` with exactly 3 requests
(no fallback attempt); testKey offline×2 → `chat_offline` (2 requests);
cancel during offline backoff → no callback, 1 request. All original 503
scenarios still pass (retry counts unchanged).

**Zip:** `mobile-jarvis-v1.0.1-source.zip` (same composition as v1.0.0,
push file swapped to `push-jarvis-v101.txt`). Push file: `cd
~/Mobile-JARVIS`, `git pull origin main`, unzip, `git add -A`, commit
"Mobile JARVIS v1.0.1", `git push origin main`.

**Still needs his phone (never claimed proven here):** Play on a card
starts playback; deleted book restored on upgrade; an error row appears
in chat after a failed send; Test Key status line shows; a flaky
connection no longer fails on the first hiccup.

---

# Mobile JARVIS — Native Android Build Report v1.0.0

**Date:** 2026-11-18 · **Version:** 1.0.0 (versionCode 1) · **Package:** `pk.techtonic.jarvis`
**minSdk 24 / targetSdk 34** · **Zero dependencies** — no androidx, no third-party
libraries; platform `android.*` + Kotlin stdlib + `org.json` only. That keeps the
build free of Maven-download risk on CI and lets the local gate compile against
`android.jar` alone.

**Base:** HTML v1.0.0 (`~/workspace/deliver/mobile-jarvis/jarvis-mobile.html`,
all 7 sections ported) + every addition from his phone tests on 2026-11-18.

## What is in the build

**7 tabs** (Chat, Briefing, Bayan, Notes, Hisab, Kitab, Settings) + LockActivity
(launcher; passes straight through when the lock is off) + VoicePickerActivity.

- **Chat** — Gemini brain, default `gemini-3.5-flash`, changeable in Settings.
  History (SQLite), search, clear-with-confirm, copy + WhatsApp share per answer,
  speak-answer button, typing indicator. No key = plain message, zero network.
- **Voice** — tap-to-talk (ur-PK, one en-US retry) + spoken replies.
- **Morning Briefing** — Urdu date + Hijri, Layyah weather (Open-Meteo, no key),
  prayer times (Aladhan method 1), notes count, scholar-of-the-day shortcut,
  spoken briefing. Each block fails independently with a plain message.
- **Bayan** — 6 Ahle Sunnat scholar cards, YouTube search links only (no invented
  titles). Tasbih counter + Qibla compass live here (devotional hub).
- **Notes & Reminders** — CRUD, search, pin, date badges, quick-add chips,
  date+time reminders that actually ALERT (AlarmManager + notification with
  Dismiss), voice notes (speak → auto-saved as text).
- **Hisab** — safe calculator (no eval), milk-sale hisab (integer-paisa math,
  saved entries + running total), unit converter (kg/maund, metre/yard/gaz).
- **Settings** — Gemini key (password field, local only) + Test Key, model,
  Urdu/English toggle, voice row, JSON backup export/import (accepts BOTH the
  native shape and the HTML v1.0.0 shape), auto-backup (SAF file, on close),
  per-prayer alert toggles (all OFF by default), app lock (PIN + fingerprint),
  About v1.0.0.
- **Kitab (Book Reader)** — see below; the core of his JARVIS vision.

**Standards kept:** Jameel Noori Nastaleeq embedded (`res/font/`, md5
`4b37da11a19bd60a9432a7603aada419` — Jameel on Urdu text only, sans for
Latin/digits per the MCP lesson), Urdu-first RTL, footer exactly
`powered by TECHTONICPK (03200989317)`, zero emoji, no hardcoded API key
(grep-verified), no trial/licence lock (his personal app), SQLite storage,
back-navigation standard (system back moves between tabs; exit only from Chat
with confirm), offline-first UI with plain messages when net is missing.

## His phone-test additions (all in)

1. **STOP CONTROLS EVERYWHERE** — every long operation has a visible Stop:
   in-flight Gemini request (cancel = disconnect + flag, checked between
   retry attempts too), TTS speech, listening, spoken briefing, briefing
   fetch, book playback (notification Stop included). Stop means halt NOW;
   nothing resumes or retries without his word.
2. **VOICE CHOICE** — VoicePickerActivity lists every installed TTS voice
   (name + locale), tap to select, Preview button, rate + pitch sliders
   (0.5x–2.0x). No Urdu voice installed = plain message + one-tap path to
   system TTS settings. Nothing hardcoded.
3. **STARS FIX (his "sab se bara masla") — all 3 layers:** (1) SYSTEM_PROMPT
   forbids all Markdown symbols; (2) `TextCleaner.stripForSpeech` strips
   every symbol before ANY text reaches TTS; (3) chat display renders
   `**bold**` as real bold via `toDisplaySpanned` — raw markers never visible.
4. **GEMINI 503 HANDLING** — retryable codes (429/500/502/503/504) retried
   3× on the configured model (2s, 4s backoff, cancel-checked), then one
   automatic fallback attempt with `gemini-3.5-flash-lite` (toast says so
   plainly), then a plain Urdu busy message — the user never sees a bare
   "503". 400/401/403 fail at once (key/request wrong). Test Key retries
   once, then reports busy — never as a key error.
5. **MIC, ROCK-SOLID** — plain-Urdu rationale dialog BEFORE the system
   permission ask; denied = plain message + one-tap path to app Settings;
   no recognizer on the device = plain message, never a dead button;
   visible listening state ("سن رہا ہوں…") + Stop button; typed input always
   next to the mic. (Coordinator fix: MainActivity forwards permission
   results to MicPermission — without it the mic flow would silently never
   resolve.)
6. **DELEGATED ADDITIONS (his "aap mission samajhte ho")** — real reminder
   alerts; per-prayer alerts (default OFF); Qibla compass (bearing computed
   in code, 258.6° for Layyah; plain message when sensors are absent);
   Tasbih counter (targets 33/100/500, vibrate on target, daily total saved);
   voice notes; app lock (PIN + real BiometricPrompt, PIN fallback, default
   OFF); auto-backup (SAF Drive file, on close, silent failure never crashes).

## Kitab — Book Reader + background playback (CORE)

- **Foreground service** (`ReadingService`, mediaPlayback type) + media
  notification (Play/Pause toggle + Stop). Plays with the screen off and the
  app in the background. Notification tap opens the app on the Kitab tab.
- **Audio focus:** transient loss (incoming call) pauses and resumes after
  only if it was playing; full loss stops. **Headset/Bluetooth disconnect**
  pauses at once (never blares on speaker).
- **Bookmark** saved after every chunk + on pause/stop/destroy; resumes
  where he left off, even after the app closes. After a process kill the
  service stops itself (no surprise auto-play); the bookmark survives, one
  tap on Play resumes.
- **Chunking:** cleaned text split into ~900-char chunks at sentence
  boundaries (Urdu `۔`/`؟` count), never mid-word; exact char offsets.
- **Battery hint:** first-ever book start shows a plain-Urdu dialog guiding
  him to disable battery optimization for JARVIS (one-tap request intent +
  settings-list fallback). Manifest declares
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` so the one-tap path works.
- **Add book:** paste text or upload `.txt`/`.md`. **PDF is DEFERRED** with
  the plain in-app note — his Nastaliq PDFs extract garbled, and garbled
  text is never read aloud as if it were the book.
- **Voice-off guard:** starting a book with voice disabled is refused with a
  plain message (otherwise TTS answers onDone instantly and the book would
  "finish" in seconds with no audio).

### Pre-loaded library (9 books, 3,829,338 B, as assets)

| File | Book | Bytes | md5 |
|---|---|---|---|
| book1-imtihan.md | علماء کا امتحان v4 | 1,268,697 | 129f660710827925f67356fda51538fa |
| book2-tech.md | ٹیکنالوجی انسائیکلوپیڈیا | 934,122 | cdda72bb952711afb1d891a4e32c85cd |
| book3-surahs.md | آخری دس سورتیں | 580,335 | cdc9843cbf29bae7d0c0f62126639f16 |
| book4-azan-namaz.md | اذان اور نماز کی درستگی | 682,804 | ab50afb34b94f8260611232e945466ab |
| book5-mehfil.md | علماء کی محفل | 99,931 | b0aebc81b35c5d88683e794534e589e6 |
| book6-iman-mehnat.md | ایمان اور محنت | 92,130 | 18059b730c5bc55c9e58b052eea73e56 |
| book7-alahazrat.md | اعلیٰ حضرت اور پروفیسر ضیاء الدین | 15,218 | 8c662c279c4aff1af28ab62a5db58479 |
| book8-ai-kya-hai.txt | اے آئی کیا ہے؟ | 81,750 | 81bf8acbc001f703160ec0ffc58657a1 |
| book9-manual-app.txt | بغیر انٹرنیٹ کے ایپ کیسے بنائیں؟ | 74,351 | 25f9158b608f81c6c0763c3c10089e9e |

Seeded once into SQLite on first Kitab open; never re-seeded (a deleted book
stays deleted, bookmarks survive). The reader strips ALL Markdown
(`@@` keeps the Arabic words, drops the @ signs; `**`, `##`, links keep text,
drop URLs) — verified on a real Book 4 passage (see QA).

**NOT bundled (honest list):** Saraiki books (desi hisab ed1/ed2, bhoolay
alfaz) — no clean .md master exists; text lives inside `build_book.py`
scripts (extraction would risk code fragments). «مشکل اصطلاحات» — only
garbled PDF-extraction text exists. «گھر بیٹھے کمائی», AI Agents handbook,
AI news digest — no clean text master (HTML/PDF only; PDF extraction is
garbled). Old alim-test-book v1/v2/v3/v3.1 — superseded by v4, deliberately
excluded.

**Honest limit:** Book 3 contains Quranic verses; TTS reads them with the
selected voice's approximation — this must NOT be presented as correct
tajweed. A teacher remains final for recitation.

## NOT in this build (his own deferrals)

Saraiki UI translation (big job — later); answering from his own books
(needs RAG — later); shared memory with the laptop OpenJarvis (later).

## QA — what was verified here

**Pre-push gate — PASS** (`gate/run_gate.sh`, final run on the finished tree):
aapt2 resource compile exit 0; kotlinc compile of all 33 Kotlin files
(7,737 lines) + generated R stubs against android-34 — **0 errors**
(warnings are deprecation notices for the platform Fragment APIs, expected
with the no-androidx design); string parity check **CLEAN — 286 keys in
values, 286 in values-en, identical sets**, no unescaped apostrophes, no
emoji, every `@string/` and `R.string.` reference resolves.

**JVM behavioural gate — 55/55 PASS** (`gate/run_jvm_tests.sh`, runs the REAL
TextCleaner + GeminiClient classes on the JVM):
- TextCleaner 32/32: a real 4,000-char Book 4 passage (with `@@`, `**`, `##`)
  and a Markdown-heavy chat answer — spoken text contains zero `* # \` @ _ [ ]`,
  no leaked URLs, no double spaces; Arabic words and bold words survive.
- Gemini 503 23/23, against a scripted mock (this VM blocks all TCP from JVM
  processes, so the mock is a URLStreamHandlerFactory returning scripted
  HttpURLConnections — the real client code runs end to end; only socket I/O
  is stubbed): retry 503→503→200 answers with no fallback (3 requests, all
  primary model); primary exhausted → lite model answers with fallback=true
  (4th request is `gemini-3.5-flash-lite`); all-503 → `chat_server_busy`
  (4 requests); no key → `chat_no_key` with ZERO requests; 400 →
  `chat_error` with exactly 1 request; cancel during backoff → NO callback
  and only 1 request; testKey on persistent 503 → `chat_server_busy` after
  exactly 1 retry.

**Mic flow — verified by code inspection** (live mic needs his phone):
granted path (rationale → system dialog → SpeechInput ur-PK), denied path
(plain dialog + one-tap app Settings in both Chat and Notes), no-recognizer
path (`isRecognitionAvailable` → `mic_no_recognizer` toast), listening
indicator + Stop in both fragments, MainActivity permission forwarding.

**Background playback — verified by code inspection** (screen-off/call/
headset behaviour needs his phone): startForeground with media notification
+ Play/Pause/Stop actions, audio-focus listener (transient/gain/loss),
ACTION_AUDIO_BECOMING_NOISY receiver, bookmark saves, battery-hint dialog.

**Hygiene:** no hardcoded API key (grep), no TODO/FIXME, no androidx imports
(one comment mentions androidx to explain a platform-API choice), gradlew
executable, debug keystore committed (same stable debug key as MCP, so debug
APKs update-install over each other), CI workflow
`.github/workflows/build-debug.yml` (Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24).

**Gate-found defects fixed during reconciliation (6 kotlinc errors +
2 missing strings, all fixed, gate re-run to PASS):** W4 used androidx
BiometricManager API names (`from()`, `BIOMETRIC_WEAK`) with platform
imports — fixed to `getSystemService()` + `Authenticators.BIOMETRIC_WEAK`;
platform BiometricPrompt has no `BIOMETRIC_ERROR_NEGATIVE_BUTTON` constant
(verified with javap) — fixed with the AOSP value 13 + comment; W5
BookSeeder nullable-chain fix; ReadingService `internal` → `private`
chunker; added shared `ok`/`cancel` strings. Coordinator additions:
MainActivity permission forwarding (mic flow), Kitab deep link from the
reading notification (EXTRA_TAB + single-top), battery-optimization
permission in the manifest.

**Worker deviations accepted:** W1 reworded `chat_voice_unsupported` for
native (no "browser"); W4 import accepts HTML's real role (`assistant`) and
settings keys (`lang`/`voiceOn`) + native `time` field on notes;
W3 added `reminder_no_permission` + optional `hijriSuffix` param;
W5 `ReadingState.notifyListeners()` (Object.notify() is final) +
`kitab_voice_off` key for the voice-off guard; W5's chunker verified in
Python against all 9 real books (2,634 chunks, no chunk over 900 chars).

## What honestly needs his phone (never claimed proven here)

Mic on his Vivo (ur-PK recognition, permission grant/deny flow); Urdu TTS
voice availability (Google TTS Urdu pack may need downloading); live Gemini
call with his key (incl. a real busy spell for the 503 path); background
playback with the screen off + incoming-call pause/resume + headset
disconnect; reminder + prayer alarms actually firing at time; fingerprint
unlock on his Vivo; SAF auto-backup write to a Drive-picked file; first-seed
time for the 1.27 MB book; notification actions from the lock screen.

## Files

- Source tree: `~/workspace/deliver/jarvis-native/`
- Source zip: `mobile-jarvis-v1.0.0-source.zip` — 155 files, gate build
  outputs excluded, no .class/.apk/.git content (size + md5 are in the
  delivery message — the zip cannot list its own hash)
- Push file: `push-jarvis-v100.txt` (Termux commands; he creates the repo
  in the GitHub app first — the connector cannot create repos)
- Contracts: `CONTRACTS.md` (worker contract, kept in the tree for future
  fix rounds)
- Gate tooling: `gate/` (aapt2+kotlinc gate, string checker, JVM tests with
  Android stubs — rerunnable for future rounds)
