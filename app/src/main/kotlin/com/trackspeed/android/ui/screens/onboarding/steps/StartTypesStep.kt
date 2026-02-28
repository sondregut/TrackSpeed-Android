package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

private data class StartTypeInfo(val nameRes: Int, val descriptionRes: Int, val icon: ImageVector)

private val startTypes = listOf(
    StartTypeInfo(R.string.onboarding_starttypes_touch_name, R.string.onboarding_starttypes_touch_desc, Icons.Default.TouchApp),
    StartTypeInfo(R.string.onboarding_starttypes_countdown_name, R.string.onboarding_starttypes_countdown_desc, Icons.Default.Timer),
    StartTypeInfo(R.string.onboarding_starttypes_flying_name, R.string.onboarding_starttypes_flying_desc, Icons.Default.DirectionsRun),
    StartTypeInfo(R.string.onboarding_starttypes_voice_name, R.string.onboarding_starttypes_voice_desc, Icons.Default.Mic),
    StartTypeInfo(R.string.onboarding_starttypes_auto_name, R.string.onboarding_starttypes_auto_desc, Icons.Default.AutoMode)
)

@Composable
fun StartTypesStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.onboarding_starttypes_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_starttypes_subtitle), fontSize = 17.sp, color = Color(0xFFAEAEB2), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(startTypes) { type ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(type.icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = Color(0xFF0A84FF))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(type.nameRes), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(stringResource(type.descriptionRes), fontSize = 13.sp, color = Color(0xFF8E8E93))
                        }
                    }
                }
            }
        }

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }
}
