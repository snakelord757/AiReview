package ru.aireview.rag

import ru.aireview.github.DocumentationFile
import kotlin.test.Test
import kotlin.test.assertTrue

class MarkdownChunkerTest {
    @Test
    fun `preserves path and heading while respecting approximate chunk size`() {
        val text = "# Rules\n\n" + "Use immutable values. ".repeat(20)
        val chunks = MarkdownChunker(chunkSize = 100, overlap = 15)
            .chunk(listOf(DocumentationFile("docs/rules.md", "sha", text)))

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.path == "docs/rules.md" })
        assertTrue(chunks.all { it.heading == "# Rules" })
        assertTrue(chunks.all { it.text.length <= 110 })
    }
}

