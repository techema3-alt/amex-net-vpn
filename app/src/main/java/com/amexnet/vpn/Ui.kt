package com.amexnet.vpn

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

object C {
    val BG = Color.parseColor("#0D1117")
    val CYAN = Color.parseColor("#00E5FF")
    val GREEN = Color.parseColor("#00E676")
    val GREEN_DARK = Color.parseColor("#00C853")
    val RED = Color.parseColor("#FF5252")
    val RED_DARK = Color.parseColor("#D32F2F")
    val YELLOW = Color.parseColor("#FFD600")
    val CARD = Color.parseColor("#161B22")
    val INPUT = Color.parseColor("#21262D")
    val CONSOLE = Color.parseColor("#090D12")
    val BORDER = Color.parseColor("#30363D")
    val MUTED = Color.parseColor("#8B949E")
    val WHITE = Color.parseColor("#F0F6FC")
    val LOG = Color.parseColor("#C9D1D9")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.shape(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(fill)
    g.cornerRadius = dp(radiusDp).toFloat()
    if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke)
    return g
}

fun Context.pressable(normal: Int, pressed: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): StateListDrawable {
    val s = StateListDrawable()
    s.addState(intArrayOf(android.R.attr.state_pressed), shape(pressed, radiusDp, stroke, strokeDp))
    s.addState(intArrayOf(), shape(normal, radiusDp, stroke, strokeDp))
    return s
}

fun Context.tv(text: CharSequence, sp: Float = 14f, color: Int = C.WHITE, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = sp
    t.setTextColor(color)
    if (bold) t.typeface = Typeface.DEFAULT_BOLD
    return t
}

fun Context.section(text: String): TextView {
    val t = tv(text, 11f, C.CYAN, true)
    t.letterSpacing = 0.15f
    t.setPadding(0, 0, 0, dp(8))
    return t
}

fun Context.card(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    l.background = shape(C.CARD, 14, C.BORDER, 1)
    l.setPadding(dp(14), dp(14), dp(14), dp(14))
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.bottomMargin = dp(12)
    l.layoutParams = lp
    return l
}

fun Context.input(
    hint: String = "",
    secret: Boolean = false,
    numeric: Boolean = false,
    multiline: Boolean = false
): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setHintTextColor(C.MUTED)
    e.setTextColor(C.WHITE)
    e.textSize = 14f
    e.background = shape(C.INPUT, 8, C.BORDER, 1)
    e.setPadding(dp(12), dp(10), dp(12), dp(10))
    e.inputType = when {
        numeric -> InputType.TYPE_CLASS_NUMBER
        secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    if (multiline) {
        e.minLines = 3
        e.gravity = Gravity.TOP or Gravity.START
    }
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.bottomMargin = dp(10)
    e.layoutParams = lp
    return e
}

fun Context.btn(
    text: String,
    fill: Int = C.INPUT,
    fg: Int = C.CYAN,
    stroke: Int = C.BORDER,
    heightDp: Int = 44
): Button {
    val b = Button(this)
    b.text = text
    b.isAllCaps = false
    b.setTextColor(fg)
    b.textSize = 13f
    b.typeface = Typeface.DEFAULT_BOLD
    b.stateListAnimator = null
    b.background = pressable(fill, C.BORDER, 10, stroke, 1)
    b.minHeight = 0
    b.minimumHeight = 0
    b.setPadding(dp(14), 0, dp(14), 0)
    b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp))
    return b
}

fun Context.spinner(items: List<String>): Spinner {
    val ctx = this
    val sp = Spinner(this)
    sp.background = shape(C.INPUT, 8, C.BORDER, 1)
    sp.setPadding(dp(8), dp(6), dp(8), dp(6))
    sp.adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent) as TextView
            v.setTextColor(C.WHITE)
            v.textSize = 14f
            return v
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getDropDownView(position, convertView, parent) as TextView
            v.setTextColor(C.WHITE)
            v.setBackgroundColor(C.INPUT)
            v.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            v.textSize = 14f
            return v
        }
    }
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
    lp.bottomMargin = dp(10)
    sp.layoutParams = lp
    return sp
}

fun Context.switchView(): Switch {
    val s = Switch(this)
    s.thumbTintList = ColorStateList.valueOf(C.CYAN)
    s.trackTintList = ColorStateList.valueOf(C.BORDER)
    return s
}

fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams {
    val p = if (weight > 0f) LinearLayout.LayoutParams(w, h, weight) else LinearLayout.LayoutParams(w, h)
    p.setMargins(left, top, right, bottom)
    return p
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
