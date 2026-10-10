package com.amexnet.vpn

import android.app.Application
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Store.trustedNow()
        if (processName() != packageName) return
        AppLog.i("AMEX NET VPN ${BuildConfig.VERSION_NAME} ready (${arch()})")
        if (Store.autoPing) AutoPinger.apply(true)
        installCrashHandler()
    }

    private fun processName(): String = try {
        File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
    } catch (e: Exception) {
        packageName
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (!Store.crashReports) {
                previous?.uncaughtException(t, e)
                return@setDefaultUncaughtExceptionHandler
            }
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val report = "AMEX NET VPN ${BuildConfig.VERSION_NAME}\n" +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
                    "Thread: ${t.name}\n\n$sw"
                File(filesDir, "crash.txt").writeText(Redact.apply(report).take(60000))
                val i = Intent(this, CrashActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i)
            } catch (x: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }
}

fun arch(): String = if (android.os.Process.is64Bit()) "64-bit (arm64-v8a)" else "32-bit (armeabi-v7a)"
