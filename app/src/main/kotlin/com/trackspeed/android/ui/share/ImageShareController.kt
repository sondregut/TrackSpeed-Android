package com.trackspeed.android.ui.share

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal enum class ImageShareAction { SHARE, SAVE }
internal data class ImageSharePending(val key: String, val action: ImageShareAction)
internal data class ImageShareFailure(val key: String, val action: ImageShareAction)
internal data class ImageShareState(
    val outputKey: String? = null,
    val pending: ImageSharePending? = null,
    val savedKeys: Set<String> = emptySet(),
    val failure: ImageShareFailure? = null
) {
    fun isSaved(key: String): Boolean = key in savedKeys
}

/** ViewModel-owned state; operations are explicit user actions and never automatically retried. */
internal class ImageShareController(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(ImageShareState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var generation = 0

    fun selectOutput(key: String) {
        if (state.value.outputKey == key) return
        cancel()
        mutableState.value = state.value.copy(outputKey = key, failure = null)
    }

    fun perform(key: String, action: ImageShareAction, export: suspend () -> Unit) {
        selectOutput(key)
        if (state.value.pending != null || (action == ImageShareAction.SAVE && state.value.isSaved(key))) return
        val token = ++generation
        mutableState.value = state.value.copy(pending = ImageSharePending(key, action), failure = null)
        operation = scope.launch {
            try {
                export()
                ensureActive()
                if (token != generation || state.value.outputKey != key) return@launch
                val saved = if (action == ImageShareAction.SAVE) (state.value.savedKeys + key).toList().takeLast(16).toSet() else state.value.savedKeys
                mutableState.value = state.value.copy(savedKeys = saved)
            } catch (cancelled: CancellationException) {
                if (token == generation && action == ImageShareAction.SAVE) {
                    mutableState.value = state.value.copy(failure = ImageShareFailure(key, action))
                }
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) mutableState.value = state.value.copy(failure = ImageShareFailure(key, action))
            } finally {
                if (token == generation) {
                    mutableState.value = state.value.copy(pending = null)
                    operation = null
                }
            }
        }
    }

    /** Cancellation cannot undo a MediaStore write that has already completed. Never claim it did. */
    fun cancel() {
        val pending = state.value.pending
        ++generation
        operation?.cancel(); operation = null
        mutableState.value = state.value.copy(pending = null,
            failure = if (pending?.action == ImageShareAction.SAVE) ImageShareFailure(pending.key, pending.action) else state.value.failure)
    }
}

/** Length-prefixed fields avoid ambiguous concatenation. Only the digest is retained in export state. */
internal fun imageShareFingerprint(fields: List<String?>): String {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { stream ->
        fields.forEach { field ->
            if (field == null) stream.writeInt(-1) else {
                val bytes = field.toByteArray(Charsets.UTF_8)
                stream.writeInt(bytes.size); stream.write(bytes)
            }
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()).joinToString("") { "%02x".format(it) }
}
