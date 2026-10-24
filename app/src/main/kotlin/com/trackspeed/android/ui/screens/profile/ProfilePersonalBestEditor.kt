package com.trackspeed.android.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trackspeed.android.R
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportCategory
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.ui.util.localizedDisplayName

/** A draft-only editor. No profile write occurs until the explicit Save callback.
 * The owner supplies loaded values, saving/error state, and decides whether to
 * close after local persistence or show an existing pending-sync message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePersonalBestEditor(
    initialValues: ProfilePersonalBestValues,
    onDismiss: () -> Unit,
    onSave: (ProfilePersonalBestValues) -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    var eventRaw by rememberSaveable { mutableStateOf(initialValues.primaryEvent.rawValue) }
    var eventText by rememberSaveable { mutableStateOf(formatEditableProfileRecord(initialValues.personalRecordSeconds)) }
    var flyingRaw by rememberSaveable { mutableStateOf((initialValues.flyingDistance ?: FlyingDistance.METERS_10).rawValue) }
    var flyingText by rememberSaveable { mutableStateOf(formatEditableProfileRecord(initialValues.flyingPrSeconds)) }
    var chooseEvent by rememberSaveable { mutableStateOf(false) }
    val draft = ProfilePersonalBestDraft(
        SportDiscipline.fromRawValue(eventRaw) ?: SportDiscipline.SPRINT_100M,
        eventText, FlyingDistance.fromRawValue(flyingRaw) ?: FlyingDistance.METERS_10, flyingText
    )
    Dialog(onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.profile_section_personal_bests)) },
                    navigationIcon = {
                        TextButton(onClick = onDismiss, enabled = !isSaving) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    },
                    actions = {
                        TextButton(onClick = { draft.valuesOrNull()?.let(onSave) },
                            enabled = !isSaving && draft.canSave) {
                            Text(stringResource(R.string.run_detail_save))
                        }
                    }
                )
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.onboarding_flying_event_pr), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { chooseEvent = true }, enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(draft.primaryEvent.localizedDisplayName())
                    }
                    OutlinedTextField(value = eventText, onValueChange = { eventText = it },
                        modifier = Modifier.fillMaxWidth(), enabled = !isSaving,
                        label = { Text(stringResource(R.string.onboarding_flying_event_pr)) },
                        placeholder = { Text("10.50 / 1:58.20") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
                        singleLine = true, isError = draft.invalidPersonalRecord,
                        supportingText = {
                            if (draft.invalidPersonalRecord) Text(stringResource(R.string.onboarding_flying_invalid_event_time))
                        })
                    HorizontalDivider()
                    Text(stringResource(R.string.onboarding_flying_subtitle), style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        FlyingDistance.entries.forEachIndexed { index, distance ->
                            SegmentedButton(selected = draft.flyingDistance == distance,
                                onClick = { flyingRaw = distance.rawValue }, enabled = !isSaving,
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = SegmentedButtonDefaults.itemShape(index, FlyingDistance.entries.size)) {
                                Text(distance.rawValue)
                            }
                        }
                    }
                    OutlinedTextField(value = flyingText, onValueChange = { flyingText = it },
                        modifier = Modifier.fillMaxWidth(), enabled = !isSaving,
                        label = { Text(stringResource(R.string.onboarding_flying_time_label)) },
                        placeholder = { Text("1.050") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false),
                        singleLine = true, isError = draft.invalidFlyingPr,
                        supportingText = {
                            if (draft.invalidFlyingPr) Text(stringResource(R.string.onboarding_flying_invalid_time))
                        })
                    if (isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (errorMessage != null) Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (chooseEvent) {
        AlertDialog(onDismissRequest = { chooseEvent = false },
            title = { Text(stringResource(R.string.onboarding_athlete_event_label)) },
            confirmButton = {
                TextButton(onClick = { chooseEvent = false }) { Text(stringResource(R.string.common_cancel)) }
            },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SportCategory.entries.forEach { category ->
                        Text(category.localizedDisplayName(), style = MaterialTheme.typography.titleSmall)
                        SportDiscipline.entries.filter { it.category == category }.forEach { event ->
                            TextButton(onClick = { eventRaw = event.rawValue; chooseEvent = false },
                                modifier = Modifier.fillMaxWidth()) { Text(event.localizedDisplayName()) }
                        }
                    }
                }
            })
    }
}
