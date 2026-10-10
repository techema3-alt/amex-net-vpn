package com.amexnet.vpn

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Every editable field a profile can hold. */
enum class F(
    val key: String,
    val label: String,
    val hint: String = "",
    val secret: Boolean = false,
    val multiline: Boolean = false,
    val numeric: Boolean = false,
    val choices: List<String>? = null
) {
    HOST("host", "Host", "server.example.com"),
    PORT("port", "Port", "443", numeric = true),
    USER("user", "Username", "username"),
    PASS("pass", "Password", "password", secret = true),
    SNI("sni", "SNI", "sni.example.com"),
    PATH("path", "WebSocket path", "/"),
    PROXY("proxy", "Proxy (host:port)", "1.2.3.4:8080"),
    PAYLOAD("payload", "Payload", "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]", multiline = true),
    LINK("link", "Paste link", "vless://  vmess://  trojan://"),
    UUID("uuid", "UUID", "00000000-0000-0000-0000-000000000000"),
    ENC("enc", "Encryption", "none"),
    AID("aid", "Alter ID", "0", numeric = true),
    NET("net", "Transport", choices = listOf("tcp", "ws", "grpc")),
    SEC("sec", "Security", choices = listOf("none", "tls", "tls-insecure", "reality")),
    WSHOST("wshost", "WS Host header", "optional")
}

class FieldSpec(
    val f: F,
    val label: String = f.label,
    val hint: String = f.hint,
    val choices: List<String>? = f.choices
)

