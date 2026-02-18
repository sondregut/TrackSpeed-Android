package com.trackspeed.android.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /**
     * Raw flow from DataStore. Use [isOnboardingCompleted] for a one-shot check,
     * or collect this flow for ongoing observation.
     */
    val onboardingCompletedFlow: Flow<Boolean> = settingsRepository.onboardingCompleted

    /**
     * One-shot read from DataStore. This waits for the real persisted value
     * rather than returning a hardcoded initial value.
     */
    suspend fun isOnboardingCompleted(): Boolean {
        return settingsRepository.onboardingCompleted.first()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
        }
    }
}
