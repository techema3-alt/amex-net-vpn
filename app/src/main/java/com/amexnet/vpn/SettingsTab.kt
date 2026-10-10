package com.amexnet.vpn

import android.os.Build
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.text.Editable
import android.text.TextWatcher

class SettingsTab(private val a: MainActivity) {
    val view: View

    init {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))

        // DNS
        val dnsCard = a.card()
        dnsCard.addView(a.section("DNS"))
        val dnsSpin: Spinner = a.spinner(listOf("1.1.1.1 (Cloudflare)", "8.8.8.8 (Google)", "Custom"))
        val custom: EditText = a.input("e.g. 9.9.9.9")
        dnsSpin.setSelection(Store.dnsMode)
        custom.setText(Store.dnsCustom)
        custom.visibility = if (Store.dnsMode == 2) View.VISIBLE else View.GONE
        dnsSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Store.dnsMode = pos
                custom.visibility = if (pos == 2) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        custom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Store.dnsCustom = s?.toString().orEmpty()
            }
        })
        dnsCard.addView(dnsSpin)
        dnsCard.addView(custom)
        dnsCard.addView(a.tv("Applies the next time you connect.", 12f, C.MUTED))
        root.addView(dnsCard)

        // Toggles
        val tg = a.card()
        tg.addView(a.section("GENERAL"))
        tg.addView(toggle("Connection notification", "Shows speed and a Disconnect button. Android still requires a minimal notice while connected.", Store.notif) { Store.notif = it })
        tg.addView(toggle("Auto-reconnect", "Reconnect automatically after a network change, e.g. WiFi to mobile data.", Store.autoReconnect) { Store.autoReconnect = it })
        tg.addView(toggle("DNS tunneling fallback", "If a DNS server can't be reached through the tunnel, retry through 1.1.1.1, 8.8.8.8 and 9.9.9.9 over TCP.", Store.dnsFallback) { Store.dnsFallback = it })
        tg.addView(toggle("Crash reporting", "Show a readable crash report you can copy or share if the app crashes.", Store.crashReports) { Store.crashReports = it })
        tg.addView(toggle("Block screenshots", "Hides the app in screenshots, screen recording and the recent apps list.", Store.secureScreen) { Store.secureScreen = it; a.applySecure() })
        tg.addView(toggle("App lock", "Ask for your phone PIN, pattern or fingerprint when opening the app. Needs a screen lock set in Android.", Store.appLock) {
            Store.appLock = it
            if (it && !a.isDeviceSecure()) a.toast("Set a screen lock in Android settings first, otherwise App lock does nothing")
        })
        root.addView(tg)

        // About
        val about = a.card()
        about.addView(a.section("ABOUT"))
        about.addView(infoRow("App version", BuildConfig.VERSION_NAME))
        about.addView(infoRow("Architecture", arch()))
        about.addView(infoRow("Package", "com.amexnet.vpn"))
        about.addView(infoRow("Install ID", Store.deviceId))
        about.addView(infoRow("Saved profiles", Vault.describe()))
        about.addView(infoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        root.addView(about)

        val sv = ScrollView(a)
        sv.setBackgroundColor(C.BG)
        sv.addView(root)
        view = sv
    }

    private fun toggle(title: String, sub: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, a.dp(6), 0, a.dp(10))
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.addView(a.tv(title, 15f, C.WHITE, true))
        col.addView(a.tv(sub, 12f, C.MUTED))
        row.addView(col, lp(0, WRAP, 1f, right = a.dp(10)))
        val sw = a.switchView()
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        row.addView(sw)
        return row
    }

    private fun infoRow(k: String, v: String): View {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, a.dp(4), 0, a.dp(4))
        row.addView(a.tv(k, 13f, C.MUTED), lp(0, WRAP, 1f))
        row.addView(a.tv(v, 13f, C.WHITE))
        return row
    }
}
