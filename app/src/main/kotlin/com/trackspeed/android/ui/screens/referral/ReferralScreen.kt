package com.trackspeed.android.ui.screens.referral

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trackspeed.android.referral.ReferralContact
import com.trackspeed.android.referral.ReferralStats
import com.trackspeed.android.ui.theme.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferralScreen(
    onNavigateBack: () -> Unit,
    viewModel: ReferralViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onContactsPermissionResult(granted)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshContactsPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize().gradientBackground()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.referral_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = AccentBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        ReferralContent(
            state = state,
            onCopyCode = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.referral_your_code), state.referralLink))
                viewModel.onCopiedToClipboard()
            },
            onShare = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, state.shareMessage)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.referral_share_chooser)))
            },
            onSyncContacts = {
                when (state.contactsPermissionState) {
                    ContactsPermissionState.Granted -> viewModel.loadContacts()
                    ContactsPermissionState.Unknown,
                    ContactsPermissionState.Denied -> contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            },
            onOpenSettings = {
                openAppSettings(context)
            },
            onContactSearchChanged = viewModel::onContactSearchChanged,
            onInviteContact = { contact ->
                val launched = launchContactInvite(
                    context = context,
                    phoneNumber = contact.phoneNumber,
                    body = viewModel.contactInviteMessage(),
                    fallbackText = state.shareMessage
                )
                if (launched) {
                    viewModel.onContactInviteLaunched(contact.id)
                }
            },
            modifier = Modifier.padding(padding)
        )
    }
    } // close Box
}

