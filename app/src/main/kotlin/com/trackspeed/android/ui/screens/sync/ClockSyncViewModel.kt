package com.trackspeed.android.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.sync.ClockSyncManager
import com.trackspeed.android.sync.SyncQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClockSyncViewModel @Inject constructor(
    private val clockSyncManager: ClockSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClockSyncUiState())
    val uiState: StateFlow<ClockSyncUiState> = _uiState.asStateFlow()

    init {
        // Observe sync state changes
        viewModelScope.launch {
            clockSyncManager.syncState.collect { state ->
                _uiState.update { current ->
                    when (state) {
                        is ClockSyncManager.SyncState.NotSynced -> current.copy(
                            status = SyncStatus.NOT_SYNCED,
                            progress = 0f,
                            errorMessage = null
                        )
                        is ClockSyncManager.SyncState.WaitingForPeer -> current.copy(
                            status = SyncStatus.WAITING_FOR_PEER,
                            progress = 0f,
                            errorMessage = null
                        )
                        is ClockSyncManager.SyncState.Connecting -> current.copy(
                            status = SyncStatus.CONNECTING,
                            progress = 0f,
                            errorMessage = null
                        )
                        is ClockSyncManager.SyncState.Syncing -> current.copy(
                            status = SyncStatus.SYNCING,
                            progress = state.progress,
                            errorMessage = null
                        )
                        is ClockSyncManager.SyncState.Synced -> current.copy(
                            status = SyncStatus.SYNCED,
                            progress = 1f,
                            offsetMs = state.offsetMs,
                            quality = state.quality,
                            uncertaintyMs = state.uncertaintyMs,
                            errorMessage = null
                        )
                        is ClockSyncManager.SyncState.Error -> current.copy(
                            status = SyncStatus.ERROR,
                            progress = 0f,
                            errorMessage = state.message
                        )
                    }
                }
            }
        }

        // Observe server/client role
        viewModelScope.launch {
            clockSyncManager.isServer.collect { isServer ->
                _uiState.update { it.copy(isServer = isServer) }
            }
        }
    }

    fun startAsServer() {
        clockSyncManager.startAsServer()
        _uiState.update { it.copy(isServer = true) }
    }

    fun startAsClient() {
        clockSyncManager.startAsClient()
        _uiState.update { it.copy(isServer = false) }
    }

    fun stop() {
        clockSyncManager.stop()
    }

    fun getSyncDetails(): ClockSyncManager.SyncDetails? {
        return clockSyncManager.getSyncDetails()
    }

    override fun onCleared() {
        super.onCleared()
        clockSyncManager.stop()
    }
}

data class ClockSyncUiState(
    val status: SyncStatus = SyncStatus.NOT_SYNCED,
    val isServer: Boolean = false,
    val progress: Float = 0f,
    val offsetMs: Double = 0.0,
    val quality: SyncQuality = SyncQuality.BAD,
    val uncertaintyMs: Double = 0.0,
    val errorMessage: String? = null
)

enum class SyncStatus {
    NOT_SYNCED,
    WAITING_FOR_PEER,
    CONNECTING,
    SYNCING,
    SYNCED,
    ERROR
}
