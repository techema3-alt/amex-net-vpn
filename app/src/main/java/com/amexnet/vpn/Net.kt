package com.amexnet.vpn

import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.jcraft.jsch.SocketFactory as JschSocketFactory

class Conn(
    val input: InputStream,
    val output: OutputStream,
    val shutdownOut: () -> Unit,
    val close: () -> Unit
)

interface Connector {
    fun open(host: String, port: Int): Conn
}

object Http {
    fun readHeaders(inp: InputStream, limit: Int = 32768): String {
        val sb = StringBuilder()
        while (sb.length < limit) {
            val b = inp.read()
            if (b < 0) throw IOException("Connection closed during handshake")
            sb.append(b.toChar())
            val n = sb.length
            if (n >= 4 && sb[n - 4] == '\r' && sb[n - 3] == '\n' && sb[n - 2] == '\r' && sb[n - 1] == '\n') break
            if (n >= 2 && sb[n - 2] == '\n' && sb[n - 1] == '\n') break
        }
        return sb.toString()
    }

    fun status(head: String): Int =
        Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    fun firstLine(head: String): String = head.lineSequence().firstOrNull()?.trim().orEmpty()

    /** Expands payload placeholders and splits on [split]. */
    fun expand(raw: String, host: String, port: Int, hostPort: String = "$host:$port"): List<ByteArray> {
        var s = raw.replace("\r\n", "\n").replace("\n", "\r\n")
        s = s.replace("\\r", "\r").replace("\\n", "\n")
        s = s.replace("[crlf]", "\r\n").replace("[lf]", "\n").replace("[cr]", "\r")
            .replace("[host_port]", hostPort).replace("[host]", host).replace("[port]", port.toString())
            .replace("[protocol]", "HTTP/1.1")
        return s.split("[split]").map { it.toByteArray(Charsets.ISO_8859_1) }
    }

    fun send(out: OutputStream, parts: List<ByteArray>) {
        for ((i, part) in parts.withIndex()) {
            out.write(part)
            out.flush()
            if (i < parts.size - 1) Thread.sleep(150)
        }
    }
}

object Tls {
    private val ctx: SSLContext by lazy {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val c = SSLContext.getInstance("TLS")
        c.init(null, tm, SecureRandom())
        c
    }

    fun wrap(s: Socket, sni: String, host: String, port: Int): SSLSocket {
        val ss = ctx.socketFactory.createSocket(s, sni.ifEmpty { host }, port, true) as SSLSocket
        if (sni.isNotEmpty()) {
            try {
                val p = ss.sslParameters
                p.serverNames = listOf(SNIHostName(sni))
                ss.sslParameters = p
            } catch (e: Exception) {
            }
        }
        ss.startHandshake()
        return ss
    }
}

/** Builds the transport underneath SSH for each SSH tunnel mode. */
class TunnelSocketFactory(private val p: Profile) : JschSocketFactory {
    private class T(val sock: Socket, val inp: InputStream, val out: OutputStream)

    private val ins = IdentityHashMap<Socket, InputStream>()
    private val outs = IdentityHashMap<Socket, OutputStream>()

