package com.trackspeed.android.ui.screens.athletes

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.ui.theme.*

private val CardBg = SurfaceDark
private val TextSecondary = com.trackspeed.android.ui.theme.TextSecondary
private val AccentBlue = AccentNavy
private val DeleteRed = Color(0xFFFF453A)

@Composable
fun AthleteListScreen(
    onAthleteClick: (String) -> Unit = {},
    onAddClick: () -> Unit = {},
    viewModel: AthleteListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    AthleteListContent(
        uiState = uiState,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onAthleteClick = onAthleteClick,
        onAddClick = onAddClick,
        onDeleteAthlete = viewModel::deleteAthlete
    )
}

@Composable
private fun AthleteListContent(
    uiState: AthleteListUiState,
    onSearchQueryChanged: (String) -> Unit,
    onAthleteClick: (String) -> Unit,
    onAddClick: () -> Unit,
    onDeleteAthlete: (AthleteEntity) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().gradientBackground()) {
    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (uiState.athletes.isNotEmpty() || uiState.searchQuery.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onAddClick,
                    containerColor = AccentBlue,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add athlete"
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Athletes",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.athletes.isEmpty() && uiState.searchQuery.isBlank()) {
                EmptyState(onAddClick = onAddClick)
            } else {
                // Search bar
                SearchBar(
                    query = uiState.searchQuery,
                    onQueryChanged = onSearchQueryChanged
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.athletes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No matching athletes",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )
                    }
                } else {
                    AthleteList(
                        athletes = uiState.athletes,
                        onAthleteClick = onAthleteClick,
                        onDeleteAthlete = onDeleteAthlete
                    )
                }
            }
        }
    }
    } // close Box
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text("Search athletes", color = TextSecondary)
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = TextSecondary
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CardBg,
            unfocusedContainerColor = CardBg,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AccentBlue,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AthleteList(
    athletes: List<AthleteEntity>,
    onAthleteClick: (String) -> Unit,
    onDeleteAthlete: (AthleteEntity) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = athletes,
            key = { it.id }
        ) { athlete ->
            val currentAthlete by rememberUpdatedState(athlete)
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) {
                        onDeleteAthlete(currentAthlete)
                        true
                    } else {
                        false
                    }
                }
            )

            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    val color by animateColorAsState(
                        targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                            DeleteRed
                        } else {
                            Color.Transparent
                        },
                        label = "swipe-bg"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp))
                            .background(color)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color.White
                        )
                    }
                },
                enableDismissFromStartToEnd = false
            ) {
                AthleteRow(
                    athlete = athlete,
                    onClick = { onAthleteClick(athlete.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun AthleteRow(
    athlete: AthleteEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with color and initial
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(athleteColor(athlete.color)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = athlete.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = athlete.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!athlete.nickname.isNullOrBlank()) {
                    Text(
                        text = athlete.nickname,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = TextSecondary
            )
        }
    }
}

@Composable
private fun EmptyState(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = TextSecondary.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "No Athletes Yet",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Add athletes to track their times\nand personal bests.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Card(
            onClick = onAddClick,
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AccentBlue)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.PersonAdd,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Add Athlete",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
            }
        }
    }
}

internal fun athleteColor(colorName: String): Color {
    return when (colorName.lowercase()) {
        "red" -> Color(0xFFF44336)
        "orange" -> Color(0xFFFF9800)
        "yellow" -> Color(0xFFFFEB3B)
        "green" -> Color(0xFF4CAF50)
        "blue" -> Color(0xFF2196F3)
        "purple" -> Color(0xFF9C27B0)
        "pink" -> Color(0xFFE91E63)
        "teal" -> Color(0xFF009688)
        else -> Color(0xFF2196F3)
    }
}

// -- Previews --

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000
)
@Composable
private fun AthleteListPreview() {
    TrackSpeedTheme(darkTheme = true) {
        AthleteListContent(
            uiState = AthleteListUiState(
                athletes = listOf(
                    AthleteEntity(name = "John Smith", nickname = "Flash", color = "blue"),
                    AthleteEntity(name = "Sarah Connor", nickname = null, color = "red"),
                    AthleteEntity(name = "Mike Johnson", nickname = "Speed", color = "green")
                )
            ),
            onSearchQueryChanged = {},
            onAthleteClick = {},
            onAddClick = {},
            onDeleteAthlete = {}
        )
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000,
    name = "Athletes - Empty"
)
@Composable
private fun AthleteListEmptyPreview() {
    TrackSpeedTheme(darkTheme = true) {
        AthleteListContent(
            uiState = AthleteListUiState(),
            onSearchQueryChanged = {},
            onAthleteClick = {},
            onAddClick = {},
            onDeleteAthlete = {}
        )
    }
}
