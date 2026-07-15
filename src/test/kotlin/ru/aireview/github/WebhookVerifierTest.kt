package ru.aireview.github

import ru.aireview.web.WebhookVerifier
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookVerifierTest {
    @Test
    fun `accepts valid signature and rejects modified body`() {
        val secret = "test-secret"
        val body = "{\"action\":\"opened\"}".toByteArray()
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            doFinal(body)
        }
        val signature = "sha256=" + HexFormat.of().formatHex(mac)

        assertTrue(WebhookVerifier(secret).isValid(body, signature))
        assertFalse(WebhookVerifier(secret).isValid("changed".toByteArray(), signature))
        assertFalse(WebhookVerifier(secret).isValid(body, "bad"))
    }
}

