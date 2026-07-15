package ru.aireview.github

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import ru.aireview.config.GitHubConfig
import java.io.StringReader
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface GitHubTokenProvider { suspend fun token(installationId: Long?): String }

class GitHubAuth(private val config: GitHubConfig, private val http: HttpClient) : GitHubTokenProvider {
    private data class CachedToken(val value: String, val expiresAt: Instant)
    private data class InstallationToken(@JsonProperty("token") val token: String, @JsonProperty("expires_at") val expiresAt: Instant)
    private val cache = ConcurrentHashMap<Long, CachedToken>()

    override suspend fun token(installationId: Long?): String {
        config.token?.let { return it }
        requireNotNull(installationId) { "GitHub App webhook has no installation id" }
        cache[installationId]?.takeIf { it.expiresAt.isAfter(Instant.now().plusSeconds(60)) }?.let { return it.value }

        val response = http.post("${config.apiUrl}/app/installations/$installationId/access_tokens") {
            githubHeaders()
            bearerAuth(createAppJwt())
        }.body<InstallationToken>()
        cache[installationId] = CachedToken(response.token, response.expiresAt)
        return response.token
    }

    private fun createAppJwt(): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(requireNotNull(config.appId))
            .withIssuedAt(now.minusSeconds(30))
            .withExpiresAt(now.plusSeconds(8 * 60))
            .sign(Algorithm.RSA256(null, parsePrivateKey(requireNotNull(config.privateKey))))
    }

    private fun parsePrivateKey(pem: String): RSAPrivateKey {
        val parsed = PEMParser(StringReader(pem)).use { it.readObject() }
        val converter = JcaPEMKeyConverter()
        val key: PrivateKey = when (parsed) {
            is PEMKeyPair -> converter.getKeyPair(parsed).private
            is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> converter.getPrivateKey(parsed)
            else -> error("Unsupported GitHub App private key format")
        }
        return key as? RSAPrivateKey ?: error("GitHub App private key is not RSA")
    }
}

fun HttpRequestBuilder.githubHeaders() {
    accept(ContentType.parse("application/vnd.github+json"))
    header("X-GitHub-Api-Version", "2026-03-10")
    header(HttpHeaders.UserAgent, "ai-review/1.0")
}
