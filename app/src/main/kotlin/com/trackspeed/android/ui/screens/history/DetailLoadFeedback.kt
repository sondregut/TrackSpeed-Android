package com.trackspeed.android.ui.screens.history

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary

/** Shared initial-load and retained-content feedback; exposes no raw database error payload. */
@Composable
internal fun DetailLoadFeedback(
    isLoading: Boolean,
    failed: Boolean,
    hasLoadedData: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(28.dp), color = TextPrimary)
            Text(stringResource(R.string.detail_loading), color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium)
        } else if (failed) {
            Text(stringResource(if (hasLoadedData) R.string.detail_load_error_saved else R.string.detail_load_error),
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.paywall_retry))
            }
        }
    }
}
