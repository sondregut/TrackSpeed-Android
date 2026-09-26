package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.onboarding.OnboardingPainPoint
import com.trackspeed.android.ui.theme.AccentBlue
import com.trackspeed.android.ui.theme.CardBackground
import com.trackspeed.android.ui.theme.DividerColor
import com.trackspeed.android.ui.theme.TextMuted
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary

@Composable
fun PainSwipeStep(
    selectedPainPoints: Set<OnboardingPainPoint>,
    onContinue: () -> Unit
) {
    val focusPainPoints = remember(selectedPainPoints) {
        val selectedInDefaultOrder = OnboardingPainPoint.defaultOrder
            .filter { selectedPainPoints.contains(it) }
        val selectedRemainder = OnboardingPainPoint.entries
            .filter { selectedPainPoints.contains(it) && !selectedInDefaultOrder.contains(it) }

        (selectedInDefaultOrder + selectedRemainder).ifEmpty {
            OnboardingPainPoint.defaultOrder.take(3)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.onboarding_pain_summary_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (selectedPainPoints.isEmpty()) {
                stringResource(R.string.onboarding_pain_summary_subtitle_default)
            } else {
                stringResource(R.string.onboarding_pain_summary_subtitle_selected)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AccentBlue.copy(alpha = 0.12f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                        .padding(5.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (selectedPainPoints.isEmpty()) {
                        stringResource(R.string.onboarding_pain_summary_badge_default)
                    } else {
                        stringResource(
                            R.string.onboarding_pain_summary_badge_selected,
                            selectedPainPoints.size
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(focusPainPoints) { pain ->
                PainFocusRow(
                    pain = pain,
                    isUserSelected = selectedPainPoints.contains(pain)
                )
            }
        }

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(
                text = stringResource(R.string.onboarding_pain_summary_continue),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun PainFocusRow(
    pain: OnboardingPainPoint,
    isUserSelected: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = if (isUserSelected) AccentBlue else DividerColor,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = iconForPain(pain),
            contentDescription = null,
            tint = if (isUserSelected) AccentBlue else TextSecondary,
            modifier = Modifier.size(28.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(pain.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isUserSelected) {
                    stringResource(R.string.onboarding_pain_summary_selected_item)
                } else {
                    stringResource(R.string.onboarding_pain_summary_common_item)
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

private fun iconForPain(pain: OnboardingPainPoint): ImageVector {
    return when (pain) {
        OnboardingPainPoint.PROGRESS_BLIND -> Icons.Default.ShowChart
        OnboardingPainPoint.STOPWATCH_INACCURATE -> Icons.Default.Timer
        OnboardingPainPoint.HARDWARE_EXPENSIVE -> Icons.Default.AttachMoney
        OnboardingPainPoint.NO_COACH -> Icons.Default.PersonOff
        OnboardingPainPoint.WASTED_SESSIONS -> Icons.Default.EventBusy
        OnboardingPainPoint.SPLITS_BLACK_BOX -> Icons.Default.CallSplit
    }
}
