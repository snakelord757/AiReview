package ru.aireview.config

import java.nio.file.Path

data class AppConfig(
    val port: Int,
    val github: GitHubConfig,
    val deepSeek: DeepSeekConfig,
    val embeddings: EmbeddingConfig,
    val rag: RagConfig,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig = AppConfig(
            port = env.int("PORT", 8080),
            github = GitHubConfig(
                apiUrl = env["GITHUB_API_URL"] ?: "https://api.github.com",
                webhookSecret = env.required("GITHUB_WEBHOOK_SECRET"),
                token = env["GITHUB_TOKEN"]?.takeIf { it.isNotBlank() },
                appId = env["GITHUB_APP_ID"]?.takeIf { it.isNotBlank() },
                privateKey = env["GITHUB_APP_PRIVATE_KEY"]?.replace("\\n", "\n")?.takeIf { it.isNotBlank() },
            ),
            deepSeek = DeepSeekConfig(
                baseUrl = env["DEEPSEEK_BASE_URL"] ?: "https://api.deepseek.com",
                apiKey = env.required("DEEPSEEK_API_KEY"),
                model = env["DEEPSEEK_MODEL"] ?: "deepseek-v4-flash",
                maxPatchChars = env.int("MAX_PATCH_CHARS", 60_000),
            ),
            embeddings = EmbeddingConfig(
                baseUrl = env["OLLAMA_BASE_URL"] ?: "http://ollama:11434",
                model = env["OLLAMA_EMBED_MODEL"] ?: "embeddinggemma:300m",
            ),
            rag = RagConfig(
                indexDirectory = Path.of(env["RAG_INDEX_DIR"] ?: "data/indexes"),
                chunkSize = env.int("RAG_CHUNK_SIZE", 1800),
                overlap = env.int("RAG_CHUNK_OVERLAP", 200),
                topK = env.int("RAG_TOP_K", 5),
            ),
        ).also { it.validate() }

        private fun Map<String, String>.required(name: String): String =
            get(name)?.takeIf { it.isNotBlank() } ?: error("Required environment variable $name is not set")

        private fun Map<String, String>.int(name: String, default: Int): Int =
            get(name)?.toIntOrNull() ?: default
    }

    private fun validate() {
        require(github.token != null || (github.appId != null && github.privateKey != null)) {
            "Set GITHUB_TOKEN or both GITHUB_APP_ID and GITHUB_APP_PRIVATE_KEY"
        }
        require(rag.chunkSize > rag.overlap && rag.overlap >= 0)
        require(rag.topK > 0)
    }
}

data class GitHubConfig(
    val apiUrl: String,
    val webhookSecret: String,
    val token: String?,
    val appId: String?,
    val privateKey: String?,
)

data class DeepSeekConfig(val baseUrl: String, val apiKey: String, val model: String, val maxPatchChars: Int)
data class EmbeddingConfig(val baseUrl: String, val model: String)
data class RagConfig(val indexDirectory: Path, val chunkSize: Int, val overlap: Int, val topK: Int)
