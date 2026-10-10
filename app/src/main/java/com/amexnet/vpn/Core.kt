package com.amexnet.vpn

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Strings that must never appear in logs or crash reports (secrets of protected configs). */
object Redact {
    private val secrets = java.util.concurrent.CopyOnWriteArraySet<String>()

    fun set(values: Collection<String>) {
        secrets.clear()
        for (v in values) if (v.length >= 3) secrets.add(v)
    }

    fun clear() = secrets.clear()

    fun apply(s: String): String {
        var r = s
        for (x in secrets) r = r.replace(x, "\u2022\u2022\u2022")
        return r
    }
}

object AppLog {
    private val buf = ArrayDeque<String>()
    val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(level: Char, msg: String) {
        val line: String
        synchronized(this) {
            line = fmt.format(Date()) + " " + level + " " + Redact.apply(msg)
            buf.addLast(line)
            while (buf.size > 1500) buf.removeFirst()
        }
        for (l in listeners) l(line)
    }

    fun i(m: String) = add('I', m)
    fun w(m: String) = add('W', m)
    fun e(m: String) = add('E', m)

    @Synchronized
    fun snapshot(): List<String> = buf.toList()

    @Synchronized
    fun clear() = buf.clear()

    /** Re-applies redaction to lines already in the buffer. */
    @Synchronized
    fun scrub() {
        val tmp = buf.map { Redact.apply(it) }
        buf.clear()
        buf.addAll(tmp)
    }

    fun text(): String = snapshot().joinToString("\n")
}

enum class St { DISCONNECTED, CONNECTING, CONNECTED }

object VpnState {
    @Volatile var state = St.DISCONNECTED
    @Volatile var since = 0L
    @Volatile var name = ""
    @Volatile var rx = 0L
    @Volatile var tx = 0L
    @Volatile var rxSpeed = 0L
    @Volatile var txSpeed = 0L
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun set(s: St) {
        state = s
        if (s == St.CONNECTED) since = SystemClock.elapsedRealtime()
        if (s != St.CONNECTED) {
            rx = 0; tx = 0; rxSpeed = 0; txSpeed = 0
        }
        for (l in listeners) l()
    }
}

fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun fmtSpeed(bps: Long): String = fmtBytes(bps) + "/s"

fun fmtUptime(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}
