package com.trackspeed.android.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.cloud.AuthService
import com.trackspeed.android.cloud.AuthState
import com.trackspeed.android.cloud.isRealAuthenticated
import com.trackspeed.android.cloud.dashboard.DashboardError
import com.trackspeed.android.cloud.dashboard.WebDashboardService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class WebDashboardUiState(
    val signedIn: Boolean = false,
    val pending: DashboardAction? = null,
    val error: DashboardError? = null
)

@HiltViewModel
class WebDashboardViewModel @Inject constructor(
    private val authService: AuthService,
    service: WebDashboardService
) : ViewModel() {
    private val controller = DashboardRequestController(viewModelScope,
        namedAccount = { authService.authState.value.isRealAuthenticated() }, requestLink = service::requestLink)
    internal val state = combine(authService.authState, controller.state) { auth, request ->
        WebDashboardUiState(auth.isRealAuthenticated(), request.pending, request.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebDashboardUiState())
    internal val launches = controller.launches

    init {
        viewModelScope.launch {
            var priorIdentity: String? = (authService.authState.value as? AuthState.Authenticated)?.userId
            authService.authState.collect { auth ->
                val identity = (auth as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }?.userId
                if (identity != priorIdentity) controller.cancel()
                priorIdentity = identity
            }
        }
    }

    internal fun request(action: DashboardAction) = controller.request(action)
    internal fun retry() = controller.retry()
    internal fun cancelPending() = controller.cancel()
    internal fun externalLaunchFailed() = controller.externalLaunchFailed()
}
