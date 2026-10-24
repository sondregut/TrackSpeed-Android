package com.trackspeed.android.ui.screens.help

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.cloud.TimingWorkloadCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

internal data class HelpFeedbackUiState(
    val kind: PrivateFeedbackKind? = null,
    val draft: PrivateFeedbackDraft? = null,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val importing: Boolean = false,
    val persistenceError: Boolean = false,
    val failure: PrivateFeedbackFailure? = null,
    val receipt: PrivateFeedbackReceipt? = null
)

@HiltViewModel
class HelpFeedbackViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: PrivateFeedbackClient,
    private val timing: TimingWorkloadCoordinator
) : ViewModel() {
    private val drafts = PrivateFeedbackDraftStore(File(context.noBackupFilesDir, "PrivateFeedback"))
    private val _state = MutableStateFlow(HelpFeedbackUiState())
    internal val state: StateFlow<HelpFeedbackUiState> = _state
    private var operation: Job? = null
    private var importJob: Job? = null
    private var persistence: Job? = null
    private var generation = 0L
    internal val metadata: Map<String, String> = mapOf("appVersion" to BuildConfig.VERSION_NAME,
        "buildNumber" to BuildConfig.VERSION_CODE.toString(), "osVersion" to Build.VERSION.RELEASE,
        "device" to Build.MODEL, "screen" to "Help & Feedback")

    internal fun open(kind: PrivateFeedbackKind) {
        generation++; val request = generation
        operation?.cancel(); importJob?.cancel()
        _state.value = HelpFeedbackUiState(kind = kind, loading = true)
        operation = viewModelScope.launch {
            persistence?.join()
            val stored = try { drafts.load(kind) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == generation) _state.update { it.copy(persistenceError = true) }; null
            }
            if (request == generation) _state.update { it.copy(draft = stored ?: PrivateFeedbackDraft(kind = kind), loading = false) }
        }
    }

    internal fun edit(change: (PrivateFeedbackDraft) -> PrivateFeedbackDraft) {
        val current = _state.value
        val old = current.draft ?: return
        if (current.sending || current.importing || current.receipt != null) return
        val new = old.edited(change(old), System.currentTimeMillis())
        if (new == old) return
        _state.update { it.copy(draft = new, failure = null) }
        persist(new)
    }

    private fun persist(draft: PrivateFeedbackDraft) {
        val previous = persistence
        persistence = viewModelScope.launch {
            previous?.join()
            try { drafts.save(draft); _state.update { it.copy(persistenceError = false) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(persistenceError = true) } }
        }
    }

    internal fun closeComposer(onClosed: () -> Unit = {}) {
        generation++; operation?.cancel(); importJob?.cancel()
        viewModelScope.launch {
            persistence?.join()
            _state.value = HelpFeedbackUiState()
            onClosed()
        }
    }

    internal fun addImages(uris: List<Uri>) {
        val current = _state.value; val draft = current.draft ?: return
        if (current.sending || current.importing || uris.isEmpty()) return
        val request = generation
        _state.update { it.copy(importing = true, failure = null) }
        importJob = viewModelScope.launch {
            try {
                val result = draft.attachments.toMutableList()
                for (uri in uris.take(3 - result.size)) result += importPrivateFeedbackImage(context.contentResolver, uri)
                if (generation == request) {
                    val edited = draft.edited(draft.copy(attachments = result), System.currentTimeMillis())
                    _state.update { it.copy(draft = edited, importing = false) }; persist(edited)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == request) _state.update { it.copy(importing = false, failure = PrivateFeedbackFailure.IMAGE) } }
        }
    }

    internal fun discard() {
        val draft = _state.value.draft ?: return
        if (_state.value.sending || _state.value.importing) return
        operation = viewModelScope.launch {
            persistence?.join()
            try { drafts.remove(draft.kind, draft.id); _state.value = HelpFeedbackUiState() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(persistenceError = true) } }
        }
    }

    internal fun send() {
        val current = _state.value; val draft = current.draft ?: return
        if (!draft.canSend || current.sending || current.importing || current.receipt != null) return
        if (timing.isLiveTimingActive) {
            _state.update { it.copy(failure = PrivateFeedbackFailure.TIMING_ACTIVE) }; return
        }
        val request = generation
        _state.update { it.copy(sending = true, failure = null) }
        operation = viewModelScope.launch {
            try {
                persistence?.join()
                val submitted = draft.beginAttempt(metadata, System.currentTimeMillis())
                // Persist the exact retry identity before sending. No automatic upload on load/relaunch.
                drafts.save(submitted)
                _state.update { it.copy(draft = submitted, persistenceError = false) }
                val receipt = client.send(submitted)
                if (request == generation) {
                    _state.update { it.copy(receipt = receipt, sending = false) }
                    try { drafts.remove(submitted.kind, expectedId = submitted.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { _state.update { it.copy(persistenceError = true) } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: PrivateFeedbackException) {
                if (request == generation) _state.update { it.copy(sending = false, failure = failure.reason) }
            } catch (_: Exception) {
                if (request == generation) _state.update { it.copy(sending = false, persistenceError = true, failure = PrivateFeedbackFailure.PERSISTENCE) }
            }
        }
    }
}
