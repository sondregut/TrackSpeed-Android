package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.data.model.UserRole
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.ui.util.localizedDisplayName

@Composable
fun RoleSelectionStep(selectedRole: UserRole?, onRoleSelected: (UserRole) -> Unit, onContinue: () -> Unit) {
    SelectionPage(
        title = stringResource(R.string.guide_role_title),
        photo = R.drawable.onboarding_welcome_photo,
        canContinue = selectedRole != null,
        onContinue = onContinue
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UserRole.entries.forEach { role ->
                val selected = selectedRole == role
                val foreground = if (selected) Color.Black else Color.White
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 88.dp)
                        .choiceSurface(selected).selectable(
                            selected = selected, role = Role.RadioButton,
                            onClick = { onRoleSelected(role) }
                        ).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (LocalDensity.current.fontScale < 1.3f) {
                        Box(
                            Modifier.size(42.dp).background(foreground.copy(alpha = 0.1f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (role == UserRole.ATHLETE) Icons.Default.DirectionsRun else Icons.Default.Groups,
                                contentDescription = null, tint = foreground, modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        stringResource(if (role == UserRole.ATHLETE) R.string.guide_role_myself else R.string.guide_role_coach),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = foreground
                    )
                    RadioButton(
                        selected = selected, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = foreground, unselectedColor = foreground)
                    )
                }
            }
        }
    }
}

@Composable
fun SportSelectionStep(selectedDiscipline: SportDiscipline?, onDisciplineSelected: (SportDiscipline) -> Unit, onContinue: () -> Unit) {
    val sports = listOf(SportDiscipline.SPRINT_100M, SportDiscipline.FOOTBALL, SportDiscipline.SOCCER,
        SportDiscipline.BASKETBALL, SportDiscipline.RUGBY, SportDiscipline.OTHER)
    SelectionPage(
        title = stringResource(R.string.guide_sport_title),
        photo = R.drawable.onboarding_ready_photo,
        canContinue = selectedDiscipline != null,
        onContinue = onContinue
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            // Native font scaling and narrow windows must not squeeze a choice into a two-column tile.
            val singleColumn = LocalDensity.current.fontScale >= 1.3f || maxWidth < 320.dp
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sports.chunked(if (singleColumn) 1 else 2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { sport ->
                            SportChoice(
                                sport = sport, selected = selectedDiscipline == sport,
                                singleColumn = singleColumn, modifier = Modifier.weight(1f),
                                onSelect = { onDisciplineSelected(sport) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SportChoice(sport: SportDiscipline, selected: Boolean, singleColumn: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val foreground = if (selected) Color.Black else Color.White
    val label = when (sport) {
        SportDiscipline.SPRINT_100M -> stringResource(R.string.guide_track_field)
        SportDiscipline.OTHER -> stringResource(R.string.parity_onboarding_other_sport)
        else -> sport.localizedDisplayName()
    }
    val surface = modifier.heightIn(min = 112.dp).choiceSurface(selected)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(12.dp)
    if (singleColumn) {
        Row(surface, verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = foreground)
            RadioButton(selected, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = foreground, unselectedColor = foreground))
        }
    } else {
        Column(surface, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(sportIcon(sport), contentDescription = null, tint = foreground, modifier = Modifier.size(26.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = foreground, textAlign = TextAlign.Center)
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
        }
    }
}

private fun sportIcon(sport: SportDiscipline): ImageVector = when (sport) {
    SportDiscipline.SPRINT_100M -> Icons.Default.DirectionsRun
    SportDiscipline.FOOTBALL -> Icons.Default.SportsFootball
    SportDiscipline.SOCCER -> Icons.Default.SportsSoccer
    SportDiscipline.BASKETBALL -> Icons.Default.SportsBasketball
    SportDiscipline.RUGBY -> Icons.Default.SportsRugby
    else -> Icons.Default.Sports
}

private fun Modifier.choiceSurface(selected: Boolean): Modifier = clip(RoundedCornerShape(12.dp))
    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.62f))

@Composable
private fun SelectionPage(title: String, @DrawableRes photo: Int, canContinue: Boolean, onContinue: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        OnboardingPhotoBackdrop(photo)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(title, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, modifier = Modifier.semantics { heading() })
                content()
            }
            OnboardingPhotoContinueButton(
                stringResource(R.string.common_continue), onContinue,
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                enabled = canContinue
            )
        }
    }
}
