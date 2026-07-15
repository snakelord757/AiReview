package ru.aireview.config

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonConfigurationTest {
    private data class ExternalResponse(val embeddings: List<List<Double>> = emptyList())

    @Test
    fun `ignores metadata added by external APIs`() {
        val json = """{"embeddings":[[0.1,0.2]],"model":"embeddinggemma:300m","total_duration":42}"""

        val response = ObjectMapper().configureAiReviewJson().readValue(json, ExternalResponse::class.java)

        assertEquals(listOf(listOf(0.1, 0.2)), response.embeddings)
    }
}
