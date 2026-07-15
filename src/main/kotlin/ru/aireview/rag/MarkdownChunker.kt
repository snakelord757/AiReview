package ru.aireview.rag

import ru.aireview.github.DocumentationFile

class MarkdownChunker(private val chunkSize: Int, private val overlap: Int) {
    fun chunk(files: List<DocumentationFile>): List<DocumentChunk> = files.flatMap { file -> chunk(file) }

    private fun chunk(file: DocumentationFile): List<DocumentChunk> {
        val sections = splitSections(file.content)
        return sections.flatMapIndexed { sectionIndex, section ->
            window(section.body).mapIndexed { windowIndex, text ->
                DocumentChunk(
                    id = "${file.sha}:$sectionIndex:$windowIndex",
                    path = file.path,
                    heading = section.heading,
                    text = buildString {
                        if (section.heading.isNotBlank()) appendLine(section.heading)
                        append(text.trim())
                    },
                )
            }
        }.filter { it.text.isNotBlank() }
    }

    private data class Section(val heading: String, val body: String)

    private fun splitSections(markdown: String): List<Section> {
        val result = mutableListOf<Section>()
        var heading = ""
        val body = StringBuilder()
        fun flush() {
            if (body.isNotBlank()) result += Section(heading, body.toString())
            body.clear()
        }
        markdown.lineSequence().forEach { line ->
            if (line.matches(Regex("^#{1,6}\\s+.+"))) {
                flush()
                heading = line.trim()
            } else {
                body.appendLine(line)
            }
        }
        flush()
        return result.ifEmpty { listOf(Section("", markdown)) }
    }

    private fun window(text: String): List<String> {
        if (text.length <= chunkSize) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + chunkSize, text.length)
            if (end < text.length) {
                val paragraph = text.lastIndexOf("\n\n", end).takeIf { it > start + chunkSize / 2 }
                val newline = text.lastIndexOf('\n', end).takeIf { it > start + chunkSize / 2 }
                end = paragraph ?: newline ?: end
            }
            chunks += text.substring(start, end)
            if (end == text.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return chunks
    }
}

