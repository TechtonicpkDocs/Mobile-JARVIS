package pk.techtonic.jarvis.ui

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Fragment
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.alerts.ReminderScheduler
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.data.NoteRow
import pk.techtonic.jarvis.speech.SpeechInput
import pk.techtonic.jarvis.util.MicPermission
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Notes & Reminders tab (W3): CRUD notes from JarvisDb, date/time pickers
 * for reminder alerts, search, quick-add chips, and voice notes via the
 * shared MicPermission + SpeechInput flow (W2).
 *
 * A reminder alarm is set only when date AND time are both set and in the
 * future. Date without time saves the note with no alarm. Deleting a note
 * cancels its alarm.
 */
class NotesFragment : Fragment() {

    private lateinit var db: JarvisDb
    private lateinit var noteInput: EditText
    private lateinit var btnDate: Button
    private lateinit var btnTime: Button
    private lateinit var btnMic: Button
    private lateinit var btnMicStop: Button
    private lateinit var listeningText: TextView
    private lateinit var searchInput: EditText
    private lateinit var noteListView: ListView
    private lateinit var emptyText: TextView

    private var selectedDate = ""
    private var selectedTime = ""
    private var query = ""

    private var adapter: NoteAdapter? = null
    private var speechInput: SpeechInput? = null
    private var listening = false

