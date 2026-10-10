package com.amexnet.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object XrayConfig {
    private fun isIp(h: String) = h.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h)

    fun build(p: Profile, addr: String, dns: String, socksPort: Int, user: String, pass: String): String {
        val host = p.v("host")
        val port = p.port(443)
        val net = p.v("net").ifEmpty { "tcp" }
        if (net != "tcp" && net != "ws" && net != "grpc") {
            throw IOException("Transport '$net' isn't supported here (use tcp, ws or grpc)")
        }
        var sec = p.v("sec").ifEmpty { if (p.mode == Mode.TROJAN) "tls" else "none" }
        val insecure = sec == "tls-insecure"
        if (insecure) sec = "tls"
        val wsHost = p.v("wshost")
        val sni = p.v("sni").ifEmpty { wsHost.ifEmpty { if (isIp(host)) "" else host } }

        val out = JSONObject().put("tag", "proxy")
        when (p.mode) {
            Mode.VLESS -> {
                val user = JSONObject().put("id", p.v("uuid")).put("encryption", p.v("enc").ifEmpty { "none" })
                if (p.v("flow").isNotEmpty()) user.put("flow", p.v("flow"))
                out.put("protocol", "vless").put(
                    "settings", JSONObject().put(
                        "vnext", JSONArray().put(
                            JSONObject().put("address", addr).put("port", port).put("users", JSONArray().put(user))
                        )
                    )
                )
            }
            Mode.VMESS -> {
                val user = JSONObject().put("id", p.v("uuid")).put("alterId", p.v("aid").toIntOrNull() ?: 0)
                    .put("security", p.v("enc").ifEmpty { "auto" })
                out.put("protocol", "vmess").put(
                    "settings", JSONObject().put(
                        "vnext", JSONArray().put(
                            JSONObject().put("address", addr).put("port", port).put("users", JSONArray().put(user))
                        )
                    )
                )
            }
            Mode.TROJAN -> {
                out.put("protocol", "trojan").put(
                    "settings", JSONObject().put(
                        "servers", JSONArray().put(
                            JSONObject().put("address", addr).put("port", port).put("password", p.f["pass"].orEmpty())
                        )
                    )
                )
            }
            else -> throw IllegalArgumentException("Not a V2Ray mode")
        }

        val ss = JSONObject().put("network", net).put("security", sec)
        if (sec == "tls") {
            val t = JSONObject().put("allowInsecure", insecure)
            if (sni.isNotEmpty()) t.put("serverName", sni)
            if (p.v("fp").isNotEmpty()) t.put("fingerprint", p.v("fp"))
            if (p.v("alpn").isNotEmpty()) {
                val a = JSONArray()
                for (x in p.v("alpn").split(",")) if (x.isNotBlank()) a.put(x.trim())
                t.put("alpn", a)
            }
            ss.put("tlsSettings", t)
        } else if (sec == "reality") {
            ss.put(
                "realitySettings", JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", p.v("fp").ifEmpty { "chrome" })
                    .put("publicKey", p.v("pbk"))
                    .put("shortId", p.v("sid"))
                    .put("spiderX", p.v("spx"))
            )
        }
        if (net == "ws") {
            ss.put(
                "wsSettings", JSONObject()
                    .put("path", p.v("path").ifEmpty { "/" })
                    .put("headers", JSONObject().put("Host", wsHost.ifEmpty { sni.ifEmpty { host } }))
            )
        } else if (net == "grpc") {
            ss.put("grpcSettings", JSONObject().put("serviceName", p.v("path")))
        }
        out.put("streamSettings", ss)

        val inbound = JSONObject()
            .put("tag", "socks").put("listen", "127.0.0.1").put("port", socksPort).put("protocol", "socks")
            .put(
                "settings", JSONObject().put("auth", "password").put("udp", true).put("ip", "127.0.0.1")
                    .put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", pass)))
            )

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", JSONArray().put(dns).put("1.1.1.1").put("8.8.8.8")))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(out).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .toString()
    }
}

/** Runs the bundled Xray core (libxray.so) as a child process exposing a local SOCKS5 inbound. */
class XrayRunner(
    private val ctx: Context,
    private val p: Profile,
    private val socksPort: Int,
    private val user: String,
    private val pass: String
) {
    private var proc: Process? = null
    private val tail = ArrayDeque<String>()

    fun alive(): Boolean {
        val pr = proc ?: return false
        return try { pr.exitValue(); false } catch (e: IllegalThreadStateException) { true }
    }

    fun start() {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) throw IOException("V2Ray core (libxray.so) is missing from this build")
        val host = p.v("host")
        val addr = try {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
                ?: InetAddress.getByName(host).hostAddress.orEmpty()
        } catch (e: Exception) {
            throw IOException("Can't resolve $host")
        }
        val cfg = File(ctx.filesDir, "xray.json")
        cfg.writeText(XrayConfig.build(p, addr, Store.dnsServer(), socksPort, user, pass))

        val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath)
        pb.directory(ctx.filesDir)
        pb.redirectErrorStream(true)
        pb.environment()["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
        pb.environment()["XRAY_LOCATION_ASSET"] = ctx.filesDir.absolutePath
        val pr = try {
            pb.start()
        } catch (e: Exception) {
            cfg.delete()
            throw e
        }
        proc = pr

        val reader = Thread({
            try {
                pr.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(tail) {
                        tail.addLast(line)
                        while (tail.size > 6) tail.removeFirst()
                    }
                    AppLog.i("xray: $line")
                }
            } catch (e: Exception) {
            }
        }, "xray-log")
        reader.isDaemon = true
        reader.start()

        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (!alive()) {
                Thread.sleep(150)
                cfg.delete()
                throw IOException("V2Ray core exited: " + synchronized(tail) { tail.lastOrNull().orEmpty() })
            }
            try {
                val s = Socket()
                try { s.connect(InetSocketAddress("127.0.0.1", socksPort), 300) } finally { s.close() }
                cfg.delete() // credentials must not stay on disk once the core has read them
                return
            } catch (e: Exception) {
                Thread.sleep(150)
            }
        }
        cfg.delete()
        stop()
        throw IOException("V2Ray core did not start in time")
    }

    fun stop() {
        try { proc?.destroy() } catch (e: Exception) {}
        proc = null
    }
}
