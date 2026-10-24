package com.trackspeed.android.ui.screens.ideas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.cloud.ideas.*
import com.trackspeed.android.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun IdeasBoardScreen(onBack: () -> Unit, viewModel: IdeasBoardViewModel = hiltViewModel()) {
    val store = viewModel.store
    val state by store.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val back = {
        if (state.draft != null) store.closeComposer() else { store.cancelPending(); onBack() }
    }
    BackHandler(onBack = back)
    LaunchedEffect(store) { store.load() }
    DisposableEffect(owner, store) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> store.load()
                Lifecycle.Event.ON_STOP -> store.cancelPending()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); store.cancelPending() }
    }
    IdeasBoardContent(state, IdeasBoardActions(back, store::load, store::setCategory, store::setSort,
        store::vote, store::openComposer, store::editDraft, store::submit, store::closeComposer, store::checkBoardAfterSubmission))
}

internal data class IdeasBoardActions(
    val back: () -> Unit = {}, val refresh: () -> Unit = {},
    val category: (IdeaCategory) -> Unit = {}, val sort: (IdeaSort) -> Unit = {},
    val vote: (String) -> Unit = {}, val compose: () -> Unit = {},
    val edit: (IdeaDraft) -> Unit = {}, val submit: () -> Unit = {},
    val cancelComposer: () -> Unit = {}, val checkBoard: () -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IdeasBoardContent(state: IdeasBoardState, actions: IdeasBoardActions) {
    val draft = state.draft
    Scaffold(modifier = Modifier.gradientBackground(), containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(title = { Text(stringResource(if (draft == null) R.string.ideas_title else R.string.ideas_public_title)) },
                navigationIcon = { IconButton(onClick = if (draft == null) actions.back else actions.cancelComposer) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                } }, actions = {
                    if (draft == null) {
                        IconButton(onClick = actions.refresh, enabled = !state.loading && !state.busy) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.ideas_refresh))
                        }
                        IconButton(onClick = actions.compose) { Icon(Icons.Default.Add, stringResource(R.string.ideas_new)) }
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark,
                    titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary, actionIconContentColor = TextPrimary))
        }
    ) { padding ->
        if (draft != null) {
            IdeasComposer(state, draft, actions, Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("ideas-list"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(stringResource(R.string.ideas_heading), style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ideas_description), color = TextSecondary)
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IdeaCategory.entries.forEach { category ->
                            FilterChip(selected = state.category == category, onClick = { actions.category(category) },
                                label = { Text(stringResource(category.label())) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IdeaSort.entries.forEach { sort ->
                            FilterChip(selected = state.sort == sort, onClick = { actions.sort(sort) },
                                label = { Text(stringResource(if (sort == IdeaSort.MOST_VOTED) R.string.ideas_most_voted else R.string.ideas_newest)) })
                        }
                    }
                }
                if (state.loading) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = TextPrimary)
                        Text(stringResource(R.string.ideas_loading), color = TextSecondary)
                    }
                }
                state.error?.let { error -> item {
                    if (!state.loaded) Text(stringResource(R.string.ideas_load_title), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(stringResource(error.message()), color = TextSecondary)
                    TextButton(onClick = actions.refresh, enabled = !state.loading && !state.busy) {
                        Text(stringResource(R.string.ideas_retry))
                    }
                } }
                if (state.loaded && !state.loading && state.error == null && state.visiblePosts.isEmpty()) item {
                    Text(stringResource(if (state.category == IdeaCategory.ALL) R.string.ideas_empty else R.string.ideas_empty_filter),
                        style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ideas_empty_detail), color = TextSecondary)
                    TextButton(onClick = actions.compose) { Text(stringResource(R.string.ideas_submit)) }
                }
                items(state.visiblePosts, key = { it.id }) { post ->
                    IdeaPostCard(post, post.id in state.votedIds,
                        enabled = state.votesKnown && !state.loading && !state.submitting && post.id !in state.votingIds,
                        onVote = { actions.vote(post.id) })
                }
            }
        }
    }
}

