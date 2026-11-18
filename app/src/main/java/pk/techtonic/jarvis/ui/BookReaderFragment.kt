package pk.techtonic.jarvis.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Fragment
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.data.BookRow
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.data.JarvisPrefs
import pk.techtonic.jarvis.reader.BookSeeder
import pk.techtonic.jarvis.reader.ReadingService
import pk.techtonic.jarvis.reader.ReadingState
import pk.techtonic.jarvis.speech.TtsManager
import pk.techtonic.jarvis.util.TextCleaner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kitab tab (tab 6): the book library + the reader, in one fragment.
 *
 * LIBRARY mode (default): list of books (title + word count + progress %
 * + last-read date); tap a book to open the reader; delete per row (with
 * confirm — deleting a pre-loaded book removes it, it does NOT re-seed);
 * "Add book" opens a dialog (title + paste text + upload .txt/.md).
 * PDF upload stays DEFERRED: the dialog says so plainly, and no PDF
 * extraction is attempted anywhere.
 *
 * READER mode: book title, progress bar + "chunk X of Y" + %, the current
 * chunk's text, Play / Pause / Resume / Stop (all visible; Stop halts NOW
 * via the service and saves the bookmark), and a speed SeekBar (0.5x–2.0x,
 * writes prefs.ttsRate + TtsManager.applySettings()).
 *
 * Playback itself lives in [ReadingService] (foreground service) so the
 * book keeps playing with the screen off. This fragment only sends
 * commands and mirrors [ReadingState].
 */
class BookReaderFragment : Fragment() {

    private lateinit var db: JarvisDb
    private lateinit var prefs: JarvisPrefs

    private var books: List<BookRow> = emptyList()
    private var libraryLoaded = false
    private var loadingLibrary = false
    private var seeding = false
    private var autoOpenAttempted = false
    private var currentBookId: Long = -1L
    private var inReader = false
    private val wordCountCache = HashMap<Long, Int>()

    // Library views
    private var libraryView: View? = null
    private var readerView: View? = null
    private var bookListView: ListView? = null
    private var loadingText: TextView? = null
    private var emptyText: TextView? = null
    private var listAdapter: BookAdapter? = null

    // Reader views
    private var readerTitle: TextView? = null
    private var readerChunkLabel: TextView? = null
    private var readerProgressLabel: TextView? = null
    private var readerProgressBar: ProgressBar? = null
    private var readerChunkText: TextView? = null
    private var playButton: Button? = null
    private var pauseButton: Button? = null
    private var resumeButton: Button? = null
    private var stopButton: Button? = null
    private var speedSeekBar: SeekBar? = null
    private var speedLabel: TextView? = null

    // Add-book dialog (kept so the file-picker result can fill it).
    private var addDialog: AlertDialog? = null
    private var addTitleField: EditText? = null
    private var addTextField: EditText? = null

    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private val stateListener: () -> Unit = { refreshReaderUi() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val act = activity as? MainActivity ?: return null
        db = act.db
        prefs = act.prefs
        val root = inflater.inflate(R.layout.fragment_book_reader, container, false)

        libraryView = root.findViewById(R.id.kitab_library_view)
        readerView = root.findViewById(R.id.kitab_reader_view)
        bookListView = root.findViewById(R.id.kitab_book_list)
        loadingText = root.findViewById(R.id.kitab_loading_text)
        emptyText = root.findViewById(R.id.kitab_empty_text)
        readerTitle = root.findViewById(R.id.kitab_reader_title)
        readerChunkLabel = root.findViewById(R.id.kitab_reader_chunk)
        readerProgressLabel = root.findViewById(R.id.kitab_reader_percent)
        readerProgressBar = root.findViewById(R.id.kitab_reader_progress)
        readerChunkText = root.findViewById(R.id.kitab_reader_text)
        playButton = root.findViewById(R.id.kitab_play_button)
        pauseButton = root.findViewById(R.id.kitab_pause_button)
        resumeButton = root.findViewById(R.id.kitab_resume_button)
        stopButton = root.findViewById(R.id.kitab_stop_button)
        speedSeekBar = root.findViewById(R.id.kitab_speed_bar)
        speedLabel = root.findViewById(R.id.kitab_speed_label)

        root.findViewById<Button>(R.id.kitab_add_button)
            .setOnClickListener { showAddBookDialog() }
        root.findViewById<Button>(R.id.kitab_back_button)
            .setOnClickListener { showLibrary() }
        playButton?.setOnClickListener { onPlayClicked() }
        pauseButton?.setOnClickListener {
            sendServiceCommand(ReadingService.ACTION_PAUSE)
        }
        resumeButton?.setOnClickListener {
            sendServiceCommand(ReadingService.ACTION_RESUME)
        }
        stopButton?.setOnClickListener {
            sendServiceCommand(ReadingService.ACTION_STOP)
            Toast.makeText(activity, R.string.stopped, Toast.LENGTH_SHORT).show()
        }
        setupSpeedBar()

        listAdapter = BookAdapter()
        bookListView?.adapter = listAdapter
        bookListView?.setOnItemClickListener { _, _, position, _ ->
            val book = books.getOrNull(position) ?: return@setOnItemClickListener
            openReader(book.id)
        }

        // First open: seed the pre-loaded library on a background thread.
        if (!prefs.booksSeeded && !seeding) {
            seeding = true
            showLoading(true)
            BookSeeder.seedIfNeeded(act.applicationContext, db, prefs) {
                seeding = false
                if (isAdded) refreshLibrary()
            }
        } else {
            refreshLibrary()
        }
        return root
    }

