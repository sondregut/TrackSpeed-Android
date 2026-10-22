package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trackspeed.android.R
import com.trackspeed.android.data.model.UserRole
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.ui.util.localizedDisplayName

@Composable
fun RoleSelectionStep(selectedRole: UserRole?, onRoleSelected: (UserRole) -> Unit, onContinue: () -> Unit) {
    SelectionPage(stringResource(R.string.guide_role_title), selectedRole != null, onContinue) {
        UserRole.entries.forEach { role ->
            Choice(selectedRole == role, stringResource(if (role == UserRole.ATHLETE) R.string.guide_role_myself else R.string.guide_role_coach)) { onRoleSelected(role) }
        }
    }
}

@Composable
fun SportSelectionStep(selectedDiscipline: SportDiscipline?, onDisciplineSelected: (SportDiscipline) -> Unit, onContinue: () -> Unit) {
    SelectionPage(stringResource(R.string.guide_sport_title), selectedDiscipline != null, onContinue) {
        listOf(SportDiscipline.SPRINT_100M, SportDiscipline.FOOTBALL, SportDiscipline.SOCCER,
            SportDiscipline.BASKETBALL, SportDiscipline.RUGBY, SportDiscipline.OTHER).forEach { sport ->
            Choice(selectedDiscipline == sport, if (sport == SportDiscipline.SPRINT_100M) stringResource(R.string.guide_track_field) else sport.localizedDisplayName()) { onDisciplineSelected(sport) }
        }
    }
}

@Composable
private fun SelectionPage(title: String, canContinue: Boolean, onContinue: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        Button(onClick = onContinue, enabled = canContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.common_continue)) }
    }
}

@Composable
private fun Choice(selected: Boolean, label: String, onSelect: () -> Unit) {
    OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    }
}
