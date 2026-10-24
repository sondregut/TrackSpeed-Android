package com.trackspeed.android.cloud.dashboard

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DashboardLinkContractTest {
    private val link = "https://mytrackspeed.com/dashboard#access_token=synthetic-test-value"
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private fun success() = DashboardHttpResponse(200, "application/json", """{"url":"$link","expiresAt":"2026-10-24T12:05:00Z"}""")
    private fun error(response: DashboardHttpResponse) = (DashboardResponseParser.parse(response, now) as DashboardResult.Failure).error

    @Test fun allowlistedHttpsDashboardPreservesFragmentExactly() {
        assertEquals(link, DashboardLink.validated(link)?.url)
        assertNotNull(DashboardLink.validated("https://mytrackspeed.com:443/dashboard/#opaque-token"))
        val result = DashboardResponseParser.parse(success(), now) as DashboardResult.Success
        assertEquals(link, result.link.url)
        assertFalse(result.toString().contains("synthetic-test-value"))
        assertFalse(result.link.toString().contains("synthetic-test-value"))
        assertFalse(success().toString().contains("synthetic-test-value"))
    }

    @Test fun unrelatedOrAmbiguousDestinationsAreRejected() {
        listOf(
            "http://mytrackspeed.com/dashboard#token", "javascript:alert(1)",
            "https://mytrackspeed.com.evil.test/dashboard#token", "https://evil.test/dashboard#token",
            "https://mytrackspeed.com@evil.test/dashboard#token", "https://evil@mytrackspeed.com/dashboard#token",
            "https://mytrackspeed.com:444/dashboard#token", "https://mytrackspeed.com/invite#token",
            "https://mytrackspeed.com/dashboard/../other#token", "https://mytrackspeed.com/%64ashboard#token",
            "https://mytrackspeed.com/dashboard?redirect=https://evil.test#token",
            "https://mytrackspeed.com/dashboard", "https://mytrackspeed.com/dashboard#",
            "https://mytrackspeed.com/dashboard#token\n", "https://mytrackspeed.com\\@evil.test/dashboard#token"
        ).forEach { assertNull(it.substringBefore('#'), DashboardLink.validated(it)) }
    }

    @Test fun serviceFailuresHaveStableNonSensitiveMessages() {
        assertEquals(DashboardError.NOT_SIGNED_IN, error(DashboardHttpResponse(400, "application/json", """{"error":"anonymous_user"}""")))
        assertEquals(DashboardError.EMAIL_REQUIRED, error(DashboardHttpResponse(400, "application/json", """{"error":"email_required"}""")))
        for (status in listOf(401, 403)) assertEquals(DashboardError.SESSION_EXPIRED, error(DashboardHttpResponse(status, "application/json", "{}")))
        for (status in listOf(404, 405, 501)) assertEquals(DashboardError.UNAVAILABLE, error(DashboardHttpResponse(status, "text/html", "private error detail")))
        assertEquals(DashboardError.RATE_LIMITED, error(DashboardHttpResponse(429, "application/json", "{}")))
        assertEquals(DashboardError.FAILED, error(DashboardHttpResponse(429, "text/html", "challenge")))
        assertEquals(DashboardError.UNAVAILABLE, error(DashboardHttpResponse(200, "text/html", "not deployed")))
        assertEquals(DashboardError.FAILED, error(DashboardHttpResponse(302, "text/html", "redirect")))
    }

    @Test fun malformedExpiredAndOversizedResponsesNeverBecomeLaunchEvents() {
        assertEquals(DashboardError.FAILED, error(DashboardHttpResponse(200, "application/json", "not JSON")))
        assertEquals(DashboardError.FAILED, error(DashboardHttpResponse(200, "application/json", """{"url":"https://evil.test/dashboard#token"}""")))
        assertEquals(DashboardError.FAILED, error(DashboardHttpResponse(200, "application/json", """{"url":"$link","expiresAt":"2026-10-24T11:59:00Z"}""")))
        assertEquals(DashboardError.FAILED, error(DashboardHttpResponse(200, "application/json", """{"url":"$link","expiresAt":"invalid"}""")))
        assertEquals(DashboardError.FAILED, error(DashboardHttpResponse(200, "application/json", " ".repeat(16385))))
    }

    @Test fun anonymousMissingAndExpiredSessionsDoNotCallTheClient() = runBlocking {
        var calls = 0
        for ((access, expected) in listOf(
            DashboardAccess(null, true, null) to DashboardError.NOT_SIGNED_IN,
            DashboardAccess("guest", true, "synthetic-token") to DashboardError.NOT_SIGNED_IN,
            DashboardAccess("", false, "synthetic-token") to DashboardError.NOT_SIGNED_IN,
            DashboardAccess("member", false, null) to DashboardError.SESSION_EXPIRED
        )) {
            val requester = DashboardLinkRequester({ access }, { calls++; success() }, { now })
            assertEquals(expected, (requester.request() as DashboardResult.Failure).error)
        }
        assertEquals(0, calls)
    }

    @Test fun fakeClientGetsExistingTokenButAccountSwitchDiscardsItsLink() = runBlocking {
        var account = DashboardAccess("member-one", false, "synthetic-token")
        var seenToken: String? = null
        val requester = DashboardLinkRequester({ account }, { token ->
            seenToken = token
            account = DashboardAccess("member-two", false, "new-synthetic-token")
            success()
        }, { now })
        assertEquals(DashboardError.SESSION_EXPIRED, (requester.request() as DashboardResult.Failure).error)
        assertEquals("synthetic-token", seenToken)
        assertFalse(account.toString().contains("new-synthetic-token"))
    }

    @Test fun offlineFailureCanBeRetriedWithFreshSessionAndNoRetainedLink() = runBlocking {
        var requests = 0
        val requester = DashboardLinkRequester({ DashboardAccess("member", false, "synthetic-token") }, {
            if (++requests == 1) throw IOException("private network detail")
            success()
        }, { now })
        val failure = requester.request() as DashboardResult.Failure
        assertEquals(DashboardError.OFFLINE, failure.error)
        assertFalse(failure.toString().contains("private network detail"))
        assertTrue(requester.request() is DashboardResult.Success)
        assertEquals(2, requests)
    }

    @Test fun cancellationIsPropagatedInsteadOfReportedAsFailure() = runBlocking {
        val requester = DashboardLinkRequester({ DashboardAccess("member", false, "synthetic-token") }, {
            throw CancellationException("cancel")
        }, { now })
        try {
            requester.request()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
