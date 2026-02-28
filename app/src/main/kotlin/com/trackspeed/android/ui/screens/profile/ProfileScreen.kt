package com.trackspeed.android.ui.screens.profile

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

// Design tokens (theme colors imported from ui.theme)
private val ProGreen = Color(0xFF00E676)
private val DangerRed = Color(0xFFFF453A)

@Composable
fun ProfileScreen(
    onPaywallClick: () -> Unit = {},
    onAthletesClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onReferralClick: () -> Unit = {},
    onWindAdjustmentClick: () -> Unit = {},
    onDistanceConverterClick: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Photo picker launcher (gallery)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onAvatarPhotoSelected(it) }
    }

    // Camera capture launcher
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        viewModel.onAvatarPhotoCaptured(success, captureUri)
    }

    // Photo source dialog state
    var showPhotoSourceDialog by remember { mutableStateOf(false) }

    ProfileScreenContent(
        uiState = uiState,
        onPaywallClick = onPaywallClick,
        onAthletesClick = onAthletesClick,
        onSettingsClick = onSettingsClick,
        onReferralClick = onReferralClick,
        onWindAdjustmentClick = onWindAdjustmentClick,
        onDistanceConverterClick = onDistanceConverterClick,
        onAvatarClick = {
            if (uiState.avatarPhotoPath != null) {
                // Has existing photo -- show options to change/remove
                showPhotoSourceDialog = true
            } else {
                // No photo yet -- go straight to gallery
                galleryLauncher.launch("image/*")
            }
        },
        onNameChanged = viewModel::updateUserName,
        onSignOutClick = viewModel::showSignOutDialog,
        onDeleteAccountClick = viewModel::showDeleteAccountDialog,
        onConfirmSignOut = viewModel::confirmSignOut,
        onDismissSignOut = viewModel::dismissSignOutDialog,
        onConfirmDeleteAccount = viewModel::confirmDeleteAccount,
        onDismissDeleteAccount = viewModel::dismissDeleteAccountDialog
    )

    // Photo source selection dialog
    if (showPhotoSourceDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceDialog = false },
            title = { Text(stringResource(R.string.profile_photo_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.profile_photo_choose_option), color = TextSecondary) },
            containerColor = SurfaceDark,
            confirmButton = {
                TextButton(onClick = {
                    showPhotoSourceDialog = false
                    galleryLauncher.launch("image/*")
                }) {
                    Text(stringResource(R.string.profile_photo_choose_new), color = AccentNavy)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showPhotoSourceDialog = false
                        val uri = viewModel.createTempPhotoUri()
                        captureUri = uri
                        cameraLauncher.launch(uri)
                    }) {
                        Text(stringResource(R.string.profile_photo_take), color = AccentNavy)
                    }
                    TextButton(onClick = {
                        showPhotoSourceDialog = false
                        viewModel.removeAvatarPhoto()
                    }) {
                        Text(stringResource(R.string.profile_photo_remove), color = DangerRed)
                    }
                }
            }
        )
    }
}

