package com.trackspeed.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.ui.theme.AccentNavy
import com.trackspeed.android.ui.theme.SurfaceDark
import com.trackspeed.android.ui.theme.TextMuted
import com.trackspeed.android.ui.theme.TextSecondary

/**
 * Start type options matching iOS StartType enum.
 */
enum class StartTypeOption(val id: String, val displayName: String, val description: String) {
    STANDING("standing", "Standing", "Camera detects athlete crossing"),
    FLYING("flying", "Flying", "Timer starts on first crossing"),
    BLOCK("block", "Block", "Timer starts from block release")
}

/**
 * Horizontal row of selectable start type chips.
 * Reusable across session setup and settings screens.
 * Matches iOS StartTypeSelectorRow component.
 *
 * @param selectedType Currently selected start type ID (e.g. "standing", "flying", "block").
 * @param onTypeSelected Called with the start type ID when a chip is selected.
 * @param options List of start type options to display. Defaults to all standard types.
 * @param modifier Modifier.
 */
@Composable
fun StartTypeSelectorRow(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    options: List<StartTypeOption> = StartTypeOption.entries,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = selectedType == option.id

            FilterChip(
                selected = isSelected,
                onClick = { onTypeSelected(option.id) },
                label = {
                    Text(
                        text = option.displayName,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp,
                        color = if (isSelected) Color.White else TextSecondary
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SurfaceDark,
                    selectedContainerColor = AccentNavy,
                    labelColor = TextSecondary,
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = TextMuted,
                    selectedBorderColor = AccentNavy,
                    enabled = true,
                    selected = isSelected
                )
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun StartTypeSelectorRowPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StartTypeSelectorRow(
            selectedType = "standing",
            onTypeSelected = {}
        )
        StartTypeSelectorRow(
            selectedType = "flying",
            onTypeSelected = {}
        )
    }
}
