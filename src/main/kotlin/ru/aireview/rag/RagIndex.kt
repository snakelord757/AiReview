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
            load(file)?.takeIf { it.isCurrent(revision) }?.let { return it }
        }

        val chunks = chunker.chunk(docs)
        val vectors = embeddings.embed(chunks.map(::documentEmbeddingInput))
        val index = StoredIndex(
            revision = revision,
            model = embeddingConfig.model,
            formatVersion = INDEX_FORMAT_VERSION,
            chunks = chunks.zip(vectors) { chunk, vector -> IndexedChunk(chunk, vector) },
        )
        synchronized(locks.computeIfAbsent(repository) { Any() }) {
            load(file)?.takeIf { it.isCurrent(revision) }?.let { return it }
            save(file, index)
        }
        return index
    }

    suspend fun search(index: StoredIndex, query: String): List<RetrievedChunk> {
        if (index.chunks.isEmpty()) return emptyList()
        val queryVector = embeddings.embed(listOf("task: code review policy retrieval | query: $query")).single()
        val ranked = index.chunks.asSequence()
            .map {
                val vectorScore = cosine(queryVector, it.embedding)
                val lexicalScore = lexicalScore(query, "${it.chunk.path} ${it.chunk.heading} ${it.chunk.text}")
                RetrievedChunk(
                    chunk = it.chunk,
                    score = vectorScore + LEXICAL_WEIGHT * lexicalScore,
                    vectorScore = vectorScore,
                    lexicalScore = lexicalScore,
                    pinned = isPolicyDocument(it.chunk.path),
                )
            }
            .sortedByDescending { it.score }
            .toList()
        val pinned = ranked.filter { it.pinned }.take(MAX_PINNED_CHUNKS)
        val pinnedIds = pinned.mapTo(hashSetOf()) { it.chunk.id }
        return pinned + ranked.asSequence().filterNot { it.chunk.id in pinnedIds }.take(config.topK)
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

    private fun StoredIndex.isCurrent(revision: String): Boolean =
        this.revision == revision && model == embeddingConfig.model && formatVersion == INDEX_FORMAT_VERSION

    companion object {
        private const val INDEX_FORMAT_VERSION = 2
        private const val LEXICAL_WEIGHT = 0.9
        private const val MAX_PINNED_CHUNKS = 8
    }
}

internal fun documentEmbeddingInput(chunk: DocumentChunk): String =
    "title: ${chunk.path} ${chunk.heading} | text: ${chunk.text}"

internal fun isPolicyDocument(path: String): Boolean {
    val name = path.substringAfterLast('/').lowercase()
    return POLICY_MARKERS.any(name::contains)
}

internal fun lexicalScore(query: String, document: String): Double {
    val queryTerms = lexicalTerms(query)
    if (queryTerms.isEmpty()) return 0.0
    val documentTerms = lexicalTerms(document)
    val overlap = queryTerms.count(documentTerms::contains).toDouble() / queryTerms.size
    val operatorMatches = IMPORTANT_OPERATORS.count { it in query && it in document }
    return overlap + operatorMatches * 1.5
}

private fun lexicalTerms(text: String): Set<String> =
    TERM.findAll(text.lowercase()).map { it.value }.filter { it.length > 1 }.toSet()

private val TERM = Regex("[\\p{L}\\p{N}_]+")
private val IMPORTANT_OPERATORS = setOf("!!", "?:", "lateinit")
private val POLICY_MARKERS = setOf("code-style", "codestyle", "style", "guideline", "standard", "rule", "security")
