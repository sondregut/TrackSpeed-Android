package com.trackspeed.android.ui.screens.onboarding.steps

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.play.core.review.ReviewManagerFactory
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

@Composable
fun RatingStep(onContinue: () -> Unit) {
    val context = LocalContext.current

    // Auto-trigger Play Store in-app review (like iOS StoreKit)
    LaunchedEffect(Unit) {
        try {
            val reviewManager = ReviewManagerFactory.create(context)
            val request = reviewManager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    val activity = context as? Activity
                    if (activity != null) {
                        reviewManager.launchReviewFlow(activity, reviewInfo)
                    }
                }
            }
        } catch (_: Exception) {
            // Review not available, continue silently
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // Star rating display
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.onboarding_rating_score),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Row {
                    repeat(5) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = AccentGold
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.onboarding_rating_made_for_you),
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.onboarding_rating_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        // Testimonial cards
        TestimonialCard(
            quote = stringResource(R.string.onboarding_rating_quote1),
            author = stringResource(R.string.onboarding_rating_author1)
        )
        Spacer(Modifier.height(12.dp))
        TestimonialCard(
            quote = stringResource(R.string.onboarding_rating_quote2),
            author = stringResource(R.string.onboarding_rating_author2)
        )
        Spacer(Modifier.height(12.dp))
        TestimonialCard(
            quote = stringResource(R.string.onboarding_rating_quote3),
            author = stringResource(R.string.onboarding_rating_author3)
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
        ) {
            Text(
                stringResource(R.string.common_continue),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TestimonialCard(
    quote: String,
    author: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.FormatQuote,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = AccentNavy.copy(alpha = 0.5f)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    quote,
                    fontSize = 15.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.onboarding_rating_author_format, author),
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
