package ru.aireview.web

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookVerifier(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    fun isValid(body: ByteArray, signature: String?): Boolean {
        if (signature == null || !signature.startsWith("sha256=")) return false
        val expected = Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(body)
        }
        val actual = signature.removePrefix("sha256=").hexToBytesOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching { ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}

