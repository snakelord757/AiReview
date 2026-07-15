package ru.aireview.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridRetrievalTest {
    @Test
    fun `exact operators strongly affect lexical score`() {
        val query = "Fragment viewBinding!!"

        val matching = lexicalScore(query, "Avoid !! and model nullability explicitly")
        val unrelated = lexicalScore(query, "Use descriptive class names")

        assertTrue(matching > unrelated)
        assertTrue(matching >= 1.5)
    }

    @Test
    fun `recognizes mandatory policy document names`() {
        assertTrue(isPolicyDocument("docs/code-style.md"))
        assertTrue(isPolicyDocument("docs/security-guidelines.md"))
        assertEquals(false, isPolicyDocument("docs/architecture.md"))
    }

    @Test
    fun `uses embeddinggemma retrieval input format`() {
        val chunk = DocumentChunk("1", "docs/code-style.md", "Null safety", "Avoid !!")
        assertEquals(
            "title: docs/code-style.md Null safety | text: Avoid !!",
            documentEmbeddingInput(chunk),
        )
    }
}
