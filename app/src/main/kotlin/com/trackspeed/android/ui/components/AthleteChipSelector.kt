package com.trackspeed.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardBackground = Color(0xFF2C2C2E)
private val TextSecondary = Color(0xFF8E8E93)
private val AccentBlue = Color(0xFF0A84FF)
private val SurfaceColor = Color(0xFF3A3A3C)
private val BorderColor = Color(0xFF48484A)

/**
 * Data class representing an athlete for chip display.
 */
data class AthleteChipData(
    val id: String,
    val name: String,
    val color: Color,
    val runCount: Int? = null
)

/**
 * Multi-select horizontal chip row for athletes.
 * Shows colored dot per athlete, name, optional run count,
 * and an add button at the end.
 * Matches iOS AthleteChipSelector component.
 *
 * @param athletes List of athletes to display as chips.
 * @param selectedIds Set of currently selected athlete IDs.
 * @param onAthleteToggle Called when an athlete chip is tapped.
 * @param onAddAthlete Called when the add button is tapped. Null to hide the button.
 * @param modifier Modifier.
 */
@Composable
fun AthleteChipSelector(
    athletes: List<AthleteChipData>,
    selectedIds: Set<String>,
    onAthleteToggle: (String) -> Unit,
    onAddAthlete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ATHLETE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    fontSize = 10.sp
                ),
                color = TextSecondary
            )

            if (onAddAthlete != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onAddAthlete() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Add athlete",
                        tint = AccentBlue,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "Add",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = AccentBlue
                    )
                }
            }
        }

        // Horizontally scrolling chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.width(0.dp)) // Start padding

            // "No Athlete" chip
            AthleteChip(
                name = "No Athlete",
                initial = "?",
                chipColor = Color(0xFF8E8E93),
                isSelected = selectedIds.isEmpty(),
                runCount = null,
                onClick = {
                    // Deselect all when "No Athlete" is tapped
                    onAthleteToggle("")
                }
            )

            // Athlete chips
            athletes.forEach { athlete ->
                AthleteChip(
                    name = athlete.name,
                    initial = athlete.name.take(1),
                    chipColor = athlete.color,
                    isSelected = selectedIds.contains(athlete.id),
                    runCount = athlete.runCount,
                    onClick = { onAthleteToggle(athlete.id) }
                )
            }

            Spacer(modifier = Modifier.width(0.dp)) // End padding
        }
    }
}

/**
 * Individual athlete chip with avatar, name, and optional run count.
 */
@Composable
private fun AthleteChip(
    name: String,
    initial: String,
    chipColor: Color,
    isSelected: Boolean,
    runCount: Int?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (isSelected) {
                    Modifier
                        .background(chipColor)
                        .border(2.dp, chipColor, RoundedCornerShape(50))
                } else {
                    Modifier
                        .background(SurfaceColor)
                        .border(1.dp, BorderColor, RoundedCornerShape(50))
                }
            )
            .clickable { onClick() }
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Avatar circle
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isSelected) Color.White.copy(alpha = 0.2f) else chipColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                ),
                color = if (isSelected) Color.White else Color.White
            )
        }

        // Name and optional run count
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isSelected) Color.White else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (runCount != null) {
                Text(
                    text = "$runCount run${if (runCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = if (isSelected) Color.White.copy(alpha = 0.7f) else TextSecondary
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AthleteChipSelectorPreview() {
    AthleteChipSelector(
        athletes = listOf(
            AthleteChipData("1", "Usain Bolt", Color(0xFF0A84FF), runCount = 12),
            AthleteChipData("2", "Elaine Thompson", Color(0xFFBF5AF2), runCount = 8),
            AthleteChipData("3", "Noah Lyles", Color(0xFF30D158), runCount = 3)
        ),
        selectedIds = setOf("1"),
        onAthleteToggle = {},
        onAddAthlete = {},
        modifier = Modifier.padding(16.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AthleteChipSelectorEmptyPreview() {
    AthleteChipSelector(
        athletes = emptyList(),
        selectedIds = emptySet(),
        onAthleteToggle = {},
        onAddAthlete = {},
        modifier = Modifier.padding(16.dp)
    )
}
