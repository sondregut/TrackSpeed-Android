package com.trackspeed.android.ui.screens.athletes

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.model.StartType
import com.trackspeed.android.ui.theme.*
import com.trackspeed.android.ui.util.localizedDisplayName
import java.util.Locale
import com.trackspeed.android.R

private val DeleteRed = Color(0xFFFF453A)

private val presetColors = listOf(
    "red" to Color(0xFFF44336),
    "orange" to Color(0xFFFF9800),
    "yellow" to Color(0xFFFFEB3B),
    "green" to Color(0xFF4CAF50),
    "blue" to Color(0xFF2196F3),
    "purple" to Color(0xFF9C27B0),
    "pink" to Color(0xFFE91E63),
    "gray" to Color(0xFF8E8E93)
)

@Composable
fun AthleteFormScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: AthleteFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let(viewModel::onPhotoSelected)
    }

    AthleteFormContent(
        uiState = uiState,
        onNameChanged = viewModel::onNameChanged,
        onNicknameChanged = viewModel::onNicknameChanged,
        onColorSelected = viewModel::onColorSelected,
        onPickPhoto = { photoLauncher.launch("image/*") },
        onRemovePhoto = viewModel::removePhoto,
        onBirthdateChanged = viewModel::onBirthdateChanged,
        onGenderSelected = viewModel::onGenderSelected,
        onSave = { viewModel.save(onNavigateBack) },
        onDelete = { viewModel.delete(onNavigateBack) },
        onCancel = onNavigateBack
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun AthleteFormContent(
    uiState: AthleteFormUiState,
    onNameChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onColorSelected: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onBirthdateChanged: (String) -> Unit,
    onGenderSelected: (String?) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
    ) {
        // Top bar
        TopAppBar(
            title = {
                Text(
                    text = stringResource(
                        if (uiState.isEditMode) R.string.athlete_edit else R.string.athlete_add
                    ),
                    fontWeight = FontWeight.SemiBold
                )
            },
            navigationIcon = {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.common_cancel), color = AccentBlue)
                }
            },
            actions = {
                TextButton(
                    onClick = onSave,
                    enabled = uiState.name.isNotBlank()
                ) {
                    Text(
                        stringResource(R.string.run_detail_save),
                        color = if (uiState.name.isNotBlank()) AccentBlue else TextSecondary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = TextPrimary
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
                val avatarBitmap by produceState<Bitmap?>(
                    initialValue = null,
                    key1 = uiState.photoPath
                ) {
                    val loadedBitmap = withContext(Dispatchers.IO) {
                        uiState.photoPath?.let { path ->
                            try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
                        }
                    }
                    value = loadedBitmap
                }

                val currentAvatarBitmap = avatarBitmap
                if (currentAvatarBitmap != null) {
                    Image(
                        bitmap = currentAvatarBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .border(3.dp, selectedColor, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
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

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(onClick = onPickPhoto) {
                    Text(
                        text = stringResource(
                            if (uiState.photoPath == null) {
                                R.string.athlete_add_photo
                            } else {
                                R.string.athlete_change_photo
                            }
                        ),
                        color = AccentBlue
                    )
                }

                if (uiState.photoPath != null) {
                    TextButton(onClick = onRemovePhoto) {
                        Text(stringResource(R.string.profile_photo_remove), color = DeleteRed)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Name section
            SectionLabel(stringResource(R.string.athlete_section_name))

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column {
                    TextField(
                        value = uiState.name,
                        onValueChange = onNameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.onboarding_profile_name_label), color = TextSecondary) },
                        singleLine = true,
                        colors = textFieldColors()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(1.dp)
                            .background(BorderSubtle)
                    )

                    TextField(
                        value = uiState.nickname,
                        onValueChange = onNicknameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(stringResource(R.string.athlete_nickname_optional), color = TextSecondary)
                        },
                        singleLine = true,
                        colors = textFieldColors()
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Profile section
            SectionLabel(stringResource(R.string.athlete_section_profile))

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column {
                    TextField(
                        value = uiState.birthdateText,
                        onValueChange = onBirthdateChanged,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(stringResource(R.string.athlete_birthdate_hint), color = TextSecondary)
                        },
                        singleLine = true,
                        isError = uiState.hasBirthdateError,
                        colors = textFieldColors()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(1.dp)
                            .background(BorderSubtle)
                    )

                    GenderPicker(
                        selectedGender = uiState.gender,
                        onGenderSelected = onGenderSelected
                    )
                }
            }

            if (uiState.hasBirthdateError) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.athlete_birthdate_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = DeleteRed,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Color section
            SectionLabel(stringResource(R.string.athlete_section_color))

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
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
                                    contentDescription = stringResource(
                                        R.string.athlete_color_selected_cd,
                                        localizedColorName(name)
                                    ),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.isEditMode) {
                Spacer(modifier = Modifier.height(28.dp))

                SectionLabel(stringResource(R.string.athlete_section_personal_bests))

                Spacer(modifier = Modifier.height(8.dp))

                PersonalBestsCard(personalBests = uiState.personalBests)
            }

            // Delete button (edit mode only)
            if (uiState.isEditMode) {
                Spacer(modifier = Modifier.height(40.dp))

                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
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
                            text = stringResource(R.string.athlete_delete),
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
            title = { Text(stringResource(R.string.athlete_delete)) },
            text = {
                Text(stringResource(R.string.athlete_delete_confirmation, uiState.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.run_detail_delete_confirm), color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = AccentBlue)
                }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
}

@Composable
private fun PersonalBestsCard(personalBests: Map<String, Double>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        if (personalBests.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_no_personal_bests),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(16.dp)
            )
            return@Card
        }

        Column(modifier = Modifier.padding(16.dp)) {
            groupedPersonalBests(personalBests).forEachIndexed { groupIndex, group ->
                if (groupIndex > 0) {
                    Spacer(modifier = Modifier.height(14.dp))
                }

                Text(
                    text = group.startType.localizedDisplayName(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                group.entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.distanceLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(
                                R.string.common_seconds_value,
                                String.format(Locale.getDefault(), "%.3f", entry.timeSeconds)
                            ),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = AccentGreen
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenderPicker(
    selectedGender: String?,
    onGenderSelected: (String?) -> Unit
) {
    val options = listOf(
        "male" to stringResource(R.string.athlete_gender_male),
        "female" to stringResource(R.string.athlete_gender_female),
        "other" to stringResource(R.string.athlete_gender_other)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.athlete_gender),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, label) ->
                val selected = selectedGender == value
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) AccentBlue.copy(alpha = 0.16f) else BorderSubtle)
                        .border(
                            width = 1.dp,
                            color = if (selected) AccentBlue else Color.Transparent,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            onGenderSelected(if (selected) null else value)
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = if (selected) AccentBlue else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun localizedColorName(colorName: String): String = stringResource(
    when (colorName) {
        "red" -> R.string.athlete_color_red
        "orange" -> R.string.athlete_color_orange
        "yellow" -> R.string.athlete_color_yellow
        "green" -> R.string.athlete_color_green
        "blue" -> R.string.athlete_color_blue
        "purple" -> R.string.athlete_color_purple
        "pink" -> R.string.athlete_color_pink
        else -> R.string.athlete_color_gray
    }
)

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

private data class PersonalBestGroup(
    val startType: StartType,
    val entries: List<PersonalBestEntry>
)

private data class PersonalBestEntry(
    val distanceLabel: String,
    val timeSeconds: Double
)

private fun groupedPersonalBests(personalBests: Map<String, Double>): List<PersonalBestGroup> {
    return personalBests
        .mapNotNull { (key, time) ->
            parsePersonalBestKey(key)?.let { (startType, distanceLabel) ->
                startType to PersonalBestEntry(distanceLabel, time)
            }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .map { (startType, entries) ->
            PersonalBestGroup(
                startType = startType,
                entries = entries.sortedBy { numericDistance(it.distanceLabel) }
            )
        }
        .sortedBy { it.startType.rawValue }
}

private fun parsePersonalBestKey(key: String): Pair<StartType, String>? {
    val parts = key.split("_", limit = 2)
    return if (parts.size == 2) {
        StartType.fromRawValue(parts[0]) to parts[1]
    } else if (key.endsWith("m")) {
        StartType.FLYING to key
    } else {
        null
    }
}

private fun numericDistance(label: String): Double {
    return label.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: Double.MAX_VALUE
}

@Composable
private fun textFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
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
    TrackSpeedTheme() {
        AthleteFormContent(
            uiState = AthleteFormUiState(isLoaded = true),
            onNameChanged = {},
            onNicknameChanged = {},
            onColorSelected = {},
            onPickPhoto = {},
            onRemovePhoto = {},
            onBirthdateChanged = {},
            onGenderSelected = {},
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
    TrackSpeedTheme() {
        AthleteFormContent(
            uiState = AthleteFormUiState(
                name = "John Smith",
                nickname = "Flash",
                selectedColor = "red",
                birthdateText = "1998-04-12",
                gender = "male",
                isEditMode = true,
                isLoaded = true
            ),
            onNameChanged = {},
            onNicknameChanged = {},
            onColorSelected = {},
            onPickPhoto = {},
            onRemovePhoto = {},
            onBirthdateChanged = {},
            onGenderSelected = {},
            onSave = {},
            onDelete = {},
            onCancel = {}
        )
    }
}