    override fun onResume() {
        super.onResume()
        ReadingState.addListener(stateListener)
        refreshReaderUi()
    }

    override fun onPause() {
        ReadingState.removeListener(stateListener)
        super.onPause()
    }

    override fun onDestroyView() {
        addDialog?.dismiss()
        addDialog = null
        addTitleField = null
        addTextField = null
        libraryView = null
        readerView = null
        bookListView = null
        loadingText = null
        emptyText = null
        listAdapter = null
        readerTitle = null
        readerChunkLabel = null
        readerProgressLabel = null
        readerProgressBar = null
        readerChunkText = null
        playButton = null
        pauseButton = null
        resumeButton = null
        stopButton = null
        speedSeekBar = null
        speedLabel = null
        super.onDestroyView()
    }

    // ---- Library ----

    private fun refreshLibrary() {
        if (loadingLibrary || !isAdded) return
        loadingLibrary = true
        Thread {
            val loaded = try {
                db.allBooks()
            } catch (e: Exception) {
                emptyList<BookRow>()
            }
            activity?.runOnUiThread {
                loadingLibrary = false
                if (!isAdded) return@runOnUiThread
                books = loaded
                wordCountCache.clear()
                libraryLoaded = true
                showLoading(false)
                listAdapter?.notifyDataSetChanged()
                updateLibraryEmptyState()
                // If a book is playing (or paused) and this is the first
                // load of this fragment, open its reader so the controls
                // are right there. Only once: the Back button must stay
                // in the library afterwards.
                if (!autoOpenAttempted) {
                    autoOpenAttempted = true
                    val liveId = ReadingState.bookId
                    if (liveId != -1L && ReadingState.chunkCount > 0 &&
                        books.any { it.id == liveId }
                    ) {
                        openReader(liveId)
                    }
                }
            }
        }.start()
    }

    private fun showLoading(show: Boolean) {
        loadingText?.visibility = if (show) View.VISIBLE else View.GONE
        bookListView?.visibility = if (show) View.GONE else View.VISIBLE
        if (show) emptyText?.visibility = View.GONE
    }

