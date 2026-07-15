package ru.aireview.rag

data class DocumentChunk(val id: String, val path: String, val heading: String, val text: String)
data class IndexedChunk(val chunk: DocumentChunk, val embedding: List<Double>)
data class StoredIndex(
    val revision: String = "",
    val model: String = "",
    val formatVersion: Int = 0,
    val chunks: List<IndexedChunk> = emptyList(),
)
data class RetrievedChunk(
    val chunk: DocumentChunk,
    val score: Double,
    val vectorScore: Double,
    val lexicalScore: Double,
    val pinned: Boolean,
)
