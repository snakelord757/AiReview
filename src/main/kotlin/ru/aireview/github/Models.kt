package ru.aireview.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class PullRequestWebhook(
    val action: String = "",
    val number: Int = 0,
    val installation: Installation? = null,
    val repository: Repository = Repository(),
    @JsonProperty("pull_request") val pullRequest: PullRequest = PullRequest(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Installation(val id: Long = 0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Repository(@JsonProperty("full_name") val fullName: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class PullRequest(val head: GitRef = GitRef(), val draft: Boolean = false)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitRef(val sha: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class PullRequestFile(
    val filename: String = "",
    val status: String = "",
    val patch: String? = null,
    @JsonProperty("previous_filename") val previousFilename: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitTree(val sha: String = "", val truncated: Boolean = false, val tree: List<GitTreeEntry> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitTreeEntry(val path: String = "", val mode: String = "", val type: String = "", val sha: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitBlob(val content: String = "", val encoding: String = "base64")

data class DocumentationFile(val path: String, val sha: String, val content: String)

data class ReviewCommentRequest(val path: String, val line: Int, val side: String = "RIGHT", val body: String)
data class CreateReviewRequest(val body: String, val event: String = "COMMENT", val comments: List<ReviewCommentRequest>)
data class IssueCommentRequest(val body: String)

