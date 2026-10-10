package com.amexnet.vpn

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

class HomeTab(private val a: MainActivity) {
    val view: ScrollView

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var dot: View
    private lateinit var statusTv: TextView
    private lateinit var profileTv: TextView
    private lateinit var uptimeTv: TextView
    private lateinit var connectBtn: Button
    private lateinit var rxTotal: TextView
    private lateinit var rxSpeed: TextView
    private lateinit var txTotal: TextView
    private lateinit var txSpeed: TextView
    private lateinit var pingTv: TextView
    private lateinit var profileSpin: Spinner
    private lateinit var modeSpin: Spinner
    private lateinit var modeSub: TextView
    private lateinit var nameEt: EditText
    private lateinit var protBox: LinearLayout
    private lateinit var formBox: LinearLayout
    private lateinit var notesBox: LinearLayout

    private var profiles = mutableListOf<Profile>()
    private lateinit var cur: Profile
    private val fields = LinkedHashMap<String, View>()
    private var suppress = false

    private val tick = object : Runnable {
        override fun run() {
            if (VpnState.state == St.CONNECTED) {
                uptimeTv.text = fmtUptime(SystemClock.elapsedRealtime() - VpnState.since)
                rxTotal.text = fmtBytes(VpnState.rx)
                txTotal.text = fmtBytes(VpnState.tx)
                rxSpeed.text = fmtSpeed(VpnState.rxSpeed)
                txSpeed.text = fmtSpeed(VpnState.txSpeed)
            }
            ui.postDelayed(this, 1000)
        }
    }

    init {
        view = build()
        reload()
        render()
    }

    fun start() { ui.removeCallbacks(tick); ui.post(tick) }
    fun stop() { ui.removeCallbacks(tick) }
    fun onShow() { reload(); render() }

    // ------------------------------------------------------------ layout

    private fun build(): ScrollView {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(24))

        // status block
        val statusRow = LinearLayout(a)
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER
        dot = View(a)
        dot.layoutParams = lp(a.dp(14), a.dp(14), right = a.dp(10))
        statusTv = a.tv("Disconnected", 20f, C.WHITE, true)
        statusRow.addView(dot)
        statusRow.addView(statusTv)
        root.addView(statusRow, lp(MATCH, WRAP))

        profileTv = a.tv("", 13f, C.MUTED)
        profileTv.gravity = Gravity.CENTER
        root.addView(profileTv, lp(MATCH, WRAP, top = a.dp(4)))

        uptimeTv = a.tv("00:00:00", 28f, C.CYAN, true)
        uptimeTv.typeface = Typeface.MONOSPACE
        uptimeTv.gravity = Gravity.CENTER
        uptimeTv.visibility = View.GONE
        root.addView(uptimeTv, lp(MATCH, WRAP, top = a.dp(8)))

        // connect button: wide rounded rectangle, 54dp tall, 25dp radius
        connectBtn = Button(a)
        connectBtn.textSize = 16f
        connectBtn.typeface = Typeface.DEFAULT_BOLD
        connectBtn.isAllCaps = false
        connectBtn.stateListAnimator = null
        connectBtn.setOnClickListener { onConnect() }
        root.addView(connectBtn, lp(MATCH, a.dp(54), top = a.dp(16), bottom = a.dp(16)))

        // traffic row
        val traffic = LinearLayout(a)
        traffic.orientation = LinearLayout.HORIZONTAL
        val down = trafficCard("\u2B07", "DOWNLINK", C.GREEN)
        val up = trafficCard("\u2B06", "UPLINK", C.CYAN)
        rxTotal = down.second; rxSpeed = down.third
        txTotal = up.second; txSpeed = up.third
        down.first.layoutParams = lp(0, WRAP, 1f, right = a.dp(6), bottom = a.dp(12))
        up.first.layoutParams = lp(0, WRAP, 1f, left = a.dp(6), bottom = a.dp(12))
        traffic.addView(down.first)
        traffic.addView(up.first)
        root.addView(traffic, lp(MATCH, WRAP))

