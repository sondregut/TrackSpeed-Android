package com.trackspeed.android.ui.util

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class RetainedLoadState<T>(
    val data: T? = null,
    val isLoading: Boolean = true,
    val failed: Boolean = false
)

/** A screen-owned read stream. Retry preserves the last successfully loaded value. */
internal class RetainedFlowLoader<T>(
    private val scope: CoroutineScope,
    private val source: () -> Flow<T>
) {
    private val mutableState = MutableStateFlow(RetainedLoadState<T>())
    val state: StateFlow<RetainedLoadState<T>> = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    init { retry() }

    @MainThread
    fun retry() {
        val request = ++generation
        job?.cancel()
        mutableState.update { it.copy(isLoading = true, failed = false) }
        job = scope.launch {
            try {
                source().collect { value ->
                    ensureActive()
                    if (request == generation) mutableState.value = RetainedLoadState(value, isLoading = false)
                }
                // A read stream that completes without any value is not an empty dataset.
                if (request == generation && mutableState.value.isLoading) {
                    mutableState.update { it.copy(isLoading = false, failed = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // UI receives a stable error state, never raw database/user payloads.
                if (request == generation) mutableState.update { it.copy(isLoading = false, failed = true) }
            }
        }
    }
}