    /** Reminder waiting on the POST_NOTIFICATIONS grant (API 33+). */
    private var pendingReminderNoteId = -1L
    private var pendingReminderDate = ""
    private var pendingReminderTime = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_notes, container, false)
        db = (activity as MainActivity).db
        speechInput = SpeechInput(activity)

        noteInput = view.findViewById(R.id.note_input)
        btnDate = view.findViewById(R.id.note_date_btn)
        btnTime = view.findViewById(R.id.note_time_btn)
        btnMic = view.findViewById(R.id.note_mic_btn)
        btnMicStop = view.findViewById(R.id.note_mic_stop)
        listeningText = view.findViewById(R.id.note_listening)
        searchInput = view.findViewById(R.id.note_search)
        noteListView = view.findViewById(R.id.note_list)
        emptyText = view.findViewById(R.id.note_empty)

        // Quick-add chips insert preset text; they never auto-save.
        view.findViewById<Button>(R.id.note_chip_home).setOnClickListener {
            insertChip(getString(R.string.note_chip_home))
        }
        view.findViewById<Button>(R.id.note_chip_shop).setOnClickListener {
            insertChip(getString(R.string.note_chip_shop))
        }
        view.findViewById<Button>(R.id.note_chip_milk).setOnClickListener {
            insertChip(getString(R.string.note_chip_milk))
        }
        view.findViewById<Button>(R.id.note_chip_reminder).setOnClickListener {
            insertChip(getString(R.string.note_chip_reminder))
        }

        btnDate.setOnClickListener { pickDate() }
        btnTime.setOnClickListener { pickTime() }
        view.findViewById<Button>(R.id.note_add_btn).setOnClickListener { addNote() }
        btnMic.setOnClickListener { startVoiceNote() }
        btnMicStop.setOnClickListener { stopListening() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                query = s.toString()
                refreshList()
            }
        })

        adapter = NoteAdapter()
        noteListView.adapter = adapter
        refreshList()
        return view
    }

    override fun onDestroyView() {
        speechInput?.destroy()
        speechInput = null
        listening = false
        super.onDestroyView()
    }

    /**
     * Forwards the mic permission result to MicPermission (W2) and handles
     * the notification-permission result for reminders. NOTE: MicPermission
     * requests through the Activity, so the result reaches this fragment
     * only when MainActivity forwards onRequestPermissionsResult to its
     * fragments — see the coordinator handoff note.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (MicPermission.onRequestPermissionsResult(requestCode, grantResults)) return
        if (requestCode == REQUEST_NOTIFY) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            val noteId = pendingReminderNoteId
            val date = pendingReminderDate
            val time = pendingReminderTime
            pendingReminderNoteId = -1L
            pendingReminderDate = ""
            pendingReminderTime = ""
            if (!isAdded) return
            if (granted && noteId >= 0) {
                if (ReminderScheduler.schedule(activity, noteId, date, time)) {
                    Toast.makeText(activity, R.string.reminder_set, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, R.string.reminder_past, Toast.LENGTH_SHORT).show()
                }
            } else if (noteId >= 0) {
                Toast.makeText(activity, R.string.reminder_no_permission, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Add / pickers ----------

    private fun insertChip(preset: String) {
        val current = noteInput.text.toString()
        noteInput.setText(
            if (current.isBlank()) preset else current.trimEnd() + "\n" + preset
        )
        noteInput.setSelection(noteInput.text.length)
        noteInput.requestFocus()
    }

    private fun pickDate() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                selectedDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                btnDate.text = selectedDate
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickTime() {
        val now = Calendar.getInstance()
        TimePickerDialog(
            activity,
            { _, hour, minute ->
                selectedTime = String.format(Locale.US, "%02d:%02d", hour, minute)
                btnTime.text = selectedTime
            },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun addNote() {
        val text = noteInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(activity, R.string.note_empty_text, Toast.LENGTH_SHORT).show()
            noteInput.requestFocus()
            return
        }
        val date = selectedDate
        val time = selectedTime
        val id = db.addNote(text, date, time)
        noteInput.setText("")
        selectedDate = ""
        selectedTime = ""
        btnDate.setText(R.string.note_date_label)
        btnTime.setText(R.string.note_time_label)
        refreshList()
        Toast.makeText(activity, R.string.note_added, Toast.LENGTH_SHORT).show()

        // Reminder alarm only when date AND time are both set.
        if (date.isNotEmpty() && time.isNotEmpty()) {
            val triggerAt = ReminderScheduler.parseDateTime(date, time)
            if (triggerAt == null || triggerAt <= System.currentTimeMillis()) {
                Toast.makeText(activity, R.string.reminder_past, Toast.LENGTH_SHORT).show()
                return
            }
            requestReminder(id, date, time)
        }
    }

    /** Schedule the reminder, asking for POST_NOTIFICATIONS first on API 33+. */
    private fun requestReminder(noteId: Long, date: String, time: String) {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingReminderNoteId = noteId
            pendingReminderDate = date
            pendingReminderTime = time
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFY
            )
            return
        }
        if (ReminderScheduler.schedule(act, noteId, date, time)) {
            Toast.makeText(act, R.string.reminder_set, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(act, R.string.reminder_past, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- List ----------

    private fun refreshList() {
        val all = try {
            db.allNotes()
        } catch (ignored: Exception) {
            emptyList()
        }
        val q = query.trim().lowercase(Locale.getDefault())
        val shown = if (q.isEmpty()) {
            all
        } else {
            all.filter { it.text.lowercase(Locale.getDefault()).contains(q) }
        }
        adapter?.setNotes(shown)
        if (shown.isEmpty()) {
            emptyText.setText(if (q.isEmpty()) R.string.note_empty else R.string.note_no_result)
            emptyText.visibility = View.VISIBLE
            noteListView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            noteListView.visibility = View.VISIBLE
        }
    }

    private fun togglePin(note: NoteRow) {
        db.setNotePinned(note.id, !note.pinned)
        refreshList()
    }

    private fun deleteNote(note: NoteRow) {
        AlertDialog.Builder(activity)
            .setMessage(R.string.note_delete_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                db.deleteNote(note.id)
                ReminderScheduler.cancel(activity, note.id)
                refreshList()
                Toast.makeText(activity, R.string.note_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private inner class NoteAdapter : BaseAdapter() {
        private var notes: List<NoteRow> = emptyList()

        fun setNotes(list: List<NoteRow>) {
            notes = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = notes.size
        override fun getItem(position: Int): Any = notes[position]
        override fun getItemId(position: Int): Long = notes[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(activity)
                .inflate(R.layout.item_note, parent, false)
            val note = notes[position]
            row.findViewById<TextView>(R.id.item_note_text).text = note.text
            val dateView = row.findViewById<TextView>(R.id.item_note_date)
            if (note.date.isNotEmpty()) {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(Calendar.getInstance().time)
                val badge = StringBuilder(note.date)
                if (note.time.isNotEmpty()) badge.append(" ").append(note.time)
                if (note.date == today) {
                    badge.append(" · ").append(getString(R.string.note_today))
                }
                dateView.text = badge.toString()
                dateView.visibility = View.VISIBLE
            } else {
                dateView.visibility = View.GONE
            }
            val pinBtn = row.findViewById<Button>(R.id.item_note_pin)
            pinBtn.setText(if (note.pinned) R.string.note_unpin else R.string.note_pin)
            pinBtn.setOnClickListener { togglePin(note) }
            row.findViewById<Button>(R.id.item_note_delete).setOnClickListener {
                deleteNote(note)
            }
            return row
        }
    }

    // ---------- Voice notes ----------

    private fun startVoiceNote() {
        val act = activity ?: return
        if (listening) return
        MicPermission.ensure(
            act,
            onGranted = { beginListening() },
            onDenied = { showMicDenied() }
        )
    }

    private fun beginListening() {
        if (!isAdded) return
        listening = true
        listeningText.visibility = View.VISIBLE
        btnMicStop.visibility = View.VISIBLE
        btnMic.isEnabled = false
        speechInput?.start(
            onResult = { transcript ->
                if (!isAdded) return@start
                hideListeningUi()
                val text = transcript.trim()
                if (text.isEmpty()) return@start
                db.addNote(text, "", "")
                refreshList()
                Toast.makeText(
                    activity,
                    getString(R.string.voice_note_saved).replace("{text}", text),
                    Toast.LENGTH_LONG
                ).show()
            },
            onError = { key ->
                if (!isAdded) return@start
                hideListeningUi()
                Toast.makeText(activity, voiceErrorText(key), Toast.LENGTH_SHORT).show()
            }
        )
    }

    /** Stop delivers partial results (SpeechInput.stop), which arrive as a
        normal result and are saved as a voice note. */
    private fun stopListening() {
        speechInput?.stop()
        hideListeningUi()
    }

    private fun hideListeningUi() {
        listening = false
        listeningText.visibility = View.GONE
        btnMicStop.visibility = View.GONE
        btnMic.isEnabled = true
    }

    private fun showMicDenied() {
        if (!isAdded) return
        AlertDialog.Builder(activity)
            .setMessage(R.string.mic_denied)
            .setPositiveButton(R.string.mic_open_settings) { _, _ ->
                activity?.let { MicPermission.openAppSettings(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** SpeechInput reports errors as string keys ("mic_no_recognizer",
        "chat_voice_failed", "chat_voice_unsupported"). Resolve the key;
        fall back to the generic voice-failed message when it is missing. */
    private fun voiceErrorText(key: String): String {
        val resId = resources.getIdentifier(key, "string", activity.packageName)
        return if (resId != 0) getString(resId) else getString(R.string.chat_voice_failed)
    }

    companion object {
        private const val REQUEST_NOTIFY = 2001
    }
}