    override fun createSocket(host: String, port: Int): Socket {
        val h = p.v("host")
        val prt = p.port(22)
        val sni = p.v("sni")
        val path = p.v("path").ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        val t: T = when (p.mode) {
            Mode.SSH_DIRECT -> {
                val s = plain(h, prt)
                T(s, s.getInputStream(), s.getOutputStream())
            }
            Mode.SSH_SSL -> {
                val s = Tls.wrap(plain(h, prt), sni, h, prt)
                T(s, s.inputStream, s.outputStream)
            }
            Mode.SSH_WS -> {
                val s = plain(h, prt)
                val i = s.getInputStream()
                val o = s.getOutputStream()
                upgrade(i, o, h, path)
                T(s, i, o)
            }
            Mode.SSH_SSL_WS -> {
                val s = Tls.wrap(plain(h, prt), sni, h, prt)
                upgrade(s.inputStream, s.outputStream, sni.ifEmpty { h }, path)
                T(s, s.inputStream, s.outputStream)
            }
            Mode.SSH_PAYLOAD -> {
                val s = plain(h, prt)
                val o = s.getOutputStream()
                val payload = p.v("payload")
                if (payload.isNotEmpty()) Http.send(o, Http.expand(p.f["payload"].orEmpty(), h, prt))
                T(s, sniff(s.getInputStream()), o)
            }
            Mode.SSH_SSL_PROXY -> {
                val proxy = p.v("proxy")
                if (proxy.isEmpty()) throw IOException("Proxy is required for this mode")
                val ph = proxy.substringBeforeLast(':', proxy)
                val pp = if (proxy.contains(':')) proxy.substringAfterLast(':').toIntOrNull() ?: 8080 else 8080
                val s = plain(ph, pp)
                val o = s.getOutputStream()
                val i = s.getInputStream()
                val raw = p.f["payload"].orEmpty().ifBlank { "CONNECT [host_port] HTTP/1.1[crlf]Host: [host_port][crlf][crlf]" }
                Http.send(o, Http.expand(raw, h, prt))
                val head = Http.readHeaders(i)
                val st = Http.status(head)
                if (st !in 200..299 && st != 101) throw IOException("Proxy replied: ${Http.firstLine(head).ifEmpty { "no response" }}")
                AppLog.i("Proxy accepted: ${Http.firstLine(head)}")
                val ssl = Tls.wrap(s, sni, h, prt)
                T(ssl, ssl.inputStream, ssl.outputStream)
            }
            else -> throw IOException("Not an SSH tunnel mode")
        }
        synchronized(ins) {
            ins[t.sock] = t.inp
            outs[t.sock] = t.out
        }
        return t.sock
    }

    override fun getInputStream(socket: Socket): InputStream =
        synchronized(ins) { ins[socket] } ?: socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream =
        synchronized(ins) { outs[socket] } ?: socket.getOutputStream()

    private fun plain(host: String, port: Int): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), 15000)
        return s
    }

    private fun upgrade(inp: InputStream, out: OutputStream, hostHeader: String, path: String) {
        val kb = ByteArray(16)
        SecureRandom().nextBytes(kb)
        val req = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: Mozilla/5.0 (Linux; Android 13)\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${Base64.encodeToString(kb, Base64.NO_WRAP)}\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        val head = Http.readHeaders(inp)
        if (Http.status(head) != 101) throw IOException("WebSocket upgrade refused: ${Http.firstLine(head)}")
        AppLog.i("WebSocket upgrade accepted")
    }

    /** If the server answers the payload with an HTTP response, consume it; otherwise leave the bytes (SSH banner) untouched. */
    private fun sniff(raw: InputStream): InputStream {
        val pb = PushbackInputStream(raw, 16)
        val first = ByteArray(5)
        var n = 0
        while (n < 5) {
            val r = pb.read(first, n, 5 - n)
            if (r < 0) break
            n += r
        }
        if (n == 5 && String(first, Charsets.ISO_8859_1) == "HTTP/") {
            val head = "HTTP/" + Http.readHeaders(pb)
            val st = Http.status(head)
            if (st >= 400) throw IOException("Server replied: ${Http.firstLine(head)}")
            AppLog.i("Payload accepted: ${Http.firstLine(head)}")
        } else if (n > 0) {
            pb.unread(first, 0, n)
        }
        return pb
    }
}

class SshResult(val session: Session, val fingerprint: String)

/** Accepts the first host key it sees (trust on first use) and rejects any different key afterwards. */
class PinRepo(private val pinned: String) : HostKeyRepository {
    @Volatile var seen = ""

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val fp = fingerprint(key)
        seen = fp
        return if (pinned.isEmpty() || pinned == fp) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "amex-pin"
    override fun getHostKey(): Array<HostKey> = arrayOf()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = arrayOf()

