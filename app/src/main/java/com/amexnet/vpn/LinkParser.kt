package com.amexnet.vpn

import android.net.Uri
import android.util.Base64
import org.json.JSONObject

object LinkParser {
    fun parse(text: String): Profile? {
        val t = text.trim()
        return try {
            when {
                t.startsWith("vless://", true) -> vless(t)?.sanitize()
                t.startsWith("vmess://", true) -> vmess(t)?.sanitize()
                t.startsWith("trojan://", true) -> trojan(t)?.sanitize()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun common(u: Uri, p: Profile) {
        val q = { k: String -> u.getQueryParameter(k).orEmpty() }
        val type = q("type").ifEmpty { "tcp" }
        p.f["net"] = type
        p.f["sni"] = q("sni").ifEmpty { q("peer") }
        p.f["wshost"] = q("host")
        p.f["path"] = if (type == "grpc") q("serviceName") else q("path")
        p.f["flow"] = q("flow")
        p.f["fp"] = q("fp")
        p.f["pbk"] = q("pbk")
        p.f["sid"] = q("sid")
        p.f["spx"] = q("spx")
        p.f["alpn"] = q("alpn")
        var sec = q("security").ifEmpty { "none" }
        if (sec == "tls" && (q("allowInsecure") == "1" || q("insecure") == "1")) sec = "tls-insecure"
        p.f["sec"] = sec
    }

    private fun vless(t: String): Profile? {
        val u = Uri.parse(t)
        val host = u.host.orEmpty()
        if (host.isEmpty()) return null
        val p = Profile(mode = Mode.VLESS, name = u.fragment?.takeIf { it.isNotBlank() } ?: host)
        p.f["uuid"] = u.userInfo.orEmpty()
        p.f["host"] = host
        p.f["port"] = (if (u.port > 0) u.port else 443).toString()
        p.f["enc"] = u.getQueryParameter("encryption").orEmpty().ifEmpty { "none" }
        common(u, p)
        return p
    }

    private fun trojan(t: String): Profile? {
        val u = Uri.parse(t)
        val host = u.host.orEmpty()
        if (host.isEmpty()) return null
        val p = Profile(mode = Mode.TROJAN, name = u.fragment?.takeIf { it.isNotBlank() } ?: host)
        p.f["pass"] = u.userInfo.orEmpty()
        p.f["host"] = host
        p.f["port"] = (if (u.port > 0) u.port else 443).toString()
        common(u, p)
        if (u.getQueryParameter("security").isNullOrEmpty()) p.f["sec"] = "tls"
        return p
    }

    private fun vmess(t: String): Profile? {
        val body = t.substring(8).substringBefore('#').trim()
        val j = JSONObject(String(b64(body), Charsets.UTF_8))
        val host = j.optString("add")
        if (host.isEmpty()) return null
        val p = Profile(mode = Mode.VMESS, name = j.optString("ps").ifBlank { host })
        val net = j.optString("net").ifEmpty { "tcp" }
        p.f["host"] = host
        p.f["port"] = j.opt("port")?.toString().orEmpty().ifEmpty { "443" }
        p.f["uuid"] = j.optString("id")
        p.f["aid"] = j.opt("aid")?.toString().orEmpty().ifEmpty { "0" }
        p.f["enc"] = j.optString("scy").ifEmpty { "auto" }
        p.f["net"] = net
        p.f["sec"] = if (j.optString("tls") == "tls") "tls" else "none"
        p.f["sni"] = j.optString("sni")
        p.f["wshost"] = j.optString("host")
        p.f["path"] = j.optString("path")
        p.f["alpn"] = j.optString("alpn")
        p.f["fp"] = j.optString("fp")
        return p
    }

    private fun b64(s: String): ByteArray {
        var x = s.replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "").replace(" ", "")
        while (x.length % 4 != 0) x += "="
        return Base64.decode(x, Base64.DEFAULT)
    }
}
