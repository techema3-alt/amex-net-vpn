package com.amexnet.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class CrashActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val f = File(filesDir, "crash.txt")
        val report = try { if (f.exists()) f.readText() else "No crash details were saved." } catch (e: Exception) { "Could not read the crash report." }
        try { f.delete() } catch (e: Exception) {}

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.BG)
        root.setPadding(dp(16), dp(24), dp(16), dp(16))

        root.addView(tv("AMEX NET VPN crashed", 20f, C.RED, true))
        val sub = tv("Copy or share this report when asking for help, then restart the app.", 13f, C.MUTED)
        sub.setPadding(0, dp(6), 0, dp(12))
        root.addView(sub)

        val block = TextView(this)
        block.text = report
        block.setTextColor(C.LOG)
        block.textSize = 11f
        block.typeface = Typeface.MONOSPACE
        block.setTextIsSelectable(true)
        block.background = shape(C.CONSOLE, 10, C.BORDER, 1)
        block.setPadding(dp(12), dp(12), dp(12), dp(12))
        val sv = ScrollView(this)
        sv.addView(block)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(12), 0, 0)
        val copy = btn("Copy report")
        val share = btn("Share report")
        val restart = btn("Restart App", C.GREEN, C.BG, C.GREEN)
        copy.layoutParams = lp(0, dp(48), 1f, right = dp(6))
        share.layoutParams = lp(0, dp(48), 1f, right = dp(6))
        restart.layoutParams = lp(0, dp(48), 1f)
        row.addView(copy); row.addView(share); row.addView(restart)
        root.addView(row)

        copy.setOnClickListener {
            Clip.copy(this, "AMEX NET VPN crash report", report, sensitive = false)
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        share.setOnClickListener {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
            startActivity(Intent.createChooser(i, "Share crash report"))
        }
        restart.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(i)
            finish()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        setContentView(root)
    }
}