enum class Mode(val id: String, val title: String, val subtitle: String, val fields: List<FieldSpec>) {
    SSH_DIRECT(
        "ssh_direct", "SSH Direct",
        "Plain SSH tunnel straight to your server. Best on open networks.",
        listOf(FieldSpec(F.HOST), FieldSpec(F.PORT, hint = "22"), FieldSpec(F.USER), FieldSpec(F.PASS))
    ),
    SSH_SSL(
        "ssh_ssl", "SSH SSL",
        "SSH wrapped in TLS with a custom SNI. Looks like normal HTTPS traffic.",
        listOf(FieldSpec(F.HOST), FieldSpec(F.PORT, hint = "443"), FieldSpec(F.SNI), FieldSpec(F.USER), FieldSpec(F.PASS))
    ),
    SSH_WS(
        "ssh_ws", "SSH WS Direct",
        "SSH carried over a WebSocket upgrade on a plain connection.",
        listOf(FieldSpec(F.HOST), FieldSpec(F.PORT, hint = "80"), FieldSpec(F.PATH), FieldSpec(F.USER), FieldSpec(F.PASS))
    ),
    SSH_SSL_WS(
        "ssh_ssl_ws", "SSH SSL WS",
        "SSH over WebSocket inside TLS. Works through most CDNs.",
        listOf(
            FieldSpec(F.HOST), FieldSpec(F.PORT, hint = "443"), FieldSpec(F.SNI), FieldSpec(F.PATH),
            FieldSpec(F.USER), FieldSpec(F.PASS)
        )
    ),
    SSH_PAYLOAD(
        "ssh_payload", "SSH Payload",
        "SSH after a custom HTTP payload. Placeholders: [host] [port] [host_port] [crlf] [split]",
        listOf(
            FieldSpec(F.HOST), FieldSpec(F.PORT, hint = "80"), FieldSpec(F.USER), FieldSpec(F.PASS),
            FieldSpec(F.PAYLOAD)
        )
    ),
    SSH_SSL_PROXY(
        "ssh_ssl_proxy", "SSH SSL Proxy Payload",
        "Payload sent through an HTTP proxy first, then TLS with your SNI, then SSH.",
        listOf(
            FieldSpec(F.HOST), FieldSpec(F.PORT, hint = "443"), FieldSpec(F.SNI), FieldSpec(F.PROXY),
            FieldSpec(F.USER), FieldSpec(F.PASS), FieldSpec(F.PAYLOAD)
        )
    ),
    HTTP_CONNECT(
        "http_connect", "Direct HTTP CONNECT",
        "Routes all traffic through an HTTP CONNECT proxy. Payload is optional ([host_port] is replaced per request).",
        listOf(
            FieldSpec(F.HOST, label = "Proxy host"), FieldSpec(F.PORT, hint = "8080"),
            FieldSpec(F.PAYLOAD, label = "Payload (optional)")
        )
    ),
    VLESS(
        "vless", "V2Ray VLESS",
        "Paste a vless:// link, or fill in the server by hand.",
        listOf(
            FieldSpec(F.LINK, hint = "vless://..."), FieldSpec(F.HOST, label = "Server"), FieldSpec(F.PORT),
            FieldSpec(F.UUID), FieldSpec(F.ENC), FieldSpec(F.NET), FieldSpec(F.SEC), FieldSpec(F.SNI),
            FieldSpec(F.PATH, label = "WS path / gRPC service"), FieldSpec(F.WSHOST)
        )
    ),
    VMESS(
        "vmess", "V2Ray VMess",
        "Paste a vmess:// link, or fill in the server by hand.",
        listOf(
            FieldSpec(F.LINK, hint = "vmess://..."), FieldSpec(F.HOST, label = "Server"), FieldSpec(F.PORT),
            FieldSpec(F.UUID), FieldSpec(F.AID),
            FieldSpec(F.ENC, label = "Security", choices = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")),
            FieldSpec(F.NET), FieldSpec(F.SEC), FieldSpec(F.SNI),
            FieldSpec(F.PATH, label = "WS path / gRPC service"), FieldSpec(F.WSHOST)
        )
    ),
    TROJAN(
        "trojan", "Trojan",
        "Paste a trojan:// link, or fill in the server by hand.",
        listOf(
            FieldSpec(F.LINK, hint = "trojan://..."), FieldSpec(F.HOST, label = "Server"), FieldSpec(F.PORT),
            FieldSpec(F.PASS), FieldSpec(F.SNI), FieldSpec(F.NET),
            FieldSpec(F.SEC, choices = listOf("tls", "tls-insecure", "none", "reality")),
            FieldSpec(F.PATH, label = "WS path / gRPC service"), FieldSpec(F.WSHOST)
        )
    );

    val isSsh: Boolean get() = id.startsWith("ssh_")
    val isV2: Boolean get() = this == VLESS || this == VMESS || this == TROJAN
}

/** Fields hidden and locked when a protected configuration is loaded from someone else. */
val LOCKED_KEYS = setOf("host", "sni", "payload", "proxy", "user", "pass", "uuid", "wshost", "path")

private val ALLOWED_KEYS: Set<String> =
    F.values().map { it.key }.filter { it != "link" }.toSet() + setOf("flow", "fp", "pbk", "sid", "spx", "alpn", "hostkey")
private val HOSTISH_KEYS = setOf("host", "sni", "wshost", "proxy")
private val HOSTISH = Regex("^[A-Za-z0-9._:\\[\\]-]{0,260}$")

/** Cleans anything that came from outside (files, emoji, links): unknown keys dropped, sizes capped, hosts validated. */
fun Profile.sanitize(): Profile {
    name = name.replace(Regex("[\\r\\n\\u0000]"), " ").trim().take(64).ifEmpty { "Imported" }
    val keep = LinkedHashMap<String, String>()
    for ((k, v) in f) {
        if (k !in ALLOWED_KEYS) continue
        var x = v.take(if (k == "payload") 4096 else 512)
        if (k in HOSTISH_KEYS && !HOSTISH.matches(x.trim())) x = ""
        if (k == "port" || k == "aid") x = x.filter { it.isDigit() }.take(5)
        keep[k] = x
    }
    f.clear()
    f.putAll(keep)
    note = note.take(2000)
    if (expiry.isNotBlank() && Expiry.parse(expiry) == null) expiry = ""
    return this
}

/** Values to scrub from logs and crash reports while a protected config is in use. */
fun secretsOf(p: Profile): List<String> {
    if (!p.enforced) return emptyList()
    return LOCKED_KEYS.mapNotNull { p.f[it]?.trim()?.takeIf { v -> v.length >= 3 } }
}

class Profile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New profile",
    var mode: Mode = Mode.SSH_DIRECT,
    val f: MutableMap<String, String> = mutableMapOf(),
    var note: String = "",
    var expiry: String = "",
    var locked: Boolean = false,
    var imported: Boolean = false
) {
    /** A protected config that arrived from someone else: secrets stay hidden and it can't be re-exported. */
    val enforced: Boolean get() = locked && imported

    fun v(k: String): String = f[k].orEmpty().trim()
    fun port(def: Int): Int = v("port").toIntOrNull() ?: def

    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("id", id)
        j.put("name", name)
        j.put("mode", mode.id)
        val fo = JSONObject()
        for ((k, value) in f) fo.put(k, value)
        j.put("f", fo)
        j.put("note", note)
        j.put("expiry", expiry)
        j.put("locked", locked)
        j.put("imported", imported)
        return j
    }

    companion object {
        fun fromJson(j: JSONObject, keepId: Boolean = true): Profile {
            val m = mutableMapOf<String, String>()
            val fo = j.optJSONObject("f")
            if (fo != null) {
                val it = fo.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    m[k] = fo.optString(k)
                }
            }
            val modeId = j.optString("mode")
            return Profile(
                id = if (keepId) j.optString("id", UUID.randomUUID().toString()) else UUID.randomUUID().toString(),
                name = j.optString("name", "Imported"),
                mode = Mode.values().firstOrNull { it.id == modeId } ?: Mode.SSH_DIRECT,
                f = m,
                note = j.optString("note"),
                expiry = j.optString("expiry"),
                locked = j.optBoolean("locked"),
                imported = j.optBoolean("imported")
            )
        }
    }
}

object Expiry {
    private fun fmt(): SimpleDateFormat {
        val f = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        f.isLenient = false
        return f
    }

    fun parse(s: String): Date? = try { fmt().parse(s.trim()) } catch (e: Exception) { null }

    fun isExpired(s: String): Boolean {
        if (s.isBlank()) return false
        val d = parse(s) ?: return false
        return Store.trustedNow() > d.time + 24L * 3600L * 1000L
    }
}

