package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.videooverlay.OverlaySpeedUnit
import com.trackspeed.android.ui.components.ShareCardTheme
import com.trackspeed.android.ui.components.ShareableResultCard



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareResultScreen(
    onNavigateBack: () -> Unit,
    viewModel: ShareResultViewModel = hiltViewModel()
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
                        text = stringResource(R.string.share_title),
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
                    text = stringResource(R.string.share_data_not_found),
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

            // Card preview
            ShareableResultCard(
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

            // Theme selector section
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

            RunImageOptionControls(
                options = uiState.imageOptions,
                hasSplits = cardData.segments.isNotEmpty(),
                hasPhotos = cardData.hasPhotos,
                hasStartType = cardData.hasStartType,
                hasName = !cardData.athleteName.isNullOrBlank(),
                onChange = viewModel::selectImageOptions,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
            )

            if (cardData.speedValue != null) {
                RunImageSpeedUnitControls(OverlaySpeedUnit.fromRaw(cardData.speedUnit), viewModel::selectImageSpeedUnit,
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp))
            }

            val key = checkNotNull(outputKey)
            val options = uiState.imageOptions
            val theme = uiState.selectedTheme
            val chooserTitle = stringResource(R.string.share_chooser_title)
            ImageShareActions(key, exportState, onAction = { action ->
                viewModel.imageShare.perform(key, action) {
                    exportShareImage(context, key, action, chooserTitle, options.format.exportWidth, options.format.exportHeight, compositionContext) {
                        ShareableResultCard(data = cardData, theme = theme, options = options)
                    }
                }
            }, onCancel = viewModel.imageShare::cancel, modifier = Modifier.padding(horizontal = 32.dp))

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Horizontal row of color swatches for picking the card theme.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSelector(
    selectedTheme: ShareCardTheme,
    onThemeSelected: (ShareCardTheme) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ShareCardTheme.entries.forEach { theme ->
            val isSelected = theme == selectedTheme
            val label = stringResource(theme.displayNameRes)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(theme.swatchColor)
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.5.dp,
                                color = AccentBlue,
                                shape = CircleShape
                            )
                        } else {
                            Modifier.border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.15f),
                                shape = CircleShape
                            )
                        }
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onThemeSelected(theme) })
                    .semantics { contentDescription = label }
                    .testTag("image-theme-${theme.name}"),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
