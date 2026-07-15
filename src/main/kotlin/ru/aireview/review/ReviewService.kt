package ru.aireview.review

import org.slf4j.LoggerFactory
import ru.aireview.github.*
import ru.aireview.rag.RagIndex

class ReviewService(
    private val github: GitHubClient,
    private val rag: RagIndex,
    private val deepSeek: DeepSeekClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun review(event: PullRequestWebhook) {
        val repository = event.repository.fullName
        val number = event.number
        val headSha = event.pullRequest.head.sha
        val installationId = event.installation?.id
        require(repository.isNotBlank() && number > 0 && headSha.isNotBlank()) { "Incomplete pull_request webhook" }

        log.info("Starting review repository={} pull={} sha={}", repository, number, headSha)
        val files = github.pullRequestFiles(repository, number, installationId)
            .filter { it.patch != null && it.status != "removed" }
        val (revision, docs) = github.documentation(repository, headSha, installationId)
        val index = rag.prepare(repository, revision, docs)

        val comments = files.flatMap { file ->
            val parsed = DiffParser.parse(requireNotNull(file.patch))
            val query = buildString {
                append(file.filename.replace('/', ' '))
                append('\n')
                append(file.patch.take(4_000))
            }
            val context = rag.search(index, query)
            deepSeek.review(file, parsed, context).comments.map {
                ReviewCommentRequest(path = file.filename, line = it.line, body = it.body)
            }
        }.take(50)

        if (comments.isEmpty()) {
            github.createIssueComment(repository, number, "Everything OK", installationId)
        } else {
            github.createReview(
                repository,
                number,
                CreateReviewRequest(
                    body = "AI review based on `docs/**/*.md` (${comments.size} finding(s)).",
                    comments = comments,
                ),
                installationId,
            )
        }
        log.info("Finished review repository={} pull={} comments={}", repository, number, comments.size)
    }
}
