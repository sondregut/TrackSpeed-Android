package com.trackspeed.android.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.AccentGreen
import com.trackspeed.android.ui.theme.BorderSubtle
import com.trackspeed.android.ui.theme.CardBackground
import com.trackspeed.android.ui.theme.TextMuted
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary
import com.trackspeed.android.ui.util.formatTime

enum class TimingSessionEndOrigin {
    LOCAL,
    HOST,
    PARTNER
}

data class TimingSessionEndConfirmation(
    val isSharedSession: Boolean,
    val runCount: Int,
    val bestTime: Double?
)

data class TimingSessionEndSummary(
    val origin: TimingSessionEndOrigin,
    val runCount: Int,
    val bestTime: Double?,
    val savedSessionId: String?,
    val isGuest: Boolean = false
) {
    val canViewSession: Boolean
        get() = runCount > 0 && savedSessionId != null && !isGuest
}

sealed interface TimingSessionEndPresentation {
    data class Confirmation(val value: TimingSessionEndConfirmation) : TimingSessionEndPresentation
    data class Saving(val isSharedSession: Boolean) : TimingSessionEndPresentation
    data class Completed(val summary: TimingSessionEndSummary) : TimingSessionEndPresentation
}

@Composable
fun TimingSessionEndOverlay(
    presentation: TimingSessionEndPresentation,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onViewSession: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = when (presentation) {
        is TimingSessionEndPresentation.Confirmation -> MaterialTheme.colorScheme.error
        is TimingSessionEndPresentation.Saving -> MaterialTheme.colorScheme.primary
        is TimingSessionEndPresentation.Completed -> AccentGreen
    }
    val phaseTitle = when (presentation) {
        is TimingSessionEndPresentation.Confirmation -> if (presentation.value.isSharedSession) {
            stringResource(R.string.session_end_shared_eyebrow)
        } else {
            stringResource(R.string.session_end_eyebrow)
        }
        is TimingSessionEndPresentation.Saving -> stringResource(R.string.session_end_finalizing)
        is TimingSessionEndPresentation.Completed -> stringResource(R.string.session_end_closed)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(30.dp)
                        .height(4.dp)
                        .background(accent, RoundedCornerShape(100.dp))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = phaseTitle,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.app_name).uppercase(),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                when (presentation) {
                    is TimingSessionEndPresentation.Confirmation -> ConfirmationContent(
                        confirmation = presentation.value,
                        accent = accent
                    )
                    is TimingSessionEndPresentation.Saving -> SavingContent(
                        isSharedSession = presentation.isSharedSession,
                        accent = accent
                    )
                    is TimingSessionEndPresentation.Completed -> CompletedContent(
                        summary = presentation.summary,
                        accent = accent
                    )
                }
            }

            when (presentation) {
                is TimingSessionEndPresentation.Confirmation -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.session_end_finish_action),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                        ) {
                            Text(
                                text = stringResource(R.string.session_end_continue_action),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                is TimingSessionEndPresentation.Saving -> Unit
                is TimingSessionEndPresentation.Completed -> {
                    val summary = presentation.summary
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (summary.canViewSession) {
                            Button(
                                onClick = { summary.savedSessionId?.let(onViewSession) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.session_end_view_saved_action),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        val doneModifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                        if (summary.canViewSession) {
                            OutlinedButton(
                                onClick = onDone,
                                modifier = doneModifier,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                            ) {
                                Text(stringResource(R.string.session_end_done_action), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = onDone,
                                modifier = doneModifier,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    stringResource(R.string.session_end_return_action),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationContent(
    confirmation: TimingSessionEndConfirmation,
    accent: Color
) {
    StateMark(Icons.Filled.Stop, accent)
    TitleBlock(
        title = stringResource(R.string.session_end_finish_title),
        body = confirmationMessage(confirmation)
    )
    SessionMetrics(confirmation.runCount, confirmation.bestTime)
    val hasRuns = confirmation.runCount > 0
    StatusRow(
        icon = if (hasRuns) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircle,
        color = if (hasRuns) AccentGreen else TextMuted,
        title = if (hasRuns) {
            stringResource(R.string.session_end_results_protected)
        } else {
            stringResource(R.string.session_end_nothing_deleted)
        },
        detail = if (hasRuns) {
            stringResource(R.string.session_end_results_protected_detail)
        } else {
            stringResource(R.string.session_end_nothing_deleted_detail)
        }
    )
}

@Composable
private fun confirmationMessage(confirmation: TimingSessionEndConfirmation): String {
    if (confirmation.runCount == 0) {
        return if (confirmation.isSharedSession) {
            stringResource(R.string.session_end_no_runs_shared_message)
        } else {
            stringResource(R.string.session_end_no_runs_message)
        }
    }

    val kept = if (confirmation.runCount == 1) {
        stringResource(R.string.session_end_one_run_kept)
    } else {
        stringResource(R.string.session_end_runs_kept, confirmation.runCount)
    }
    return if (confirmation.isSharedSession) {
        stringResource(R.string.session_end_shared_message, kept)
    } else {
        kept
    }
}

@Composable
private fun SavingContent(isSharedSession: Boolean, accent: Color) {
    StateMark(Icons.Filled.Save, accent)
    TitleBlock(
        title = stringResource(R.string.session_end_securing_title),
        body = if (isSharedSession) {
            stringResource(R.string.session_end_securing_shared_message)
        } else {
            stringResource(R.string.session_end_securing_message)
        }
    )
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp),
        color = accent,
        trackColor = CardBackground
    )
    StatusRow(
        icon = Icons.Filled.PhoneAndroid,
        color = accent,
        title = stringResource(R.string.session_end_saving_phone),
        detail = stringResource(R.string.session_end_saving_phone_detail)
    )
}

@Composable
private fun CompletedContent(summary: TimingSessionEndSummary, accent: Color) {
    StateMark(Icons.Filled.Check, accent)
    TitleBlock(
        title = stringResource(R.string.session_end_complete_title),
        body = when (summary.origin) {
            TimingSessionEndOrigin.LOCAL -> stringResource(R.string.session_end_local_context)
            TimingSessionEndOrigin.HOST -> stringResource(R.string.session_end_host_context)
            TimingSessionEndOrigin.PARTNER -> stringResource(R.string.session_end_partner_context)
        }
    )
    SessionMetrics(summary.runCount, summary.bestTime)
    val hasRuns = summary.runCount > 0
    StatusRow(
        icon = if (hasRuns) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircle,
        color = if (hasRuns) AccentGreen else TextMuted,
        title = if (hasRuns) {
            stringResource(R.string.session_end_saved_phone)
        } else {
            stringResource(R.string.session_end_no_completed_runs)
        },
        detail = when {
            !hasRuns -> stringResource(R.string.session_end_nothing_to_save)
            summary.isGuest -> stringResource(R.string.session_end_guest_saved_detail)
            else -> stringResource(R.string.session_end_cloud_saved_detail)
        }
    )
}

@Composable
private fun StateMark(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(66.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(27.dp))
    }
}

@Composable
private fun TitleBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            color = TextPrimary,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = body,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun SessionMetrics(runCount: Int, bestTime: Double?) {
    Column {
        HorizontalDivider(color = BorderSubtle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Metric(stringResource(R.string.session_end_runs_label), runCount.toString(), Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(48.dp)
                    .background(BorderSubtle)
            )
            Metric(
                label = stringResource(R.string.session_end_best_label),
                value = bestTime?.let { stringResource(R.string.session_end_best_value, formatTime(it)) }
                    ?: stringResource(R.string.session_end_best_empty),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 28.dp)
            )
        }
        HorizontalDivider(color = BorderSubtle)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp
        )
        Text(
            text = value,
            color = TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    color: Color,
    title: String,
    detail: String
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = detail,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