        // ping + auto ping
        val pingCard = a.card()
        val pingRow = LinearLayout(a)
        pingRow.orientation = LinearLayout.HORIZONTAL
        pingRow.gravity = Gravity.CENTER_VERTICAL
        val pingBtn = a.btn("PING")
        pingBtn.layoutParams = lp(WRAP, a.dp(40), right = a.dp(12))
        pingTv = a.tv("\u2014", 15f, C.WHITE, true)
        val autoLabel = a.tv("Auto-Ping", 13f, C.MUTED)
        autoLabel.setPadding(0, 0, a.dp(8), 0)
        val autoSw: Switch = a.switchView()
        autoSw.isChecked = Store.autoPing
        autoSw.setOnCheckedChangeListener { _, on ->
            Store.autoPing = on
            AutoPinger.apply(on)
            AppLog.i("Auto-Ping " + if (on) "enabled" else "disabled")
        }
        pingRow.addView(pingBtn)
        pingRow.addView(pingTv, lp(0, WRAP, 1f))
        pingRow.addView(autoLabel)
        pingRow.addView(autoSw)
        pingCard.addView(pingRow)
        root.addView(pingCard)
        pingBtn.setOnClickListener {
            collect()
            val p = cur
            pingTv.setTextColor(C.MUTED)
            pingTv.text = "Pinging..."
            thread {
                val ms = Net.ping(p)
                a.runOnUiThread {
                    if (ms >= 0) { pingTv.setTextColor(C.GREEN); pingTv.text = "$ms ms" }
                    else { pingTv.setTextColor(C.RED); pingTv.text = "Timeout" }
                    AppLog.i("Ping ${p.v("host")}: " + if (ms >= 0) "$ms ms" else "timeout")
                }
            }
        }

