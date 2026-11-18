package pk.techtonic.jarvis.ui

import android.app.AlertDialog
import android.app.Fragment
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.data.ChatRow
import pk.techtonic.jarvis.net.GeminiClient
import pk.techtonic.jarvis.speech.SpeechInput
import pk.techtonic.jarvis.speech.TtsManager
import pk.techtonic.jarvis.util.MicPermission
import pk.techtonic.jarvis.util.TextCleaner

/**
 * Chat tab: TECHTONICPK AI conversation with Gemini.
 *
 * - History lives in JarvisDb (chat table); the list shows it oldest-first.
 * - User bubbles right (bubble_user), AI bubbles left (bubble_ai).
 * - Every AI answer carries Copy / WhatsApp / Speak actions.
 * - STOP button (shared stop contract): visible while a request is in
 *   flight or while listening; halts NOW, never retries.
 * - Mic: MicPermission.ensure -> SpeechInput; the transcript lands in the
 *   input field (typed input is ALWAYS visible next to the mic).
 * - No key: sending shows chat_no_key plainly, no crash, no network.
 * - Every failure (no key, offline, server busy, API error) is stored
 *   as a visible "error" row in the chat history itself — a 2-second
 *   toast was too easy to miss (phone test 2026-11-18: "na koi error
 *   na koi response"). The toast is kept too; the row is the fix.
 * - onPause cancels any in-flight call; every callback is guarded with
 *   alive() so no orphan callback touches a dead fragment.
 */
class ChatFragment : Fragment() {

    private var listView: ListView? = null
    private var emptyView: View? = null
    private var searchInput: EditText? = null
    private var inputField: EditText? = null
    private var sendButton: Button? = null
    private var micButton: Button? = null
    private var stopButton: Button? = null
    private var typingView: TextView? = null
    private var listeningView: TextView? = null

    private val allMessages = ArrayList<ChatRow>()
    private val shownMessages = ArrayList<ChatRow>()
    private var adapter: ChatAdapter? = null
    private var searchQuery = ""

