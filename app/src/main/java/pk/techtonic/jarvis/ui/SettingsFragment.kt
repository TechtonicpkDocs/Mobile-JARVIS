package pk.techtonic.jarvis.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Fragment
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.alerts.PrayerScheduler
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.data.JarvisPrefs
import pk.techtonic.jarvis.net.GeminiClient
import pk.techtonic.jarvis.speech.TtsManager
import pk.techtonic.jarvis.util.AutoBackup
import pk.techtonic.jarvis.util.BackupManager
import pk.techtonic.jarvis.util.LockHelper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Settings tab (W4) — platform Fragment, ScrollView layout.
 *
 * Sections: (1) Gemini key + model + Test Key (with Stop), (2) Language,
 * (3) Voice (on/off + voice picker row + summary), (4) Backup export /
 * import, (5) Auto-backup (SAF file + status), (6) Prayer alerts (six
 * toggles, POST_NOTIFICATIONS asked on first enable), (7) App lock
 * (PIN + fingerprint), (8) About.
 *
 * Document pickers use startActivityForResult / onActivityResult
 * (platform API — the newer contracts API needs a support library).
 */
class SettingsFragment : Fragment() {

    private lateinit var prefs: JarvisPrefs
    private lateinit var db: JarvisDb

    private lateinit var keyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var toggleKeyButton: Button
    private lateinit var testButton: Button
    private lateinit var stopButton: Button
    private lateinit var langGroup: RadioGroup
    private lateinit var voiceSwitch: Switch
    private lateinit var voiceSummary: TextView
    private lateinit var autoBackupSwitch: Switch
    private lateinit var autoBackupStatus: TextView
    private lateinit var lockSwitch: Switch
    private lateinit var bioSwitch: Switch
    private lateinit var prayerSwitches: Array<Switch>

    private var keyVisible = false
    private var testCall: GeminiClient.Call? = null
    private var pendingPrayer: String? = null
    private var pendingAutoBackupEnable = false

    // Guards so programmatic setChecked() calls never fire the listeners.
    private var suppressPrayerToggle = false
    private var suppressLockToggle = false
    private var suppressBioToggle = false
    private var suppressAutoBackupToggle = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val main = activity as? MainActivity ?: return
        prefs = main.prefs
        db = main.db

        keyEdit = view.findViewById(R.id.set_key)
        modelEdit = view.findViewById(R.id.set_model)
        toggleKeyButton = view.findViewById(R.id.set_togglekey)
        testButton = view.findViewById(R.id.set_testkey)
        stopButton = view.findViewById(R.id.set_test_stop)
        langGroup = view.findViewById(R.id.set_lang_group)
        voiceSwitch = view.findViewById(R.id.set_voice)
        voiceSummary = view.findViewById(R.id.set_voice_summary)
        autoBackupSwitch = view.findViewById(R.id.autobackup_enable)
        autoBackupStatus = view.findViewById(R.id.autobackup_status)
        lockSwitch = view.findViewById(R.id.lock_enable)
        bioSwitch = view.findViewById(R.id.lock_biometric)

