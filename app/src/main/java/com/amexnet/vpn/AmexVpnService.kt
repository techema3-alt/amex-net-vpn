package com.amexnet.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jcraft.jsch.Session
import hev.htproxy.TProxyService
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CancellationException

private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

private fun randomToken(): String {
    val b = ByteArray(16)
    SecureRandom().nextBytes(b)
    return b.joinToString("") { "%02x".format(it) }
}

class AmexVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.amexnet.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.amexnet.vpn.DISCONNECT"
        const val EXTRA_ID = "profile_id"
        private const val CH_MAIN = "amex_vpn_main"
        private const val CH_MIN = "amex_vpn_min"
        private const val NOTIF_ID = 1001
    }

    /** Everything belonging to one connection attempt, so an old attempt can never close a newer one's resources. */
    private class Run {
        @Volatile var cancelled = false
        // Random local port and credentials per connection, so no other app can use the local proxy
        val port = freePort()
        val user = randomToken()
        val pass = randomToken()
        var socks: SocksServer? = null
        var ssh: Session? = null
        var xray: XrayRunner? = null
        var tun: ParcelFileDescriptor? = null
        @Volatile var tunStarted = false

        fun close() {
            cancelled = true
            if (tunStarted) {
                tunStarted = false
                try { TProxyService.TProxyStopService() } catch (e: Throwable) {}
            }
            try { tun?.close() } catch (e: Exception) {}
            tun = null
            try { socks?.stop() } catch (e: Exception) {}
            try { ssh?.disconnect() } catch (e: Exception) {}
            try { xray?.stop() } catch (e: Exception) {}
        }
    }

    private val lock = Any()
    @Volatile private var run: Run? = null
    @Volatile private var stopping = false
    @Volatile private var reconnecting = false
    private var profile: Profile? = null
    private var wake: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastNet: Network? = null
    private var baseRx = 0L
    private var baseTx = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val act = intent?.action
        val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
        when (act) {
            ACTION_CONNECT -> {
                val p = Store.find(id)
                if (p == null) {
                    goForeground("Starting...")
                    fullStop()
                } else {
                    goForeground("Connecting to ${p.name}...")
                    startConnect(p)
                }
            }
            ACTION_DISCONNECT -> {
                AppLog.i("Disconnecting...")
                fullStop()
            }
            else -> if (VpnState.state == St.DISCONNECTED) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startConnect(p: Profile) {
        synchronized(lock) {
            run?.close()
            stopping = false
            reconnecting = false
            profile = p
            VpnState.name = p.name
            VpnState.set(St.CONNECTING)
            val r = Run()
            run = r
            val t = Thread({
                try {
                    connectBlocking(r, p)
                } catch (e: CancellationException) {
                    r.close()
                } catch (e: Throwable) {
                    if (!r.cancelled) {
                        AppLog.e("Connection failed: ${friendly(e)}")
                        fullStop()
                    } else {
                        r.close()
                    }
                }
            }, "vpn-connect")
            t.isDaemon = true
            t.start()
        }
    }

    private fun checkRun(r: Run) {
        if (r.cancelled) throw CancellationException()
    }

    private fun connectBlocking(r: Run, p: Profile) {
        if (Expiry.isExpired(p.expiry)) throw IOException("This configuration expired on ${p.expiry}")
        if (p.enforced) Redact.set(secretsOf(p)) else Redact.clear()
        AppLog.scrub()
        acquireWake()
        AppLog.i("Connecting to ${p.name} (${p.mode.title})...")
        startBackend(r, p)
        checkRun(r)
        AppLog.i("Handshake complete")
        startTunnel(r)
        checkRun(r)
        val uid = android.os.Process.myUid()
        baseRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        baseTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        VpnState.set(St.CONNECTED)
        AppLog.i("Connected and routing internet")
        registerNetCallback()
        startMonitor(r, p)
        updateNotification()
    }

    private fun startBackend(r: Run, p: Profile) {
        when {
            p.mode.isSsh -> {
                AppLog.i("Connecting to remote proxy...")
                val res = SshTunnel.connect(p)
                val s = res.session
                r.ssh = s
                checkRun(r)
                AppLog.i("SSH authenticated as ${p.v("user")}")
                if (p.v("hostkey").isEmpty() && res.fingerprint.isNotEmpty()) {
                    p.f["hostkey"] = res.fingerprint
                    Store.upsert(p)
                    AppLog.i("Server identity pinned (${res.fingerprint.take(18)}...)")
                } else {
                    AppLog.i("Server identity verified")
                }
                val srv = SocksServer(SshConnector(s), r.port, Store.dnsFallback, r.user, r.pass)
                srv.start()
                r.socks = srv
            }
            p.mode == Mode.HTTP_CONNECT -> {
                AppLog.i("Connecting to remote proxy...")
                val srv = SocksServer(HttpConnectConnector(p), r.port, Store.dnsFallback, r.user, r.pass)
                srv.start()
                r.socks = srv
                AppLog.i("HTTP CONNECT proxy ${p.v("host")}:${p.port(8080)} ready")
            }
            else -> {
                AppLog.i("Starting V2Ray core...")
                val x = XrayRunner(this, p, r.port, r.user, r.pass)
                r.xray = x
                x.start()
            }
        }
    }

    private fun startTunnel(r: Run) {
        val b = Builder()
            .setSession("AMEX NET VPN")
            .setMtu(1500)
            .addAddress("26.26.26.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(Store.dnsServer())
        try { b.addDisallowedApplication(packageName) } catch (e: Exception) {}
        val pfd = b.establish() ?: throw IOException("Android refused to start the VPN (permission revoked?)")
        r.tun = pfd
        val cfg = File(filesDir, "tun.yml")
        cfg.writeText(
            "tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n" +
                "socks5:\n  port: ${r.port}\n  address: 127.0.0.1\n  udp: 'udp'\n" +
                "  username: '${r.user}'\n  password: '${r.pass}'\n" +
                "misc:\n  log-level: warn\n"
        )
        TProxyService.TProxyStartService(cfg.absolutePath, pfd.fd)
        r.tunStarted = true
        // The local proxy credentials must not stay on disk once the tunnel has read them
        val wipe = Thread({
            try { Thread.sleep(5000) } catch (e: InterruptedException) {}
            cfg.delete()
        }, "cfg-wipe")
        wipe.isDaemon = true
        wipe.start()
    }

    private fun startMonitor(r: Run, p: Profile) {
        val t = Thread({
            var lastRx = 0L
            var lastTx = 0L
            var lastT = SystemClock.elapsedRealtime()
            var n = 0
            while (!r.cancelled) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                if (r.cancelled) break
                val s = r.ssh
                if (s != null && !s.isConnected) { onLost(r, "SSH session closed"); break }
                val x = r.xray
                if (x != null && !x.alive()) { onLost(r, "V2Ray core stopped"); break }
                val uid = android.os.Process.myUid()
                val rx = (TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L) - baseRx).coerceAtLeast(0L)
                val tx = (TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L) - baseTx).coerceAtLeast(0L)
                val now = SystemClock.elapsedRealtime()
                val dt = (now - lastT).coerceAtLeast(1L)
                VpnState.rxSpeed = ((rx - lastRx) * 1000L / dt).coerceAtLeast(0L)
                VpnState.txSpeed = ((tx - lastTx) * 1000L / dt).coerceAtLeast(0L)
                VpnState.rx = rx
                VpnState.tx = tx
                lastRx = rx; lastTx = tx; lastT = now
                if (++n % 2 == 0) updateNotification()
                if (n % 30 == 0 && Expiry.isExpired(p.expiry)) {
                    AppLog.w("This configuration has expired, disconnecting")
                    fullStop()
                    break
                }
            }
        }, "vpn-monitor")
        t.isDaemon = true
        t.start()
    }

    private fun onLost(r: Run, reason: String) {
        if (r.cancelled || stopping) return
        AppLog.w("Tunnel lost: $reason")
        if (Store.autoReconnect) {
            reconnect("tunnel lost")
        } else {
            fullStop()
        }
    }

    private fun reconnect(why: String) {
        val p = profile ?: return
        synchronized(lock) {
            if (reconnecting || stopping) return
            reconnecting = true
        }
        val t = Thread({
            try {
                for (attempt in 1..4) {
                    if (stopping) return@Thread
                    AppLog.i("Reconnecting ($why), attempt $attempt...")
                    run?.close()
                    VpnState.set(St.CONNECTING)
                    val r = Run()
                    run = r
                    try {
                        connectBlocking(r, p)
                        return@Thread
                    } catch (e: CancellationException) {
                        r.close()
                        return@Thread
                    } catch (e: Throwable) {
                        r.close()
                        if (stopping) return@Thread
                        AppLog.w("Reconnect failed: ${friendly(e)}")
                        try { Thread.sleep(attempt * 3000L) } catch (x: InterruptedException) { return@Thread }
                    }
                }
                AppLog.e("Could not reconnect, disconnecting")
                fullStop()
            } finally {
                reconnecting = false
            }
        }, "vpn-reconnect")
        t.isDaemon = true
        t.start()
    }

    private fun registerNetCallback() {
        if (netCb != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val last = lastNet
                lastNet = network
                if (last != null && last != network && Store.autoReconnect &&
                    VpnState.state == St.CONNECTED && !stopping
                ) {
                    AppLog.i("Network changed")
                    reconnect("network changed")
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) {
            AppLog.w("Auto-reconnect watcher unavailable: ${e.message}")
        }
    }

    private fun unregisterNetCallback() {
        val cb = netCb ?: return
        try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (e: Exception) {}
        netCb = null
        lastNet = null
    }

    private fun fullStop() {
        val wasActive = VpnState.state != St.DISCONNECTED
        stopping = true
        run?.close()
        run = null
        unregisterNetCallback()
        releaseWake()
        VpnState.set(St.DISCONNECTED)
        if (wasActive) AppLog.i("Disconnected")
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
        stopSelf()
    }

    override fun onRevoke() {
        AppLog.w("VPN was taken over by another app or revoked by Android")
        fullStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopping = true
        run?.close()
        run = null
        unregisterNetCallback()
        releaseWake()
        if (VpnState.state != St.DISCONNECTED) VpnState.set(St.DISCONNECTED)
        super.onDestroy()
    }

    // ---- wake lock / notification ----

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amexnet:vpn").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) {}
        wake = null
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MAIN, "VPN status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MIN, "VPN status (minimal)", NotificationManager.IMPORTANCE_MIN))
    }

    private fun buildNotification(text: String): Notification {
        ensureChannels()
        val minimal = !Store.notif
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disc = PendingIntent.getService(
            this, 1, Intent(this, AmexVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, if (minimal) CH_MIN else CH_MAIN)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("AMEX NET VPN")
            .setContentText(if (minimal) "VPN is active" else text)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(if (minimal) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
        if (!minimal) b.addAction(0, "Disconnect", disc)
        return b.build()
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun updateNotification() {
        val text = when (VpnState.state) {
            St.CONNECTED -> "${VpnState.name}  \u2193 ${fmtSpeed(VpnState.rxSpeed)}  \u2191 ${fmtSpeed(VpnState.txSpeed)}"
            St.CONNECTING -> "Connecting to ${VpnState.name}..."
            St.DISCONNECTED -> return
        }
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
        }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("HostKey has been changed", true) || m.contains("reject HostKey", true) ->
                "Server identity changed (host key mismatch). This could be an attack. If you reinstalled the server, use Forget on the profile to pin the new identity"
            m.contains("Auth fail", true) || m.contains("Auth cancel", true) ->
                "SSH authentication failed (check username and password)"
            m.contains("timed out", true) || m.contains("timeout", true) -> "Connection timed out ($m)"
            else -> m
        }
    }
}
