package com.amexnet.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Encrypts saved profiles with a key that lives in the Android Keystore and never leaves it. */
object Vault {
    private const val ALIAS = "amexnet_profiles_v1"
    private const val PREFIX = "v1:"
    @Volatile private var degraded = false

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun encrypt(plain: String): String = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        // Some devices have a broken keystore. Keep the app usable, but say so in Settings.
        degraded = true
        plain
    }

    fun decrypt(s: String): String? {
        if (!s.startsWith(PREFIX)) return null
        return try {
            val all = Base64.decode(s.substring(PREFIX.length), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.w("Saved profiles could not be decrypted (the keystore key is gone)")
            null
        }
    }

    fun describe(): String {
        if (degraded) return "Unavailable (profiles stored unencrypted)"
        return try {
            val k = key()
            val info = SecretKeyFactory.getInstance(k.algorithm, "AndroidKeyStore").getKeySpec(k, KeyInfo::class.java) as KeyInfo
            if (info.isInsideSecureHardware) "Encrypted, hardware-backed" else "Encrypted, software keystore"
        } catch (e: Exception) {
            "Encrypted"
        }
    }
}

/** Clipboard copy that marks content sensitive (Android 13+) and wipes it again after a minute. */
object Clip {
    private val handler = Handler(Looper.getMainLooper())

    fun copy(c: Context, label: String, text: String, sensitive: Boolean = true) {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            val b = PersistableBundle()
            b.putBoolean("android.content.extra.IS_SENSITIVE", true)
            clip.description.extras = b
        }
        cm.setPrimaryClip(clip)
        if (sensitive) {
            handler.postDelayed({
                try {
                    val now = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (now == text) {
                        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                        else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                } catch (e: Exception) {
                }
            }, 60_000)
        }
    }
}
