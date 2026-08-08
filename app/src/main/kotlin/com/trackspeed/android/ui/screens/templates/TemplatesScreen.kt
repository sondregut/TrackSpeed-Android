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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.model.TestPreset
import com.trackspeed.android.model.TestPresetCategory
import com.trackspeed.android.ui.theme.*

private val AccentGreen = Color(0xFF30D158)
private val AccentOrange = Color(0xFFFF9500)
private val AccentPurple = Color(0xFFAF52DE)

@Composable
private fun TestPresetCategory.accentColor(): Color = when (this) {
    TestPresetCategory.ACCELERATION -> AccentGreen
    TestPresetCategory.MAX_SPEED -> AccentBlue
    TestPresetCategory.AGILITY -> AccentOrange
    TestPresetCategory.COMBINE -> AccentPurple
}

private fun TestPreset.icon(): ImageVector = when (iconKey) {
    "bolt" -> Icons.Outlined.ElectricBolt
    "pole-vault" -> Icons.Outlined.NorthEast
    "sportscourt" -> Icons.Outlined.EmojiEvents
    "swap", "triangle" -> Icons.Outlined.SwapHoriz
    "repeat" -> Icons.Outlined.Timer
    "figure.run" -> Icons.AutoMirrored.Filled.DirectionsRun
    else -> Icons.Outlined.RocketLaunch
}

@Composable
fun TemplatesScreen(
    onTemplateClick: (distance: Double, startType: String, minPhones: Int, presetId: String?) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredTemplates = remember(searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            TestPreset.all
        } else {
            TestPreset.all.filter { preset ->
                preset.name.contains(query, ignoreCase = true) ||
                    preset.shortName.contains(query, ignoreCase = true) ||
                    preset.category.displayName.contains(query, ignoreCase = true)
            }
        }
    }
    val templatesByCategory = filteredTemplates.groupBy { it.category }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Title
        item {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                text = stringResource(R.string.templates_title),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                ),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.templates_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(32.dp))
            TemplateSearchField(
                query = searchQuery,
                onQueryChanged = { searchQuery = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Category sections in defined order
        val categoryOrder = listOf(
            TestPresetCategory.ACCELERATION,
            TestPresetCategory.MAX_SPEED,
            TestPresetCategory.AGILITY,
            TestPresetCategory.COMBINE
        )

        for (category in categoryOrder) {
            val templates = templatesByCategory[category] ?: continue

            item(key = "header_${category.name}") {
                SectionHeader(title = category.displayName, color = category.accentColor())
                Spacer(modifier = Modifier.height(12.dp))
            }

            items(
                items = templates,
                key = { it.id }
            ) { template ->
                TemplateCard(
                    template = template,
                    onClick = {
                        onTemplateClick(
                            template.distance,
                            template.defaultStartType.rawValue,
                            template.minPhones,
                            template.id
                        )
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item(key = "spacer_${category.name}") {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (filteredTemplates.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.templates_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier,
        placeholder = {
            Text(
                text = stringResource(R.string.templates_search_placeholder),
                color = TextSecondary
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.templates_search_cd),
                tint = TextSecondary
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.templates_clear_search_cd),
                        tint = TextSecondary
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedContainerColor = SurfaceDark,
            unfocusedContainerColor = SurfaceDark,
            disabledContainerColor = SurfaceDark,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AccentBlue
        )
    )
}

@Composable
private fun SectionHeader(title: String, color: Color = TextSecondary) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            ),
            color = TextMuted
        )
    }
}

@Composable
private fun TemplateCard(
    template: TestPreset,
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
                    imageVector = template.icon(),
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
                    if (template.distance > 0.0) {
                        DistanceBadge(
                            text = template.shortDistance,
                            color = accentColor
                        )
                    }
                    PhoneCountBadge(minPhones = template.minPhones, maxPhones = template.maxPhones)
                    StartTypeBadge(
                        text = template.defaultStartType.shortName
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = template.tips.firstOrNull().orEmpty(),
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
private fun PhoneCountBadge(
    minPhones: Int,
    maxPhones: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = BorderSubtle
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.PhoneAndroid,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = if (minPhones == maxPhones) "$minPhones" else "$minPhones-$maxPhones",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp
                ),
                color = TextSecondary
            )
        }
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