        wireGemini()
        wireLanguage()
        wireVoice(view)
        wireBackup(view)
        wireAutoBackup(view)
        wirePrayerAlerts(view)
        wireLock(view)
        refreshVoiceSummary()
        refreshAutoBackupStatus()
        refreshLockSection()
    }

    override fun onResume() {
        super.onResume()
        // Voice may have changed in the picker; an auto-backup error may
        // have been recorded while the app was in the background.
        if (::prefs.isInitialized) {
            refreshVoiceSummary()
            refreshAutoBackupStatus()
        }
    }

    override fun onDestroyView() {
        testCall?.cancel()
        testCall = null
        super.onDestroyView()
    }

    // ---- (1) Gemini ----

    private fun wireGemini() {
        keyEdit.setText(prefs.geminiKey)
        modelEdit.setText(prefs.model)
        keyEdit.onTextChanged { prefs.geminiKey = it.trim() }
        modelEdit.onTextChanged { text ->
            val value = text.trim()
            prefs.model = if (value.isBlank()) JarvisPrefs.DEFAULT_MODEL else value
        }
        toggleKeyButton.setOnClickListener {
            keyVisible = !keyVisible
            keyEdit.inputType = if (keyVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            toggleKeyButton.setText(
                if (keyVisible) R.string.set_hidekey else R.string.set_showkey
            )
            keyEdit.setSelection(keyEdit.text.length)
        }
        testButton.setOnClickListener { testKey() }
        stopButton.setOnClickListener {
            testCall?.cancel()
            testCall = null
            testButton.isEnabled = true
            stopButton.visibility = View.GONE
            toast(R.string.stopped)
        }
    }

    private fun testKey() {
        if (prefs.geminiKey.isBlank()) {
            toast(R.string.set_test_nokey)
            return
        }
        testButton.isEnabled = false
        stopButton.visibility = View.VISIBLE
        testCall = GeminiClient(prefs).testKey(
            onOk = {
                testCall = null
                if (!isAdded) return@testKey
                testButton.isEnabled = true
                stopButton.visibility = View.GONE
                toast(R.string.set_test_ok)
            },
            onError = { err ->
                testCall = null
                if (!isAdded) return@testKey
                testButton.isEnabled = true
                stopButton.visibility = View.GONE
                toast(resolveErrorMessage(err))
            }
        )
    }

    /** An error may be a string-resource key ("chat_no_key") or plain text. */
    private fun resolveErrorMessage(err: String): String {
        val act = activity ?: return err
        val resId = act.resources.getIdentifier(err, "string", act.packageName)
        return if (resId != 0) act.getString(resId) else err
    }

    // ---- (2) Language ----

    private fun wireLanguage() {
        if (prefs.language == "en") langGroup.check(R.id.set_lang_en)
        else langGroup.check(R.id.set_lang_ur)
        langGroup.setOnCheckedChangeListener { _, checkedId ->
            val newLang = if (checkedId == R.id.set_lang_en) "en" else "ur"
            if (newLang != prefs.language) {
                prefs.language = newLang
                (activity as? MainActivity)?.applyLanguageAndRecreate()
            }
        }
    }

    // ---- (3) Voice ----

    private fun wireVoice(view: View) {
        voiceSwitch.isChecked = prefs.voiceEnabled
        voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.voiceEnabled = isChecked
            if (!isChecked) TtsManager.stop()
            TtsManager.applySettings()
        }
        view.findViewById<Button>(R.id.set_voice_open).setOnClickListener {
            activity?.let { startActivity(Intent(it, VoicePickerActivity::class.java)) }
        }
    }

    private fun refreshVoiceSummary() {
        if (!::voiceSummary.isInitialized) return
        val act = activity ?: return
        val voiceName = prefs.ttsVoiceName.ifBlank {
            act.getString(R.string.set_ttsvoice_default)
        }
        val rate = String.format(Locale.US, "%.1fx", prefs.ttsRate)
        voiceSummary.text = act.getString(R.string.set_voice_summary, voiceName, rate)
    }

    // ---- (4) Backup ----

    private fun wireBackup(view: View) {
        view.findViewById<Button>(R.id.set_export).setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "Jarvis-Backup.json")
            }
            startActivityForResult(intent, REQ_EXPORT)
        }
        view.findViewById<Button>(R.id.set_import).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_IMPORT)
        }
    }

    private fun runExport(uri: Uri) {
        val act = activity ?: return
        Thread {
            val ok = try {
                val json = BackupManager.buildExportJson(db, prefs)
                val out = act.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("openOutputStream returned null")
                out.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                true
            } catch (e: Exception) {
                false
            }
            act.runOnUiThread {
                if (isAdded) {
                    toast(if (ok) R.string.set_export_ok else R.string.set_export_failed)
                }
            }
        }.start()
    }

    private fun runImport(uri: Uri) {
        val act = activity ?: return
        val langBefore = prefs.language
        Thread {
            val text = readUriText(act, uri)
            val ok = text != null && BackupManager.importJson(text, db, prefs)
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    toast(R.string.set_import_ok)
                    if (prefs.language != langBefore) {
                        (activity as? MainActivity)?.applyLanguageAndRecreate()
                    } else {
                        refreshSettingsUi()
                    }
                } else {
                    toast(R.string.set_import_invalid)
                }
            }
        }.start()
    }

    /** Reads a picked file as UTF-8 text, capped so a huge file cannot OOM. */
    private fun readUriText(act: Activity, uri: Uri): String? {
        return try {
            act.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_IMPORT_BYTES) return null
                    out.write(buf, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- (5) Auto-backup ----

    private fun wireAutoBackup(view: View) {
        autoBackupSwitch.isChecked = prefs.autoBackupEnabled
        autoBackupSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoBackupToggle) return@setOnCheckedChangeListener
            if (isChecked) {
                if (prefs.autoBackupUri.isBlank()) {
                    // No file yet: pick one first; the toggle only stays
                    // on when the user actually chooses a file.
                    pendingAutoBackupEnable = true
                    openAutoBackupChooser()
                } else {
                    prefs.autoBackupEnabled = true
                }
            } else {
                prefs.autoBackupEnabled = false
            }
            refreshAutoBackupStatus()
        }
        view.findViewById<Button>(R.id.autobackup_choose).setOnClickListener {
            openAutoBackupChooser()
        }
    }

    private fun openAutoBackupChooser() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "Jarvis-Backup.tpkbackup")
        }
        startActivityForResult(intent, REQ_AUTOBACKUP)
    }

    private fun refreshAutoBackupStatus() {
        if (!::autoBackupStatus.isInitialized) return
        val act = activity ?: return
        val error = AutoBackup.getLastError(act)
        val uriString = prefs.autoBackupUri
        when {
            error.isNotBlank() -> {
                autoBackupStatus.text = act.getString(R.string.autobackup_error, error)
                autoBackupStatus.visibility = View.VISIBLE
            }
            uriString.isNotBlank() -> {
                val name = displayNameOf(Uri.parse(uriString)) ?: uriString
                autoBackupStatus.text = act.getString(R.string.autobackup_chosen, name)
                autoBackupStatus.visibility = View.VISIBLE
            }
            else -> autoBackupStatus.visibility = View.GONE
        }
    }

    private fun displayNameOf(uri: Uri): String? {
        val act = activity ?: return null
        return try {
            act.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- (6) Prayer alerts ----

    private fun wirePrayerAlerts(view: View) {
        val act = activity ?: return
        prayerSwitches = Array(PrayerScheduler.PRAYER_ORDER.size) { i ->
            view.findViewById<Switch>(PRAYER_SWITCH_IDS[i])
        }
        for (i in PrayerScheduler.PRAYER_ORDER.indices) {
            prayerSwitches[i].isChecked =
                prefs.prayerAlert(PrayerScheduler.PRAYER_ORDER[i])
        }
        for (i in PrayerScheduler.PRAYER_ORDER.indices) {
            val apiName = PrayerScheduler.PRAYER_ORDER[i]
            prayerSwitches[i].setOnCheckedChangeListener { _, isChecked ->
                if (suppressPrayerToggle) return@setOnCheckedChangeListener
                onPrayerToggle(apiName, isChecked)
            }
        }
        val exactLine = view.findViewById<TextView>(R.id.prayer_alert_exact)
        exactLine.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !PrayerScheduler.canScheduleExact(act)
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun onPrayerToggle(apiName: String, isChecked: Boolean) {
        val act = activity ?: return
        if (isChecked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                // Ask first; the pref is only set when permission lands
                // (see onRequestPermissionsResult).
                pendingPrayer = apiName
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
                )
                return
            }
            prefs.setPrayerAlert(apiName, true)
            PrayerScheduler.rescheduleToday(act)
        } else {
            prefs.setPrayerAlert(apiName, false)
            PrayerScheduler.cancelAll(act)
            PrayerScheduler.rescheduleToday(act)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_NOTIFICATIONS) {
            val pending = pendingPrayer
            pendingPrayer = null
            if (pending != null) {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.setPrayerAlert(pending, true)
                    activity?.let { PrayerScheduler.rescheduleToday(it) }
                } else {
                    // Denied: leave the toggle off, say so plainly.
                    setPrayerSwitch(pending, false)
                    toast(R.string.prayer_alert_denied)
                }
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun setPrayerSwitch(apiName: String, checked: Boolean) {
        val index = PrayerScheduler.PRAYER_ORDER.indexOf(apiName)
        if (index < 0 || !::prayerSwitches.isInitialized) return
        suppressPrayerToggle = true
        prayerSwitches[index].isChecked = checked
        suppressPrayerToggle = false
    }

    // ---- (7) App lock ----

    private fun wireLock(view: View) {
        lockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressLockToggle) return@setOnCheckedChangeListener
            if (isChecked) {
                if (prefs.lockPinHash.isBlank()) {
                    // No PIN yet: set one first; the lock only turns on
                    // when a PIN actually exists.
                    showSetPinDialog(
                        onSet = {
                            prefs.lockEnabled = true
                            refreshLockSection()
                        },
                        onCancel = {
                            suppressLockToggle = true
                            lockSwitch.isChecked = false
                            suppressLockToggle = false
                        }
                    )
                } else {
                    prefs.lockEnabled = true
                    refreshLockSection()
                }
            } else {
                showConfirmPinDialog(
                    R.string.lock_disable_confirm,
                    onOk = {
                        prefs.lockEnabled = false
                        refreshLockSection()
                        toast(R.string.lock_unlocked)
                    },
                    onCancel = {
                        suppressLockToggle = true
                        lockSwitch.isChecked = true
                        suppressLockToggle = false
                    }
                )
            }
        }
        view.findViewById<Button>(R.id.lock_set_pin).setOnClickListener {
            if (prefs.lockPinHash.isBlank()) {
                showSetPinDialog(onSet = { refreshLockSection() }, onCancel = {})
            } else {
                showConfirmPinDialog(
                    R.string.lock_current_pin,
                    onOk = {
                        showSetPinDialog(onSet = { refreshLockSection() }, onCancel = {})
                    },
                    onCancel = {}
                )
            }
        }
        bioSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressBioToggle) return@setOnCheckedChangeListener
            prefs.lockBiometric = isChecked
        }
    }

    private fun refreshLockSection() {
        if (!::lockSwitch.isInitialized) return
        val act = activity ?: return
        suppressLockToggle = true
        lockSwitch.isChecked = prefs.lockEnabled
        suppressLockToggle = false
        val bioAvailable = LockHelper.hasBiometric(act)
        bioSwitch.visibility = if (bioAvailable) View.VISIBLE else View.GONE
        bioSwitch.isEnabled = prefs.lockEnabled
        if (bioAvailable) {
            suppressBioToggle = true
            bioSwitch.isChecked = prefs.lockBiometric
            suppressBioToggle = false
        }
    }

    /** Enter-twice PIN dialog. Validates on OK; stays open on bad input. */
    private fun showSetPinDialog(onSet: () -> Unit, onCancel: () -> Unit) {
        val act = activity ?: return
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val container = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val pin1 = EditText(act).apply {
            hint = act.getString(R.string.lock_pin_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        val pin2 = EditText(act).apply {
            hint = act.getString(R.string.lock_pin_repeat)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        container.addView(pin1)
        container.addView(pin2)
        val dialog = AlertDialog.Builder(act)
            .setTitle(R.string.lock_set_pin)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val first = pin1.text.toString().trim()
            val second = pin2.text.toString().trim()
            when {
                first.length !in LockActivity.PIN_MIN..LockActivity.PIN_MAX ||
                    !first.all { c -> c.isDigit() } -> toast(R.string.lock_pin_short)
                first != second -> toast(R.string.lock_pin_mismatch)
                else -> {
                    prefs.lockPinHash = LockHelper.sha256Hex(first)
                    dialog.dismiss()
                    onSet()
                }
            }
        }
    }

    /** Asks the current PIN once. Wrong PIN: plain message, dialog stays open. */
    private fun showConfirmPinDialog(messageRes: Int, onOk: () -> Unit, onCancel: () -> Unit) {
        val act = activity ?: return
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val pin = EditText(act).apply {
            hint = act.getString(R.string.lock_pin_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val dialog = AlertDialog.Builder(act)
            .setTitle(R.string.lock_title)
            .setMessage(messageRes)
            .setView(pin)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = pin.text.toString().trim()
            if (LockHelper.sha256Hex(entered) == prefs.lockPinHash) {
                dialog.dismiss()
                onOk()
            } else {
                toast(R.string.lock_wrong_pin)
                pin.setText("")
            }
        }
    }

    // ---- Document picker results ----

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val act = activity ?: return
        when (requestCode) {
            REQ_EXPORT -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) runExport(uri) else toast(R.string.set_export_failed)
                }
            }
            REQ_IMPORT -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) runImport(uri) else toast(R.string.set_import_nofile)
                }
            }
            REQ_AUTOBACKUP -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) {
                        try {
                            act.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                            // Permission may already be held; the Uri is
                            // still saved — a later failure surfaces as
                            // the auto-backup error line in Settings.
                        }
                        prefs.autoBackupUri = uri.toString()
                        if (pendingAutoBackupEnable) {
                            prefs.autoBackupEnabled = true
                            pendingAutoBackupEnable = false
                        }
                        refreshAutoBackupStatus()
                    } else if (pendingAutoBackupEnable) {
                        pendingAutoBackupEnable = false
                        revertAutoBackupSwitch()
                    }
                } else if (pendingAutoBackupEnable) {
                    pendingAutoBackupEnable = false
                    revertAutoBackupSwitch()
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun revertAutoBackupSwitch() {
        suppressAutoBackupToggle = true
        autoBackupSwitch.isChecked = false
        suppressAutoBackupToggle = false
    }

    // ---- After a successful import: re-read every widget from prefs ----

    private fun refreshSettingsUi() {
        keyEdit.setText(prefs.geminiKey)
        keyEdit.setSelection(keyEdit.text.length)
        modelEdit.setText(prefs.model)
        modelEdit.setSelection(modelEdit.text.length)
        val langId = if (prefs.language == "en") R.id.set_lang_en else R.id.set_lang_ur
        if (langGroup.checkedRadioButtonId != langId) langGroup.check(langId)
        voiceSwitch.isChecked = prefs.voiceEnabled
        refreshVoiceSummary()
        suppressAutoBackupToggle = true
        autoBackupSwitch.isChecked = prefs.autoBackupEnabled
        suppressAutoBackupToggle = false
        refreshAutoBackupStatus()
        for (i in PrayerScheduler.PRAYER_ORDER.indices) {
            suppressPrayerToggle = true
            prayerSwitches[i].isChecked = prefs.prayerAlert(PrayerScheduler.PRAYER_ORDER[i])
            suppressPrayerToggle = false
        }
        refreshLockSection()
    }

    // ---- Small helpers ----

    private fun toast(resId: Int) {
        val act = activity ?: return
        if (!isAdded) return
        Toast.makeText(act, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        val act = activity ?: return
        if (!isAdded) return
        Toast.makeText(act, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val REQ_EXPORT = 2001
        const val REQ_IMPORT = 2002
        const val REQ_AUTOBACKUP = 2003
        const val REQ_NOTIFICATIONS = 1002

        /** Imports bigger than this are rejected (never OOM on a bad file). */
        const val MAX_IMPORT_BYTES = 32 * 1024 * 1024

        private val PRAYER_SWITCH_IDS = intArrayOf(
            R.id.sw_prayer_fajr,
            R.id.sw_prayer_sunrise,
            R.id.sw_prayer_dhuhr,
            R.id.sw_prayer_asr,
            R.id.sw_prayer_maghrib,
            R.id.sw_prayer_isha
        )
    }
}

/** Tiny TextWatcher that only reports the text after each change. */
private fun EditText.onTextChanged(action: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            action(s?.toString() ?: "")
        }
    })
}
