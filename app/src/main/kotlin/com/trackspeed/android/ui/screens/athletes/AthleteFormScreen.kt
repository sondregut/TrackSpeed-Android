package com.trackspeed.android.ui.screens.athletes

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.ui.theme.TrackSpeedTheme

private val PureBlack = Color(0xFF000000)
private val CardBg = Color(0xFF2C2C2E)
private val TextSecondary = Color(0xFF8E8E93)
private val AccentBlue = Color(0xFF0A84FF)
private val DeleteRed = Color(0xFFFF453A)

private val presetColors = listOf(
    "red" to Color(0xFFF44336),
    "orange" to Color(0xFFFF9800),
    "yellow" to Color(0xFFFFEB3B),
    "green" to Color(0xFF4CAF50),
    "blue" to Color(0xFF2196F3),
    "purple" to Color(0xFF9C27B0),
    "pink" to Color(0xFFE91E63),
    "teal" to Color(0xFF009688)
)

@Composable
fun AthleteFormScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: AthleteFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    AthleteFormContent(
        uiState = uiState,
        onNameChanged = viewModel::onNameChanged,
        onNicknameChanged = viewModel::onNicknameChanged,
        onColorSelected = viewModel::onColorSelected,
        onSave = { viewModel.save(onNavigateBack) },
        onDelete = { viewModel.delete(onNavigateBack) },
        onCancel = onNavigateBack
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AthleteFormContent(
    uiState: AthleteFormUiState,
    onNameChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onColorSelected: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        // Top bar
        TopAppBar(
            title = {
                Text(
                    text = if (uiState.isEditMode) "Edit Athlete" else "Add Athlete",
                    fontWeight = FontWeight.SemiBold
                )
            },
            navigationIcon = {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = AccentBlue)
                }
            },
            actions = {
                TextButton(
                    onClick = onSave,
                    enabled = uiState.name.isNotBlank()
                ) {
                    Text(
                        "Save",
                        color = if (uiState.name.isNotBlank()) AccentBlue else TextSecondary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PureBlack,
                titleContentColor = Color.White
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Avatar preview
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val selectedColor = presetColors.find { it.first == uiState.selectedColor }?.second
                    ?: presetColors[4].second

                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(selectedColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.name.isBlank()) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    } else {
                        Text(
                            text = uiState.name.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Name section
            SectionLabel("NAME")

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Column {
                    TextField(
                        value = uiState.name,
                        onValueChange = onNameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Full Name", color = TextSecondary) },
                        singleLine = true,
                        colors = textFieldColors()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(1.dp)
                            .background(Color(0xFF3A3A3C))
                    )

                    TextField(
                        value = uiState.nickname,
                        onValueChange = onNicknameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Nickname (optional)", color = TextSecondary) },
                        singleLine = true,
                        colors = textFieldColors()
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Color section
            SectionLabel("COLOR")

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    presetColors.forEach { (name, color) ->
                        val isSelected = uiState.selectedColor == name
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, Color.White, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { onColorSelected(name) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "$name selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Delete button (edit mode only)
            if (uiState.isEditMode) {
                Spacer(modifier = Modifier.height(40.dp))

                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeleteRed.copy(alpha = 0.15f),
                        contentColor = DeleteRed
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Delete Athlete",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Athlete") },
            text = {
                Text("Are you sure you want to delete ${uiState.name}? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = AccentBlue)
                }
            },
            containerColor = CardBg,
            titleContentColor = Color.White,
            textContentColor = TextSecondary
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        ),
        color = TextSecondary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun textFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = AccentBlue,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent
)

// -- Previews --

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000
)
@Composable
private fun AthleteFormAddPreview() {
    TrackSpeedTheme(darkTheme = true) {
        AthleteFormContent(
            uiState = AthleteFormUiState(isLoaded = true),
            onNameChanged = {},
            onNicknameChanged = {},
            onColorSelected = {},
            onSave = {},
            onDelete = {},
            onCancel = {}
        )
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000,
    name = "Form - Edit"
)
@Composable
private fun AthleteFormEditPreview() {
    TrackSpeedTheme(darkTheme = true) {
        AthleteFormContent(
            uiState = AthleteFormUiState(
                name = "John Smith",
                nickname = "Flash",
                selectedColor = "red",
                isEditMode = true,
                isLoaded = true
            ),
            onNameChanged = {},
            onNicknameChanged = {},
            onColorSelected = {},
            onSave = {},
            onDelete = {},
            onCancel = {}
        )
    }
}