@Composable
private fun IdeaPostCard(post: IdeaPost, voted: Boolean, enabled: Boolean, onVote: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(post.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(post.details, color = TextSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(post.category.singularLabel()), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(stringResource(post.status.label()), style = MaterialTheme.typography.labelMedium, color = TextPrimary)
            }
            Text(post.publicName ?: stringResource(R.string.ideas_author), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            post.createdAt?.let { date -> runCatching {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(date)
            }.getOrNull() }?.let { date ->
                Text(date,
                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            val voteDescription = stringResource(if (voted) R.string.ideas_voted_accessibility else R.string.ideas_vote_accessibility, post.title, post.votes)
            OutlinedButton(onClick = onVote, enabled = enabled && !voted,
                modifier = Modifier.semantics { contentDescription = voteDescription }.testTag("idea-vote-${post.id}")) {
                Text(stringResource(if (voted) R.string.ideas_voted else R.string.ideas_vote, post.votes))
            }
            post.adminResponse?.let { response ->
                HorizontalDivider(color = BorderSubtle)
                Text(stringResource(R.string.ideas_team_response), style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                Text(response, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun IdeasComposer(state: IdeasBoardState, draft: IdeaDraft, actions: IdeasBoardActions, modifier: Modifier) {
    val unconfirmed = state.composerError == IdeasError.SUBMIT_UNCONFIRMED
    val editable = !state.submitting && !unconfirmed
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.ideas_public_warning), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IdeaCategory.submissionCategories.forEach { category ->
                FilterChip(selected = draft.category == category, onClick = { actions.edit(draft.copy(category = category)) },
                    enabled = editable, label = { Text(stringResource(category.singularLabel())) })
            }
        }
        OutlinedTextField(value = draft.title, onValueChange = { actions.edit(draft.copy(title = it)) },
            modifier = Modifier.fillMaxWidth().testTag("idea-title"), enabled = editable, singleLine = true,
            label = { Text(stringResource(R.string.ideas_field_title)) },
            supportingText = { Text(stringResource(R.string.ideas_counter, draft.title.codePointLength(), IdeaDraft.MAX_TITLE)) })
        OutlinedTextField(value = draft.details, onValueChange = { actions.edit(draft.copy(details = it)) },
            modifier = Modifier.fillMaxWidth().testTag("idea-details"), enabled = editable, minLines = 4,
            label = { Text(stringResource(R.string.ideas_field_details)) },
            supportingText = { Text(stringResource(R.string.ideas_counter, draft.details.codePointLength(), IdeaDraft.MAX_DETAILS)) })
        OutlinedTextField(value = draft.publicName, onValueChange = { actions.edit(draft.copy(publicName = it)) },
            modifier = Modifier.fillMaxWidth().testTag("idea-name"), enabled = editable, singleLine = true,
            label = { Text(stringResource(R.string.ideas_field_name)) },
            supportingText = { Text(stringResource(R.string.ideas_counter, draft.publicName.codePointLength(), IdeaDraft.MAX_NAME)) })
        Text(stringResource(R.string.ideas_name_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        state.composerError?.let { Text(stringResource(it.message()), color = TextSecondary) }
        if (unconfirmed) {
            Button(onClick = actions.checkBoard, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ideas_check_board))
            }
        } else {
            Button(onClick = actions.submit, enabled = editable && draft.submission() != null && state.votingIds.isEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (state.submitting) R.string.ideas_submitting else R.string.ideas_submit))
            }
        }
        TextButton(onClick = actions.cancelComposer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.ideas_cancel))
        }
    }
}

private fun IdeaCategory.label() = when (this) {
    IdeaCategory.ALL -> R.string.ideas_all
    IdeaCategory.FEATURE -> R.string.ideas_features
    IdeaCategory.BUG -> R.string.ideas_bugs
    IdeaCategory.IMPROVEMENT -> R.string.ideas_improvements
}
private fun IdeaCategory.singularLabel() = when (this) {
    IdeaCategory.ALL -> R.string.ideas_title
    IdeaCategory.FEATURE -> R.string.ideas_feature
    IdeaCategory.BUG -> R.string.ideas_bug
    IdeaCategory.IMPROVEMENT -> R.string.ideas_improvement
}
private fun IdeaStatus.label() = when (this) {
    IdeaStatus.OPEN -> R.string.ideas_status_open
    IdeaStatus.PLANNED -> R.string.ideas_status_planned
    IdeaStatus.IN_PROGRESS -> R.string.ideas_status_progress
    IdeaStatus.COMPLETED -> R.string.ideas_status_completed
    IdeaStatus.CLOSED -> R.string.ideas_status_closed
}
private fun IdeasError.message() = when (this) {
    IdeasError.SESSION_UNAVAILABLE -> R.string.ideas_error_session
    IdeasError.LOAD_FAILED -> R.string.ideas_error_load
    IdeasError.VOTES_UNAVAILABLE -> R.string.ideas_error_votes
    IdeasError.VOTE_UNCONFIRMED -> R.string.ideas_error_vote
    IdeasError.SUBMIT_UNCONFIRMED -> R.string.ideas_error_submit
    IdeasError.INVALID_SUBMISSION -> R.string.ideas_error_invalid
}
