package ru.aireview.github

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import ru.aireview.config.GitHubConfig
import java.util.Base64

class GitHubClient(
    private val config: GitHubConfig,
    private val http: HttpClient,
    private val auth: GitHubTokenProvider,
) {
    suspend fun pullRequestFiles(repository: String, number: Int, installationId: Long?): List<PullRequestFile> =
        paginate("/repos/$repository/pulls/$number/files", installationId)

    suspend fun documentation(repository: String, headSha: String, installationId: Long?): Pair<String, List<DocumentationFile>> {
        val tree = get<GitTree>("/repos/$repository/git/trees/$headSha?recursive=1", installationId)
        check(!tree.truncated) { "GitHub returned a truncated tree for $repository@$headSha" }
        val entries = tree.tree.filter { it.type == "blob" && it.path.startsWith("docs/") && it.path.endsWith(".md", true) }
        val docs = entries.map { entry ->
            val blob = get<GitBlob>("/repos/$repository/git/blobs/${entry.sha}", installationId)
            check(blob.encoding == "base64") { "Unsupported blob encoding ${blob.encoding}" }
            DocumentationFile(entry.path, entry.sha, String(Base64.getMimeDecoder().decode(blob.content), Charsets.UTF_8))
        }
        val revision = entries.sortedBy { it.path }.joinToString("|") { "${it.path}:${it.sha}" }
        return revision to docs
    }

    suspend fun createReview(repository: String, number: Int, request: CreateReviewRequest, installationId: Long?) {
        post("/repos/$repository/pulls/$number/reviews", request, installationId)
    }

    suspend fun createIssueComment(repository: String, number: Int, body: String, installationId: Long?) {
        post("/repos/$repository/issues/$number/comments", IssueCommentRequest(body), installationId)
    }

    private suspend inline fun <reified T> paginate(path: String, installationId: Long?): List<T> {
        val all = mutableListOf<T>()
        var page = 1
        do {
            val items = get<List<T>>("$path?per_page=100&page=$page", installationId)
            all += items
            page++
        } while (items.size == 100)
        return all
    }

    private suspend inline fun <reified T> get(path: String, installationId: Long?): T =
        http.get(config.apiUrl + path) { authorized(installationId) }.body()

    private suspend fun post(path: String, payload: Any, installationId: Long?) {
        val response = http.post(config.apiUrl + path) {
            authorized(installationId)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        check(response.status.isSuccess()) { "GitHub ${response.status}: ${response.bodyAsText()}" }
    }

    private suspend fun HttpRequestBuilder.authorized(installationId: Long?) {
        githubHeaders()
        bearerAuth(auth.token(installationId))
    }
}
