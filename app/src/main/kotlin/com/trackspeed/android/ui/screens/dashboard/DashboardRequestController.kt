package com.trackspeed.android.ui.screens.dashboard

import androidx.annotation.MainThread
import com.trackspeed.android.cloud.dashboard.DashboardError
import com.trackspeed.android.cloud.dashboard.DashboardLink
import com.trackspeed.android.cloud.dashboard.DashboardResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal enum class DashboardAction { OPEN, SHARE }
internal data class DashboardRequestState(val pending: DashboardAction? = null, val error: DashboardError? = null)
internal class DashboardLaunch(val action: DashboardAction, val link: DashboardLink) {
    override fun toString() = "DashboardLaunch(action=$action, link=[redacted])"
}

/** Only a user action starts a request. No link enters saved state or a replaying state flow. */
internal class DashboardRequestController(
    private val scope: CoroutineScope,
    private val namedAccount: () -> Boolean,
    private val requestLink: suspend () -> DashboardResult
) {
    private val mutableState = MutableStateFlow(DashboardRequestState())
    val state = mutableState.asStateFlow()
    private val launchChannel = Channel<DashboardLaunch>(Channel.RENDEZVOUS)
    val launches = launchChannel.receiveAsFlow()
    private var job: Job? = null
    private var generation = 0L
    private var lastAction: DashboardAction? = null

    @MainThread fun request(action: DashboardAction) {
        if (job?.isActive == true) return
        if (!namedAccount()) {
            mutableState.value = DashboardRequestState(error = DashboardError.NOT_SIGNED_IN)
            return
        }
        lastAction = action
        val requestGeneration = ++generation
        mutableState.value = DashboardRequestState(pending = action)
        job = scope.launch {
            try {
                val result = requestLink()
                ensureActive()
                if (requestGeneration != generation) return@launch
                if (!namedAccount()) {
                    mutableState.value = DashboardRequestState(error = DashboardError.NOT_SIGNED_IN)
                    return@launch
                }
                when (result) {
                    is DashboardResult.Success -> {
                        // Rendezvous delivery: a stopped/unmounted screen cannot accumulate a signed link.
                        mutableState.value = DashboardRequestState()
                        launchChannel.send(DashboardLaunch(action, result.link))
                    }
                    is DashboardResult.Failure -> mutableState.value = DashboardRequestState(error = result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) mutableState.value = DashboardRequestState(error = DashboardError.FAILED)
            }
        }
    }

    @MainThread fun retry() { lastAction?.let(::request) }

    @MainThread fun cancel() {
        generation++
        job?.cancel()
        job = null
        lastAction = null
        mutableState.value = DashboardRequestState()
    }

    @MainThread fun externalLaunchFailed() {
        mutableState.value = DashboardRequestState(error = DashboardError.OPEN_UNAVAILABLE)
    }
}
