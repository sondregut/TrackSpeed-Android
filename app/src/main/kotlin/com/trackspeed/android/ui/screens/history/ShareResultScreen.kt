package com.trackspeed.android.ui.screens.history

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.ui.components.ComposeCapture
import com.trackspeed.android.ui.components.ShareCardTheme
import com.trackspeed.android.ui.components.ShareableResultCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ScreenBackground = Color(0xFF000000)
private val TopBarBackground = Color(0xFF1C1C1E)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF8E8E93)
private val AccentBlue = Color(0xFF4A90D9)

// Card render size in pixels (1080x1920 = 9:16 for Instagram Stories)
private const val CARD_WIDTH_PX = 1080
private const val CARD_HEIGHT_PX = 1920

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareResultScreen(
    onNavigateBack: () -> Unit,
    viewModel: ShareResultViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = ScreenBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.share_title),
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.share_back_cd),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TopBarBackground,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ScreenBackground)
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TextPrimary)
            }
            return@Scaffold
        }

        val cardData = uiState.cardData
        if (cardData == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ScreenBackground)
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
                .background(ScreenBackground)
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Card preview
            ShareableResultCard(
                data = cardData,
                theme = uiState.selectedTheme,
                modifier = Modifier
                    .width(270.dp)
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
                onThemeSelected = { viewModel.selectTheme(it) },
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Share button
            Button(
                onClick = {
                    scope.launch {
                        val bitmap = withContext(Dispatchers.Main) {
                            ComposeCapture.captureToBitmap(
                                context = context,
                                widthPx = CARD_WIDTH_PX,
                                heightPx = CARD_HEIGHT_PX
                            ) {
                                ShareableResultCard(
                                    data = cardData,
                                    theme = uiState.selectedTheme
                                )
                            }
                        }
                        val uri = ComposeCapture.saveToCacheForSharing(context, bitmap)
                        val intent = ComposeCapture.createShareIntent(uri)
                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser_title)))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.share_button),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Save to gallery button
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val bitmap = withContext(Dispatchers.Main) {
                            ComposeCapture.captureToBitmap(
                                context = context,
                                widthPx = CARD_WIDTH_PX,
                                heightPx = CARD_HEIGHT_PX
                            ) {
                                ShareableResultCard(
                                    data = cardData,
                                    theme = uiState.selectedTheme
                                )
                            }
                        }
                        val success = ComposeCapture.saveToGallery(context, bitmap)
                        if (success) {
                            viewModel.onSavedToGallery()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.share_saved_toast), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.share_save_failed_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    imageVector = if (uiState.isSavedToGallery) Icons.Default.Check else Icons.Outlined.SaveAlt,
                    contentDescription = null,
                    tint = if (uiState.isSavedToGallery) Color(0xFF4CAF50) else TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.isSavedToGallery) stringResource(R.string.share_saved) else stringResource(R.string.share_save_to_gallery),
                    color = if (uiState.isSavedToGallery) Color(0xFF4CAF50) else TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Horizontal row of color swatches for picking the card theme.
 */
@Composable
fun ThemeSelector(
    selectedTheme: ShareCardTheme,
    onThemeSelected: (ShareCardTheme) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShareCardTheme.entries.forEach { theme ->
            val isSelected = theme == selectedTheme
            Box(
                modifier = Modifier
                    .size(40.dp)
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
                    .clickable { onThemeSelected(theme) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.share_theme_selected_cd, theme.displayName),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
