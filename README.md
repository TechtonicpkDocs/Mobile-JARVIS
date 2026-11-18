# Mobile JARVIS — TechtonicPK

Personal AI assistant for Android, native Kotlin. Urdu-first, Gemini brain
(user's own free API key, entered in Settings — never hardcoded), fully
offline UI; only the Gemini brain, weather and prayer times need the net.

Inspired by Stanford's OpenJarvis (idea + skills + morning briefing),
rebuilt Muhammad Riyaz's own way for mobile. NOT a port of OpenJarvis's
Python code.

## Features (v1.0.0)

1. **Chat** — Gemini (`gemini-3.5-flash` default, changeable), history,
   search/clear, copy + WhatsApp share, typing indicator. No key = plain
   message, zero network calls.
2. **Voice** — tap-to-talk (ur-PK, en-US fallback), spoken replies.
   Voice picker: any installed device TTS voice + rate/pitch controls.
3. **Morning Briefing** — Urdu date + Hijri, Layyah weather (Open-Meteo,
   no key), prayer times (Aladhan, method 1), today's notes,
   scholar-of-the-day shortcut. Spoken briefing.
4. **Bayan** — 6 Ahle Sunnat scholar cards, YouTube search links only.
5. **Notes & Reminders** — CRUD, search, pin, date badges, quick-add chips.
6. **Hisab Tools** — safe calculator, milk-sale hisab (integer-paisa math),
   unit converter (kg/maund, metre/yard/gaz).
7. **Settings** — Gemini key + Test Key, model, Urdu/English toggle,
   JSON backup export/import, About.

## Stop controls (user requirement, from his own phone test)

Every long/ongoing operation has a visible STOP button: stop TTS
mid-speech, stop listening, cancel an in-flight Gemini request, stop the
spoken briefing. Stop means halt NOW — nothing resumes or retries without
his word.

## Spoken answers (user requirement)

Gemini is instructed to reply in plain natural Urdu prose with NO Markdown
symbols. Before ANY text reaches TTS, all Markdown/symbols are stripped.
In the chat UI, `**bold**` renders as real bold — raw markers are never
visible on screen.

## Build

GitHub Actions only (`.github/workflows/build-debug.yml`): push to `main`,
download the debug APK artifact. Debug signing uses the committed
`debug.keystore` (standard debug credentials) so debug APKs update-install
over each other.

- Package: `pk.techtonic.jarvis`
- minSdk 24 (Android 7.0), targetSdk 34
- No third-party dependencies — Android SDK + Kotlin stdlib only.
- Storage: SQLite. Data stays on the device.

powered by TECHTONICPK (03200989317)
