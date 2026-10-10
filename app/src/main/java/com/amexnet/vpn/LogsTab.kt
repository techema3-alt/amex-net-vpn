package com.amexnet.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LogsTab(private val a: MainActivity) {
    val view: View
    private val console: TextView
    private val scroll: ScrollView
    private val listener: (String) -> Unit = { line -> a.runOnUiThread { append(line) } }

    init {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.BG)
        root.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12))

        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        val clear = a.btn("Clear logs")
        val copy = a.btn("Copy")
        val share = a.btn("Share")
        clear.layoutParams = lp(0, a.dp(40), 1f, right = a.dp(6))
        copy.layoutParams = lp(0, a.dp(40), 1f, right = a.dp(6))
        share.layoutParams = lp(0, a.dp(40), 1f)
        row.addView(clear); row.addView(copy); row.addView(share)
        root.addView(row)

        console = TextView(a)
        console.setTextColor(C.LOG)
        console.textSize = 12f
        console.typeface = Typeface.MONOSPACE
        console.setTextIsSelectable(true)
        console.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
        scroll = ScrollView(a)
        scroll.background = a.shape(C.CONSOLE, 10, C.BORDER, 1)
        scroll.addView(console)
        val sp = lp(MATCH, 0, 1f, top = a.dp(10))
        root.addView(scroll, sp)

        clear.setOnClickListener {
            AppLog.clear()
            console.text = ""
        }
        copy.setOnClickListener {
            Clip.copy(a, "AMEX NET VPN logs", AppLog.text(), sensitive = false)
            a.toast("Logs copied")
        }
        share.setOnClickListener {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.text())
            a.startActivity(Intent.createChooser(i, "Share logs"))
        }

        for (l in AppLog.snapshot()) append(l)
        AppLog.listeners.add(listener)
        view = root
    }

    private fun append(line: String) {
        val level = line.getOrNull(9)
        val color = when {
            level == 'E' -> C.RED
            level == 'W' -> C.YELLOW
            line.contains("Connected and routing") || line.contains("Handshake complete") -> C.GREEN
            else -> C.LOG
        }
        if (console.length() > 120000) {
            console.text = ""
        }
        val s = SpannableString(line + "\n")
        s.setSpan(ForegroundColorSpan(color), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        console.append(s)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun destroy() {
        AppLog.listeners.remove(listener)
    }
}
