package com.trackspeed.android.ui.screens.ideas

import com.trackspeed.android.cloud.ideas.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class IdeasBoardState(
    val posts: List<IdeaPost> = emptyList(),
    val votedIds: Set<String> = emptySet(),
    val votesKnown: Boolean = false,
    val votingIds: Set<String> = emptySet(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: IdeasError? = null,
    val category: IdeaCategory = IdeaCategory.ALL,
    val sort: IdeaSort = IdeaSort.MOST_VOTED,
    val draft: IdeaDraft? = null,
    val submitting: Boolean = false,
    val composerError: IdeasError? = null
) {
    val visiblePosts get() = visibleIdeas(posts, category, sort)
    val busy get() = submitting || votingIds.isNotEmpty()
}

/** Owned by the route ViewModel. No automatic writes, session creation or mutation retries. */
internal class IdeasBoardStore(private val scope: CoroutineScope, private val api: IdeasBoardApi) {
    private val mutableState = MutableStateFlow(IdeasBoardState())
    val state = mutableState.asStateFlow()
    private var loadedOwner: String? = null
    private var composerOwner: String? = null
    private var loadJob: Job? = null
    private var submitJob: Job? = null
    private val voteJobs = mutableMapOf<String, Job>()
    private var generation = 0
    private var loadSerial = 0
    private var composerSerial = 0
    private val voteOriginals = mutableMapOf<String, Int>()
    private fun update(transform: (IdeasBoardState) -> IdeasBoardState) { mutableState.value = transform(mutableState.value) }

    fun setCategory(category: IdeaCategory) = update { it.copy(category = category) }
    fun setSort(sort: IdeaSort) = update { it.copy(sort = sort) }

    fun load() {
        if (state.value.loading || state.value.busy) return
        val owner = api.currentOwner()
        if (owner == null) {
            loadedOwner = null
            update { it.copy(error = IdeasError.SESSION_UNAVAILABLE, votedIds = emptySet(), votesKnown = false) }
            return
        }
        val token = generation
        val request = ++loadSerial
        update { it.copy(loading = true, error = null,
            votedIds = if (loadedOwner == owner) it.votedIds else emptySet(),
            votesKnown = loadedOwner == owner && it.votesKnown) }
        loadJob = scope.launch {
            try {
                val snapshot = api.load(owner)
                ensureActive()
                if (token != generation || request != loadSerial) return@launch
                if (api.currentOwner() != owner) throw IdeasSessionUnavailable()
                loadedOwner = owner
                update { it.copy(posts = snapshot.posts, votedIds = snapshot.votedIds, votesKnown = snapshot.votesKnown,
                    loaded = true, error = if (snapshot.votesKnown) null else IdeasError.VOTES_UNAVAILABLE) }
            } catch (_: TimeoutCancellationException) {
                if (token == generation && request == loadSerial) update { it.copy(error = IdeasError.LOAD_FAILED) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation && request == loadSerial) update { it.copy(error = failure.readError(), votesKnown = false) }
            } finally {
                if (token == generation && request == loadSerial) update { it.copy(loading = false) }
            }
        }
    }

    fun openComposer() {
        if (state.value.draft != null) return
        composerSerial++
        composerOwner = api.currentOwner()
        update { it.copy(draft = IdeaDraft(), composerError = null) }
    }
    fun editDraft(draft: IdeaDraft) {
        if (state.value.draft == null || state.value.submitting || state.value.composerError == IdeasError.SUBMIT_UNCONFIRMED) return
        update { it.copy(draft = draft.copy(title = draft.title.takeCodePoints(IdeaDraft.MAX_TITLE),
            details = draft.details.takeCodePoints(IdeaDraft.MAX_DETAILS), publicName = draft.publicName.takeCodePoints(IdeaDraft.MAX_NAME)),
            composerError = null) }
    }
    fun closeComposer() {
        // Dismiss is always available. Cancelling a request cannot undo a server-side insert.
        val wasSubmitting = state.value.submitting
        composerSerial++
        submitJob?.cancel(); submitJob = null
        composerOwner = null
        update { it.copy(draft = null, submitting = false, composerError = null,
            error = if (wasSubmitting) IdeasError.SUBMIT_UNCONFIRMED else it.error) }
    }
    fun checkBoardAfterSubmission() { closeComposer(); load() }

    fun submit() {
        val current = state.value
        if (current.submitting || current.votingIds.isNotEmpty() || current.composerError == IdeasError.SUBMIT_UNCONFIRMED) return
        val submission = current.draft?.submission()
        if (submission == null) { update { it.copy(composerError = IdeasError.INVALID_SUBMISSION) }; return }
        val owner = composerOwner
        if (owner == null || api.currentOwner() != owner) {
            update { it.copy(composerError = IdeasError.SESSION_UNAVAILABLE) }
            return
        }
        // Finish any read before the write, so an older snapshot cannot replace the refreshed board.
        loadSerial++
        loadJob?.cancel(); loadJob = null
        val token = generation
        val composer = composerSerial
        update { it.copy(submitting = true, loading = false, composerError = null) }
        submitJob = scope.launch {
            try {
                api.submit(owner, submission)
                ensureActive()
                if (token != generation || composer != composerSerial) return@launch
                composerOwner = null
                update { it.copy(draft = null, submitting = false, composerError = null) }
                // A failed follow-up read is a read error; never repeat a confirmed insert.
                load()
            } catch (_: TimeoutCancellationException) {
                if (token == generation && composer == composerSerial) update { it.copy(composerError = IdeasError.SUBMIT_UNCONFIRMED) }
            } catch (cancelled: CancellationException) {
                if (token == generation && composer == composerSerial && state.value.draft != null) update { it.copy(composerError = IdeasError.SUBMIT_UNCONFIRMED) }
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation && composer == composerSerial) update { it.copy(composerError = if (failure is IdeasSessionUnavailable)
                    IdeasError.SESSION_UNAVAILABLE else IdeasError.SUBMIT_UNCONFIRMED) }
            } finally {
                if (token == generation && composer == composerSerial) update { it.copy(submitting = false) }
            }
        }
    }

    fun vote(postId: String) {
        val current = state.value
        if (current.loading || current.submitting || !current.votesKnown || postId in current.votedIds || postId in current.votingIds) return
        val owner = api.currentOwner()
        if (owner == null || owner != loadedOwner) {
            update { it.copy(error = IdeasError.SESSION_UNAVAILABLE, votesKnown = false, votedIds = emptySet()) }
            return
        }
        val original = current.posts.firstOrNull { it.id == postId } ?: return
        val token = generation
        voteOriginals[postId] = original.votes
        update { it.copy(votedIds = it.votedIds + postId, votingIds = it.votingIds + postId, error = null,
            posts = it.posts.map { post -> if (post.id == postId) post.copy(votes = (post.votes.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else post }) }
        voteJobs[postId] = scope.launch {
            var confirmed = false
            try {
                api.vote(owner, postId)
                ensureActive()
                if (api.currentOwner() != owner) throw IdeasSessionUnavailable()
                confirmed = true
            } catch (_: TimeoutCancellationException) {
                // The request may have committed. A read retry must reconcile before another vote.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep raw server errors and submitted content out of UI/logs.
            } finally {
                if (token == generation) update { state -> state.copy(
                    votingIds = state.votingIds - postId,
                    votedIds = if (confirmed) state.votedIds else state.votedIds - postId,
                    votesKnown = confirmed && state.votesKnown,
                    error = if (confirmed) state.error else IdeasError.VOTE_UNCONFIRMED,
                    posts = if (confirmed) state.posts else state.posts.map { post -> if (post.id == postId) post.copy(votes = original.votes) else post }
                ) }
                if (token == generation) { voteJobs.remove(postId); voteOriginals.remove(postId) }
            }
        }
    }

    /** Leaving/backgrounding cancels owned work; reopening reconciles any uncertain writes by reading. */
    fun cancelPending() {
        val interrupted = voteOriginals.toMap()
        val submissionPending = state.value.submitting
        generation++
        loadSerial++
        loadJob?.cancel(); loadJob = null
        submitJob?.cancel(); submitJob = null
        voteJobs.values.toList().forEach { it.cancel() }; voteJobs.clear(); voteOriginals.clear()
        update { it.copy(loading = false, submitting = false, votingIds = emptySet(),
            votedIds = it.votedIds - interrupted.keys,
            votesKnown = it.votesKnown && interrupted.isEmpty(),
            posts = it.posts.map { post -> interrupted[post.id]?.let { count -> post.copy(votes = count) } ?: post },
            error = if (interrupted.isNotEmpty()) IdeasError.VOTE_UNCONFIRMED else it.error,
            composerError = if (submissionPending) IdeasError.SUBMIT_UNCONFIRMED else it.composerError) }
    }
}

private fun Exception.readError() = if (this is IdeasSessionUnavailable) IdeasError.SESSION_UNAVAILABLE else IdeasError.LOAD_FAILED
