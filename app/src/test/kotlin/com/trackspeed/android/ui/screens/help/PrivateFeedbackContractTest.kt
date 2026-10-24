package com.trackspeed.android.ui.screens.help

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PrivateFeedbackContractTest {
    private val metadata = mapOf("appVersion" to "1.0", "buildNumber" to "11", "device" to "test-model", "screen" to "Help & Feedback", "token" to "excluded", "deviceName" to "excluded", "location" to "excluded")
    private fun draft() = PrivateFeedbackDraft(kind = PrivateFeedbackKind.BUG, details = "  Test report  ", email = " person@example.test ")

    @Test fun payloadUsesExistingContractAndOnlyDisclosedMetadata() {
        val payload = privateFeedbackPayload(draft().beginAttempt(metadata, 1))
        assertEquals("submit", payload.action); assertEquals("trackspeed", payload.appID); assertEquals("bug", payload.kind)
        assertEquals("Test report", payload.details); assertEquals("person@example.test", payload.email)
        assertEquals(setOf("appVersion", "buildNumber", "device", "screen"), payload.metadata.keys)
        assertFalse(FeedbackJson.encodeToString(payload).contains("excluded"))
    }
    @Test fun metadataOptOutSendsEmptyObjectAndBugCannotBecomePublic() {
        val data = draft().copy(includeDiagnostics = false, publicationRequested = true).beginAttempt(metadata, 1)
        val payload = privateFeedbackPayload(data)
        assertTrue(payload.metadata.isEmpty()); assertFalse(payload.publicationRequested)
    }
    @Test fun improvementPublicationIsSeparateExplicitChoice() {
        val data = draft().copy(kind = PrivateFeedbackKind.IMPROVEMENT)
        assertFalse(privateFeedbackPayload(data.beginAttempt(metadata, 1)).publicationRequested)
        assertTrue(privateFeedbackPayload(data.copy(publicationRequested = true).beginAttempt(metadata, 1)).publicationRequested)
    }
    @Test fun unchangedRetryRetainsIdAndOriginalMetadataSnapshot() {
        val attempted = draft().beginAttempt(metadata, 1)
        assertEquals(attempted, attempted.beginAttempt(mapOf("buildNumber" to "12"), 2))
        assertEquals(attempted, attempted.edited(attempted, 3))
    }
    @Test fun editingAttemptedPayloadRotatesIdAndClearsFrozenMetadata() {
        val attempted = draft().beginAttempt(metadata, 1)
        val changed = attempted.edited(attempted.copy(details = "Changed"), attempted.updatedAtMillis)
        assertNotEquals(attempted.id, changed.id); assertFalse(changed.attempted); assertTrue(changed.attemptedMetadata.isEmpty())
        assertTrue(changed.updatedAtMillis > attempted.updatedAtMillis)
    }
    @Test fun detailsAndEmailAreBoundedAndInvalidAddressCannotSend() {
        val blank = PrivateFeedbackDraft(kind = PrivateFeedbackKind.QUESTION)
        assertFalse(blank.canSend)
        val bounded = blank.edited(blank.copy(details = "a".repeat(5_000), email = "b".repeat(300)), 1)
        assertEquals(4_000, bounded.details.length); assertEquals(254, bounded.email.length); assertFalse(bounded.canSend)
        assertTrue(bounded.edited(bounded.copy(email = ""), 2).canSend)
    }
    @Test fun matchingReceivedReceiptIsRequired() = runBlocking {
        val attempted = draft().beginAttempt(metadata, 1)
        val receipt = submitPrivateFeedback(PrivateFeedbackTransport { PrivateFeedbackHttpResponse(200, "{\"id\":\"${attempted.id}\",\"received\":true}") }, attempted)
        assertEquals(attempted.id, receipt.id)
        for (body in listOf("{\"id\":\"wrong\",\"received\":true}", "{\"id\":\"${attempted.id}\",\"received\":false}", "<html>ok</html>")) {
            try { submitPrivateFeedback(PrivateFeedbackTransport { PrivateFeedbackHttpResponse(200, body) }, attempted); fail("Accepted invalid receipt") }
            catch (error: PrivateFeedbackException) { assertEquals(PrivateFeedbackFailure.INVALID_RECEIPT, error.reason) }
        }
    }
    @Test fun failedOrRateLimitedResponseDoesNotClaimReceipt() = runBlocking {
        val attempted = draft().beginAttempt(metadata, 1)
        for (status in listOf(302, 401, 429, 500)) {
            try { submitPrivateFeedback(PrivateFeedbackTransport { PrivateFeedbackHttpResponse(status, "{}") }, attempted); fail("Accepted HTTP $status") }
            catch (error: PrivateFeedbackException) {
                assertEquals(if (status == 429) PrivateFeedbackFailure.RATE_LIMITED else PrivateFeedbackFailure.UNAVAILABLE, error.reason)
            }
        }
    }
    @Test fun cancellationPropagatesAndNeverProducesReceipt() = runBlocking {
        try { submitPrivateFeedback(PrivateFeedbackTransport { throw kotlinx.coroutines.CancellationException() }, draft().beginAttempt(metadata, 1)); fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
