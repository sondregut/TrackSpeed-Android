package com.trackspeed.android.ui.share

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trackspeed.android.R
import com.trackspeed.android.ui.components.ComposeCapture
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Lifecycle ownership is local to the visible sharing route; Back never starts an export. */
@Composable
internal fun imageShareBackAction(controller: ImageShareController, outputKey: String?, onBack: () -> Unit): () -> Unit {
    val owner = LocalLifecycleOwner.current
    val back = { controller.cancel(); onBack() }
    BackHandler(onBack = back)
    LaunchedEffect(controller, outputKey) { outputKey?.let(controller::selectOutput) }
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.cancel() }
    }
    return back
}

/** Existing capture/cache/MediaStore/chooser flow, with immutable render inputs and phase boundaries. */
internal suspend fun exportShareImage(
    context: Context,
    key: String,
    action: ImageShareAction,
    chooserTitle: String,
    width: Int,
    height: Int,
    parentCompositionContext: CompositionContext,
    content: @Composable () -> Unit
) {
    currentCoroutineContext().ensureActive()
    val bitmap = withContext(Dispatchers.Main.immediate) {
        ComposeCapture.captureToBitmap(context, width, height, parentCompositionContext, content)
    }
    try {
        currentCoroutineContext().ensureActive()
        when (action) {
            ImageShareAction.SAVE -> {
                val saved = withContext(Dispatchers.IO) { ComposeCapture.saveToGallery(context, bitmap) }
                if (!saved) throw IOException("Image save not confirmed")
            }
            ImageShareAction.SHARE -> {
                val uri = withContext(Dispatchers.IO) {
                    // A subsequent export cannot overwrite an image still being read by a chooser recipient.
                    ComposeCapture.saveToCacheForSharing(context, bitmap, "trackspeed_${key.take(16)}_${UUID.randomUUID()}.png")
                }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main.immediate) {
                    currentCoroutineContext().ensureActive()
                    context.startActivity(Intent.createChooser(ComposeCapture.createShareIntent(uri), chooserTitle))
                }
            }
        }
    } finally {
        bitmap.recycle() // This render owns the output bitmap; never recycle stored crossing photos.
    }
}

/** Stateless controls for production routes and isolated native interaction tests. */
@Composable
internal fun ImageShareActions(
    outputKey: String,
    state: ImageShareState,
    onAction: (ImageShareAction) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busy = state.pending != null
    val saved = state.isSaved(outputKey)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onAction(ImageShareAction.SHARE) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.share_button))
        }
        OutlinedButton(onClick = { onAction(ImageShareAction.SAVE) }, enabled = !busy && !saved,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(if (saved) Icons.Default.Check else Icons.Outlined.SaveAlt, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (saved) R.string.share_saved else R.string.share_save_to_gallery))
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = TextPrimary)
                Text(stringResource(R.string.image_share_preparing), color = TextSecondary)
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
        state.failure?.takeIf { it.key == outputKey }?.let { failure ->
            Text(stringResource(if (failure.action == ImageShareAction.SAVE)
                R.string.image_share_save_unconfirmed else R.string.image_share_failed), color = TextSecondary)
            TextButton(onClick = { onAction(failure.action) }, enabled = !busy) {
                Text(stringResource(R.string.paywall_retry))
            }
        }
    }
}
