package com.trackspeed.android.ui.screens.dashboard

import com.trackspeed.android.cloud.dashboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class DashboardRequestControllerTest {
    private fun result() = DashboardResult.Success(checkNotNull(DashboardLink.validated("https://mytrackspeed.com/dashboard#synthetic-token")))
    private fun owner() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun constructionAndGuestActionsNeverRequestLinks() {
        val scope = owner(); var calls = 0
        try {
            val controller = DashboardRequestController(scope, { false }, { calls++; result() })
            assertEquals(0, calls)
            controller.request(DashboardAction.OPEN)
            assertEquals(0, calls)
            assertEquals(DashboardError.NOT_SIGNED_IN, controller.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun repeatedTapHasOneRequestAndExplicitActionGetsOneLaunch() = runBlocking {
        val scope = owner(); var calls = 0
        val response = CompletableDeferred<DashboardResult>()
        try {
            val controller = DashboardRequestController(scope, { true }, { calls++; response.await() })
            val launches = mutableListOf<DashboardLaunch>()
            scope.launch { controller.launches.collect { launches += it } }
            controller.request(DashboardAction.SHARE)
            controller.request(DashboardAction.OPEN)
            assertEquals(1, calls)
            assertEquals(DashboardAction.SHARE, controller.state.value.pending)
            response.complete(result()); yield()
            assertEquals(listOf(DashboardAction.SHARE), launches.map { it.action })
            assertNull(controller.state.value.pending)
            assertFalse(controller.state.value.toString().contains("synthetic-token"))
        } finally { scope.cancel() }
    }

    @Test fun cancelOnBackOrStopDropsPendingResponseAndClearsRetryIntent() = runBlocking {
        val scope = owner(); var calls = 0
        val response = CompletableDeferred<DashboardResult>()
        try {
            val controller = DashboardRequestController(scope, { true }, { calls++; response.await() })
            val launches = mutableListOf<DashboardLaunch>()
            scope.launch { controller.launches.collect { launches += it } }
            controller.request(DashboardAction.OPEN)
            controller.cancel()
            response.complete(result()); yield()
            controller.retry()
            assertTrue(launches.isEmpty())
            assertNull(controller.state.value.pending)
            assertNull(controller.state.value.error)
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun stoppedScreenCannotBufferALinkForNextVisit() = runBlocking {
        val scope = owner()
        try {
            val controller = DashboardRequestController(scope, { true }, { result() })
            controller.request(DashboardAction.OPEN) // no screen collector: send is suspended
            controller.cancel()
            val launches = mutableListOf<DashboardLaunch>()
            scope.launch { controller.launches.collect { launches += it } }
            yield()
            assertTrue(launches.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun retryRepeatsOnlyLastUserActionAndExternalFailureIsNotOverwritten() = runBlocking {
        val scope = owner(); var calls = 0
        try {
            val controller = DashboardRequestController(scope, { true }, {
                if (++calls == 1) DashboardResult.Failure(DashboardError.OFFLINE) else result()
            })
            val actions = mutableListOf<DashboardAction>()
            scope.launch { controller.launches.collect {
                actions += it.action
                controller.externalLaunchFailed()
            } }
            controller.request(DashboardAction.SHARE)
            assertEquals(DashboardError.OFFLINE, controller.state.value.error)
            controller.retry(); yield()
            assertEquals(listOf(DashboardAction.SHARE), actions)
            assertEquals(DashboardError.OPEN_UNAVAILABLE, controller.state.value.error)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun accountWithdrawalPreventsLaunchAfterResponse() = runBlocking {
        val scope = owner(); var named = true
        try {
            val controller = DashboardRequestController(scope, { named }, { named = false; result() })
            val launches = mutableListOf<DashboardLaunch>()
            scope.launch { controller.launches.collect { launches += it } }
            controller.request(DashboardAction.OPEN); yield()
            assertTrue(launches.isEmpty())
            assertEquals(DashboardError.NOT_SIGNED_IN, controller.state.value.error)
        } finally { scope.cancel() }
    }
}
