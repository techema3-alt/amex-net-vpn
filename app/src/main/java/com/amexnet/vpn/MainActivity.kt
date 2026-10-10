package com.amexnet.vpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val REQ_VPN = 11
        const val REQ_OPEN = 12
        const val REQ_SAVE = 13
        const val REQ_NOTIF = 14
        const val REQ_LOCK = 15
        private const val LOCK_AFTER_MS = 60_000L
    }

    lateinit var home: HomeTab
    lateinit var profilesTab: ProfilesTab
    private lateinit var cloud: CloudTab
    private lateinit var logs: LogsTab
    private lateinit var settings: SettingsTab

    private lateinit var content: FrameLayout
    private val tabViews = mutableListOf<View>()
    private val tabLabels = mutableListOf<TextView>()
    private val tabBars = mutableListOf<View>()
    private var current = 0
    private var pendingProfile: Profile? = null
    var pendingExport: ByteArray? = null
    private val ui = Handler(Looper.getMainLooper())
    private var lockView: View? = null
    private var needsAuth = true
    private var authInFlight = false
    private var pausedAt = 0L
    private val stateListener: () -> Unit = { ui.post { home.render() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this)
        if (File(filesDir, "crash.txt").exists()) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }

        home = HomeTab(this)
        profilesTab = ProfilesTab(this)
        cloud = CloudTab(this)
        logs = LogsTab(this)
        settings = SettingsTab(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.BG)

        // top bar
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(C.CARD)
        bar.setPadding(dp(16), 0, dp(10), 0)
        val title = tv("AMEX NET VPN", 20f, C.CYAN, true)
        bar.addView(title, lp(0, WRAP, 1f))
        val badge = tv("v${BuildConfig.VERSION_NAME}", 11f, C.MUTED)
        badge.background = shape(C.INPUT, 8, C.BORDER, 1)
        badge.setPadding(dp(8), dp(3), dp(8), dp(3))
        bar.addView(badge)
        val menu = tv("\u2630", 24f, C.CYAN, true)
        menu.setPadding(dp(16), dp(8), dp(8), dp(8))
        menu.setOnClickListener { showMenu(it) }
        bar.addView(menu)
        root.addView(bar, lp(MATCH, dp(60)))

        // tab row
        val tabScroll = HorizontalScrollView(this)
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.setBackgroundColor(C.CARD)
        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabScroll.addView(tabRow)
        root.addView(tabScroll, lp(MATCH, WRAP))

        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))

        val names = listOf("HOME", "PROFILES", "CLOUD", "LOGS", "SETTINGS")
        val views = listOf(home.view, profilesTab.view, cloud.view, logs.view, settings.view)
        for (i in names.indices) {
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            val label = tv(names[i], 13f, C.MUTED, true)
            label.letterSpacing = 0.08f
            label.setPadding(dp(16), dp(12), dp(16), dp(10))
            val ind = View(this)
            cell.addView(label)
            cell.addView(ind, lp(MATCH, dp(3)))
            cell.setOnClickListener { select(i) }
            tabRow.addView(cell)
            tabLabels.add(label)
            tabBars.add(ind)
            tabViews.add(views[i])
            content.addView(views[i], FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        val lock = buildLockView()
        lock.visibility = View.GONE
        frame.addView(lock, FrameLayout.LayoutParams(MATCH, MATCH))
        lockView = lock
        setContentView(frame)
        applySecure()
        select(0)
        VpnState.listeners.add(stateListener)
        handleIntent(intent)
    }

    fun select(i: Int) {
        if (current == 0 && i != 0) home.collectAndSave()
        current = i
        for (k in tabViews.indices) {
            tabViews[k].visibility = if (k == i) View.VISIBLE else View.GONE
            tabLabels[k].setTextColor(if (k == i) C.CYAN else C.MUTED)
            tabBars[k].setBackgroundColor(if (k == i) C.CYAN else C.CARD)
        }
        when (i) {
            0 -> home.onShow()
            1 -> profilesTab.refresh()
        }
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Import .amex file")
        m.menu.add(0, 2, 1, "Export as .amex file")
        m.menu.add(0, 3, 2, "Export as Emoji")
        m.menu.add(0, 4, 3, "Import from Emoji")
        m.menu.add(0, 5, 4, "Paste from Clipboard")
        m.menu.add(0, 6, 5, "About")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> profilesTab.importFile()
                2 -> profilesTab.exportFile()
                3 -> profilesTab.exportEmoji()
                4 -> profilesTab.importEmojiDialog()
                5 -> profilesTab.pasteClipboard()
                6 -> select(4)
            }
            true
        }
        m.show()
    }

    fun currentProfile(): Profile? {
        if (current == 0) home.collectAndSave()
        return Store.selected()
    }

    /** Hides the app from screenshots, screen recording and the recents thumbnail. */
    fun applySecure() {
        if (Store.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun isDeviceSecure(): Boolean = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    private fun buildLockView(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(C.BG)
        box.isClickable = true
        box.addView(tv("\uD83D\uDD12", 44f, C.CYAN, true))
        val t = tv("AMEX NET VPN is locked", 18f, C.WHITE, true)
        t.setPadding(0, dp(12), 0, dp(16))
        box.addView(t)
        val b: Button = btn("Unlock", C.CYAN, C.BG, C.CYAN)
        b.layoutParams = lp(dp(180), dp(48))
        b.setOnClickListener { requestAuth() }
        box.addView(b)
        return box
    }

    private fun requestAuth() {
        if (authInFlight) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val i = km.createConfirmDeviceCredentialIntent("AMEX NET VPN", "Unlock to continue")
        if (i == null) {
            needsAuth = false
            lockView?.visibility = View.GONE
            return
        }
        authInFlight = true
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_LOCK)
    }

    private fun checkLock() {
        val lock = lockView ?: return
        if (Store.appLock && isDeviceSecure()) {
            if (!needsAuth && SystemClock.elapsedRealtime() - pausedAt > LOCK_AFTER_MS) needsAuth = true
        } else {
            needsAuth = false
        }
        lock.visibility = if (needsAuth) View.VISIBLE else View.GONE
        if (needsAuth && !authInFlight) requestAuth()
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ connecting

    fun startConnect(p: Profile) {
        val err = Validate.check(p)
        if (err != null) {
            toast(err)
            AppLog.e(err)
            return
        }
        if (Expiry.isExpired(p.expiry)) {
            toast("This configuration has expired")
            AppLog.e("This configuration expired on ${p.expiry}")
            return
        }
        if (otherVpnActive()) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("Another VPN is currently running. Stop it to continue?")
                .setPositiveButton("Stop & Continue") { _, _ ->
                    AppLog.i("Replacing the other VPN...")
                    proceed(p)
                }
                .setNegativeButton("Cancel") { _, _ -> AppLog.i("Connection cancelled: another VPN is running") }
                .setOnCancelListener { AppLog.i("Connection cancelled: another VPN is running") }
                .show()
        } else {
            proceed(p)
        }
    }

    private fun otherVpnActive(): Boolean {
        if (VpnState.state != St.DISCONNECTED) return false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    private fun proceed(p: Profile) {
        pendingProfile = p
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        prepareVpn()
    }

    private fun prepareVpn() {
        val p = pendingProfile ?: return
        // Starting our own VpnService is what makes Android drop any other active VPN.
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, REQ_VPN) else launchService(p)
    }

    private fun launchService(p: Profile) {
        val i = Intent(this, AmexVpnService::class.java)
        i.action = AmexVpnService.ACTION_CONNECT
        i.putExtra(AmexVpnService.EXTRA_ID, p.id)
        ContextCompat.startForegroundService(this, i)
    }

    fun disconnect() {
        val i = Intent(this, AmexVpnService::class.java)
        i.action = AmexVpnService.ACTION_DISCONNECT
        startService(i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_NOTIF) prepareVpn()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_VPN -> {
                val p = pendingProfile
                if (res == RESULT_OK && p != null) launchService(p) else AppLog.w("VPN permission was not granted")
            }
            REQ_LOCK -> {
                authInFlight = false
                if (res == RESULT_OK) {
                    needsAuth = false
                    pausedAt = SystemClock.elapsedRealtime()
                    lockView?.visibility = View.GONE
                }
            }
            REQ_OPEN -> data?.data?.let { profilesTab.importFromUri(it) }
            REQ_SAVE -> {
                val uri = data?.data
                val bytes = pendingExport
                if (res == RESULT_OK && uri != null && bytes != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        toast("Exported")
                    } catch (e: Exception) {
                        toast("Export failed")
                        AppLog.e("Export failed: ${e.message}")
                    }
                }
                pendingExport = null
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    private fun handleIntent(i: Intent?) {
        val u: Uri = i?.data ?: return
        if (i.action == Intent.ACTION_VIEW) {
            select(1)
            profilesTab.importFromUri(u)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::home.isInitialized) {
            applySecure()
            checkLock()
            home.start()
            home.render()
        }
    }

    override fun onPause() {
        if (::home.isInitialized) { home.stop(); if (current == 0) home.collectAndSave() }
        pausedAt = SystemClock.elapsedRealtime()
        super.onPause()
    }

    override fun onDestroy() {
        VpnState.listeners.remove(stateListener)
        if (::logs.isInitialized) logs.destroy()
        super.onDestroy()
    }
}