        // select server
        val srvCard = a.card()
        val hdr = LinearLayout(a)
        hdr.orientation = LinearLayout.HORIZONTAL
        hdr.gravity = Gravity.CENTER_VERTICAL
        hdr.addView(a.section("SELECT SERVER"), lp(0, WRAP, 1f))
        val refresh = a.tv("\u27F3", 22f, C.CYAN, true)
        refresh.setPadding(a.dp(10), 0, 0, a.dp(8))
        refresh.setOnClickListener { collectAndSave(); reload(); render(); a.toast("Profiles refreshed") }
        hdr.addView(refresh)
        srvCard.addView(hdr)
        profileSpin = a.spinner(listOf("-"))
        profileSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppress || pos < 0 || pos >= profiles.size || profiles[pos].id == cur.id) return
                collectAndSave()
                cur = profiles[pos]
                Store.selectedId = cur.id
                loadForm()
                render()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        srvCard.addView(profileSpin)

        srvCard.addView(a.tv("PROFILE NAME", 11f, C.MUTED).also { it.setPadding(0, 0, 0, a.dp(4)) })
        nameEt = a.input("Profile name")
        srvCard.addView(nameEt)

        srvCard.addView(a.tv("TUNNEL MODE", 11f, C.MUTED).also { it.setPadding(0, 0, 0, a.dp(4)) })
        modeSpin = a.spinner(Mode.values().map { it.title })
        modeSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppress) return
                val m = Mode.values()[pos]
                if (m == cur.mode) return
                collect()
                cur.mode = m
                buildForm()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        srvCard.addView(modeSpin)
        modeSub = a.tv("", 12f, C.MUTED)
        modeSub.setPadding(0, 0, 0, a.dp(12))
        srvCard.addView(modeSub)

        protBox = LinearLayout(a)
        protBox.orientation = LinearLayout.VERTICAL
        srvCard.addView(protBox)

        formBox = LinearLayout(a)
        formBox.orientation = LinearLayout.VERTICAL
        srvCard.addView(formBox)

        val btnRow = LinearLayout(a)
        btnRow.orientation = LinearLayout.HORIZONTAL
        val save = a.btn("Save")
        val add = a.btn("New")
        val del = a.btn("Delete", C.INPUT, C.RED)
        save.layoutParams = lp(0, a.dp(42), 1f, right = a.dp(6))
        add.layoutParams = lp(0, a.dp(42), 1f, right = a.dp(6))
        del.layoutParams = lp(0, a.dp(42), 1f)
        btnRow.addView(save); btnRow.addView(add); btnRow.addView(del)
        srvCard.addView(btnRow)
        root.addView(srvCard)
        save.setOnClickListener { collectAndSave(); reload(); render(); a.toast("Saved") }
        add.setOnClickListener {
            collectAndSave()
            val p = Profile(name = "Profile ${Store.profiles().size + 1}", mode = cur.mode)
            Store.upsert(p)
            Store.selectedId = p.id
            reload(); render()
        }
        del.setOnClickListener {
            AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("Delete \"${cur.name}\"?")
                .setPositiveButton("Delete") { _, _ -> Store.delete(cur.id); reload(); render() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // notes & message
        notesBox = a.card()
        root.addView(notesBox)

        // community / help
        val help = a.card()
        help.addView(a.section("COMMUNITY & HELP"))
        help.addView(a.tv("Share configs with friends as .amex files or emoji strings from the Profiles tab. If something fails, open the Logs tab and copy the log when asking for help.", 13f, C.MUTED))
        root.addView(help)

        val sv = ScrollView(a)
        sv.setBackgroundColor(C.BG)
        sv.isFillViewport = true
        sv.addView(root)
        return sv
    }

    private fun trafficCard(icon: String, title: String, tint: Int): Triple<LinearLayout, TextView, TextView> {
        val c = a.card()
        val top = LinearLayout(a)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(a.tv(icon, 16f, tint, true), lp(WRAP, WRAP, right = a.dp(6)))
        top.addView(a.tv(title, 11f, C.MUTED, true))
        c.addView(top)
        val total = a.tv("0 B", 17f, C.WHITE, true)
        total.setPadding(0, a.dp(6), 0, 0)
        val speed = a.tv("0 B/s", 12f, tint)
        c.addView(total)
        c.addView(speed)
        return Triple(c, total, speed)
    }

    // ------------------------------------------------------------ data <-> form

    fun reload() {
        profiles = Store.profiles()
        cur = Store.selected() ?: profiles.first()
        Store.selectedId = cur.id
        if (VpnState.state == St.DISCONNECTED) {
            if (cur.enforced) Redact.set(secretsOf(cur)) else Redact.clear()
            AppLog.scrub()
        }
        suppress = true
        profileSpin.adapter = a.spinner(profiles.map { it.name }).adapter
        profileSpin.setSelection(profiles.indexOfFirst { it.id == cur.id }.coerceAtLeast(0))
        suppress = false
        loadForm()
    }

    private fun loadForm() {
        suppress = true
        nameEt.setText(cur.name)
        modeSpin.setSelection(cur.mode.ordinal)
        suppress = false
        buildForm()
    }

    private fun buildForm() {
        modeSub.text = cur.mode.subtitle
        formBox.removeAllViews()
        protBox.removeAllViews()
        fields.clear()

        if (cur.enforced) {
            val strip = a.card()
            strip.background = a.shape(C.INPUT, 10, C.YELLOW, 1)
            strip.addView(a.tv("\uD83D\uDD12  PROTECTED CONFIGURATION", 12f, C.YELLOW, true))
            strip.addView(a.tv("Server details are hidden and locked by the creator. This config can't be exported.", 12f, C.MUTED))
            val reset = a.tv("Reset", 13f, C.CYAN, true)
            reset.paint.isUnderlineText = true
            reset.setPadding(0, a.dp(6), 0, 0)
            reset.setOnClickListener {
                AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Clear the locked fields and unlock this profile for manual editing?")
                    .setPositiveButton("Reset") { _, _ ->
                        for (k in LOCKED_KEYS) cur.f[k] = ""
                        cur.f["hostkey"] = ""
                        cur.locked = false
                        Store.upsert(cur)
                        buildForm()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            strip.addView(reset)
            protBox.addView(strip)
        }

        for (spec in cur.mode.fields) {
            val key = spec.f.key
            val lbl = a.tv(spec.label.uppercase(), 11f, C.MUTED)
            lbl.setPadding(0, 0, 0, a.dp(4))
            formBox.addView(lbl)
            val w: View
            if (spec.choices != null) {
                val items = spec.choices.toMutableList()
                val have = cur.f[key].orEmpty()
                if (have.isNotEmpty() && have !in items) items.add(have)
                val sp = a.spinner(items)
                val idx = items.indexOf(have)
                sp.setSelection(if (idx >= 0) idx else 0)
                w = sp
            } else {
                val et = a.input(spec.hint, spec.f.secret, spec.f.numeric, spec.f.multiline)
                if (spec.f != F.LINK) et.setText(if (cur.enforced && key in LOCKED_KEYS) "\u2022\u2022\u2022\u2022\u2022\u2022" else cur.f[key].orEmpty())
                if (spec.f == F.LINK) {
                    et.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            val parsed = LinkParser.parse(s?.toString().orEmpty()) ?: return
                            ui.post { applyParsed(parsed) }
                        }
                    })
                }
                w = et
            }
            if (cur.enforced && key in LOCKED_KEYS) {
                w.isEnabled = false
                w.alpha = 0.55f
            }
            fields[key] = w
            formBox.addView(w)
        }
        val pin = cur.v("hostkey")
        if (pin.isNotEmpty() && !cur.enforced) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(a.tv("\uD83D\uDD11 Server identity pinned  ${pin.take(18)}...", 12f, C.MUTED), lp(0, WRAP, 1f))
            val forget = a.tv("Forget", 13f, C.CYAN, true)
            forget.paint.isUnderlineText = true
            forget.setPadding(a.dp(10), a.dp(6), 0, a.dp(6))
            forget.setOnClickListener {
                collect()
                Store.upsert(cur, clearPin = true)
                a.toast("Pinned identity cleared. The next connection pins the server again")
                buildForm()
            }
            row.addView(forget)
            formBox.addView(row, lp(MATCH, WRAP, bottom = a.dp(10)))
        }
        rebuildNotes()
    }

    private fun applyParsed(p: Profile) {
        cur.mode = p.mode
        cur.f.putAll(p.f)
        if (cur.name.startsWith("Profile ") || cur.name == "My first profile" || cur.name == "New profile") cur.name = p.name
        Store.upsert(cur)
        loadForm()
        a.toast("Link imported")
        AppLog.i("Imported ${p.mode.title} link: ${p.name}")
    }

    /** Copy form values into the current profile (does not persist). */
    private fun collect() {
        if (!::cur.isInitialized) return
        val n = nameEt.text.toString().trim()
        if (n.isNotEmpty()) cur.name = n
        for ((key, w) in fields) {
            if (key == "link") continue
            if (cur.enforced && key in LOCKED_KEYS) continue
            when (w) {
                is Spinner -> cur.f[key] = w.selectedItem?.toString().orEmpty()
                is EditText -> cur.f[key] = w.text.toString().trim()
            }
        }
    }

    fun collectAndSave() {
        collect()
        if (::cur.isInitialized) Store.upsert(cur)
    }

    fun current(): Profile = cur

    // ------------------------------------------------------------ notes & message

    private fun rebuildNotes() {
        notesBox.removeAllViews()
        notesBox.addView(a.section("NOTES & MESSAGE"))
        if (cur.expiry.isNotBlank()) {
            val expired = Expiry.isExpired(cur.expiry)
            val pill = a.tv(
                if (expired) "\u23F3 Expired: ${cur.expiry}" else "\u23F3 Valid until: ${cur.expiry}",
                12f, if (expired) C.RED else C.YELLOW, true
            )
            pill.background = a.shape(C.INPUT, 12, if (expired) C.RED else C.YELLOW, 1)
            pill.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4))
            notesBox.addView(pill, lp(WRAP, WRAP, bottom = a.dp(10)))
        }
        val msg = a.tv(
            if (cur.note.isBlank()) "No message for this configuration." else cur.note,
            14f, if (cur.note.isBlank()) C.MUTED else C.WHITE
        )
        if (cur.note.isNotBlank()) {
            msg.autoLinkMask = Linkify.ALL
            msg.setLinkTextColor(C.CYAN)
            msg.movementMethod = LinkMovementMethod.getInstance()
            Linkify.addLinks(msg, Linkify.ALL)
        }
        notesBox.addView(msg)
        if (!cur.enforced) {
            val edit = a.btn("Edit notes")
            edit.layoutParams = lp(WRAP, a.dp(38), top = a.dp(10))
            edit.setOnClickListener { editNotes() }
            notesBox.addView(edit)
        }
    }

    private fun editNotes() {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(20), a.dp(12), a.dp(20), 0)
        val m = a.input("Message shown to whoever loads this config (links are clickable)", multiline = true)
        m.setText(cur.note)
        val e = a.input("Valid until, dd/mm/yyyy (optional)")
        e.setText(cur.expiry)
        val lock = CheckBox(a)
        lock.text = "Protect configuration (lock host, SNI and payload)"
        lock.setTextColor(C.WHITE)
        lock.isChecked = cur.locked
        box.addView(m); box.addView(e); box.addView(lock)
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Notes & message")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val exp = e.text.toString().trim()
                if (exp.isNotEmpty() && Expiry.parse(exp) == null) {
                    a.toast("Use the date format dd/mm/yyyy")
                    return@setPositiveButton
                }
                collect()
                cur.note = m.text.toString().trim()
                cur.expiry = exp
                cur.locked = lock.isChecked
                Store.upsert(cur)
                buildForm()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ connect / render

    private fun onConnect() {
        if (VpnState.state == St.DISCONNECTED) {
            collectAndSave()
            a.startConnect(cur)
        } else {
            a.disconnect()
        }
    }

    fun render() {
        if (!::cur.isInitialized) return
        val st = VpnState.state
        val color = when (st) {
            St.CONNECTED -> C.GREEN
            St.CONNECTING -> C.YELLOW
            St.DISCONNECTED -> C.RED
        }
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        dot.background = g
        statusTv.text = when (st) {
            St.CONNECTED -> "Connected"
            St.CONNECTING -> "Connecting..."
            St.DISCONNECTED -> "Disconnected"
        }
        profileTv.text = "Server: " + (if (st == St.DISCONNECTED) cur.name else VpnState.name.ifEmpty { cur.name })
        uptimeTv.visibility = if (st == St.CONNECTED) View.VISIBLE else View.GONE
        if (st == St.DISCONNECTED) {
            connectBtn.text = "CONNECT"
            connectBtn.setTextColor(C.BG)
            connectBtn.background = a.pressable(C.GREEN, C.GREEN_DARK, 25)
            rxTotal.text = "0 B"; txTotal.text = "0 B"; rxSpeed.text = "0 B/s"; txSpeed.text = "0 B/s"
        } else {
            connectBtn.text = if (st == St.CONNECTING) "CANCEL" else "DISCONNECT"
            connectBtn.setTextColor(Color.WHITE)
            connectBtn.background = a.pressable(C.RED, C.RED_DARK, 25)
        }
    }
}
