package com.amexnet.vpn

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class ProfilesTab(private val a: MainActivity) {
    val view: View
    private val list: LinearLayout
    private val linkEt: EditText

    init {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(24))

        root.addView(a.section("LOCAL PROFILES"))
        list = LinearLayout(a)
        list.orientation = LinearLayout.VERTICAL
        root.addView(list)

        val io = a.card()
        io.addView(a.section("IMPORT / EXPORT"))
        io.addView(pair(a.btn("Import .amex file").also { it.setOnClickListener { importFile() } },
            a.btn("Export as .amex file").also { it.setOnClickListener { exportFile() } }))
        io.addView(pair(a.btn("Export as Emoji").also { it.setOnClickListener { exportEmoji() } },
            a.btn("Import from Emoji").also { it.setOnClickListener { importEmojiDialog() } }))
        val paste = a.btn("Paste from Clipboard", C.CYAN, C.BG, C.CYAN)
        paste.layoutParams = lp(MATCH, a.dp(44), bottom = a.dp(12))
        paste.setOnClickListener { pasteClipboard() }
        io.addView(paste)
        io.addView(a.tv("Exports are protected by a password you choose. Share the password separately from the file.", 12f, C.MUTED).also { it.setPadding(0, 0, 0, a.dp(12)) })

        io.addView(a.tv("PASTE A LINK", 11f, C.MUTED).also { it.setPadding(0, 0, 0, a.dp(4)) })
        linkEt = a.input("vless://  vmess://  trojan://")
        io.addView(linkEt)
        val add = a.btn("Add from link")
        add.layoutParams = lp(MATCH, a.dp(44))
        add.setOnClickListener { addLink() }
        io.addView(add)
        root.addView(io)

        val sv = ScrollView(a)
        sv.setBackgroundColor(C.BG)
        sv.addView(root)
        view = sv
        refresh()
    }

    private fun pair(x: View, y: View): LinearLayout {
        val r = LinearLayout(a)
        r.orientation = LinearLayout.HORIZONTAL
        x.layoutParams = lp(0, a.dp(44), 1f, right = a.dp(6), bottom = a.dp(8))
        y.layoutParams = lp(0, a.dp(44), 1f, left = a.dp(6), bottom = a.dp(8))
        r.addView(x)
        r.addView(y)
        return r
    }

    fun refresh() {
        list.removeAllViews()
        val sel = Store.selectedId
        for (p in Store.profiles()) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val active = p.id == sel
            row.background = a.shape(C.CARD, 12, if (active) C.CYAN else C.BORDER, 1)
            row.setPadding(a.dp(14), a.dp(12), a.dp(10), a.dp(12))
            val col = LinearLayout(a)
            col.orientation = LinearLayout.VERTICAL
            col.addView(a.tv(p.name + if (p.locked) "  \uD83D\uDD12" else "", 15f, C.WHITE, true))
            val host = if (p.enforced) "hidden" else p.v("host").ifEmpty { "no host yet" }
            col.addView(a.tv("${p.mode.title}  \u00B7  $host", 12f, C.MUTED))
            row.addView(col, lp(0, WRAP, 1f))
            val del = a.tv("\u2715", 18f, C.RED, true)
            del.setPadding(a.dp(14), a.dp(6), a.dp(8), a.dp(6))
            del.setOnClickListener {
                AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Delete \"${p.name}\"?")
                    .setPositiveButton("Delete") { _, _ -> Store.delete(p.id); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            row.addView(del)
            row.setOnClickListener {
                Store.selectedId = p.id
                a.select(0)
            }
            list.addView(row, lp(MATCH, WRAP, bottom = a.dp(8)))
        }
    }

    // ---------------------------------------------------------------- password dialogs

    private fun chars(et: EditText): CharArray {
        val e = et.text
        val arr = CharArray(e.length)
        e.getChars(0, e.length, arr, 0)
        return arr
    }

    private fun askNewPassword(title: String, onOk: (CharArray) -> Unit) {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(20), a.dp(12), a.dp(20), 0)
        box.addView(
            a.tv("Choose a password of at least ${AmexCodec.MIN_PASSWORD} characters. Without it nobody can open the config, so share it separately from the file.", 12f, C.MUTED)
                .also { it.setPadding(0, 0, 0, a.dp(10)) }
        )
        val p1 = a.input("Password", secret = true)
        val p2 = a.input("Repeat password", secret = true)
        box.addView(p1)
        box.addView(p2)
        val d = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Continue", null)
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val x = chars(p1)
                val y = chars(p2)
                when {
                    x.size < AmexCodec.MIN_PASSWORD -> a.toast("Password is too short")
                    !x.contentEquals(y) -> a.toast("Passwords don't match")
                    else -> {
                        p1.setText(""); p2.setText("")
                        d.dismiss()
                        y.fill('\u0000')
                        onOk(x)
                        return@setOnClickListener
                    }
                }
                x.fill('\u0000'); y.fill('\u0000')
            }
        }
        d.show()
    }

    private fun askPassword(title: String, onOk: (CharArray) -> Unit) {
        val box = LinearLayout(a)
        box.setPadding(a.dp(20), a.dp(12), a.dp(20), 0)
        val p1 = a.input("Password", secret = true)
        box.addView(p1, lp(MATCH, WRAP))
        val d = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Open", null)
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val x = chars(p1)
                if (x.isEmpty()) { a.toast("Enter the password"); return@setOnClickListener }
                p1.setText("")
                d.dismiss()
                onOk(x)
            }
        }
        d.show()
    }

    // ---------------------------------------------------------------- export

    private fun blocked(p: Profile): Boolean {
        if (p.enforced) {
            a.toast("Protected configurations can't be exported")
            return true
        }
        return false
    }

    private fun encryptAsync(p: Profile, pw: CharArray, done: (ByteArray) -> Unit) {
        a.toast("Encrypting...")
        thread {
            try {
                val b = AmexCodec.encode(p, pw)
                a.runOnUiThread { done(b) }
            } catch (e: Exception) {
                a.runOnUiThread { a.toast("Export failed") }
                AppLog.e("Export failed: ${e.message}")
            } finally {
                pw.fill('\u0000')
            }
        }
    }

    fun exportFile() {
        val p = a.currentProfile() ?: return
        if (blocked(p)) return
        askNewPassword("Password for the .amex file") { pw ->
            encryptAsync(p, pw) { bytes ->
                a.pendingExport = bytes
                val safe = p.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifEmpty { "profile" }
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "application/octet-stream"
                i.putExtra(Intent.EXTRA_TITLE, "$safe.amex")
                a.startActivityForResult(i, MainActivity.REQ_SAVE)
            }
        }
    }

    fun exportEmoji() {
        val p = a.currentProfile() ?: return
        if (blocked(p)) return
        askNewPassword("Password for the emoji config") { pw ->
            encryptAsync(p, pw) { bytes -> showEmoji(p, AmexCodec.toEmoji(bytes)) }
        }
    }

    private fun showEmoji(p: Profile, s: String) {
        val tvw = TextView(a)
        tvw.text = s
        tvw.textSize = 16f
        tvw.setTextColor(C.WHITE)
        tvw.setTextIsSelectable(true)
        tvw.setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(12))
        val sv = ScrollView(a)
        sv.addView(tvw)
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("\"${p.name}\" as emoji")
            .setView(sv)
            .setPositiveButton("Share") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s)
                a.startActivity(Intent.createChooser(i, "Share config"))
            }
            .setNeutralButton("Copy") { _, _ ->
                Clip.copy(a, "AMEX NET VPN config", s)
                a.toast("Copied (cleared from the clipboard in 1 minute)")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- import

    fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        a.startActivityForResult(i, MainActivity.REQ_OPEN)
    }

    fun importFromUri(uri: Uri) {
        try {
            val bytes = a.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Can't open that file")
            if (bytes.size > 1_000_000) throw IllegalStateException("File too large")
            askDecode(bytes)
        } catch (e: Exception) {
            a.toast("Can't read that file")
            AppLog.e("Import failed: ${e.message}")
        }
    }

    private fun askDecode(bytes: ByteArray) {
        if (bytes.size < AmexCodec.MIN_SIZE) {
            a.toast("Not a valid AMEX NET VPN config")
            return
        }
        askPassword("Enter the password") { pw ->
            a.toast("Decrypting...")
            thread {
                try {
                    val p = AmexCodec.decode(bytes, pw)
                    a.runOnUiThread { addImported(p) }
                } catch (e: AmexCodec.BadPassword) {
                    a.runOnUiThread { a.toast("Wrong password or damaged config") }
                } catch (e: Exception) {
                    a.runOnUiThread { a.toast("Not a valid AMEX NET VPN config") }
                } finally {
                    pw.fill('\u0000')
                }
            }
        }
    }

    fun importEmojiDialog() {
        val et = a.input("Paste the emoji string here", multiline = true)
        et.setText(clipText())
        val box = LinearLayout(a)
        box.setPadding(a.dp(20), a.dp(12), a.dp(20), 0)
        box.addView(et, lp(MATCH, WRAP))
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Import from Emoji")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                if (!importText(et.text.toString())) a.toast("That isn't a valid AMEX NET VPN config")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clipText(): String {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString().orEmpty()
    }

    fun pasteClipboard() {
        val t = clipText()
        if (t.isBlank()) { a.toast("Clipboard is empty"); return }
        if (!importText(t)) a.toast("Clipboard doesn't contain a supported config")
    }

    private fun addLink() {
        val t = linkEt.text.toString()
        if (t.isBlank()) { a.toast("Paste a link first"); return }
        if (importText(t)) linkEt.setText("") else a.toast("Unsupported or invalid link")
    }

    /** Links import straight away; emoji strings ask for their password first. */
    fun importText(text: String): Boolean {
        val t = text.trim()
        val link = LinkParser.parse(t)
        if (link != null) { addImported(link); return true }
        if (AmexCodec.looksLikeEmoji(t)) {
            return try {
                askDecode(AmexCodec.fromEmoji(t))
                true
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    private fun addImported(p: Profile) {
        p.imported = true
        Store.upsert(p)
        Store.selectedId = p.id
        refresh()
        a.toast("Imported: ${p.name}")
        AppLog.i("Imported profile \"${if (p.enforced) "protected" else p.name}\" (${p.mode.title})")
    }
}