    private fun updateLibraryEmptyState() {
        val empty = books.isEmpty()
        emptyText?.visibility =
            if (empty && libraryLoaded) View.VISIBLE else View.GONE
        bookListView?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showLibrary() {
        inReader = false
        readerView?.visibility = View.GONE
        libraryView?.visibility = View.VISIBLE
        refreshLibrary()
    }

    private fun confirmDeleteBook(book: BookRow) {
        val act = activity ?: return
        AlertDialog.Builder(act)
            .setMessage(getString(R.string.kitab_delete_confirm, book.title))
            .setPositiveButton(R.string.yes) { _, _ -> deleteBook(book) }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun deleteBook(book: BookRow) {
        // If this book is playing, stop it first.
        if (ReadingState.bookId == book.id && ReadingState.chunkCount > 0) {
            sendServiceCommand(ReadingService.ACTION_STOP)
        }
        Thread {
            try {
                db.deleteBook(book.id)
            } catch (e: Exception) {
                // Delete failed: the refresh below shows the truth.
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (currentBookId == book.id) {
                    currentBookId = -1L
                    showLibrary()
                } else {
                    refreshLibrary()
                }
                Toast.makeText(activity, R.string.kitab_deleted, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private inner class BookAdapter : BaseAdapter() {
        override fun getCount(): Int = books.size

        override fun getItem(position: Int): Any = books[position]

        override fun getItemId(position: Int): Long = books[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_book, parent, false)
            val book = books[position]
            val titleView = view.findViewById<TextView>(R.id.kitab_item_title)
            val metaView = view.findViewById<TextView>(R.id.kitab_item_meta)
            val deleteButton = view.findViewById<Button>(R.id.kitab_item_delete)
            titleView.text = book.title
            val words = wordCountCache.getOrPut(book.id) { countWords(book.text) }
            val total = book.text.length
            val pct = if (total > 0) (book.position * 100 / total) else 0
            metaView.text = getString(R.string.kitab_words, words) + "  •  " +
                getString(R.string.kitab_progress, pct) + "  •  " +
                getString(R.string.kitab_last_read, dateFormat.format(Date(book.updated)))
            deleteButton.setOnClickListener { confirmDeleteBook(book) }
            return view
        }
    }

    private fun countWords(text: String): Int {
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

    // ---- Add book ----

    private fun showAddBookDialog() {
        val act = activity ?: return
        val view = LayoutInflater.from(act).inflate(R.layout.dialog_add_book, null)
        val titleField = view.findViewById<EditText>(R.id.kitab_add_title)
        val textField = view.findViewById<EditText>(R.id.kitab_add_text)
        val uploadButton = view.findViewById<Button>(R.id.kitab_add_upload)
        val saveButton = view.findViewById<Button>(R.id.kitab_add_save)
        addTitleField = titleField
        addTextField = textField
        // Save stays disabled until both fields are non-blank.
        saveButton.isEnabled = false
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveButton.isEnabled = titleField.text.toString().isNotBlank() &&
                    textField.text.toString().isNotBlank()
            }
        }
        titleField.addTextChangedListener(watcher)
        textField.addTextChangedListener(watcher)
        uploadButton.setOnClickListener { pickBookFile() }
        saveButton.setOnClickListener {
            val title = titleField.text.toString().trim()
            val text = textField.text.toString()
            if (title.isBlank() || text.isBlank()) return@setOnClickListener
            Thread {
                try {
                    db.addBook(title, text)
                } catch (e: Exception) {
                    // Insert failed: the refresh below shows the truth.
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    addDialog?.dismiss()
                    Toast.makeText(activity, R.string.kitab_added, Toast.LENGTH_SHORT).show()
                    refreshLibrary()
                }
            }.start()
        }
        addDialog = AlertDialog.Builder(act)
            .setView(view)
            .setCancelable(true)
            .show()
    }

    private fun pickBookFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_BOOK)
        } catch (e: Exception) {
            // No file picker on the device: paste stays available.
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PICK_BOOK) {
            if (resultCode == Activity.RESULT_OK) {
                val uri = data?.data
                if (uri != null) loadBookFile(uri)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun loadBookFile(uri: Uri) {
        val act = activity ?: return
        Thread {
            var text: String? = null
            var name: String? = null
            try {
                act.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    text = it.readText()
                }
                act.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx)
                    }
                }
            } catch (e: Exception) {
                text = null
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val loaded = text
                if (loaded.isNullOrBlank()) return@runOnUiThread
                addTextField?.setText(loaded)
                // Fill the title from the file name when it is empty.
                val titleField = addTitleField
                if (titleField != null && titleField.text.toString().isBlank() &&
                    !name.isNullOrBlank()
                ) {
                    var clean = name!!.substringAfterLast('/')
                    val dot = clean.lastIndexOf('.')
                    if (dot > 0) clean = clean.substring(0, dot)
                    titleField.setText(clean)
                }
            }
        }.start()
    }

    // ---- Reader ----

    private fun openReader(bookId: Long) {
        val book = books.firstOrNull { it.id == bookId } ?: return
        currentBookId = bookId
        inReader = true
        libraryView?.visibility = View.GONE
        readerView?.visibility = View.VISIBLE
        readerTitle?.text = book.title
        showBookPreview(book)
        refreshReaderUi()
    }

    /** Preview: the first chunk of the book, markers rendered as spans. */
    private fun showBookPreview(book: BookRow) {
        val raw = book.text
        if (raw.isEmpty()) {
            readerChunkText?.text = ""
            return
        }
        var end = minOf(900, raw.length)
        if (end < raw.length) {
            val space = raw.lastIndexOf(' ', end)
            if (space > 100) end = space
        }
        readerChunkText?.text = TextCleaner.toDisplaySpanned(raw.substring(0, end))
    }

