package com.trackspeed.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Accent colors
private val AccentBlue = Color(0xFF0A84FF)
private val AccentGreen = Color(0xFF30D158)
private val SurfaceDark = Color(0xFF1C1C1E)
private val CardDark = Color(0xFF2C2C2E)

/**
 * Start mode options matching the iOS start type selection.
 */
enum class StartMode(
    val displayName: String,
    val description: String,
    val icon: ImageVector
) {
    FLYING(
        displayName = "Flying",
        description = "Gate-triggered start. Timer starts when athlete crosses the gate line.",
        icon = Icons.AutoMirrored.Filled.DirectionsRun
    ),
    COUNTDOWN(
        displayName = "Countdown",
        description = "3... 2... 1... BEEP! Visual countdown with automatic start.",
        icon = Icons.Default.Timer
    ),
    VOICE(
        displayName = "Voice Command",
        description = "\"On your marks... Set... GO!\" Spoken commands like a real starter.",
        icon = Icons.Default.Mic
    ),
    TOUCH(
        displayName = "Touch Start",
        description = "Touch screen, hold, then lift finger to start the timer.",
        icon = Icons.Default.TouchApp
    ),
    INFRAME(
        displayName = "In-Frame",
        description = "Stand in front of camera, step away to start.",
        icon = Icons.Default.PersonOff
    );

    companion object {
        fun fromString(value: String): StartMode = when {
            value.equals("standard", ignoreCase = true) -> FLYING
            else -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FLYING
        }
    }
}

/**
 * A bottom sheet dialog that lets the user pick their preferred start mode.
 *
 * Displays four options (Standard, Countdown, Voice, Touch) with icons and
 * descriptions. The currently selected mode is highlighted with the accent color.
 *
 * @param currentMode The currently selected start mode.
 * @param onModeSelected Called when the user selects a mode.
 * @param onDismiss Called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartOverlaySelector(
    currentMode: StartMode,
    onModeSelected: (StartMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = {
            Box(
                modifier = Modifier.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Title
            Text(
                text = "Start Mode",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Mode options
            StartMode.entries.forEachIndexed { index, mode ->
                StartModeOption(
                    mode = mode,
                    isSelected = mode == currentMode,
                    onClick = {
                        onModeSelected(mode)
                        onDismiss()
                    }
                )
                if (index < StartMode.entries.lastIndex) {
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StartModeOption(
    mode: StartMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val selectedColor = AccentBlue
    val iconTint = if (isSelected) selectedColor else Color.White.copy(alpha = 0.7f)
    val titleColor = if (isSelected) selectedColor else Color.White
    val bgColor = if (isSelected) selectedColor.copy(alpha = 0.1f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isSelected) selectedColor.copy(alpha = 0.15f)
                    else CardDark
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.displayName,
                color = titleColor,
                fontSize = 17.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = mode.description,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        // Selection indicator
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(selectedColor)
            )
        }
    }
}
