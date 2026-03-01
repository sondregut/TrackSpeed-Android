package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*
import com.trackspeed.android.data.model.SportCategory
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.data.model.UserRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AthleteDetailsStep(
    selectedRole: UserRole?,
    selectedDiscipline: SportDiscipline?,
    onRoleSelected: (UserRole) -> Unit,
    onDisciplineSelected: (SportDiscipline) -> Unit,
    onContinue: () -> Unit
) {
    var showDisciplinePicker by remember { mutableStateOf(false) }

    val isAthlete = selectedRole == UserRole.ATHLETE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header at top (matching iOS)
        Spacer(Modifier.height(32.dp))
        Text(
            if (isAthlete) stringResource(R.string.onboarding_athlete_title_athlete)
            else stringResource(R.string.onboarding_athlete_title_coach),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isAthlete) stringResource(R.string.onboarding_athlete_subtitle_athlete)
            else stringResource(R.string.onboarding_athlete_subtitle_coach),
            fontSize = 15.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        // Centered content (matching iOS layout)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Trophy/stopwatch icon based on role (matching iOS goldMedal color)
            Icon(
                imageVector = if (isAthlete) Icons.Default.EmojiEvents else Icons.Default.Timer,
                contentDescription = null,
                tint = AccentGold,
                modifier = Modifier.size(56.dp)
            )

            // Discipline picker button (matching iOS style with icon + chevron)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.onboarding_athlete_event_label),
                    fontSize = 15.sp,
                    color = TextSecondary
                )

                OutlinedButton(
                    onClick = { showDisciplinePicker = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedDiscipline != null) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    selectedDiscipline.displayName,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    selectedDiscipline.category.displayName,
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.onboarding_athlete_event_placeholder),
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showDisciplinePicker) {
        ModalBottomSheet(
            onDismissRequest = { showDisciplinePicker = false },
            containerColor = SurfaceDark
        ) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                SportDiscipline.byCategory().forEach { (category, disciplines) ->
                    item {
                        Text(
                            category.displayName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(disciplines) { discipline ->
                        TextButton(
                            onClick = {
                                onDisciplineSelected(discipline)
                                showDisciplinePicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    discipline.displayName,
                                    color = if (selectedDiscipline == discipline) AccentBlue else TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selectedDiscipline == discipline) {
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = AccentBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
