package com.trackspeed.android.cloud.ideas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

internal enum class IdeaCategory(val wire: String) {
    ALL("all"), FEATURE("feature"), BUG("bug"), IMPROVEMENT("improvement");
    companion object { val submissionCategories = listOf(FEATURE, IMPROVEMENT) }
}
internal enum class IdeaSort { MOST_VOTED, NEWEST }
internal enum class IdeaStatus(val wire: String) {
    OPEN("open"), PLANNED("planned"), IN_PROGRESS("in_progress"), COMPLETED("completed"), CLOSED("declined")
}
internal data class IdeaPost(
    val id: String,
    val title: String,
    val details: String,
    val category: IdeaCategory,
    val status: IdeaStatus = IdeaStatus.OPEN,
    val publicName: String? = null,
    val votes: Int = 0,
    val createdAt: Instant? = null,
    val adminResponse: String? = null
)
internal data class IdeasSnapshot(val posts: List<IdeaPost>, val votedIds: Set<String>, val votesKnown: Boolean = true)
internal enum class IdeasError { SESSION_UNAVAILABLE, LOAD_FAILED, VOTES_UNAVAILABLE, VOTE_UNCONFIRMED, SUBMIT_UNCONFIRMED, INVALID_SUBMISSION }
internal class IdeasSessionUnavailable : IllegalStateException("Feedback session unavailable")

internal interface IdeasBoardApi {
    /** Existing named OR anonymous SDK session only. Never creates or refreshes a session. */
    fun currentOwner(): String?
    suspend fun load(owner: String): IdeasSnapshot
    suspend fun submit(owner: String, submission: IdeaSubmission)
    suspend fun vote(owner: String, postId: String)
}

internal data class IdeaDraft(
    val title: String = "",
    val details: String = "",
    val category: IdeaCategory = IdeaCategory.FEATURE,
    val publicName: String = ""
) {
    fun submission(): IdeaSubmission? {
        val cleanTitle = title.trim().takeCodePoints(MAX_TITLE)
        val cleanDetails = details.trim().takeCodePoints(MAX_DETAILS)
        if (cleanTitle.isEmpty() || cleanDetails.isEmpty() || category !in IdeaCategory.submissionCategories) return null
        return IdeaSubmission(cleanTitle, cleanDetails, category.wire,
            publicName.trim().takeCodePoints(MAX_NAME).ifEmpty { null })
    }
    companion object { const val MAX_TITLE = 100; const val MAX_DETAILS = 600; const val MAX_NAME = 60 }
}

/** Unicode-safe input cap. Does not split surrogate pairs or alter timing/session data. */
internal fun String.takeCodePoints(limit: Int): String =
    if (codePointCount(0, length) <= limit) this else substring(0, offsetByCodePoints(0, limit))
internal fun String.codePointLength(): Int = codePointCount(0, length)

@Serializable
internal data class IdeaSubmission(
    val title: String,
    val description: String,
    val category: String,
    @SerialName("author_name") val publicName: String?
)
@Serializable
internal data class IdeaVoteInsert(val post_id: String, val voter_identifier: String)
@Serializable
internal data class IdeaPostRow(
    val id: String, val title: String, val description: String? = null,
    val category: String, val status: String = "open",
    @SerialName("author_name") val publicName: String? = null,
    @SerialName("vote_count") val votes: Int = 0,
    @SerialName("created_at") val createdAt: String
)
@Serializable
internal data class IdeaAdminRow(
    @SerialName("post_id") val postId: String, val content: String,
    @SerialName("created_at") val createdAt: String
)
@Serializable
internal data class IdeaVoteRow(@SerialName("post_id") val postId: String)

internal fun mapIdeaPosts(rows: List<IdeaPostRow>, adminRows: List<IdeaAdminRow>): List<IdeaPost> {
    val responses = mutableMapOf<String, String>()
    // Server supplies chronological order. Ignore blank replies; retain latest meaningful reply.
    adminRows.forEach { row -> row.content.trim().takeIf(String::isNotEmpty)?.let { responses[row.postId] = it } }
    return rows.mapNotNull { row ->
        val category = IdeaCategory.entries.firstOrNull { it.wire == row.category && it != IdeaCategory.ALL }
            ?: return@mapNotNull null
        IdeaPost(row.id, row.title, row.description.orEmpty(), category,
            IdeaStatus.entries.firstOrNull { it.wire == row.status } ?: IdeaStatus.OPEN,
            row.publicName?.trim()?.takeIf(String::isNotEmpty), row.votes.coerceAtLeast(0),
            runCatching { Instant.parse(row.createdAt) }.getOrNull(), responses[row.id])
    }
}

internal fun visibleIdeas(posts: List<IdeaPost>, category: IdeaCategory, sort: IdeaSort): List<IdeaPost> {
    val filtered = posts.filter { category == IdeaCategory.ALL || it.category == category }
    val latest = compareByDescending<IdeaPost> { it.createdAt ?: Instant.MIN }.thenBy { it.id }
    return filtered.sortedWith(if (sort == IdeaSort.NEWEST) latest else compareByDescending<IdeaPost> { it.votes }.then(latest))
}