    companion object {
        fun fingerprint(blob: ByteArray): String =
            "SHA256:" + Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(blob), Base64.NO_WRAP or Base64.NO_PADDING
            )
    }
}

object SshTunnel {
    fun connect(p: Profile): SshResult {
        val jsch = JSch()
        val repo = PinRepo(p.v("hostkey"))
        jsch.setHostKeyRepository(repo)
        val s = jsch.getSession(p.v("user"), p.v("host"), p.port(22))
        s.setPassword(p.f["pass"].orEmpty())
        // The pinning repository above does the real check, before the password is sent
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        // Older servers (Dropbear, old OpenSSH) still need these
        addAlg(s, "server_host_key", "ssh-rsa")
        addAlg(s, "kex", "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1")
        addAlg(s, "cipher.s2c", "aes128-cbc,aes256-cbc")
        addAlg(s, "cipher.c2s", "aes128-cbc,aes256-cbc")
        s.setSocketFactory(TunnelSocketFactory(p))
        s.setServerAliveInterval(15000)
        s.setServerAliveCountMax(4)
        s.connect(25000)
        return SshResult(s, repo.seen)
    }

    private fun addAlg(s: Session, key: String, extra: String) {
        val cur = s.getConfig(key).orEmpty()
        s.setConfig(key, if (cur.isEmpty()) extra else "$cur,$extra")
    }
}

class SshConnector(private val session: Session) : Connector {
    override fun open(host: String, port: Int): Conn {
        val ch = session.getStreamForwarder(host, port)
        val i = ch.inputStream
        val o = ch.outputStream
        ch.connect(15000)
        return Conn(i, o, { try { o.close() } catch (e: Exception) {} }, { try { ch.disconnect() } catch (e: Exception) {} })
    }
}

class HttpConnectConnector(private val p: Profile) : Connector {
    override fun open(host: String, port: Int): Conn {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(p.v("host"), p.port(8080)), 15000)
        try {
            val target = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            val raw = p.f["payload"].orEmpty()
            val out = s.getOutputStream()
            if (raw.isBlank()) {
                out.write("CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray())
                out.flush()
            } else {
                Http.send(out, Http.expand(raw, host, port, target))
            }
            val head = Http.readHeaders(s.getInputStream())
            val st = Http.status(head)
            if (st !in 200..299) throw IOException("Proxy replied: ${Http.firstLine(head).ifEmpty { "no response" }}")
            return Conn(
                s.getInputStream(), out,
                { try { s.shutdownOutput() } catch (e: Exception) {} },
                { try { s.close() } catch (e: Exception) {} }
            )
        } catch (e: Exception) {
            try { s.close() } catch (x: Exception) {}
            throw e
        }
    }
}