    private var gemini: GeminiClient? = null
    private var currentCall: GeminiClient.Call? = null
    private var speechInput: SpeechInput? = null
    private var listening = false
    private var awaitingPartial = false
    private var speaking = false
    private var speakingRowId: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_chat, container, false)
        listView = view.findViewById(R.id.chat_list)
        emptyView = view.findViewById(R.id.chat_empty)
        searchInput = view.findViewById(R.id.chat_search)
        inputField = view.findViewById(R.id.chat_input)
        sendButton = view.findViewById(R.id.chat_send)
        micButton = view.findViewById(R.id.chat_mic)
        stopButton = view.findViewById(R.id.chat_stop)
        typingView = view.findViewById(R.id.chat_typing)
        listeningView = view.findViewById(R.id.chat_mic_listening)

        adapter = ChatAdapter()
        listView?.adapter = adapter

        sendButton?.setOnClickListener { send() }
        micButton?.setOnClickListener { onMicPressed() }
        stopButton?.setOnClickListener { onStopPressed() }
        view.findViewById<Button>(R.id.chat_clear).setOnClickListener { onClearPressed() }
        searchInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilter()
            }
        })

        loadMessages()
        scrollToBottom()
        return view
    }

    override fun onPause() {
        // Never leave an orphan request, listener, or voice behind.
        currentCall?.cancel()
        currentCall = null
        if (listening) {
            listening = false
            awaitingPartial = false
            speechInput?.cancel()
        }
        if (speaking) {
            speaking = false
            speakingRowId = -1L
            TtsManager.stop()
        }
        showTyping(false)
        updateListeningUi()
        sendButton?.isEnabled = true
        super.onPause()
    }

    override fun onDestroy() {
        speechInput?.destroy()
        speechInput = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        // MicPermission owns its request code; forward first.
        if (MicPermission.onRequestPermissionsResult(requestCode, grantResults)) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ---- History ----

    private fun mainActivity(): MainActivity? = activity as? MainActivity

    private fun loadMessages() {
        val db = mainActivity()?.db ?: return
        allMessages.clear()
        allMessages.addAll(db.allChat())
        applyFilter()
        updateEmptyView()
    }

    private fun applyFilter() {
        shownMessages.clear()
        if (searchQuery.isEmpty()) {
            shownMessages.addAll(allMessages)
        } else {
            for (row in allMessages) {
                if (row.text.contains(searchQuery, ignoreCase = true)) {
                    shownMessages.add(row)
                }
            }
        }
        adapter?.notifyDataSetChanged()
    }

    private fun updateEmptyView() {
        val empty = allMessages.isEmpty()
        emptyView?.visibility = if (empty) View.VISIBLE else View.GONE
        listView?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun scrollToBottom() {
        val lv = listView ?: return
        val count = adapter?.count ?: 0
        if (count > 0) lv.setSelection(count - 1)
    }

    private fun showTyping(show: Boolean) {
        typingView?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) scrollToBottom()
    }

    private fun updateListeningUi() {
        listeningView?.visibility = if (listening) View.VISIBLE else View.GONE
        micButton?.isSelected = listening
        stopButton?.visibility =
            if (listening || currentCall != null) View.VISIBLE else View.GONE
    }

    // ---- Send ----

    private fun send() {
        val act = mainActivity() ?: return
        val text = inputField?.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) return
        if (currentCall != null) return // one request at a time
        if (!act.prefs.hasKey()) {
            // No key: the failure stays visible in the chat itself —
            // a toast alone was too easy to miss. No network call.
            inputField?.setText("")
            act.db.addChat(ROLE_USER, text)
            addErrorRow(R.string.chat_no_key)
            toast(R.string.chat_no_key)
            return
        }
        inputField?.setText("")
        act.db.addChat(ROLE_USER, text)
        loadMessages()
        scrollToBottom()
        showTyping(true)
        sendButton?.isEnabled = false
        stopButton?.visibility = View.VISIBLE
        val client = gemini ?: GeminiClient(act.prefs).also { gemini = it }
        currentCall = client.generate(
            text,
            onResult = { reply, usedFallback ->
                if (alive()) {
                    currentCall = null
                    showTyping(false)
                    stopButton?.visibility = View.GONE
                    sendButton?.isEnabled = true
                    mainActivity()?.db?.addChat(ROLE_AI, reply)
                    loadMessages()
                    scrollToBottom()
                    if (usedFallback) {
                        // Plain note only — never stored in the history.
                        toast(R.string.chat_fallback_used)
                    }
                }
            },
            onError = { key ->
                if (alive()) {
                    currentCall = null
                    showTyping(false)
                    stopButton?.visibility = View.GONE
                    sendButton?.isEnabled = true
                    // Persistent row in the chat itself + the toast.
                    addErrorRow(errorStringRes(key))
                    toast(errorStringRes(key))
                }
            }
        )
    }

    /** Store a failure as a visible row in the chat history itself. */
    private fun addErrorRow(messageRes: Int) {
        val act = mainActivity() ?: return
        act.db.addChat(ROLE_ERROR, act.getString(messageRes))
        loadMessages()
        scrollToBottom()
    }

    // ---- Stop (shared stop contract: halt NOW, never retry) ----

    private fun onStopPressed() {
        if (listening) {
            stopListening()
            toast(R.string.stopped)
            return
        }
        val call = currentCall
        if (call != null) {
            currentCall = null
            call.cancel()
            showTyping(false)
            stopButton?.visibility = View.GONE
            sendButton?.isEnabled = true
            toast(R.string.chat_stopped)
        }
    }

    // ---- Mic ----

    private fun onMicPressed() {
        val act = activity ?: return
        if (listening) {
            stopListening()
            toast(R.string.stopped)
            return
        }
        MicPermission.ensure(
            act,
            onGranted = { startListening() },
            onDenied = { showMicDeniedDialog() }
        )
    }

    private fun startListening() {
        val act = activity ?: return
        val input = speechInput
            ?: SpeechInput(act.applicationContext).also { speechInput = it }
        listening = true
        awaitingPartial = false
        updateListeningUi()
        input.start(
            onResult = { transcript ->
                if (alive()) {
                    listening = false
                    awaitingPartial = false
                    updateListeningUi()
                    inputField?.setText(transcript)
                    inputField?.setSelection(transcript.length)
                }
            },
            onError = { key ->
                if (alive()) {
                    listening = false
                    awaitingPartial = false
                    updateListeningUi()
                    toast(voiceErrorStringRes(key))
                }
            }
        )
    }

    /** Stop = SpeechInput.stop(): partial results are still delivered. */
    private fun stopListening() {
        listening = false
        awaitingPartial = true
        speechInput?.stop()
        updateListeningUi()
    }

    private fun showMicDeniedDialog() {
        val act = activity ?: return
        AlertDialog.Builder(act)
            .setMessage(R.string.mic_denied)
            .setPositiveButton(R.string.mic_open_settings) { _, _ ->
                MicPermission.openAppSettings(act)
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ---- Clear ----

    private fun onClearPressed() {
        val act = activity ?: return
        AlertDialog.Builder(act)
            .setMessage(R.string.chat_clear_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                mainActivity()?.db?.clearChat()
                loadMessages()
                toast(R.string.chat_cleared)
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ---- Per-answer actions ----

    private fun copyAnswer(text: String) {
        val act = activity ?: return
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("JARVIS", TextCleaner.markdownToPlain(text))
            )
            toast(R.string.chat_copied)
        } catch (e: Exception) {
            toast(R.string.chat_copy_failed)
        }
    }

    private fun shareAnswer(text: String) {
        val plain = TextCleaner.markdownToPlain(text)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, plain)
        }
        try {
            // WhatsApp first; fall back to the chooser when it is absent.
            val waIntent = Intent(sendIntent).setPackage("com.whatsapp")
            startActivity(waIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent.createChooser(sendIntent, getString(R.string.chat_share_whatsapp))
                )
            } catch (e2: ActivityNotFoundException) {
                toast(R.string.chat_send_failed)
            }
        } catch (e: Exception) {
            toast(R.string.chat_send_failed)
        }
    }

    private fun speakAnswer(row: ChatRow) {
        TtsManager.stop()
        speaking = true
        speakingRowId = row.id
        adapter?.notifyDataSetChanged()
        // TtsManager strips Markdown itself (TextCleaner.stripForSpeech).
        TtsManager.speak(
            row.text,
            onDone = {
                if (alive()) {
                    speaking = false
                    speakingRowId = -1L
                    adapter?.notifyDataSetChanged()
                }
            },
            onError = {
                if (alive()) {
                    speaking = false
                    speakingRowId = -1L
                    adapter?.notifyDataSetChanged()
                }
            }
        )
    }

    private fun stopSpeaking() {
        TtsManager.stop()
        speaking = false
        speakingRowId = -1L
        adapter?.notifyDataSetChanged()
        toast(R.string.stopped)
    }

    // ---- Helpers ----

    /** True only while the fragment is attached and can touch views. */
    private fun alive(): Boolean = isAdded && activity != null

    private fun toast(resId: Int) {
        val act = activity ?: return
        Toast.makeText(act, resId, Toast.LENGTH_SHORT).show()
    }

    private fun errorStringRes(key: String): Int = when (key) {
        "chat_no_key" -> R.string.chat_no_key
        "chat_api_error" -> R.string.chat_api_error
        "chat_empty_reply" -> R.string.chat_empty_reply
        "chat_server_busy" -> R.string.chat_server_busy
        "chat_offline" -> R.string.chat_offline
        "chat_error" -> R.string.chat_error
        else -> R.string.chat_send_failed
    }

    private fun voiceErrorStringRes(key: String): Int = when (key) {
        "chat_voice_unsupported" -> R.string.chat_voice_unsupported
        "mic_no_recognizer" -> R.string.mic_no_recognizer
        else -> R.string.chat_voice_failed
    }

    // ---- List ----

    private inner class ChatAdapter : BaseAdapter() {

        override fun getCount(): Int = shownMessages.size

        override fun getItem(position: Int): Any = shownMessages[position]

        override fun getItemId(position: Int): Long = shownMessages[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = shownMessages[position]
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message, parent, false)
            val root = view.findViewById<LinearLayout>(R.id.chat_item_root)
            val bubble = view.findViewById<LinearLayout>(R.id.chat_item_bubble)
            val messageView = view.findViewById<TextView>(R.id.chat_item_text)
            val actions = view.findViewById<LinearLayout>(R.id.chat_item_actions)
            val copyBtn = view.findViewById<Button>(R.id.chat_item_copy)
            val shareBtn = view.findViewById<Button>(R.id.chat_item_share)
            val speakBtn = view.findViewById<Button>(R.id.chat_item_speak)

            val isUser = row.role == ROLE_USER
            val isError = row.role == ROLE_ERROR
            root.gravity = if (isUser) Gravity.END else Gravity.START
            bubble.setBackgroundResource(
                if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_ai
            )
            // Error rows carry the plain "خرابی: " / "Error: " marker so
            // a failure can never be mistaken for an answer.
            messageView.text = if (isError) {
                getString(R.string.chat_error_prefix) + row.text
            } else {
                TextCleaner.toDisplaySpanned(row.text)
            }

            if (isUser || isError) {
                // No copy/share/speak actions on user rows or error rows.
                actions.visibility = View.GONE
            } else {
                actions.visibility = View.VISIBLE
                val isSpeakingThis = speaking && speakingRowId == row.id
                speakBtn.setText(
                    if (isSpeakingThis) R.string.chat_stop_speaking else R.string.chat_speak
                )
                copyBtn.setOnClickListener { copyAnswer(row.text) }
                shareBtn.setOnClickListener { shareAnswer(row.text) }
                speakBtn.setOnClickListener {
                    if (isSpeakingThis) stopSpeaking() else speakAnswer(row)
                }
            }
            return view
        }
    }

    companion object {
        private const val ROLE_USER = "user"
        private const val ROLE_AI = "ai"

        /** Failure rows (no key / offline / server busy / API error). */
        private const val ROLE_ERROR = "error"
    }
}
