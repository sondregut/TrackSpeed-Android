package com.trackspeed.android.ui.components

import androidx.annotation.StringRes
import com.trackspeed.android.model.StartType
import com.trackspeed.android.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
// Accent colors - use theme values

/**
 * Start mode options matching the iOS start type selection.
 */
enum class StartMode(
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val rawValue: String
) {
    FLYING(
        displayNameRes = R.string.start_type_flying_start,
        descriptionRes = R.string.start_type_flying_full_desc,
        icon = Icons.AutoMirrored.Filled.DirectionsRun,
        rawValue = "flying"
    ),
    TOUCH(
        displayNameRes = R.string.start_type_touch_release,
        descriptionRes = R.string.start_type_touch_release_desc,
        icon = Icons.Default.TouchApp,
        rawValue = "touchRelease"
    ),
    COUNTDOWN(
        displayNameRes = R.string.start_selector_countdown,
        descriptionRes = R.string.start_type_countdown_full_desc,
        icon = Icons.Default.Timer,
        rawValue = "countdown"
    ),
    VOICE(
        displayNameRes = R.string.start_selector_voice,
        descriptionRes = R.string.start_selector_voice_desc,
        icon = Icons.Default.Mic,
        rawValue = "voiceCommand"
    ),
    INFRAME(
        displayNameRes = R.string.start_type_inframe_start,
        descriptionRes = R.string.start_type_inframe_gate_desc,
        icon = Icons.Default.CenterFocusStrong,
        rawValue = "inFrame"
    ),
    MOTION(R.string.start_type_motion, R.string.camera_start_motion_description, Icons.Default.CenterFocusStrong, "motionStart"),
    REACTION(R.string.start_type_reaction, R.string.race_remote_start_required, Icons.Default.Timer, "reactionStart");

    companion object {
        val localModes get() = entries.filter { StartType.fromRawValue(it.rawValue).supportsLocalStart }
        fun fromString(value: String): StartMode {
            val startType = StartType.fromRawValue(value)
            return entries.firstOrNull { it.rawValue == startType.rawValue } ?: FLYING
        }
    }
}

/**
 * A bottom sheet dialog that lets the user pick their preferred start mode.
 *
 * Displays the five canonical start types with icons and
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
    availableModes: List<StartMode> = StartMode.localModes,
    showProBadges: Boolean = false,
    onModeSelected: (StartMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .selectableGroup()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Title
            Text(
                text = stringResource(R.string.start_selector_title),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Mode options
            availableModes.forEachIndexed { index, mode ->
                StartModeOption(
                    mode = mode,
                    isSelected = mode == currentMode,
                    showProBadge = showProBadges && StartType.fromRawValue(mode.rawValue).isPro,
                    onClick = {
                        onModeSelected(mode)
                        onDismiss()
                    }
                )
                if (index < availableModes.lastIndex) {
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
    showProBadge: Boolean,
    onClick: () -> Unit
) {
    val selectedColor = AccentBlue
    val iconTint = if (isSelected) selectedColor else TextSecondary
    val titleColor = if (isSelected) selectedColor else TextPrimary
    val bgColor = if (isSelected) selectedColor.copy(alpha = 0.1f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton
            )
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
                    else CardBackground
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(mode.displayNameRes),
                    color = titleColor,
                    fontSize = 17.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                )
                if (showProBadge) {
                    Text(
                        text = stringResource(R.string.common_pro_badge),
                        color = AccentBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentBlue.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(mode.descriptionRes),
                color = TextSecondary,
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