object Validate {
    fun check(p: Profile): String? {
        if (p.v("host").isEmpty()) return "Enter the server host first"
        val port = p.v("port").toIntOrNull()
        if (port == null || port !in 1..65535) return "Enter a valid port (1-65535)"
        return when (p.mode) {
            Mode.VLESS, Mode.VMESS -> if (p.v("uuid").isEmpty()) "Enter the UUID" else null
            Mode.TROJAN -> if (p.f["pass"].orEmpty().isEmpty()) "Enter the Trojan password" else null
            Mode.HTTP_CONNECT -> null
            Mode.SSH_SSL_PROXY -> when {
                p.v("user").isEmpty() -> "Enter the SSH username"
                p.v("proxy").isEmpty() -> "Enter the proxy (host:port)"
                else -> null
            }
            else -> if (p.v("user").isEmpty()) "Enter the SSH username" else null
        }
    }
}

object Store {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("amex", Context.MODE_PRIVATE)
    }

    fun profiles(): MutableList<Profile> {
        val out = mutableListOf<Profile>()
        var migrate = false
        try {
            val raw = sp.getString("profiles", null)
            val json = when {
                raw == null -> "[]"
                raw.startsWith("[") -> { migrate = true; raw }
                else -> Vault.decrypt(raw) ?: "[]"
            }
            val a = JSONArray(json)
            for (i in 0 until a.length()) out.add(Profile.fromJson(a.getJSONObject(i)))
        } catch (e: Exception) {
        }
        if (migrate && out.isNotEmpty()) saveAll(out)
        if (out.isEmpty()) {
            val p = Profile(name = "My first profile")
            out.add(p)
            saveAll(out)
            selectedId = p.id
        }
        return out
    }

    fun saveAll(l: List<Profile>) {
        val a = JSONArray()
        for (p in l) a.put(p.toJson())
        sp.edit().putString("profiles", Vault.encrypt(a.toString())).apply()
    }

    fun upsert(p: Profile, clearPin: Boolean = false) {
        val l = profiles()
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) {
            val old = l[i]
            when {
                clearPin || old.v("host") != p.v("host") -> p.f["hostkey"] = ""
                p.f["hostkey"].isNullOrEmpty() -> old.f["hostkey"]?.takeIf { it.isNotEmpty() }?.let { p.f["hostkey"] = it }
            }
            l[i] = p
        } else {
            l.add(p)
        }
        saveAll(l)
    }

    fun delete(id: String) {
        val l = profiles().filter { it.id != id }
        saveAll(l)
        if (selectedId == id) selectedId = l.firstOrNull()?.id ?: ""
    }

    fun find(id: String): Profile? = profiles().firstOrNull { it.id == id }

    fun selected(): Profile? {
        val l = profiles()
        return l.firstOrNull { it.id == selectedId } ?: l.firstOrNull()
    }

    var selectedId: String
        get() = sp.getString("selected", "") ?: ""
        set(v) { sp.edit().putString("selected", v).apply() }

    // ---- settings ----
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }

    var notif: Boolean
        get() = bool("notif", true)
        set(v) = setBool("notif", v)
    var autoReconnect: Boolean
        get() = bool("autoReconnect", true)
        set(v) = setBool("autoReconnect", v)
    var crashReports: Boolean
        get() = bool("crash", true)
        set(v) = setBool("crash", v)
    var autoPing: Boolean
        get() = bool("autoPing", false)
        set(v) = setBool("autoPing", v)
    var dnsFallback: Boolean
        get() = bool("dnsFallback", true)
        set(v) = setBool("dnsFallback", v)

    var secureScreen: Boolean
        get() = bool("secureScreen", true)
        set(v) = setBool("secureScreen", v)
    var appLock: Boolean
        get() = bool("appLock", false)
        set(v) = setBool("appLock", v)

    /** Current time that can't be pushed backwards by changing the phone clock (used for config expiry). */
    fun trustedNow(): Long {
        val now = System.currentTimeMillis()
        val last = sp.getLong("lastSeen", 0L)
        val t = if (now < last - 5 * 60 * 1000L) last else now
        if (t > last) sp.edit().putLong("lastSeen", t).apply()
        return t
    }

    var dnsMode: Int
        get() = sp.getInt("dnsMode", 0)
        set(v) { sp.edit().putInt("dnsMode", v).apply() }
    var dnsCustom: String
        get() = sp.getString("dnsCustom", "") ?: ""
        set(v) { sp.edit().putString("dnsCustom", v).apply() }

    fun dnsServer(): String = when (dnsMode) {
        0 -> "1.1.1.1"
        1 -> "8.8.8.8"
        else -> dnsCustom.trim().ifEmpty { "1.1.1.1" }
    }

    /** Anonymous local install ID. Never leaves the device. */
    val deviceId: String
        get() {
            var id = sp.getString("deviceId", null)
            if (id == null) {
                id = UUID.randomUUID().toString().substring(0, 8).uppercase(Locale.US)
                sp.edit().putString("deviceId", id).apply()
            }
            return id
        }
}
