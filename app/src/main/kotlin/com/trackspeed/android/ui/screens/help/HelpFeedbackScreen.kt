package com.trackspeed.android.ui.screens.help

import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R

@Composable
fun HelpFeedbackScreen(onBack: () -> Unit, onBrowseIdeas: (() -> Unit)? = null,
    viewModel: HelpFeedbackViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var restoredKind by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { viewModel.addImages(it) }
    LaunchedEffect(restoredKind) {
        val requested = restoredKind?.let { runCatching { PrivateFeedbackKind.valueOf(it) }.getOrNull() }
        if (requested != null && state.kind != requested) viewModel.open(requested)
    }
    val close: () -> Unit = {
        restoredKind = null
        if (state.kind != null) viewModel.closeComposer() else onBack()
    }
    HelpFeedbackContent(state = state, metadata = viewModel.metadata, onBack = close,
        onKind = { restoredKind = it.name; viewModel.open(it) }, onEdit = viewModel::edit,
        onAddImages = { picker.launch("image/*") }, onSend = viewModel::send,
        onDiscard = { restoredKind = null; viewModel.discard() }, onBrowseIdeas = onBrowseIdeas,
        onEmail = {
            // Opens the user's native composer; never sends a message or includes report contents automatically.
            runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${PrivateFeedbackClient.SUPPORT_EMAIL}"))) }
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HelpFeedbackContent(
    state: HelpFeedbackUiState, metadata: Map<String, String>, onBack: () -> Unit,
    onKind: (PrivateFeedbackKind) -> Unit, onEdit: ((PrivateFeedbackDraft) -> PrivateFeedbackDraft) -> Unit,
    onAddImages: () -> Unit, onSend: () -> Unit, onDiscard: () -> Unit,
    onBrowseIdeas: (() -> Unit)? = null, onEmail: () -> Unit
) {
    BackHandler(onBack = onBack)
    var discardConfirmation by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val draft = state.draft
    val busy = state.sending || state.importing
    Scaffold(modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(title = {
                Text(stringResource(state.kind?.titleResource() ?: R.string.help_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
            }, actions = {
                if (draft != null && !draft.isEmpty && state.receipt == null) {
                    TextButton(onClick = { discardConfirmation = true }, enabled = !busy) { Text(stringResource(R.string.help_discard)) }
                }
            })
        },
        bottomBar = {
            if (draft != null && state.receipt == null) {
                Surface(shadowElevation = 4.dp) {
                    Button(onClick = { focus.clearFocus(); onSend() }, enabled = draft.canSend && !busy && !state.loading,
                        modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().heightIn(min = 52.dp)) {
                        if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(if (state.sending) R.string.help_sending else R.string.help_send), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                state.kind == null -> {
                    Text("TRACKSPEED", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.help_listening), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.help_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HelpRow(R.string.help_report, R.string.help_report_detail, Icons.Outlined.ChatBubbleOutline) { onKind(PrivateFeedbackKind.BUG) }
                    HelpRow(R.string.help_improve, R.string.help_improve_detail, Icons.Outlined.Lightbulb) { onKind(PrivateFeedbackKind.IMPROVEMENT) }
                    if (onBrowseIdeas != null) HelpRow(R.string.help_board, R.string.help_board_detail, Icons.Outlined.Forum, onBrowseIdeas)
                    HelpRow(R.string.help_question, R.string.help_question_detail, Icons.Outlined.HelpOutline) { onKind(PrivateFeedbackKind.QUESTION) }
                    Text(stringResource(R.string.help_privacy_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.loading -> { CircularProgressIndicator(); Text(stringResource(R.string.help_loading)) }
                state.receipt != null -> {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                    Text(stringResource(R.string.help_received), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.help_thanks), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (draft?.email.isNullOrBlank()) R.string.help_review_no_email else R.string.help_review_email))
                    if (draft?.publicationRequested == true) Text(stringResource(R.string.help_private_until_review), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.help_reference, state.receipt.id.take(8).uppercase()), style = MaterialTheme.typography.labelMedium)
                    if (onBrowseIdeas != null) TextButton(onClick = onBrowseIdeas) { Text(stringResource(R.string.help_board)) }
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.common_done)) }
                }
                draft != null -> {
                    Text(stringResource(draft.kind.promptResource()), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(draft.kind.hintResource()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = draft.details, onValueChange = { value -> onEdit { it.copy(details = value) } },
                        label = { Text(stringResource(R.string.help_description)) }, enabled = !busy,
                        minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.help_required_count, draft.details.length)) })
                    draft.attachments.forEachIndexed { index, attachment ->
                        val bitmap = remember(attachment.id) { runCatching {
                            val bytes = Base64.decode(attachment.base64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })
                        }.getOrNull() }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (bitmap != null) Image(bitmap.asImageBitmap(), stringResource(R.string.help_screenshot_number, index + 1),
                                Modifier.size(90.dp, 130.dp))
                            TextButton(onClick = { onEdit { it.copy(attachments = it.attachments.filterNot { item -> item.id == attachment.id }) } }, enabled = !busy) {
                                Text(stringResource(R.string.help_remove_screenshot))
                            }
                        }
                    }
                    OutlinedButton(onClick = onAddImages, enabled = !busy && draft.attachments.size < 3,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(if (state.importing) R.string.help_adding_screenshot else R.string.help_add_screenshots))
                    }
                    OutlinedTextField(value = draft.email, onValueChange = { value -> onEdit { it.copy(email = value) } },
                        label = { Text(stringResource(R.string.help_email)) }, enabled = !busy,
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        isError = !draft.validEmail, modifier = Modifier.fillMaxWidth(),
                        supportingText = { if (!draft.validEmail) Text(stringResource(R.string.help_invalid_email)) })
                    if (draft.kind == PrivateFeedbackKind.IMPROVEMENT) {
                        FeedbackSwitch(R.string.help_publish_opt_in, draft.publicationRequested, !busy) { checked -> onEdit { it.copy(publicationRequested = checked) } }
                        Text(stringResource(R.string.help_publication_detail), style = MaterialTheme.typography.bodySmall)
                        if (onBrowseIdeas != null) TextButton(onClick = onBrowseIdeas) { Text(stringResource(R.string.help_similar_ideas)) }
                    }
                    FeedbackSwitch(R.string.help_diagnostics, draft.includeDiagnostics, !busy) { checked -> onEdit { it.copy(includeDiagnostics = checked) } }
                    if (draft.includeDiagnostics) {
                        Text(stringResource(R.string.help_technical_included), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.help_technical_values, metadata["appVersion"].orEmpty(), metadata["buildNumber"].orEmpty(),
                            metadata["device"].orEmpty(), metadata["osVersion"].orEmpty()), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.help_no_account_data), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.help_private_support), style = MaterialTheme.typography.labelLarge)
                    state.failure?.let { Text(stringResource(it.messageResource()), color = MaterialTheme.colorScheme.error) }
                    Text(stringResource(if (state.persistenceError) R.string.help_persistence_error else R.string.help_draft_retention),
                        style = MaterialTheme.typography.bodySmall, color = if (state.persistenceError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.failure != null) TextButton(onClick = onEmail) { Text(stringResource(R.string.help_contact_email)) }
                }
            }
        }
    }
    if (discardConfirmation) AlertDialog(onDismissRequest = { discardConfirmation = false },
        title = { Text(stringResource(R.string.help_delete_confirmation)) },
        confirmButton = { TextButton(onClick = { discardConfirmation = false; onDiscard() }) { Text(stringResource(R.string.help_delete_draft)) } },
        dismissButton = { TextButton(onClick = { discardConfirmation = false }) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun HelpRow(title: Int, detail: Int, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@Composable
private fun FeedbackSwitch(label: Int, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

internal fun PrivateFeedbackKind.titleResource() = when (this) {
    PrivateFeedbackKind.BUG -> R.string.help_report
    PrivateFeedbackKind.IMPROVEMENT -> R.string.help_improve
    PrivateFeedbackKind.QUESTION -> R.string.help_question
}
private fun PrivateFeedbackKind.promptResource() = when (this) {
    PrivateFeedbackKind.BUG -> R.string.help_bug_prompt
    PrivateFeedbackKind.IMPROVEMENT -> R.string.help_idea_prompt
    PrivateFeedbackKind.QUESTION -> R.string.help_question_prompt
}
private fun PrivateFeedbackKind.hintResource() = when (this) {
    PrivateFeedbackKind.BUG -> R.string.help_bug_hint
    PrivateFeedbackKind.IMPROVEMENT -> R.string.help_idea_hint
    PrivateFeedbackKind.QUESTION -> R.string.help_question_hint
}
private fun PrivateFeedbackFailure.messageResource() = when (this) {
    PrivateFeedbackFailure.RATE_LIMITED -> R.string.help_rate_limited
    PrivateFeedbackFailure.IMAGE -> R.string.help_image_error
    PrivateFeedbackFailure.PERSISTENCE -> R.string.help_persistence_error
    PrivateFeedbackFailure.TIMING_ACTIVE -> R.string.help_timing_active
    else -> R.string.help_send_error
}
