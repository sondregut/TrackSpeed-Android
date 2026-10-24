package com.trackspeed.android.cloud.ideas

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** Released iOS 134 feedback contract; uses the app's existing client/session and RLS. */
@Singleton
class SupabaseIdeasBoardService @Inject constructor(private val supabase: SupabaseClient) {
    internal val api: IdeasBoardApi = object : IdeasBoardApi {
        override fun currentOwner(): String? = supabase.auth.currentSessionOrNull()?.user?.id

        private fun requireOwner(owner: String) {
            if (owner.isBlank() || currentOwner() != owner) throw IdeasSessionUnavailable()
        }

        override suspend fun load(owner: String): IdeasSnapshot = withTimeout(30_000) {
            requireOwner(owner)
            val posts = supabase.postgrest["feedback_posts"].select(Columns.list(
                "id", "title", "description", "category", "status", "author_name", "vote_count", "created_at"
            )) { order("created_at", Order.DESCENDING) }.decodeList<IdeaPostRow>()
            requireOwner(owner)
            val comments = supabase.postgrest["feedback_comments"].select(Columns.list("post_id", "content", "created_at")) {
                filter { eq("is_admin", true) }
                order("created_at", Order.ASCENDING)
            }.decodeList<IdeaAdminRow>()
            requireOwner(owner)
            var votesKnown = true
            val votes = try {
                supabase.postgrest["feedback_votes"].select(Columns.list("post_id")) {
                    filter { eq("voter_identifier", owner.lowercase()) }
                }.decodeList<IdeaVoteRow>().map { it.postId }.toSet()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                votesKnown = false
                emptySet()
            }
            requireOwner(owner)
            IdeasSnapshot(mapIdeaPosts(posts, comments), votes, votesKnown)
        }

        override suspend fun submit(owner: String, submission: IdeaSubmission) = withTimeout(20_000) {
            requireOwner(owner)
            // No public account name, email, device identity, attachment or token in the body.
            supabase.postgrest["feedback_posts"].insert(submission)
            Unit
        }

        override suspend fun vote(owner: String, postId: String) = withTimeout(20_000) {
            requireOwner(owner)
            supabase.postgrest["feedback_votes"].insert(IdeaVoteInsert(postId, owner.lowercase()))
            Unit
        }
    }
}