@Composable
private fun ProfileScreenContent(
    uiState: ProfileUiState,
    onPaywallClick: () -> Unit = {},
    onAthletesClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onReferralClick: () -> Unit = {},
    onWindAdjustmentClick: () -> Unit = {},
    onDistanceConverterClick: () -> Unit = {},
    onAvatarClick: () -> Unit = {},
    onNameChanged: (String) -> Unit = {},
    onSignOutClick: () -> Unit = {},
    onDeleteAccountClick: () -> Unit = {},
    onConfirmSignOut: () -> Unit = {},
    onDismissSignOut: () -> Unit = {},
    onConfirmDeleteAccount: () -> Unit = {},
    onDismissDeleteAccount: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // -- Top Bar with Settings Gear --
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.common_settings),
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // -- Profile Header with Avatar + Name --
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar with camera badge
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                val avatarBitmap by produceState<Bitmap?>(null, uiState.avatarPhotoPath) {
                    value = withContext(Dispatchers.IO) {
                        uiState.avatarPhotoPath?.let { path ->
                            try {
                                BitmapFactory.decodeFile(path)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }

                val currentAvatarBitmap = avatarBitmap
                if (currentAvatarBitmap != null) {
                    Image(
                        bitmap = currentAvatarBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.profile_avatar_cd),
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(AccentNavy.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.userName.isNotEmpty()) {
                            Text(
                                text = uiState.userName.take(1).uppercase(),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = AccentNavy
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = stringResource(R.string.profile_avatar_cd),
                                modifier = Modifier.size(40.dp),
                                tint = AccentNavy
                            )
                        }
                    }
                }

                // Camera badge overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CameraAlt,
                        contentDescription = stringResource(R.string.profile_change_photo_cd),
                        modifier = Modifier.size(14.dp),
                        tint = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Editable name field
            var nameText by remember(uiState.userName) {
                mutableStateOf(uiState.userName)
            }

            BasicTextField(
                value = nameText,
                onValueChange = { newName ->
                    nameText = newName
                    onNameChanged(newName)
                },
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
                singleLine = true,
                cursorBrush = SolidColor(AccentNavy),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (nameText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.profile_name_placeholder),
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = TextSecondary
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Account type + sprint counter
            Text(
                text = stringResource(R.string.profile_account_type_guest),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = stringResource(R.string.profile_sprints_recorded, uiState.totalRuns),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = TextSecondary.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // -- Subscription Status / Pro Upgrade Banner --
        if (uiState.isProUser) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ProGreen.copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = ProGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.profile_trackspeed_pro),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = ProGreen
                    )
                }
            }
        } else {
            Card(
                onClick = onPaywallClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ProGreen)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.profile_upgrade_to_pro),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.Black
                        )
                        Text(
                            text = stringResource(R.string.profile_upgrade_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black.copy(alpha = 0.7f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // -- Invite Friends --
        Card(
            onClick = onReferralClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = AccentNavy
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profile_invite_friends),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.profile_invite_friends_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- Stats Row --
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                label = stringResource(R.string.profile_stat_sessions),
                value = uiState.totalSessions.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = stringResource(R.string.profile_stat_runs),
                value = uiState.totalRuns.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = stringResource(R.string.profile_stat_best_time),
                value = uiState.bestTimeSeconds?.let { formatTime(it) } ?: stringResource(R.string.profile_stat_no_value),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- Personal Bests --
        SectionHeader(stringResource(R.string.profile_section_personal_bests))

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.personalBests.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEvents,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = TextSecondary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.profile_no_personal_bests),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.profile_no_personal_bests_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    uiState.personalBests.forEachIndexed { index, pb ->
                        PersonalBestRow(
                            distanceLabel = pb.distanceLabel,
                            timeSeconds = pb.timeSeconds
                        )
                        if (index < uiState.personalBests.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = BorderSubtle
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- Athletes --
        SectionHeader(stringResource(R.string.profile_section_athletes))

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            onClick = onAthletesClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Groups,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = TextSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profile_manage_athletes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    if (uiState.athleteCount > 0) {
                        Text(
                            text = if (uiState.athleteCount == 1)
                                stringResource(R.string.profile_athlete_count_singular, uiState.athleteCount)
                            else
                                stringResource(R.string.profile_athlete_count, uiState.athleteCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- Sprint Calculators --
        SectionHeader(stringResource(R.string.profile_section_calculators))

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Column {
                // Wind Adjustment
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onWindAdjustmentClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Air,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.profile_wind_adjustment),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.profile_wind_adjustment_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                SettingsDivider()

                // Distance Converter
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDistanceConverterClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Straighten,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.profile_distance_converter),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.profile_distance_converter_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- Account Management Section --
        SectionHeader(stringResource(R.string.profile_section_account))

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Column {
                if (uiState.isSignedIn) {
                    // Sign Out row
                    AccountRow(
                        icon = Icons.Outlined.Logout,
                        label = stringResource(R.string.profile_sign_out),
                        labelColor = TextPrimary,
                        iconTint = TextSecondary,
                        onClick = onSignOutClick
                    )

                    SettingsDivider()

                    // Delete Account row
                    AccountRow(
                        icon = Icons.Outlined.Delete,
                        label = stringResource(R.string.profile_delete_account),
                        labelColor = DangerRed,
                        iconTint = DangerRed,
                        onClick = onDeleteAccountClick
                    )
                } else {
                    // Guest mode: show sign-in prompt
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.profile_guest_account),
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.profile_guest_sign_in_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // -- About --
        SectionHeader(stringResource(R.string.profile_section_about))

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Column {
                AboutRow(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.profile_version_label),
                    value = stringResource(R.string.profile_version_value)
                )

                SettingsDivider()

                AboutRow(
                    icon = null,
                    label = stringResource(R.string.profile_build_label),
                    value = stringResource(R.string.profile_build_value)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.profile_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    // -- Confirmation Dialogs --

    if (uiState.showSignOutConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissSignOut,
            title = { Text(stringResource(R.string.profile_sign_out_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.profile_sign_out_message),
                    color = TextSecondary
                )
            },
            containerColor = SurfaceDark,
            confirmButton = {
                TextButton(onClick = onConfirmSignOut) {
                    Text(stringResource(R.string.profile_sign_out), color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissSignOut) {
                    Text(stringResource(R.string.common_cancel), color = AccentNavy)
                }
            }
        )
    }

    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissDeleteAccount,
            title = { Text(stringResource(R.string.profile_delete_account_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.profile_delete_account_message),
                    color = TextSecondary
                )
            },
            containerColor = SurfaceDark,
            confirmButton = {
                TextButton(onClick = onConfirmDeleteAccount) {
                    Text(stringResource(R.string.profile_delete_account), color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteAccount) {
                    Text(stringResource(R.string.common_cancel), color = AccentNavy)
                }
            }
        )
    }
}

// -- Components --

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.gunmetalCard()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun PersonalBestRow(
    distanceLabel: String,
    timeSeconds: Double
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = distanceLabel,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Medium
            ),
            color = TextPrimary
        )
        Text(
            text = formatTime(timeSeconds),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            ),
            color = AccentGreen
        )
    }
}

@Composable
private fun AccountRow(
    icon: ImageVector,
    label: String,
    labelColor: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor
        )
    }
}

