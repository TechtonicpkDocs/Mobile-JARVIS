package pk.techtonic.jarvis.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import pk.techtonic.jarvis.JarvisApp
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.alerts.PrayerScheduler
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.data.JarvisPrefs
import pk.techtonic.jarvis.util.AutoBackup
import pk.techtonic.jarvis.util.LocaleHelper
import pk.techtonic.jarvis.util.MicPermission

/**
 * Single-activity shell: 7 tabs (Chat, Briefing, Bayan, Notes, Hisab,
 * Kitab, Settings) switched with plain bottom buttons — no Material
 * dependency by design.
 *
 * Back-navigation standard (his fleet rule): the Android system back key
 * navigates INSIDE the app (previous tab), never kills the app from a
 * sub-view. Exit is possible only from the Chat tab, with a confirm.
 *
 * On stop (app leaves the foreground) the auto-backup hook runs when
 * enabled in Settings (AutoBackup, W4). Prayer alarms are re-checked on
 * start (PrayerScheduler, W4).
 */
class MainActivity : Activity() {

    lateinit var prefs: JarvisPrefs
        private set
    lateinit var db: JarvisDb
        private set

    private val tabHistory = ArrayList<Int>()
    private var currentTab = TAB_CHAT

    override fun attachBaseContext(newBase: Context) {
        val lang = JarvisPrefs(newBase).language
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = (application as JarvisApp).prefs
        db = JarvisDb(applicationContext)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.footer_text).text =
            getString(R.string.footer_text)

        val tabs = intArrayOf(
            R.id.nav_chat, R.id.nav_briefing, R.id.nav_bayan,
            R.id.nav_notes, R.id.nav_hisab, R.id.nav_kitab, R.id.nav_settings
        )
        for (i in tabs.indices) {
            val btn = findViewById<Button>(tabs[i])
            val tab = i
            btn.setOnClickListener { showTab(tab, true) }
        }
        showTab(intent.getIntExtra(EXTRA_TAB, TAB_CHAT), false)
        // Re-check today's prayer alarms on every start (times change daily;
        // a fetch failure keeps whatever is already scheduled).
        PrayerScheduler.rescheduleToday(applicationContext)
    }

    override fun onStop() {
        super.onStop()
        // Auto-backup on close (only when enabled + a file was chosen).
        AutoBackup.maybeBackupOnClose(applicationContext)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        // super dispatches fragment-initiated requests (e.g. Settings'
        // POST_NOTIFICATIONS request) back to the requesting fragment.
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // MicPermission requests via the Activity, so its result (1001)
        // lands here, never in a fragment — forward it explicitly or the
        // mic flow (chat + voice notes) would silently never resolve.
        MicPermission.onRequestPermissionsResult(requestCode, grantResults)
    }

    fun showTab(tab: Int, recordHistory: Boolean) {
        if (recordHistory && tab != currentTab) {
            tabHistory.add(currentTab)
        }
        currentTab = tab
        val fragment: Fragment = when (tab) {
            TAB_BRIEFING -> BriefingFragment()
            TAB_BAYAN -> BayanFragment()
            TAB_NOTES -> NotesFragment()
            TAB_HISAB -> HisabFragment()
            TAB_KITAB -> BookReaderFragment()
            TAB_SETTINGS -> SettingsFragment()
            else -> ChatFragment()
        }
        fragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        highlightTab(tab)
    }

    private fun highlightTab(tab: Int) {
        val tabs = intArrayOf(
            R.id.nav_chat, R.id.nav_briefing, R.id.nav_bayan,
            R.id.nav_notes, R.id.nav_hisab, R.id.nav_kitab, R.id.nav_settings
        )
        for (i in tabs.indices) {
            val btn = findViewById<Button>(tabs[i])
            btn.isSelected = (i == tab)
            btn.alpha = if (i == tab) 1.0f else 0.6f
        }
    }

    override fun onBackPressed() {
        if (tabHistory.isNotEmpty()) {
            val prev = tabHistory.removeAt(tabHistory.size - 1)
            showTab(prev, false)
            return
        }
        if (currentTab != TAB_CHAT) {
            showTab(TAB_CHAT, false)
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.exit_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> finish() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    /** Called by Settings after a language change: re-wrap and redraw. */
    fun applyLanguageAndRecreate() {
        recreate()
    }

    companion object {
        const val TAB_CHAT = 0
        const val TAB_BRIEFING = 1
        const val TAB_BAYAN = 2
        const val TAB_NOTES = 3
        const val TAB_HISAB = 4
        const val TAB_KITAB = 5
        const val TAB_SETTINGS = 6
        /** Intent extra: open directly on this tab (used by the reading
            notification so a tap lands on the Kitab tab, not Chat). */
        const val EXTRA_TAB = "pk.techtonic.jarvis.TAB"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // App already running (single-top from the notification): switch
        // to the requested tab instead of staying where it was.
        val tab = intent.getIntExtra(EXTRA_TAB, currentTab)
        if (tab != currentTab) showTab(tab, true)
    }
}
