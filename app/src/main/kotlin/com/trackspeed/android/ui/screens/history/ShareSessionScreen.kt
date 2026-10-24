package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.theme.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.ui.share.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.trackspeed.android.R
import com.trackspeed.android.ui.components.ShareableSessionCard

private const val SESSION_CARD_WIDTH_PX = 1080
private const val SESSION_CARD_HEIGHT_PX = 1920

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSessionScreen(
    onNavigateBack: () -> Unit,
    viewModel: ShareSessionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val compositionContext = rememberCompositionContext()
    val exportState by viewModel.imageShare.state.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val environment = "${configuration.locales.toLanguageTags()}:${configuration.densityDpi}:${configuration.fontScale}"
    val outputKey = uiState.cardData?.outputKey(uiState.selectedTheme, environment, uiState.imageOptions)
    val back = imageShareBackAction(viewModel.imageShare, outputKey, onNavigateBack)

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.gradientBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.share_session_title),
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.share_back_cd),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { paddingValues ->
        if (uiState.cardData == null && (uiState.isLoading || uiState.loadFailed)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                DetailLoadFeedback(
                    isLoading = uiState.isLoading,
                    failed = uiState.loadFailed,
                    hasLoadedData = false,
                    onRetry = viewModel::retryLoading
                )
            }
            return@Scaffold
        }

        val cardData = uiState.cardData
        if (cardData == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.share_session_data_not_found),
                    color = TextSecondary,
                    fontSize = 16.sp
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.isLoading || uiState.loadFailed) {
                DetailLoadFeedback(
                    isLoading = uiState.isLoading,
                    failed = uiState.loadFailed,
                    hasLoadedData = true,
                    onRetry = viewModel::retryLoading
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            ShareableSessionCard(
                data = cardData,
                theme = uiState.selectedTheme,
                options = uiState.imageOptions,
                modifier = Modifier
                    .width(270.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .shadow(
                        elevation = 20.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = Color.Black.copy(alpha = 0.4f)
                    )
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.share_style_label),
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            ThemeSelector(
                selectedTheme = uiState.selectedTheme,
                onThemeSelected = { viewModel.imageShare.cancel(); viewModel.selectTheme(it) },
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            SessionImageOptionControls(
                options = uiState.imageOptions,
                hasSplits = cardData.runs.any { it.segments.isNotEmpty() },
                hasName = cardData.runs.any { !it.athleteName.isNullOrBlank() },
                onChange = viewModel::selectImageOptions,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
            )

            val key = checkNotNull(outputKey)
            val options = uiState.imageOptions
            val theme = uiState.selectedTheme
            val chooserTitle = stringResource(R.string.share_session_chooser_title)
            ImageShareActions(key, exportState, onAction = { action ->
                viewModel.imageShare.perform(key, action) {
                    exportShareImage(context, key, action, chooserTitle, SESSION_CARD_WIDTH_PX, SESSION_CARD_HEIGHT_PX, compositionContext) {
                        ShareableSessionCard(data = cardData, theme = theme, options = options)
                    }
                }
            }, onCancel = viewModel.imageShare::cancel, modifier = Modifier.padding(horizontal = 32.dp))

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
