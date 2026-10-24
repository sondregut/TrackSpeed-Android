package com.trackspeed.android.ui.screens.history

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ScrubberImageState<T>(
    val key: String? = null,
    val image: T? = null,
    val isLoading: Boolean = false,
    val failed: Boolean = false
) {
    /** The selection may recompose before its effect starts the next read. */
    fun forSelection(selectedKey: String): ScrubberImageState<T> =
        if (key == selectedKey) this else ScrubberImageState(key = selectedKey, isLoading = true)
}

/** Image reads never retain a previous frame under the next frame's metadata. */
internal class ScrubberImageLoader<T>(
    private val scope: CoroutineScope,
    private val read: suspend (String) -> T?
) {
    private val mutableState = MutableStateFlow(ScrubberImageState<T>())
    val state: StateFlow<ScrubberImageState<T>> = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    @MainThread
    fun select(key: String) {
        if (mutableState.value.key != key) start(key)
    }

    @MainThread
    fun retry() { mutableState.value.key?.let(::start) }

    private fun start(key: String) {
        val request = ++generation
        job?.cancel()
        mutableState.value = ScrubberImageState(key = key, isLoading = true)
        job = scope.launch {
            try {
                val image = read(key)
                ensureActive()
                if (request == generation) {
                    mutableState.value = ScrubberImageState(key = key, image = image, failed = image == null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) mutableState.value = ScrubberImageState(key = key, failed = true)
            }
        }
    }
}
