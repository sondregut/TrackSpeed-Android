package com.trackspeed.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.trackspeed.android.ui.theme.AccentGold
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
import androidx.compose.material3.Surface
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
    val hasSavedSession: Boolean
        get() = savedSessionId != null

    val canViewSession: Boolean
        get() = runCount > 0 && hasSavedSession && !isGuest
}

/** Save-status copy decisions (iOS `TimingSessionEndSummary.saveStatus*`). */
enum class TimingSessionEndSaveStatus {
    NO_RUNS,
    UNCONFIRMED,
    SAVED_GUEST,
    SAVED_SYNCING;

    companion object {
        fun of(summary: TimingSessionEndSummary): TimingSessionEndSaveStatus = when {
            summary.runCount <= 0 -> NO_RUNS
            !summary.hasSavedSession -> UNCONFIRMED
            summary.isGuest -> SAVED_GUEST
            else -> SAVED_SYNCING
        }
    }
}

sealed interface TimingSessionEndPresentation {
    data class Confirmation(val value: TimingSessionEndConfirmation) : TimingSessionEndPresentation
    data class Saving(val isSharedSession: Boolean) : TimingSessionEndPresentation
    data class Completed(val summary: TimingSessionEndSummary) : TimingSessionEndPresentation
    data class ConnectionFailed(val message: String) : TimingSessionEndPresentation
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
    if (presentation is TimingSessionEndPresentation.Confirmation) {
        TimingSessionEndConfirmationSheet(
            confirmation = presentation.value,
            onCancel = onCancel,
            onConfirm = onConfirm,
            modifier = modifier
        )
        return
    }
    val accent = when (presentation) {
        is TimingSessionEndPresentation.Confirmation -> MaterialTheme.colorScheme.error
        is TimingSessionEndPresentation.Saving -> MaterialTheme.colorScheme.primary
        is TimingSessionEndPresentation.Completed ->
            if (presentation.summary.hasSavedSession) AccentGreen else AccentGold
        is TimingSessionEndPresentation.ConnectionFailed -> MaterialTheme.colorScheme.error
    }
    val phaseTitle = when (presentation) {
        is TimingSessionEndPresentation.Confirmation -> if (presentation.value.isSharedSession) {
            stringResource(R.string.session_end_shared_eyebrow)
        } else {
            stringResource(R.string.session_end_eyebrow)
        }
        is TimingSessionEndPresentation.Saving -> stringResource(R.string.session_end_finalizing)
        is TimingSessionEndPresentation.Completed -> stringResource(R.string.session_end_closed)
        is TimingSessionEndPresentation.ConnectionFailed ->
            stringResource(R.string.connection_failed_eyebrow)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.15f), Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = 430.dp.toPx()
                    )
                )
            }
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
                verticalArrangement = Arrangement.spacedBy(24.dp)
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
                    is TimingSessionEndPresentation.ConnectionFailed -> ConnectionFailedContent(
                        message = presentation.message,
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
                is TimingSessionEndPresentation.ConnectionFailed -> {
                    Button(
                        onClick = onDone,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.connection_failed_return_action),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingSessionEndConfirmationSheet(
    confirmation: TimingSessionEndConfirmation,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val error = MaterialTheme.colorScheme.error
    Box(modifier = modifier.fillMaxSize()) {
        // Lighter scrim keeps the live timing surface readable behind the sheet.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onCancel)
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, BorderSubtle.copy(alpha = 0.9f)),
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(32.dp)
                        .height(4.dp)
                        .background(TextMuted.copy(alpha = 0.35f), RoundedCornerShape(100.dp))
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(error.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, tint = error, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.session_end_confirmation_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            stringResource(
                                if (confirmation.isSharedSession) {
                                    R.string.session_end_scope_both_phones
                                } else {
                                    R.string.session_end_scope_this_phone
                                }
                            ),
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp
                        )
                    }
                }
                if (confirmation.runCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardBackground.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ConfirmationMetric(stringResource(R.string.session_end_runs_label), confirmation.runCount.toString())
                        confirmation.bestTime?.let { best ->
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(32.dp)
                                    .background(BorderSubtle)
                            )
                            ConfirmationMetric(
                                stringResource(R.string.session_end_best_label),
                                stringResource(R.string.session_end_best_value, formatTime(best))
                            )
                        }
                    }
                }
                val result = if (confirmation.runCount == 0) {
                    stringResource(R.string.session_end_confirm_no_runs)
                } else {
                    stringResource(R.string.session_end_confirm_results_stay)
                }
                Text(
                    if (confirmation.isSharedSession) {
                        stringResource(R.string.session_end_confirm_shared_suffix, result)
                    } else {
                        result
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(stringResource(R.string.session_end_continue_action), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, error.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = error.copy(alpha = 0.12f))
                    ) {
                        Text(
                            stringResource(R.string.session_end_end_action),
                            color = error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
        Text(value, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
    GlassCard {
        StateMark(Icons.Filled.Save, accent)
        TitleBlock(
            title = stringResource(R.string.session_end_saving_title),
            body = if (isSharedSession) {
                stringResource(R.string.session_end_saving_shared_body)
            } else {
                stringResource(R.string.session_end_saving_body)
            }
        )
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp),
            color = accent,
            trackColor = CardBackground
        )
        Text(
            text = stringResource(R.string.session_end_keep_open),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CompletedContent(summary: TimingSessionEndSummary, accent: Color) {
    GlassCard {
        StateMark(if (summary.hasSavedSession) Icons.Filled.Check else Icons.Filled.Warning, accent)
        TitleBlock(
            title = stringResource(
                if (summary.hasSavedSession) R.string.session_end_complete_title else R.string.session_end_ended_title
            ),
            body = when (summary.origin) {
                TimingSessionEndOrigin.LOCAL -> stringResource(R.string.session_end_local_context_short)
                TimingSessionEndOrigin.HOST -> stringResource(R.string.session_end_host_context_short)
                TimingSessionEndOrigin.PARTNER -> stringResource(R.string.session_end_partner_context_short)
            }
        )
        SessionMetrics(summary.runCount, summary.bestTime)
        val status = TimingSessionEndSaveStatus.of(summary)
        StatusRow(
            icon = when (status) {
                TimingSessionEndSaveStatus.NO_RUNS -> Icons.Filled.RemoveCircle
                TimingSessionEndSaveStatus.UNCONFIRMED -> Icons.Filled.Error
                else -> Icons.Filled.CheckCircle
            },
            color = when (status) {
                TimingSessionEndSaveStatus.NO_RUNS -> TextMuted
                TimingSessionEndSaveStatus.UNCONFIRMED -> AccentGold
                else -> AccentGreen
            },
            title = stringResource(
                when (status) {
                    TimingSessionEndSaveStatus.NO_RUNS -> R.string.session_end_no_completed_runs
                    TimingSessionEndSaveStatus.UNCONFIRMED -> R.string.session_end_save_needs_attention
                    else -> R.string.session_end_saved_phone
                }
            ),
            detail = stringResource(
                when (status) {
                    TimingSessionEndSaveStatus.NO_RUNS -> R.string.session_end_nothing_to_save_short
                    TimingSessionEndSaveStatus.UNCONFIRMED -> R.string.session_end_save_unconfirmed_detail
                    TimingSessionEndSaveStatus.SAVED_GUEST -> R.string.session_end_guest_available_detail
                    TimingSessionEndSaveStatus.SAVED_SYNCING -> R.string.session_end_cloud_sync_detail
                }
            )
        )
    }
}

/** Translucent card standing in for iOS `adaptiveGlass` surfaces. */
@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground.copy(alpha = 0.72f), RoundedCornerShape(28.dp))
            .border(1.dp, BorderSubtle.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content
    )
}

@Composable
private fun ConnectionFailedContent(message: String, accent: Color) {
    StateMark(Icons.Filled.RemoveCircle, accent)
    TitleBlock(
        title = stringResource(R.string.connection_failed_title),
        body = message.ifBlank { stringResource(R.string.connection_failed_default_message) }
    )
    StatusRow(
        icon = Icons.Filled.PhoneAndroid,
        color = TextMuted,
        title = stringResource(R.string.connection_failed_no_session),
        detail = stringResource(R.string.connection_failed_no_session_detail)
    )
}

@Composable
private fun StateMark(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun TitleBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = TextPrimary,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = body,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium
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
                .padding(vertical = 12.dp),
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
