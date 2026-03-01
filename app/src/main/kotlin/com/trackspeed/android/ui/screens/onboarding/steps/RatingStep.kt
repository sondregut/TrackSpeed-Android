package com.trackspeed.android.ui.screens.onboarding.steps

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.play.core.review.ReviewManagerFactory
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

private data class Testimonial(
    val name: String,
    val role: String,
    val quote: String,
    val initials: String,
    val color: Color
)

private val testimonials = listOf(
    Testimonial(
        name = "Sondre Guttormsen",
        role = "Olympic Pole Vaulter",
        quote = "I use TrackSpeed to dial in my run-up before competitions. The accuracy is impressive and I always know exactly what speed I'm hitting at takeoff.",
        initials = "SG",
        color = Color(0xFF5C8DB8)
    ),
    Testimonial(
        name = "Andreas Trajkovski",
        role = "Macedonian Long Jump Record Holder",
        quote = "As a long jumper, speed is everything. I time all my sprints with TrackSpeed and love how quick and easy it is to set up.",
        initials = "AT",
        color = Color(0xFF4CAF50)
    )
)

@Composable
fun RatingStep(onContinue: () -> Unit) {
    val context = LocalContext.current

    // Auto-trigger Play Store in-app review
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
        } catch (_: Exception) { }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // Title
            Text(
                stringResource(R.string.onboarding_rating_title),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // Rating card with laurels and stars
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SurfaceDark,
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("5.0", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(5) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = AccentGold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // "Made for you" text
            Text(
                "TrackSpeed was made for\npeople like you",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            // Avatar stack
            Row(
                horizontalArrangement = Arrangement.spacedBy((-12).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                testimonials.forEach { t ->
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(t.color),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            t.initials,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                // Extra placeholder avatar
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Testimonial cards
            testimonials.forEach { testimonial ->
                TestimonialCard(testimonial)
                Spacer(Modifier.height(12.dp))
            }

            // Bottom padding for floating button
            Spacer(Modifier.height(100.dp))
        }

        // Floating continue button at bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            BackgroundGradientBottom
                        )
                    )
                )
                .padding(horizontal = 32.dp)
                .padding(bottom = 32.dp, top = 24.dp)
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text(
                    stringResource(R.string.common_continue),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TestimonialCard(testimonial: Testimonial) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with avatar, name/role, stars
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(testimonial.color),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        testimonial.initials,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        testimonial.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        testimonial.role,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(5) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = AccentGold
                        )
                    }
                }
            }

            // Quote
            Text(
                testimonial.quote,
                fontSize = 14.sp,
                color = TextSecondary,
                lineHeight = 20.sp
            )
        }
    }
}
