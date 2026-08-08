package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.onboarding.OnboardingPainPoint
import com.trackspeed.android.ui.theme.*

private const val MIN_CARD_COUNT = 3

@Composable
fun HowItWorksStep(
    selectedPainPoints: Set<OnboardingPainPoint>,
    onContinue: () -> Unit
) {
    val orderedPairs = rememberPainFixPairs(selectedPainPoints)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.onboarding_howitworks_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_howitworks_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            orderedPairs.forEach { pain ->
                PainFixRow(pain = pain)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(
                text = stringResource(R.string.common_continue),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

private fun rememberPainFixPairs(
    selectedPainPoints: Set<OnboardingPainPoint>
): List<OnboardingPainPoint> {
    val ordered = OnboardingPainPoint.entries.filter { selectedPainPoints.contains(it) }.toMutableList()
    if (ordered.size < MIN_CARD_COUNT) {
        for (pain in OnboardingPainPoint.defaultOrder) {
            if (!ordered.contains(pain)) {
                ordered += pain
            }
            if (ordered.size >= MIN_CARD_COUNT) break
        }
    }
    return ordered
}

@Composable
private fun PainFixRow(pain: OnboardingPainPoint) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(18.dp))
            .border(1.dp, DividerColor, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(accentForPain(pain), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fixIconForPain(pain),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(pain.painRes),
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                    color = TextMuted
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AccentGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(pain.fixRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
        }
    }
}

private fun fixIconForPain(pain: OnboardingPainPoint): ImageVector {
    return when (pain) {
        OnboardingPainPoint.PROGRESS_BLIND -> Icons.Default.ShowChart
        OnboardingPainPoint.STOPWATCH_INACCURATE -> Icons.Default.Timer
        OnboardingPainPoint.HARDWARE_EXPENSIVE -> Icons.Default.AttachMoney
        OnboardingPainPoint.NO_COACH -> Icons.Default.WifiTethering
        OnboardingPainPoint.WASTED_SESSIONS -> Icons.Default.ViewList
        OnboardingPainPoint.SPLITS_BLACK_BOX -> Icons.Default.CallSplit
    }
}

private fun accentForPain(pain: OnboardingPainPoint): Color {
    return when (pain) {
        OnboardingPainPoint.PROGRESS_BLIND -> Color(0xFF4CAF50)
        OnboardingPainPoint.STOPWATCH_INACCURATE -> Color(0xFF5C8DB8)
        OnboardingPainPoint.HARDWARE_EXPENSIVE -> Color(0xFFF59E0B)
        OnboardingPainPoint.NO_COACH -> Color(0xFF9A6BD4)
        OnboardingPainPoint.WASTED_SESSIONS -> Color(0xFF38A6A5)
        OnboardingPainPoint.SPLITS_BLACK_BOX -> Color(0xFFD94D6A)
    }
}
