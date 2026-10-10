package com.amexnet.vpn

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * .amex files and emoji strings.
 * JSON -> deflate -> AES-256-GCM, key from the sender's password via PBKDF2-HMAC-SHA256.
 * Layout: salt (16) | iv (12) | ciphertext + tag. There is no key inside the app, so decompiling it reveals nothing.
 */
object AmexCodec {
    class BadPassword : Exception("Wrong password or damaged file")

    const val MIN_PASSWORD = 8
    const val MIN_SIZE = 16 + 12 + 16 + 1
    private const val ITERATIONS = 150_000
    private const val EMOJI_BASE = 0x1F400
    private val rng = SecureRandom()

    fun encode(p: Profile, password: CharArray): ByteArray {
        require(password.size >= MIN_PASSWORD) { "Password too short" }
        val j = p.toJson()
        j.remove("id")
        j.remove("imported")
        val raw = deflate(j.toString().toByteArray(Charsets.UTF_8))
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val key = pbkdf2(password, salt, ITERATIONS, 32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(salt)
        val ct = c.doFinal(raw)
        key.fill(0)
        return salt + iv + ct
    }

    fun decode(data: ByteArray, password: CharArray): Profile {
        require(data.size >= MIN_SIZE) { "Not an AMEX NET VPN config" }
        val salt = data.copyOfRange(0, 16)
        val iv = data.copyOfRange(16, 28)
        val key = pbkdf2(password, salt, ITERATIONS, 32)
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(salt)
            c.doFinal(data, 28, data.size - 28)
        } catch (e: GeneralSecurityException) {
            throw BadPassword()
        } finally {
            key.fill(0)
        }
        val raw = inflate(plain)
        val p = Profile.fromJson(JSONObject(String(raw, Charsets.UTF_8)), keepId = false)
        p.imported = true
        return p.sanitize()
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, outLen: Int): ByteArray {
        val pw = String(password).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pw, "HmacSHA256"))
        pw.fill(0)
        val out = ByteArray(outLen)
        var block = 1
        var pos = 0
        while (pos < outLen) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(t.size, outLen - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            block++
        }
        return out
    }

    fun toEmoji(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.appendCodePoint(EMOJI_BASE + (x.toInt() and 0xFF))
        return sb.toString()
    }

    fun fromEmoji(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in EMOJI_BASE..(EMOJI_BASE + 255)) out.write(cp - EMOJI_BASE)
        }
        require(out.size() > 0) { "No config found in that text" }
        return out.toByteArray()
    }

    fun looksLikeEmoji(s: String): Boolean {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in EMOJI_BASE..(EMOJI_BASE + 255)) n++
        }
        return n >= MIN_SIZE
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(b)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(b: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(b)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            require(out.size() < 1_000_000) { "File too large" }
        }
        inf.end()
        return out.toByteArray()
    }
}
