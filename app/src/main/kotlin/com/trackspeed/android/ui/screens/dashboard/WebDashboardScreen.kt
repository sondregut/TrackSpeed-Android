package com.trackspeed.android.ui.screens.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.cloud.dashboard.DashboardError
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.flow.collect

@Composable
fun WebDashboardScreen(onBack: () -> Unit, viewModel: WebDashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.dashboard_share_chooser)
    val leave = { viewModel.cancelPending(); onBack() }
    BackHandler(onBack = leave)
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.cancelPending()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.cancelPending()
        }
    }
    LaunchedEffect(viewModel, lifecycleOwner, chooserTitle) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.launches.collect { launch ->
                try {
                    context.startActivity(dashboardExternalIntent(launch, chooserTitle))
                } catch (_: ActivityNotFoundException) {
                    viewModel.externalLaunchFailed()
                } catch (_: SecurityException) {
                    viewModel.externalLaunchFailed()
                }
            }
        }
    }
    WebDashboardContent(state, onBack = leave,
        onOpen = { viewModel.request(DashboardAction.OPEN) },
        onShare = { viewModel.request(DashboardAction.SHARE) }, onRetry = viewModel::retry)
}

/** Intent construction only. The user explicitly picks any Share recipient in the system chooser. */
internal fun dashboardExternalIntent(launch: DashboardLaunch, chooserTitle: String): Intent = when (launch.action) {
    DashboardAction.OPEN -> Intent(Intent.ACTION_VIEW, Uri.parse(launch.link.url)).addCategory(Intent.CATEGORY_BROWSABLE)
    DashboardAction.SHARE -> Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, launch.link.url), chooserTitle)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebDashboardContent(
    state: WebDashboardUiState,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit
) {
    Scaffold(modifier = Modifier.gradientBackground(), containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.dashboard_title)) }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark,
                titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary))
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.Computer, null, tint = TextPrimary, modifier = Modifier.size(42.dp))
            Text(stringResource(R.string.dashboard_description), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            if (!state.signedIn) {
                Text(stringResource(R.string.dashboard_sign_in_required), color = TextSecondary)
            } else {
                Button(onClick = onShare, enabled = state.pending == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.dashboard_send_link))
                }
                OutlinedButton(onClick = onOpen, enabled = state.pending == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.dashboard_open_here))
                }
                Text(stringResource(R.string.dashboard_private_link_hint), color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
                if (state.pending != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = TextPrimary)
                        Text(stringResource(R.string.dashboard_creating_link), color = TextSecondary)
                    }
                }
                state.error?.let { error ->
                    Text(stringResource(error.messageResource()), color = TextSecondary)
                    if (state.pending == null && error != DashboardError.NOT_SIGNED_IN) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.paywall_retry)) }
                    }
                }
            }
        }
    }
}

private fun DashboardError.messageResource(): Int = when (this) {
    DashboardError.NOT_SIGNED_IN -> R.string.dashboard_sign_in_required
    DashboardError.OFFLINE -> R.string.dashboard_offline
    DashboardError.SESSION_EXPIRED -> R.string.dashboard_session_expired
    DashboardError.EMAIL_REQUIRED -> R.string.dashboard_email_required
    DashboardError.RATE_LIMITED -> R.string.dashboard_rate_limited
    DashboardError.UNAVAILABLE -> R.string.dashboard_unavailable
    DashboardError.FAILED -> R.string.dashboard_failed
    DashboardError.OPEN_UNAVAILABLE -> R.string.dashboard_open_unavailable
}
