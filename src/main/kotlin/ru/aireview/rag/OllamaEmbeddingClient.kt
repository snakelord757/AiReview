package ru.aireview.rag

import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import ru.aireview.config.EmbeddingConfig

class OllamaEmbeddingClient(private val config: EmbeddingConfig, private val http: HttpClient) {
    private data class EmbedRequest(val model: String, val input: List<String>)
    private data class EmbedResponse(@JsonProperty("embeddings") val embeddings: List<List<Double>> = emptyList())

    suspend fun embed(texts: List<String>): List<List<Double>> {
        if (texts.isEmpty()) return emptyList()
        val result = mutableListOf<List<Double>>()
        texts.chunked(32).forEach { batch ->
            val response = http.post("${config.baseUrl}/api/embed") {
                contentType(ContentType.Application.Json)
                setBody(EmbedRequest(config.model, batch))
            }.body<EmbedResponse>()
            check(response.embeddings.size == batch.size) { "Ollama returned an unexpected embedding count" }
            result += response.embeddings
        }
        return result
    }
}

