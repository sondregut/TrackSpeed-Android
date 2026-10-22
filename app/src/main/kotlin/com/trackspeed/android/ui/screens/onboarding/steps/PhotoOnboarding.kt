package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.AccentNavy

internal val OnboardingFramedBackground = Color(0xFF0C1113)

/** Owned session photography, using the current iPhone crop and top shade. */
@Composable
fun OnboardingPhotoBackdrop(@DrawableRes photo: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        Image(painterResource(photo), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxWidth().height(minOf(220.dp, maxHeight * 0.3f))
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f),
                0.55f to Color.Black.copy(alpha = 0.2f), 1f to Color.Transparent)))
    }
}

/** Native button semantics and ripple, with the shared photo-story capsule shape. */
@Composable
fun OnboardingPhotoContinueButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDarkPage: Boolean = false
) {
    Button(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (onDarkPage) Color.White else AccentNavy,
            contentColor = if (onDarkPage) Color.Black else Color.White)) {
        Text(title, modifier = Modifier.weight(1f, fill = false),
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
            modifier = Modifier.size(20.dp))
    }
}

/** Main tour: copy at the top, independent scrolling, and an always reachable action. */
@Composable
internal fun OnboardingFeaturePage(
    @StringRes title: Int,
    @StringRes detail: Int,
    onContinue: () -> Unit,
    @DrawableRes photo: Int? = null,
    @StringRes button: Int = R.string.common_continue,
    illustration: @Composable ColumnScope.() -> Unit = {}
) {
    val framed = photo == null
    Box(Modifier.fillMaxSize().background(OnboardingFramedBackground)) {
        photo?.let { OnboardingPhotoBackdrop(it) }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(title), color = Color.White,
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp,
                        lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                        shadow = if (framed) null else Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), 10f)),
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(detail), color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp,
                        lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
                        shadow = if (framed) null else Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 2f), 10f)))
                Spacer(Modifier.height(20.dp))
                illustration()
            }
            OnboardingPhotoContinueButton(stringResource(button), onContinue,
                modifier = Modifier.widthIn(max = 600.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                onDarkPage = framed)
        }
    }
}
