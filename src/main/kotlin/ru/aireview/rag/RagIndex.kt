package ru.aireview.rag

import com.fasterxml.jackson.databind.ObjectMapper
import ru.aireview.config.EmbeddingConfig
import ru.aireview.config.RagConfig
import ru.aireview.github.DocumentationFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class RagIndex(
    private val config: RagConfig,
    private val embeddingConfig: EmbeddingConfig,
    private val embeddings: OllamaEmbeddingClient,
    private val mapper: ObjectMapper,
) {
    private val chunker = MarkdownChunker(config.chunkSize, config.overlap)
    private val locks = ConcurrentHashMap<String, Any>()

    suspend fun prepare(repository: String, revision: String, docs: List<DocumentationFile>): StoredIndex {
        val file = indexFile(repository)
        synchronized(locks.computeIfAbsent(repository) { Any() }) {
            load(file)?.takeIf { it.revision == revision && it.model == embeddingConfig.model }?.let { return it }
        }

        val chunks = chunker.chunk(docs)
        val vectors = embeddings.embed(chunks.map { "${it.path}\n${it.text}" })
        val index = StoredIndex(revision, embeddingConfig.model, chunks.zip(vectors) { chunk, vector -> IndexedChunk(chunk, vector) })
        synchronized(locks.computeIfAbsent(repository) { Any() }) {
            load(file)?.takeIf { it.revision == revision && it.model == embeddingConfig.model }?.let { return it }
            save(file, index)
        }
        return index
    }

    suspend fun search(index: StoredIndex, query: String): List<RetrievedChunk> {
        if (index.chunks.isEmpty()) return emptyList()
        val queryVector = embeddings.embed(listOf(query)).single()
        return index.chunks.asSequence()
            .map { RetrievedChunk(it.chunk, cosine(queryVector, it.embedding)) }
            .sortedByDescending { it.score }
            .take(config.topK)
            .toList()
    }

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        if (a.size != b.size || a.isEmpty()) return -1.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else dot / (sqrt(aa) * sqrt(bb))
    }

    private fun indexFile(repository: String) = config.indexDirectory.resolve(sha256(repository) + ".json")

    private fun load(file: java.nio.file.Path): StoredIndex? = runCatching {
        if (Files.exists(file)) mapper.readValue(file.toFile(), StoredIndex::class.java) else null
    }.getOrNull()

    private fun save(file: java.nio.file.Path, index: StoredIndex) {
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        mapper.writeValue(temp.toFile(), index)
        runCatching { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
