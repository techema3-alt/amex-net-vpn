package com.amexnet.vpn

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Placeholder only: no network calls and no server list in this build. */
class CloudTab(private val a: MainActivity) {
    val view: View

    init {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.BG)
        root.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))

        val top = LinearLayout(a)
        top.orientation = LinearLayout.HORIZONTAL
        val add = a.btn("Add Config")
        val imp = a.btn("Import from Cloud")
        add.layoutParams = lp(0, a.dp(44), 1f, right = a.dp(6))
        imp.layoutParams = lp(0, a.dp(44), 1f)
        top.addView(add)
        top.addView(imp)
        root.addView(top)

        val msg = a.tv("Cloud Config \u2014 Coming Soon", 20f, C.MUTED, true)
        msg.gravity = Gravity.CENTER
        root.addView(msg, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val pulse = View.OnClickListener {
            msg.setTextColor(C.CYAN)
            msg.animate().alpha(0.3f).setDuration(150).withEndAction {
                msg.animate().alpha(1f).setDuration(150).withEndAction { msg.setTextColor(C.MUTED) }.start()
            }.start()
        }
        add.setOnClickListener(pulse)
        imp.setOnClickListener(pulse)
        msg.setOnClickListener(pulse)
        view = root
    }
}
