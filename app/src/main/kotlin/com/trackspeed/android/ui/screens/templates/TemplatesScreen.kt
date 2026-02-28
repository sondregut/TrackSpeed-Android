package com.trackspeed.android.ui.screens.templates

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.ui.theme.*

private val AccentGreen = Color(0xFF30D158)
private val AccentOrange = Color(0xFFFF9500)
private val AccentPurple = Color(0xFFAF52DE)

private enum class TemplateCategory(val displayName: String) {
    ACCELERATION("ACCELERATION"),
    SPEED("MAX SPEED"),
    AGILITY("AGILITY"),
    COMBINE("COMBINE")
}

private data class WorkoutTemplate(
    val name: String,
    val distance: Double,
    val startType: String,
    val description: String,
    val icon: ImageVector,
    val category: TemplateCategory,
    val minPhones: Int = 1
)

private val builtInTemplates = listOf(
    // --- Acceleration (Green) ---
    WorkoutTemplate(
        name = "10m Acceleration",
        distance = 10.0,
        startType = "standing",
        description = "Short acceleration test",
        icon = Icons.Outlined.RocketLaunch,
        category = TemplateCategory.ACCELERATION,
        minPhones = 2
    ),
    WorkoutTemplate(
        name = "20m Sprint",
        distance = 20.0,
        startType = "standing",
        description = "Acceleration phase evaluation",
        icon = Icons.Outlined.RocketLaunch,
        category = TemplateCategory.ACCELERATION,
        minPhones = 2
    ),
    WorkoutTemplate(
        name = "30m Sprint",
        distance = 30.0,
        startType = "standing",
        description = "Block start acceleration test",
        icon = Icons.Outlined.RocketLaunch,
        category = TemplateCategory.ACCELERATION,
        minPhones = 2
    ),
    WorkoutTemplate(
        name = "40yd Dash",
        distance = 36.576,
        startType = "standing",
        description = "40-yard dash from standing start",
        icon = Icons.Outlined.RocketLaunch,
        category = TemplateCategory.ACCELERATION,
        minPhones = 2
    ),

    // --- Max Speed (Blue) ---
    WorkoutTemplate(
        name = "Flying 10m",
        distance = 10.0,
        startType = "flying",
        description = "Peak velocity over 10m",
        icon = Icons.Outlined.ElectricBolt,
        category = TemplateCategory.SPEED,
        minPhones = 2
    ),
    WorkoutTemplate(
        name = "Flying 20m",
        distance = 20.0,
        startType = "flying",
        description = "Maximum velocity test",
        icon = Icons.Outlined.ElectricBolt,
        category = TemplateCategory.SPEED,
        minPhones = 2
    ),
    WorkoutTemplate(
        name = "Flying 30m",
        distance = 30.0,
        startType = "flying",
        description = "Extended max velocity test",
        icon = Icons.Outlined.ElectricBolt,
        category = TemplateCategory.SPEED,
        minPhones = 2
    ),
    WorkoutTemplate(
        name = "60m Sprint",
        distance = 60.0,
        startType = "standing",
        description = "Standard 60m sprint",
        icon = Icons.AutoMirrored.Filled.DirectionsRun,
        category = TemplateCategory.SPEED,
        minPhones = 2
    ),
    WorkoutTemplate(
        name = "100m Dash",
        distance = 100.0,
        startType = "standing",
        description = "Full 100m with reaction time",
        icon = Icons.Outlined.Timer,
        category = TemplateCategory.SPEED,
        minPhones = 2
    ),
    WorkoutTemplate(
        name = "200m Sprint",
        distance = 200.0,
        startType = "standing",
        description = "Half-lap speed endurance",
        icon = Icons.Outlined.Timer,
        category = TemplateCategory.SPEED,
        minPhones = 2
    ),

    // --- Agility (Orange) — single-phone in-frame start ---
    WorkoutTemplate(
        name = "Pro Agility (5-10-5)",
        distance = 36.576,
        startType = "standing",
        description = "Short shuttle agility drill",
        icon = Icons.Outlined.SwapHoriz,
        category = TemplateCategory.AGILITY
    ),
    WorkoutTemplate(
        name = "L-Drill",
        distance = 30.0,
        startType = "standing",
        description = "Three-cone agility drill",
        icon = Icons.Outlined.SwapHoriz,
        category = TemplateCategory.AGILITY
    ),

    // --- Combine (Purple) ---
    WorkoutTemplate(
        name = "NFL 40yd",
        distance = 36.576,
        startType = "standing",
        description = "NFL Combine 40-yard dash",
        icon = Icons.Outlined.EmojiEvents,
        category = TemplateCategory.COMBINE,
        minPhones = 2
    )
)

@Composable
private fun TemplateCategory.accentColor(): Color = when (this) {
    TemplateCategory.ACCELERATION -> AccentGreen
    TemplateCategory.SPEED -> AccentBlue
    TemplateCategory.AGILITY -> AccentOrange
    TemplateCategory.COMBINE -> AccentPurple
}

private fun formatDistance(distance: Double): String {
    return if (distance == 36.576) {
        "40 yd"
    } else if (distance == distance.toLong().toDouble()) {
        "${distance.toLong()}m"
    } else {
        "${distance}m"
    }
}

private fun formatStartType(startType: String): String {
    return startType.replaceFirstChar { it.uppercase() }
}

@Composable
fun TemplatesScreen(
    onTemplateClick: (distance: Double, startType: String, minPhones: Int) -> Unit = { _, _, _ -> }
) {
    val templatesByCategory = builtInTemplates.groupBy { it.category }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Title
        item {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                text = "Templates",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                ),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Pre-configured workout setups",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(32.dp))
        }

        // Category sections in defined order
        val categoryOrder = listOf(
            TemplateCategory.ACCELERATION,
            TemplateCategory.SPEED,
            TemplateCategory.AGILITY,
            TemplateCategory.COMBINE
        )

        for (category in categoryOrder) {
            val templates = templatesByCategory[category] ?: continue

            item(key = "header_${category.name}") {
                SectionHeader(title = category.displayName)
                Spacer(modifier = Modifier.height(12.dp))
            }

            items(
                items = templates,
                key = { it.name }
            ) { template ->
                TemplateCard(
                    template = template,
                    onClick = { onTemplateClick(template.distance, template.startType, template.minPhones) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item(key = "spacer_${category.name}") {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Custom templates section
        item {
            Spacer(modifier = Modifier.height(12.dp))
            SectionHeader(title = "CUSTOM TEMPLATES")
            Spacer(modifier = Modifier.height(12.dp))
            AddTemplateCard()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        ),
        color = TextSecondary
    )
}

@Composable
private fun TemplateCard(
    template: WorkoutTemplate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = template.category.accentColor()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .gunmetalCard()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon with accent background
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = template.icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DistanceBadge(
                        text = formatDistance(template.distance),
                        color = accentColor
                    )
                    StartTypeBadge(
                        text = formatStartType(template.startType)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Chevron
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Start template",
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DistanceBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp
            ),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun StartTypeBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = BorderSubtle
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp
            ),
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun AddTemplateCard(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .gunmetalCard()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Plus icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AccentPurple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Add Template",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Create a custom workout template",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // Coming soon badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AccentPurple.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "Soon",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    ),
                    color = AccentPurple,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TemplatesScreenPreview() {
    TrackSpeedTheme() {
        TemplatesScreen()
    }
}

@Preview(
    showBackground = true,
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun TemplatesScreenSmallPreview() {
    TrackSpeedTheme() {
        TemplatesScreen()
    }
}