@Composable
private fun ReferralContent(
    state: ReferralUiState,
    onCopyCode: () -> Unit,
    onShare: () -> Unit,
    onSyncContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onContactSearchChanged: (String) -> Unit,
    onInviteContact: (ReferralContact) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Header
        Text(
            text = stringResource(R.string.referral_earn_month),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.referral_for_every_friend),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.referral_they_get_free),
            style = MaterialTheme.typography.bodyMedium,
            color = AccentGreen,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Stats
        if (state.stats.friendsJoined > 0) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = AccentGreen.copy(alpha = 0.1f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp)
                ) {
                    StatItem(
                        value = state.stats.friendsJoined.toString(),
                        label = stringResource(R.string.referral_friends_joined)
                    )
                    StatItem(
                        value = state.stats.freeMonthsEarned.toString(),
                        label = stringResource(R.string.referral_free_months)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Referral code section
        Text(
            text = stringResource(R.string.referral_your_code),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (state.isLoading) {
            CircularProgressIndicator(
                color = AccentBlue,
                modifier = Modifier.size(32.dp)
            )
        } else {
            // Large referral code display
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = CardBackground,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = DividerColor,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(vertical = 24.dp, horizontal = 16.dp)
            ) {
                Text(
                    text = state.referralCode,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    color = AccentBlue,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Referral link
        if (state.referralLink.isNotEmpty()) {
            Text(
                text = state.referralLink,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Copy button
        OutlinedButton(
            onClick = onCopyCode,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (state.copiedToClipboard) AccentGreen else AccentBlue
            ),
            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    if (state.copiedToClipboard) AccentGreen else AccentBlue
                )
            )
        ) {
            Icon(
                imageVector = if (state.copiedToClipboard) Icons.Default.Check  else Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.copiedToClipboard) stringResource(R.string.referral_copied) else stringResource(R.string.referral_copy_link),
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Share button
        Button(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.referral_share_invite),
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        ContactsInviteSection(
            state = state,
            onSyncContacts = onSyncContacts,
            onOpenSettings = onOpenSettings,
            onSearchChanged = onContactSearchChanged,
            onInviteContact = onInviteContact
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Explanation
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.referral_how_it_works),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                StepItem(number = "1", text = stringResource(R.string.referral_step_1))
                Spacer(modifier = Modifier.height(8.dp))
                StepItem(number = "2", text = stringResource(R.string.referral_step_2))
                Spacer(modifier = Modifier.height(8.dp))
                StepItem(number = "3", text = stringResource(R.string.referral_step_3))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ContactsInviteSection(
    state: ReferralUiState,
    onSyncContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onInviteContact: (ReferralContact) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.referral_contacts_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.referral_contacts_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (state.contactsPermissionState) {
                ContactsPermissionState.Unknown -> SyncContactsPrompt(
                    isLoading = state.isContactsLoading,
                    onSyncContacts = onSyncContacts
                )
                ContactsPermissionState.Denied -> ContactsPermissionPlaceholder(
                    onOpenSettings = onOpenSettings
                )
                ContactsPermissionState.Granted -> ContactsList(
                    state = state,
                    onSyncContacts = onSyncContacts,
                    onSearchChanged = onSearchChanged,
                    onInviteContact = onInviteContact
                )
            }
        }
    }
}

@Composable
private fun SyncContactsPrompt(
    isLoading: Boolean,
    onSyncContacts: () -> Unit
) {
    OutlinedButton(
        onClick = onSyncContacts,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = AccentBlue,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.PersonAdd,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = stringResource(R.string.referral_sync_contacts),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.referral_sync_contacts_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun ContactsPermissionPlaceholder(
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.referral_contacts_permission_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.referral_contacts_permission_body),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue,
                contentColor = Color.White
            )
        ) {
            Text(stringResource(R.string.referral_open_settings))
        }
    }
}

@Composable
private fun ContactsList(
    state: ReferralUiState,
    onSyncContacts: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onInviteContact: (ReferralContact) -> Unit
) {
    if (state.isContactsLoading && state.contacts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AccentBlue)
        }
        return
    }

    OutlinedTextField(
        value = state.contactSearchQuery,
        onValueChange = onSearchChanged,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.referral_search_contacts)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (state.contactSearchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchChanged("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null
                    )
                }
            }
        }
    )

    Spacer(modifier = Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.referral_contacts_count, state.filteredContacts.size),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.weight(1f))
        if (state.invitedContactIds.isNotEmpty()) {
            Text(
                text = stringResource(R.string.referral_contacts_invited_count, state.invitedContactIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = AccentGreen
            )
        } else {
            TextButton(
                onClick = onSyncContacts,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(stringResource(R.string.referral_sync_contacts))
            }
        }
    }

    if (state.contactsErrorMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.contactsErrorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    val contacts = state.filteredContacts.take(200)
    when {
        contacts.isEmpty() -> {
            Text(
                text = if (state.contactSearchQuery.isBlank()) {
                    stringResource(R.string.referral_contacts_empty)
                } else {
                    stringResource(R.string.referral_contacts_no_matches, state.contactSearchQuery)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
            )
        }
        else -> {
            Column {
                contacts.forEach { contact ->
                    ContactInviteRow(
                        contact = contact,
                        isInvited = state.invitedContactIds.contains(contact.id),
                        canInvite = state.referralCode.isNotBlank() && state.referralLink.isNotBlank(),
                        onInviteContact = onInviteContact
                    )
                    if (contact.id != contacts.last().id) {
                        HorizontalDivider(color = DividerColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactInviteRow(
    contact: ReferralContact,
    isInvited: Boolean,
    canInvite: Boolean,
    onInviteContact: (ReferralContact) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(AccentBlue.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.initials,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = AccentBlue
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.fullName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1
            )
            Text(
                text = contact.phoneNumber,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedButton(
            onClick = { onInviteContact(contact) },
            enabled = canInvite && !isInvited,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            if (isInvited) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = if (isInvited) {
                    stringResource(R.string.referral_contact_invited)
                } else {
                    stringResource(R.string.referral_invite_contact)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = AccentGreen
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun StepItem(number: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .background(
                    color = AccentBlue.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = AccentBlue
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

private fun launchContactInvite(
    context: Context,
    phoneNumber: String,
    body: String,
    fallbackText: String
): Boolean {
    val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("smsto:${Uri.encode(phoneNumber)}")
        putExtra("sms_body", body)
    }

    return try {
        if (smsIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(smsIntent)
        } else {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, fallbackText.ifBlank { body })
            }
            context.startActivity(
                Intent.createChooser(shareIntent, context.getString(R.string.referral_share_chooser))
            )
        }
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    runCatching { context.startActivity(intent) }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ReferralScreenPreview() {
    TrackSpeedTheme() {
        ReferralContent(
            state = ReferralUiState(
                referralCode = "TRK4X9",
                referralLink = "https://mytrackspeed.com/invite/TRK4X9",
                shareMessage = "Try TrackSpeed!",
                stats = ReferralStats(friendsJoined = 2, freeMonthsEarned = 1),
                isLoading = false
            ),
            onCopyCode = {},
            onShare = {},
            onSyncContacts = {},
            onOpenSettings = {},
            onContactSearchChanged = {},
            onInviteContact = {}
        )
    }
}
