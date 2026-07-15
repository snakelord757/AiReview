package ru.aireview

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import ru.aireview.config.AppConfig
import ru.aireview.github.*
import ru.aireview.rag.*
import ru.aireview.review.*
import ru.aireview.web.WebhookVerifier
import ru.aireview.web.ReviewLedger
import java.util.concurrent.ConcurrentHashMap

fun main() {
    val config = AppConfig.fromEnvironment()
    embeddedServer(Netty, host = "0.0.0.0", port = config.port) { module(config) }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig.fromEnvironment()) {
    val log = LoggerFactory.getLogger("AiReview")
    val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    install(ContentNegotiation) {
        jackson { registerModule(KotlinModule.Builder().build()); registerModule(JavaTimeModule()) }
    }
    val http = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            jackson { registerModule(KotlinModule.Builder().build()); registerModule(JavaTimeModule()) }
        }
        expectSuccess = true
        engine { requestTimeout = 120_000 }
    }
    val auth = GitHubAuth(config.github, http)
    val github = GitHubClient(config.github, http, auth)
    val embeddingClient = OllamaEmbeddingClient(config.embeddings, http)
    val rag = RagIndex(config.rag, config.embeddings, embeddingClient, mapper)
    val reviewer = ReviewService(github, rag, DeepSeekClient(config.deepSeek, http, mapper))
    val verifier = WebhookVerifier(config.github.webhookSecret)
    val ledger = ReviewLedger(config.rag.indexDirectory.resolve("completed-reviews"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val inProgress = ConcurrentHashMap.newKeySet<String>()

    monitor.subscribe(ApplicationStopped) {
        scope.cancel()
        http.close()
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        post("/webhooks/github") {
            val bodyText = call.receiveText()
            val body = bodyText.toByteArray(Charsets.UTF_8)
            if (!verifier.isValid(body, call.request.headers["X-Hub-Signature-256"])) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid signature"))
                return@post
            }
            if (call.request.headers["X-GitHub-Event"] != "pull_request") {
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "ignored"))
                return@post
            }

            val event = runCatching { mapper.readValue(body, PullRequestWebhook::class.java) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid payload"))
                return@post
            }
            val supported = event.action in setOf("opened", "reopened", "synchronize", "ready_for_review")
            if (!supported || event.pullRequest.draft) {
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "ignored"))
                return@post
            }

            val key = "${event.repository.fullName}#${event.number}@${event.pullRequest.head.sha}"
            if (ledger.isCompleted(key)) {
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "already_completed"))
                return@post
            }
            if (!inProgress.add(key)) {
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "already_processing"))
                return@post
            }
            scope.launch {
                try {
                    reviewer.review(event)
                    ledger.complete(key)
                } catch (error: Throwable) {
                    log.error("Review failed for {}", key, error)
                } finally {
                    inProgress.remove(key)
                }
            }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "processing"))
        }
    }
}
