package com.trackspeed.android.ui.screens.onboarding.steps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.delay

// Avatar colors for social proof
private val avatarColors = listOf(
    Color(0xFF5C8DB8),  // AccentNavy
    Color(0xFF34A47A),
    Color(0xFFEC9732),
    Color(0xFF9333EA)
)

@Composable
fun ReferralStep(
    referralCode: String,
    referralLink: String,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    var copiedLink by remember { mutableStateOf(false) }

    // Reset copied state after delay
    LaunchedEffect(copiedLink) {
        if (copiedLink) {
            delay(2000)
            copiedLink = false
        }
    }

    // Pre-resolve strings for the how-it-works section
    val step1Title = stringResource(R.string.onboarding_referral_step1_title)
    val step1Desc = stringResource(R.string.onboarding_referral_step1_desc)
    val step2Title = stringResource(R.string.onboarding_referral_step2_title)
    val step2Desc = stringResource(R.string.onboarding_referral_step2_desc)
    val step3Title = stringResource(R.string.onboarding_referral_step3_title)
    val step3Desc = stringResource(R.string.onboarding_referral_step3_desc)
    val shareText = stringResource(R.string.onboarding_referral_share_text, referralLink)
    val shareChooserTitle = stringResource(R.string.onboarding_referral_share_chooser)

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            // Social proof avatars
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val initials = listOf("JD", "SM", "AK", "TR")
                initials.forEachIndexed { index, initial ->
                    Box(
                        modifier = Modifier
                            .offset(x = (-12 * index).dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(avatarColors[index % avatarColors.size]),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initial,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }

                // +100 badge
                Surface(
                    modifier = Modifier
                        .offset(x = (-12 * initials.size).dp)
                        .padding(start = 8.dp),
                    color = AccentGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "+100",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentGreen,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Title section
            Text(
                text = stringResource(R.string.onboarding_referral_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.onboarding_referral_subtitle),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onboarding_referral_body),
                fontSize = 15.sp,
                color = TextSecondary,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(32.dp))

            // Referral link card
            Text(
                text = stringResource(R.string.onboarding_referral_link_label),
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = referralLink,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(8.dp))

                    Surface(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Referral Link", referralLink))
                            copiedLink = true
                        },
                        color = if (copiedLink)
                            AccentGreen.copy(alpha = 0.1f)
                        else
                            AccentBlue.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (copiedLink) AccentGreen else AccentBlue
                            )
                            Text(
                                text = if (copiedLink) stringResource(R.string.onboarding_referral_copied) else stringResource(R.string.onboarding_referral_copy),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (copiedLink) AccentGreen else AccentBlue
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // How it works section
            Text(
                text = stringResource(R.string.onboarding_referral_how_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            listOf(
                Triple("1", step1Title, step1Desc),
                Triple("2", step2Title, step2Desc),
                Triple("3", step3Title, step3Desc)
            ).forEach { (num, title, description) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(AccentBlue.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = num,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentBlue
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text(
                            text = title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = description,
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        // Bottom buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BackgroundGradientBottom)
                .padding(horizontal = 32.dp)
                .padding(bottom = 32.dp, top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, shareChooserTitle)
                    context.startActivity(shareIntent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.onboarding_referral_share_button),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.common_skip), color = TextSecondary, fontSize = 14.sp)
            }
        }
    }
}