    private fun refreshReaderUi() {
        if (!inReader || !isAdded) return
        val state = ReadingState
        val mine = state.bookId == currentBookId && state.chunkCount > 0
        if (mine) {
            readerChunkLabel?.text = getString(
                R.string.kitab_chunk_of,
                state.chunkIndex + 1,
                state.chunkCount
            )
            val pct = if (state.totalChars > 0) (state.position * 100 / state.totalChars) else 0
            readerProgressLabel?.text = getString(R.string.kitab_progress, pct)
            readerProgressBar?.progress = pct.coerceIn(0, 100)
            if (state.currentChunkText.isNotEmpty()) {
                readerChunkText?.text = TextCleaner.toDisplaySpanned(state.currentChunkText)
            }
        } else {
            // Not playing this book: show the saved bookmark progress.
            val book = books.firstOrNull { it.id == currentBookId }
            if (book != null) {
                val total = book.text.length
                val pct = if (total > 0) (book.position * 100 / total) else 0
                readerProgressLabel?.text = getString(R.string.kitab_progress, pct)
                readerProgressBar?.progress = pct.coerceIn(0, 100)
            }
            readerChunkLabel?.text = ""
        }
        val playing = mine && state.isPlaying
        playButton?.isEnabled = !playing
        pauseButton?.isEnabled = playing
        resumeButton?.isEnabled = mine && !state.isPlaying
        stopButton?.isEnabled = mine
    }

    private fun onPlayClicked() {
        if (currentBookId == -1L) {
            Toast.makeText(activity, R.string.kitab_no_book, Toast.LENGTH_SHORT).show()
            return
        }
        // TtsManager answers onDone instantly when voice is off — without
        // this gate the book would "finish" in seconds with no audio.
        if (!prefs.voiceEnabled) {
            Toast.makeText(activity, R.string.kitab_voice_off, Toast.LENGTH_LONG).show()
            return
        }
        val state = ReadingState
        val resume = state.bookId == currentBookId &&
            state.chunkCount > 0 && !state.isPlaying
        if (!prefs.batteryHintShown) {
            showBatteryHint { startPlayback(resume) }
            return
        }
        startPlayback(resume)
    }

    private fun startPlayback(resume: Boolean) {
        if (resume) {
            sendServiceCommand(ReadingService.ACTION_RESUME)
        } else {
            sendServiceCommand(ReadingService.ACTION_PLAY, currentBookId)
        }
    }

    private fun sendServiceCommand(action: String, bookId: Long = -1L) {
        val act = activity ?: return
        val intent = Intent(act, ReadingService::class.java).setAction(action)
        if (bookId != -1L) intent.putExtra(ReadingService.EXTRA_BOOK_ID, bookId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            act.startForegroundService(intent)
        } else {
            act.startService(intent)
        }
    }

    // ---- Battery hint (first book start only) ----

    private fun showBatteryHint(onDone: () -> Unit) {
        val act = activity ?: return
        AlertDialog.Builder(act)
            .setTitle(R.string.kitab_battery_title)
            .setMessage(R.string.kitab_battery_body)
            .setPositiveButton(R.string.kitab_battery_open) { _, _ ->
                prefs.batteryHintShown = true
                openBatterySettings()
                onDone()
            }
            .setNegativeButton(R.string.kitab_battery_later) { _, _ ->
                prefs.batteryHintShown = true
                onDone()
            }
            .show()
    }

    private fun openBatterySettings() {
        val act = activity ?: return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + act.packageName))
            act.startActivity(intent)
        } catch (e: Exception) {
            try {
                act.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (e2: Exception) {
                // No battery settings on this device: nothing to open.
            }
        }
    }

    // ---- Speed ----

    private fun setupSpeedBar() {
        val bar = speedSeekBar ?: return
        bar.max = 150
        bar.progress = ((prefs.ttsRate * 100f).toInt() - 50).coerceIn(0, 150)
        updateSpeedLabel()
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) updateSpeedLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val rate = (seekBar.progress + 50) / 100f
                prefs.ttsRate = rate
                TtsManager.applySettings()
                updateSpeedLabel()
            }
        })
    }

    private fun currentSpeedRate(): Float {
        val bar = speedSeekBar ?: return prefs.ttsRate
        return (bar.progress + 50) / 100f
    }

    private fun updateSpeedLabel() {
        val rate = currentSpeedRate()
        speedLabel?.text = getString(R.string.kitab_speed) + " " +
            String.format(Locale.US, "%.1fx", rate)
    }

    companion object {
        private const val REQUEST_PICK_BOOK = 2001
    }
}
