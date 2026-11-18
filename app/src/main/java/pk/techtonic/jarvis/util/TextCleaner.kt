package pk.techtonic.jarvis.util

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan

/**
 * Markdown / symbol cleaning — the fix for the user's "sab se bara masla":
 * Gemini's `**bold**` markers were read aloud by TTS as "star star".
 *
 * Three layers (see CONTRACTS.md):
 * 1. GeminiClient's SYSTEM_PROMPT asks for no Markdown at all.
 * 2. [stripForSpeech] removes every remaining marker before TTS.
 * 3. [toDisplaySpanned] renders `**bold**` as real bold spans on screen.
 *
 * [stripForSpeech] and [markdownToPlain] are pure Kotlin (no Android API)
 * so they can be unit-tested on the JVM. Only [toDisplaySpanned] touches
 * Android classes.
 *
 * Cleaning rules (exact, from the contract):
 * - `@@Arabic@@` spans: the `@@` signs go, the Arabic words STAY (real
 *   Quranic/Arabic text — never drop the words).
 * - `**bold**` -> the words, no stars. Single `*`, `#`, `_`, backticks,
 *   `~`, backslashes, HTML tags: dropped, words kept.
 * - `[text](url)` links: keep the text, drop the URL. `![alt](url)` images:
 *   keep the alt text.
 * - `## Heading` -> the heading words as a plain title (kept).
 * - `>` quote markers and `---` rules: dropped.
 * - The result contains NO `*`, NO `#`, NO backtick, NO `@`, NO `_`.
 */
object TextCleaner {

    private val HTML_TAG = Regex("""<[^>]*>""")
    private val ARABIC_SPAN = Regex("""@@(.*?)@@""", RegexOption.DOT_MATCHES_ALL)
    private val IMAGE_LINK = Regex("""!\[([^\]]*)\]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE_RUN = Regex("""\s+""")

    /**
     * Pure Kotlin: strip ALL Markdown/symbols for TTS. Collapses all
     * whitespace/newlines to single spaces. The result contains no `*`,
     * no `#`, no backtick, no `@`, no `_`.
     */
    fun stripForSpeech(text: String): String {
        return WHITESPACE_RUN.replace(markdownToPlain(text), " ").trim()
    }

    /**
     * Pure Kotlin: plain text with markers removed but words kept, kept as
     * readable text with paragraph breaks (for copy/share).
     */
    fun markdownToPlain(text: String): String {
        return tidyLines(stripMarkers(text))
    }

    /**
     * Display form: `**bold**` -> real bold spans, `@@...@@` -> the Arabic
     * words shown plainly (no @ signs), all other markers removed.
     * Raw markers are NEVER visible on screen.
     */
    fun toDisplaySpanned(text: String): Spanned {
        // Pre-clean everything EXCEPT **bold** markers, so the bold ranges
        // survive; tidy the lines first so ranges are computed on the
        // final text.
        var cleaned = HTML_TAG.replace(text, "")
        cleaned = ARABIC_SPAN.replace(cleaned, "\$1")
        cleaned = IMAGE_LINK.replace(cleaned, "\$1")
        cleaned = LINK.replace(cleaned, "\$1")
        cleaned = tidyLines(cleaned)

        val builder = SpannableStringBuilder()
        var lastEnd = 0
        for (match in BOLD.findAll(cleaned)) {
            appendCleaned(builder, cleaned.substring(lastEnd, match.range.first), false)
            appendCleaned(builder, match.groupValues[1], true)
            lastEnd = match.range.last + 1
        }
        appendCleaned(builder, cleaned.substring(lastEnd), false)
        return builder
    }

    /** Remove every marker (including `**`), keeping the words. */
    private fun stripMarkers(text: String): String {
        var s = HTML_TAG.replace(text, "")
        s = ARABIC_SPAN.replace(s, "\$1")
        s = IMAGE_LINK.replace(s, "\$1")
        s = LINK.replace(s, "\$1")
        s = BOLD.replace(s, "\$1")
        s = removeStrayChars(s)
        return s
    }

    /** Drop the leftover symbol characters (words are kept). */
    private fun removeStrayChars(text: String): String {
        return text.replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace("_", "")
            .replace("@", "")
            .replace("~", "")
            .replace("\\", "")
    }

    /**
     * Per-line tidy, shared by the plain and display forms: drop leading
     * `>` quote markers and dash-only rule lines, trim each line, collapse
     * runs of blank lines to a single blank line.
     */
    private fun tidyLines(text: String): String {
        val out = StringBuilder()
        var pendingBlank = false
        var started = false
        for (rawLine in text.split("\n")) {
            var line = rawLine.trim()
            if (line.startsWith(">")) {
                line = line.substring(1).trim()
            }
            if (line.isEmpty() || line.all { it == '-' }) {
                if (started) pendingBlank = true
                continue
            }
            if (started) {
                out.append("\n")
                if (pendingBlank) out.append("\n")
            }
            out.append(line)
            started = true
            pendingBlank = false
        }
        return out.toString()
    }

    /** Append one chunk to the display builder, bold when flagged. */
    private fun appendCleaned(
        builder: SpannableStringBuilder,
        chunk: String,
        bold: Boolean
    ) {
        if (chunk.isEmpty()) return
        val cleaned = removeStrayChars(chunk)
        if (cleaned.isEmpty()) return
        val start = builder.length
        builder.append(cleaned)
        if (bold) {
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
