package com.trackspeed.android.ui.screens.referral

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.referral.ReferralService
import com.trackspeed.android.referral.ReferralStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReferralUiState(
    val referralCode: String = "",
    val referralLink: String = "",
    val shareMessage: String = "",
    val stats: ReferralStats = ReferralStats(),
    val isLoading: Boolean = true,
    val copiedToClipboard: Boolean = false
)

@HiltViewModel
class ReferralViewModel @Inject constructor(
    private val referralService: ReferralService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReferralUiState())
    val uiState: StateFlow<ReferralUiState> = _uiState.asStateFlow()

    init {
        loadReferralData()
    }

    private fun loadReferralData() {
        viewModelScope.launch {
            try {
                val code = referralService.getOrCreateReferralCode()
                val link = referralService.getReferralLink()
                val message = referralService.getShareMessage()

                _uiState.update {
                    it.copy(
                        referralCode = code,
                        referralLink = link,
                        shareMessage = message,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }

        // Collect stats
        viewModelScope.launch {
            referralService.stats.collect { stats ->
                _uiState.update { it.copy(stats = stats) }
            }
        }
    }

    fun onCopiedToClipboard() {
        _uiState.update { it.copy(copiedToClipboard = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(copiedToClipboard = false) }
        }
    }
}
