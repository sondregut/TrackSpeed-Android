package com.trackspeed.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.cloud.RemoteConfigState
import com.trackspeed.android.ui.theme.AccentBlue
import com.trackspeed.android.ui.theme.BackgroundGradientTop
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary

@Composable
fun RemoteConfigGate(
    state: RemoteConfigState,
    currentVersion: String,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val needsUpdate = state.minSupportedVersion
        ?.let { minVersion -> compareSemver(currentVersion, minVersion) < 0 }
        ?: false

    if (!state.isKillSwitchEnabled && !state.isMaintenanceMode && !needsUpdate) {
        return
    }

    val title = when {
        state.isMaintenanceMode -> stringResource(R.string.remote_config_maintenance_title)
        needsUpdate -> stringResource(R.string.remote_config_update_title)
        else -> stringResource(R.string.remote_config_unavailable_title)
    }

    val body = when {
        state.isMaintenanceMode -> stringResource(R.string.remote_config_maintenance_body)
        needsUpdate -> stringResource(R.string.remote_config_update_body)
        else -> stringResource(R.string.remote_config_unavailable_body)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(BackgroundGradientTop),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = body,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            if (needsUpdate) {
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onUpdateClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = stringResource(R.string.remote_config_update_action),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun compareSemver(lhs: String, rhs: String): Int {
    val lhsParts = lhs.semverParts()
    val rhsParts = rhs.semverParts()
    if (lhsParts.isEmpty() || rhsParts.isEmpty()) {
        return lhs.compareTo(rhs)
    }

    val count = maxOf(lhsParts.size, rhsParts.size)
    for (index in 0 until count) {
        val left = lhsParts.getOrNull(index) ?: 0
        val right = rhsParts.getOrNull(index) ?: 0
        if (left != right) return left.compareTo(right)
    }
    return 0
}

private fun String.semverParts(): List<Int> {
    return split('.', '-')
        .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
}