/** Local SOCKS5 endpoint. hev-socks5-tunnel feeds device traffic into it; every CONNECT goes out through [connector]. */
class SocksServer(
    private val connector: Connector,
    private val port: Int,
    private val dnsFallback: Boolean,
    private val user: String,
    private val pass: String
) {
    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        val t = Thread(r, "socks")
        t.isDaemon = true
        t
    }
    @Volatile private var running = false
    @Volatile private var lastFail = 0L
    @Volatile private var udpWarned = false

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 256)
        server = ss
        running = true
        val t = Thread({
            while (running) {
                try {
                    val c = ss.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                    if (running) AppLog.w("SOCKS accept error: ${e.message}")
                }
            }
        }, "socks-accept")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(c: Socket) {
        try {
            c.tcpNoDelay = true
            c.soTimeout = 10_000
            val cin = c.getInputStream()
            val din = DataInputStream(cin)
            val o = c.getOutputStream()
            if (din.readUnsignedByte() != 5) { c.close(); return }
            val n = din.readUnsignedByte()
            val methods = ByteArray(n)
            din.readFully(methods)
            // Only username/password is accepted, so other apps on the phone can't use this local proxy.
            if (!methods.contains(2.toByte())) { o.write(byteArrayOf(5, -1)); o.flush(); c.close(); return }
            o.write(byteArrayOf(5, 2))
            o.flush()
            if (din.readUnsignedByte() != 1) { c.close(); return }
            val ub = ByteArray(din.readUnsignedByte()); din.readFully(ub)
            val pb = ByteArray(din.readUnsignedByte()); din.readFully(pb)
            val ok = MessageDigest.isEqual(ub, user.toByteArray(Charsets.UTF_8)) and
                MessageDigest.isEqual(pb, pass.toByteArray(Charsets.UTF_8))
            o.write(byteArrayOf(1, if (ok) 0 else 1))
            o.flush()
            if (!ok) { c.close(); return }
            c.soTimeout = 0
            if (din.readUnsignedByte() != 5) { c.close(); return }
            val cmd = din.readUnsignedByte()
            din.readUnsignedByte()
            val atyp = din.readUnsignedByte()
            val host: String = when (atyp) {
                1 -> {
                    val b = ByteArray(4); din.readFully(b)
                    InetAddress.getByAddress(b).hostAddress.orEmpty()
                }
                3 -> {
                    val l = din.readUnsignedByte(); val b = ByteArray(l); din.readFully(b)
                    String(b, Charsets.ISO_8859_1)
                }
                4 -> {
                    val b = ByteArray(16); din.readFully(b)
                    InetAddress.getByAddress(b).hostAddress.orEmpty()
                }
                else -> { reply(o, 8); c.close(); return }
            }
            val prt = din.readUnsignedShort()
            when (cmd) {
                1 -> doConnect(c, o, host, prt)
                3 -> doUdp(c, cin, o)
                else -> { reply(o, 7); c.close() }
            }
        } catch (e: Exception) {
            try { c.close() } catch (x: Exception) {}
        }
    }

    private fun reply(o: OutputStream, code: Int, port: Int = 0) {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
        o.flush()
    }

    private fun noteFail(host: String, port: Int, e: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastFail > 5000) {
            lastFail = now
            AppLog.w("Could not reach $host:$port through the tunnel (${e.message})")
        }
    }

    private fun doConnect(c: Socket, o: OutputStream, host: String, port: Int) {
        val conn = try {
            connector.open(host, port)
        } catch (e: Exception) {
            noteFail(host, port, e)
            try { reply(o, 5) } catch (x: Exception) {}
            try { c.close() } catch (x: Exception) {}
            return
        }
        reply(o, 0)
        val cin = c.getInputStream()
        val up = Thread({
            var failed = false
            try { copy(cin, conn.output) } catch (e: Exception) { failed = true }
            if (failed) conn.close() else try { conn.shutdownOut() } catch (e: Exception) {}
        }, "socks-up")
        up.isDaemon = true
        up.start()
        try { copy(conn.input, c.getOutputStream()) } catch (e: Exception) {}
        try { c.close() } catch (e: Exception) {}
        conn.close()
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val buf = ByteArray(32768)
        while (true) {
            val n = i.read(buf)
            if (n < 0) return
            if (n > 0) {
                o.write(buf, 0, n)
                o.flush()
            }
        }
    }

    // ---- UDP ASSOCIATE: only DNS (port 53) is supported, carried as DNS-over-TCP through the tunnel ----

    private fun doUdp(c: Socket, cin: InputStream, o: OutputStream) {
        val ds = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        reply(o, 0, ds.localPort)
        val watch = Thread({
            try { while (cin.read() >= 0) { } } catch (e: Exception) {}
            ds.close()
            try { c.close() } catch (e: Exception) {}
        }, "socks-udp-watch")
        watch.isDaemon = true
        watch.start()
        val buf = ByteArray(65535)
        var owner: InetSocketAddress? = null
        while (!ds.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            try { ds.receive(pkt) } catch (e: Exception) { break }
            val d = Arrays.copyOfRange(pkt.data, pkt.offset, pkt.offset + pkt.length)
            val from = InetSocketAddress(pkt.address, pkt.port)
            // Only the sender that opened the association may use it
            if (owner == null) owner = from else if (owner != from) continue
            try {
                pool.execute { try { udpPacket(ds, from, d) } catch (e: Exception) {} }
            } catch (e: Exception) {
                break
            }
        }
        ds.close()
    }

    private fun udpPacket(ds: DatagramSocket, from: InetSocketAddress, d: ByteArray) {
        if (d.size < 10 || d[2].toInt() != 0) return
        var p = 3
        val atyp = d[p++].toInt()
        val host: String
        when (atyp) {
            1 -> { host = InetAddress.getByAddress(d.copyOfRange(p, p + 4)).hostAddress ?: return; p += 4 }
            3 -> { val l = d[p++].toInt() and 0xFF; host = String(d, p, l, Charsets.ISO_8859_1); p += l }
            4 -> { host = InetAddress.getByAddress(d.copyOfRange(p, p + 16)).hostAddress ?: return; p += 16 }
            else -> return
        }
        val port = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
        p += 2
        if (port != 53) {
            if (!udpWarned) {
                udpWarned = true
                AppLog.w("UDP other than DNS can't pass through this tunnel (dropped)")
            }
            return
        }
        val q = d.copyOfRange(p, d.size)
        val resp = dnsQuery(host, q) ?: return
        val out = ByteArray(p + resp.size)
        System.arraycopy(d, 0, out, 0, p)
        System.arraycopy(resp, 0, out, p, resp.size)
        ds.send(DatagramPacket(out, out.size, from))
    }

    private fun dnsQuery(server: String, q: ByteArray): ByteArray? {
        val servers = if (dnsFallback) listOf(server) + listOf("1.1.1.1", "8.8.8.8", "9.9.9.9").filter { it != server } else listOf(server)
        for (s in servers) {
            try {
                return dnsOverTcp(s, q)
            } catch (e: Exception) {
                noteFail(s, 53, e)
            }
        }
        return null
    }

    private fun dnsOverTcp(server: String, q: ByteArray): ByteArray {
        val ref = AtomicReference<Conn?>()
        val f = pool.submit(Callable<ByteArray> {
            val conn = connector.open(server, 53)
            ref.set(conn)
            try {
                val msg = ByteArray(q.size + 2)
                msg[0] = (q.size shr 8).toByte()
                msg[1] = q.size.toByte()
                System.arraycopy(q, 0, msg, 2, q.size)
                conn.output.write(msg)
                conn.output.flush()
                val inp = DataInputStream(conn.input)
                val len = inp.readUnsignedShort()
                val r = ByteArray(len)
                inp.readFully(r)
                r
            } finally {
                conn.close()
            }
        })
        try {
            return f.get(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            f.cancel(true)
            ref.get()?.close()
            throw e
        }
    }
}

object Net {
    /** TCP connect time to the selected server, in ms. -1 on failure. */
    fun ping(p: Profile): Long {
        val host = p.v("host")
        if (host.isEmpty()) return -1
        val port = p.port(if (p.mode.isSsh) 22 else 443)
        return try {
            val t = System.nanoTime()
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 4000)
            } finally {
                try { s.close() } catch (e: Exception) {}
            }
            (System.nanoTime() - t) / 1_000_000
        } catch (e: Exception) {
            -1
        }
    }
}

object AutoPinger {
    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun apply(on: Boolean) {
        if (on && !running) {
            running = true
            val t = Thread({
                while (running) {
                    val p = Store.selected()
                    if (p != null && p.v("host").isNotEmpty()) {
                        val ms = Net.ping(p)
                        AppLog.i("Auto-Ping ${p.v("host")}: " + if (ms >= 0) "$ms ms" else "timeout")
                    }
                    try { Thread.sleep(10000) } catch (e: InterruptedException) { break }
                }
            }, "auto-ping")
            t.isDaemon = true
            thread = t
            t.start()
        } else if (!on && running) {
            running = false
            thread?.interrupt()
            thread = null
        }
    }
}
