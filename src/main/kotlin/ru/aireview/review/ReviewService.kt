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
        val allFiles = github.pullRequestFiles(repository, number, installationId)
        val files = allFiles.filter { it.patch != null && it.status != "removed" }
        val skippedFiles = allFiles.filter { it.patch == null && it.status != "removed" }.map { it.filename }
        val (revision, docs) = github.documentation(repository, headSha, installationId)
        val index = rag.prepare(repository, revision, docs)

        var rejectedComments = 0
        val comments = files.flatMap { file ->
            val parsed = DiffParser.parse(requireNotNull(file.patch))
            val query = buildString {
                append(file.filename.replace('/', ' '))
                append('\n')
                append(parsed.addedTextLines.joinToString("\n").take(4_000))
            }
            val context = rag.search(index, query)
            log.info(
                "RAG context repository={} pull={} file={} chunks={}",
                repository,
                number,
                file.filename,
                context.joinToString("; ") {
                    "${it.chunk.path}#${it.chunk.heading} score=${"%.3f".format(it.score)} " +
                        "vector=${"%.3f".format(it.vectorScore)} lexical=${"%.3f".format(it.lexicalScore)} pinned=${it.pinned}"
                },
            )
            val result = deepSeek.review(file, parsed, context)
            rejectedComments += result.rejectedCount
            log.info(
                "Model result repository={} pull={} file={} proposed={} accepted={} rejected={} rejectedLines={}",
                repository, number, file.filename, result.proposedCount, result.comments.size,
                result.rejectedCount, result.rejectedLines,
            )
            result.comments.map {
                ReviewCommentRequest(path = file.filename, line = it.line, body = it.body)
            }
        }.take(50)

        if (comments.isEmpty()) {
            val incompleteReasons = buildList {
                if (docs.isEmpty()) add("no `docs/**/*.md` documentation was found")
                if (files.isEmpty()) add("no changed text files with a reviewable patch were found")
                if (skippedFiles.isNotEmpty()) add("files without a textual patch were skipped: ${skippedFiles.joinToString()}")
                if (rejectedComments > 0) add("$rejectedComments model comment(s) failed line validation")
            }
            val body = if (incompleteReasons.isEmpty()) {
                "Everything OK"
            } else {
                "AI review incomplete: no findings were published, but a conclusive result is unavailable.\n\n" +
                    incompleteReasons.joinToString("\n") { "- $it" }
            }
            github.createIssueComment(repository, number, body, installationId)
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
        log.info(
            "Finished review repository={} pull={} comments={} rejected={} skippedFiles={} docs={}",
            repository, number, comments.size, rejectedComments, skippedFiles.size, docs.size,
        )
    }
}
