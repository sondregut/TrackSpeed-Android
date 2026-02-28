package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.onboarding_athlete_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        // Role toggle
        Text(stringResource(R.string.onboarding_athlete_role_label), fontSize = 17.sp, color = Color(0xFFAEAEB2))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UserRole.entries.forEach { role ->
                OutlinedButton(
                    onClick = { onRoleSelected(role) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    border = BorderStroke(
                        1.dp,
                        if (selectedRole == role) Color(0xFF0A84FF) else Color(0xFF3A3A3C)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selectedRole == role) Color(0xFF0A84FF).copy(alpha = 0.15f) else Color.Transparent
                    )
                ) {
                    Text(role.displayName, color = if (selectedRole == role) Color(0xFF0A84FF) else Color.White)
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // Discipline picker
        Text(stringResource(R.string.onboarding_athlete_event_label), fontSize = 17.sp, color = Color(0xFFAEAEB2))
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { showDisciplinePicker = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            border = BorderStroke(1.dp, Color(0xFF3A3A3C))
        ) {
            Text(
                selectedDiscipline?.displayName ?: stringResource(R.string.onboarding_athlete_event_placeholder),
                color = if (selectedDiscipline != null) Color.White else Color(0xFF8E8E93)
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showDisciplinePicker) {
        ModalBottomSheet(
            onDismissRequest = { showDisciplinePicker = false },
            containerColor = Color(0xFF1C1C1E)
        ) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                SportDiscipline.byCategory().forEach { (category, disciplines) ->
                    item {
                        Text(
                            category.displayName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E8E93),
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
                            Text(
                                discipline.displayName,
                                color = if (selectedDiscipline == discipline) Color(0xFF0A84FF) else Color.White,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
