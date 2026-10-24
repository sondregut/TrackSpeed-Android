package com.trackspeed.android.ui.screens.ideas

import com.trackspeed.android.cloud.ideas.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class IdeasBoardStoreTest {
    private class FakeApi : IdeasBoardApi {
        var owner: String? = "existing-anonymous-session"
        var reads = 0; var submissions = 0; var votes = 0
        var received: IdeaSubmission? = null
        var snapshot = IdeasSnapshot(listOf(IdeaPost("one", "A test idea", "Details", IdeaCategory.FEATURE, votes = 3)), emptySet())
        var readAction: suspend () -> IdeasSnapshot = { snapshot }
        var submitAction: suspend () -> Unit = {}
        var voteAction: suspend () -> Unit = {}
        override fun currentOwner() = owner
        override suspend fun load(owner: String): IdeasSnapshot { check(owner == this.owner); reads++; return readAction() }
        override suspend fun submit(owner: String, submission: IdeaSubmission) { check(owner == this.owner); submissions++; received = submission; submitAction() }
        override suspend fun vote(owner: String, postId: String) { check(owner == this.owner); votes++; voteAction() }
    }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun IdeasBoardStore.validDraft() { openComposer(); editDraft(IdeaDraft("Small idea", "Improve a specific thing")) }

    @Test fun constructionDoesNoIoAndAbsentSessionDoesNotInventAuth() {
        val scope = scope(); val api = FakeApi().apply { owner = null }
        try {
            val store = IdeasBoardStore(scope, api)
            assertEquals(0, api.reads)
            store.load(); store.validDraft(); store.submit()
            assertEquals(0, api.reads); assertEquals(0, api.submissions); assertEquals(0, api.votes)
            assertEquals(IdeasError.SESSION_UNAVAILABLE, store.state.value.error)
            assertEquals(IdeasError.SESSION_UNAVAILABLE, store.state.value.composerError)
            assertFalse(store.state.value.loaded)
        } finally { scope.cancel() }
    }

    @Test fun existingAnonymousSessionCanBrowseAndExplicitlySubmitWithoutNamedAccountGate() {
        val scope = scope(); val api = FakeApi()
        try {
            val store = IdeasBoardStore(scope, api)
            store.load(); store.validDraft()
            assertEquals(0, api.submissions)
            assertEquals("", store.state.value.draft!!.publicName)
            store.submit()
            assertEquals(1, api.submissions); assertEquals(2, api.reads)
            assertNull(api.received!!.publicName)
            assertNull(store.state.value.draft)
        } finally { scope.cancel() }
    }

    @Test fun loadingIsNotEmptyAndRefreshFailurePreservesPriorContent() = runBlocking {
        val scope = scope(); val api = FakeApi(); val response = CompletableDeferred<IdeasSnapshot>()
        api.readAction = { response.await() }
        try {
            val store = IdeasBoardStore(scope, api)
            store.load(); store.load()
            assertEquals(1, api.reads); assertTrue(store.state.value.loading); assertFalse(store.state.value.loaded)
            response.complete(api.snapshot); yield()
            api.readAction = { throw IOException("private server error must not reach state") }
            store.load()
            assertEquals(api.snapshot.posts, store.state.value.posts)
            assertTrue(store.state.value.loaded); assertFalse(store.state.value.loading)
            assertEquals(IdeasError.LOAD_FAILED, store.state.value.error)
            assertFalse(store.state.value.toString().contains("private server"))
            api.readAction = { IdeasSnapshot(emptyList(), emptySet()) }; store.load()
            assertTrue(store.state.value.loaded); assertTrue(store.state.value.posts.isEmpty()); assertNull(store.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun unavailableVoteHistoryKeepsBoardReadableButPreventsDuplicateVote() {
        val scope = scope(); val api = FakeApi().apply { snapshot = snapshot.copy(votesKnown = false) }
        try {
            val store = IdeasBoardStore(scope, api); store.load(); store.vote("one")
            assertEquals(1, store.state.value.posts.size); assertEquals(0, api.votes)
            assertEquals(IdeasError.VOTES_UNAVAILABLE, store.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun optimisticVoteRollsBackAndRequiresReadReconciliationBeforeRetry() = runBlocking {
        val scope = scope(); val api = FakeApi(); val vote = CompletableDeferred<Unit>()
        api.voteAction = { vote.await() }
        try {
            val store = IdeasBoardStore(scope, api); store.load(); store.vote("one"); store.vote("one"); store.load()
            assertEquals(1, api.votes); assertEquals(1, api.reads)
            assertEquals(4, store.state.value.posts.single().votes); assertTrue("one" in store.state.value.votedIds)
            vote.completeExceptionally(IOException("failed")); yield()
            assertEquals(3, store.state.value.posts.single().votes); assertFalse("one" in store.state.value.votedIds)
            assertFalse(store.state.value.votesKnown); assertTrue(store.state.value.votingIds.isEmpty())
            store.vote("one"); assertEquals(1, api.votes)
            // If response was lost after commit, a read shows the existing vote and prevents reinsert.
            api.snapshot = api.snapshot.copy(votedIds = setOf("one"), posts = api.snapshot.posts.map { it.copy(votes = 4) })
            store.load(); store.vote("one")
            assertTrue(store.state.value.votesKnown); assertEquals(1, api.votes)
        } finally { scope.cancel() }
    }

    @Test fun successfulVoteRetainsOneIncrementAndRejectsAnotherTap() {
        val scope = scope(); val api = FakeApi()
        try {
            val store = IdeasBoardStore(scope, api); store.load(); store.vote("one"); store.vote("one")
            assertEquals(1, api.votes); assertEquals(4, store.state.value.posts.single().votes)
            assertTrue(store.state.value.votesKnown); assertTrue(store.state.value.votingIds.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun submitIsExplicitAndRepeatedTapCannotCreateTwoPosts() = runBlocking {
        val scope = scope(); val api = FakeApi(); val response = CompletableDeferred<Unit>()
        api.submitAction = { response.await() }
        try {
            val store = IdeasBoardStore(scope, api); store.load(); store.validDraft()
            assertEquals(0, api.submissions)
            store.submit(); store.submit()
            assertTrue(store.state.value.submitting); assertEquals(1, api.submissions)
            response.complete(Unit); yield()
            assertFalse(store.state.value.submitting); assertNull(store.state.value.draft)
            assertEquals(1, api.submissions)
        } finally { scope.cancel() }
    }

    @Test fun confirmedSubmissionFollowedByReadFailureNeverBecomesRetryableWrite() {
        val scope = scope(); val api = FakeApi()
        try {
            val store = IdeasBoardStore(scope, api); store.load(); store.validDraft()
            api.readAction = { throw IOException("read unavailable") }
            store.submit()
            assertEquals(1, api.submissions); assertNull(store.state.value.draft)
            assertEquals(IdeasError.LOAD_FAILED, store.state.value.error)
            store.load(); assertEquals(1, api.submissions)
        } finally { scope.cancel() }
    }

    @Test fun unconfirmedSubmissionRetainsDraftAndCheckBoardOnlyReads() {
        val scope = scope(); val api = FakeApi().apply { submitAction = { throw IOException("lost response") } }
        try {
            val store = IdeasBoardStore(scope, api); store.validDraft(); store.submit()
            assertNotNull(store.state.value.draft)
            assertEquals(IdeasError.SUBMIT_UNCONFIRMED, store.state.value.composerError)
            store.submit(); store.editDraft(IdeaDraft("another", "details")); store.submit()
            assertEquals(1, api.submissions)
            store.checkBoardAfterSubmission()
            assertNull(store.state.value.draft); assertEquals(1, api.reads); assertEquals(1, api.submissions)
        } finally { scope.cancel() }
    }

    @Test fun accountChangePreventsDraftAndVoteFromUsingPreviousIdentity() {
        val scope = scope(); val api = FakeApi()
        try {
            val store = IdeasBoardStore(scope, api); store.load(); store.validDraft()
            api.owner = "different-existing-session"
            store.submit(); store.vote("one")
            assertEquals(0, api.submissions); assertEquals(0, api.votes)
            assertEquals(IdeasError.SESSION_UNAVAILABLE, store.state.value.composerError)
            assertEquals(IdeasError.SESSION_UNAVAILABLE, store.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun backOrBackgroundCancelsVoteAndRestoresCountBeforeNextRead() = runBlocking {
        val scope = scope(); val api = FakeApi(); val vote = CompletableDeferred<Unit>()
        api.voteAction = { vote.await() }
        try {
            val store = IdeasBoardStore(scope, api); store.load(); store.vote("one")
            store.cancelPending(); vote.complete(Unit); yield()
            assertEquals(3, store.state.value.posts.single().votes)
            assertFalse(store.state.value.votesKnown); assertFalse("one" in store.state.value.votedIds)
            assertTrue(store.state.value.votingIds.isEmpty()); assertEquals(1, api.votes)
            store.load(); assertTrue(store.state.value.votesKnown)
        } finally { scope.cancel() }
    }

    @Test fun backgroundedSubmissionKeepsUncertaintyAndNeverAutomaticallyResubmits() = runBlocking {
        val scope = scope(); val api = FakeApi(); val submission = CompletableDeferred<Unit>()
        api.submitAction = { submission.await() }
        try {
            val store = IdeasBoardStore(scope, api); store.validDraft(); store.submit()
            store.cancelPending(); submission.complete(Unit); yield()
            assertNotNull(store.state.value.draft); assertFalse(store.state.value.submitting)
            assertEquals(IdeasError.SUBMIT_UNCONFIRMED, store.state.value.composerError)
            store.load(); store.submit(); assertEquals(1, api.submissions)
        } finally { scope.cancel() }
    }

    @Test fun cancelledReadCannotOverwriteNewIdentityOrShowFalseEmpty() = runBlocking {
        val scope = scope(); val api = FakeApi(); val read = CompletableDeferred<IdeasSnapshot>()
        api.readAction = { read.await() }
        try {
            val store = IdeasBoardStore(scope, api); store.load(); store.cancelPending()
            api.owner = null; read.complete(api.snapshot); yield(); store.load()
            assertFalse(store.state.value.loaded); assertTrue(store.state.value.posts.isEmpty())
            assertEquals(IdeasError.SESSION_UNAVAILABLE, store.state.value.error)
        } finally { scope.cancel() }
    }
}