@Composable
private fun AboutRow(
    icon: ImageVector?,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = TextSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Spacer(modifier = Modifier.width(32.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = BorderSubtle
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        ),
        color = TextSecondary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

// -- Helpers --

private fun formatTime(seconds: Double): String {
    return when {
        seconds < 60.0 -> String.format("%.2fs", seconds)
        else -> {
            val min = (seconds / 60).toInt()
            val sec = seconds % 60
            String.format("%d:%05.2f", min, sec)
        }
    }
}

// -- Previews --

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000
)
@Composable
private fun ProfileScreenPreview() {
    TrackSpeedTheme(darkTheme = true) {
        ProfileScreenContent(
            uiState = ProfileUiState(
                userName = "Sondre",
                totalSessions = 12,
                totalRuns = 47,
                bestTimeSeconds = 7.34,
                personalBests = listOf(
                    PersonalBest(60.0, 7.34),
                    PersonalBest(100.0, 11.89),
                    PersonalBest(200.0, 24.12)
                ),
            )
        )
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000,
    name = "Profile - Empty State"
)
@Composable
private fun ProfileScreenEmptyPreview() {
    TrackSpeedTheme(darkTheme = true) {
        ProfileScreenContent(
            uiState = ProfileUiState()
        )
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000,
    name = "Profile - Pro User"
)
@Composable
private fun ProfileScreenProPreview() {
    TrackSpeedTheme(darkTheme = true) {
        ProfileScreenContent(
            uiState = ProfileUiState(
                userName = "Coach Mike",
                isProUser = true,
                totalSessions = 45,
                totalRuns = 230,
                bestTimeSeconds = 6.89,
                personalBests = listOf(
                    PersonalBest(60.0, 6.89),
                    PersonalBest(100.0, 10.94)
                ),
                isSignedIn = true
            )
        )
    }
}
