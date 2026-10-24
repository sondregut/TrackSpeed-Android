package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.auth.AuthViewModel
import com.trackspeed.android.ui.screens.settings.LanguagePickerDialog
import com.trackspeed.android.ui.screens.settings.applyLanguage
import com.trackspeed.android.ui.screens.settings.getLanguageDisplayName
import com.trackspeed.android.ui.theme.*

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeStep(
    selectedLanguage: String,
    onGetStarted: () -> Unit,
    onJoinSession: () -> Unit = {},
    onSignIn: () -> Unit,
    onLanguageSelected: (String) -> Unit = {},
    onDebugSkip: () -> Unit = {},
    onDebugPaywall: () -> Unit = {}
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showSignInSheet by remember { mutableStateOf(false) }
    var contentTop by remember { mutableStateOf<Float?>(null) }

    Box(Modifier.fillMaxSize()) {
        WelcomePhotoBackdrop(contentTop)
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // The minimum viewport height anchors controls at the bottom; scrolling keeps large text reachable.
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = onDebugSkip) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null,
                                tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
                        }
                    } else Spacer(Modifier.size(48.dp))
                    FilledTonalButton(
                        onClick = { showLanguagePicker = true },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (selectedLanguage == "system") stringResource(R.string.settings_language_system_short)
                            else getLanguageDisplayName(selectedLanguage), fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
                Column(
                    Modifier.widthIn(max = 500.dp).fillMaxWidth().padding(horizontal = 20.dp)
                        .padding(top = 20.dp, bottom = 8.dp)
                        .onGloballyPositioned { contentTop = it.positionInRoot().y },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.parity_onboarding_welcome_title),
                        fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                        color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics { heading() })
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onGetStarted, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.parity_onboarding_build_setup), fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                    OutlinedButton(
                        onClick = onJoinSession, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(50), border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.9f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.28f), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_welcome_join_session), fontSize = 17.sp,
                            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    }
                    TextButton(onClick = { showSignInSheet = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.onboarding_welcome_sign_in), color = Color.White,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center, textDecoration = TextDecoration.Underline)
                    }
                }
            }
        }
        if (showLanguagePicker) {
            LanguagePickerDialog(currentLanguage = selectedLanguage,
                onLanguageSelected = { tag -> onLanguageSelected(tag); applyLanguage(tag) },
                onDismiss = { showLanguagePicker = false })
        }
        if (showSignInSheet) {
            SignInBottomSheet(onDismiss = { showSignInSheet = false },
                onAuthenticated = { showSignInSheet = false; onSignIn() })
        }
    }
}

/** Match the current iPhone photo crop: keep its phone above the growing title/controls. */
@Composable
private fun WelcomePhotoBackdrop(contentTopInRoot: Float?) {
    var topInRoot by remember { mutableFloatStateOf(0f) }
    val relativeContentTop = contentTopInRoot?.minus(topInRoot)
    Layout(
        modifier = Modifier.fillMaxSize().clipToBounds().background(Color.Black)
            .onGloballyPositioned { topInRoot = it.positionInRoot().y }
            .drawWithContent {
                drawContent()
                val photo = welcomePhotoPlacement(size.width, size.height, relativeContentTop, density)
                val shadeHeight = minOf(180.dp.toPx(), size.height * 0.25f)
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f),
                    Color.Black.copy(alpha = 0.2f), Color.Transparent), endY = shadeHeight),
                    size = Size(size.width, shadeHeight))
                val phoneBottom = photo.top + photo.height * 0.761f
                val imageBottom = photo.top + photo.height
                val fadeHeight = (imageBottom - phoneBottom).coerceAtLeast(0f)
                if (fadeHeight > 0f) {
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent,
                        Color.Black.copy(alpha = if (imageBottom < size.height) 0.85f else 0.5f)),
                        startY = phoneBottom, endY = imageBottom),
                        topLeft = Offset(0f, phoneBottom), size = Size(size.width, fadeHeight))
                }
            },
        content = {
            Image(painterResource(R.drawable.onboarding_welcome_photo), contentDescription = null,
                contentScale = ContentScale.FillBounds)
        }
    ) { measurables, constraints ->
        val photo = welcomePhotoPlacement(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), relativeContentTop, density)
        val image = measurables.single().measure(Constraints.fixed(photo.width.roundToInt(), photo.height.roundToInt()))
        layout(constraints.maxWidth, constraints.maxHeight) {
            image.placeRelative((constraints.maxWidth - image.width) / 2, photo.top.roundToInt())
        }
    }
}

private data class WelcomePhotoPlacement(val width: Float, val height: Float, val top: Float)

private fun welcomePhotoPlacement(width: Float, height: Float, contentTop: Float?, density: Float): WelcomePhotoPlacement {
    val aspect = 850f / 1850f
    val photoWidth = maxOf(width, height * aspect)
    val photoHeight = photoWidth / aspect
    val centered = (height - photoHeight) / 2f
    val wanted = contentTop?.let { minOf(centered, it - 12f * density - photoHeight * 0.761f) } ?: centered
    return WelcomePhotoPlacement(photoWidth, photoHeight, maxOf(wanted, 52f * density - photoHeight * 0.287f))
}

// ── Sign-In Bottom Sheet (matching iOS SignInSheet) ────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInBottomSheet(
    onDismiss: () -> Unit,
    onAuthenticated: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showEmailForm by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    LaunchedEffect(Unit) { viewModel.setSignInMode() }

    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) onAuthenticated()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundGradientTop,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 32.dp)
                    .navigationBarsPadding()
            ) {
                // Title section (matching iOS: "Welcome back" + subtitle)
                Text(
                    text = stringResource(R.string.auth_welcome_back),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.auth_sign_in_subtitle),
                    fontSize = 15.sp,
                    color = TextSecondary,
                    lineHeight = 22.sp
                )

                Spacer(Modifier.height(32.dp))

                if (showEmailForm) {
                    // Email form (matching iOS expandable email form)

                    // Email field with envelope icon
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = { viewModel.updateEmail(it) },
                        placeholder = { Text(stringResource(R.string.auth_email), color = TextMuted) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Email,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = BorderSubtle,
                            unfocusedBorderColor = BorderSubtle,
                            focusedContainerColor = SurfaceDark,
                            unfocusedContainerColor = SurfaceDark,
                            cursorColor = AccentBlue
                        ),
                        singleLine = true,
                        enabled = !state.isLoading
                    )

                    Spacer(Modifier.height(16.dp))

                    // Password field with lock icon
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { viewModel.updatePassword(it) },
                        placeholder = { Text(stringResource(R.string.auth_password), color = TextMuted) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = stringResource(R.string.auth_toggle_password),
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = BorderSubtle,
                            unfocusedBorderColor = BorderSubtle,
                            focusedContainerColor = SurfaceDark,
                            unfocusedContainerColor = SurfaceDark,
                            cursorColor = AccentBlue
                        ),
                        singleLine = true,
                        enabled = !state.isLoading
                    )

                    // Confirm password (sign-up only)
                    if (state.isSignUp) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = state.confirmPassword,
                            onValueChange = { viewModel.updateConfirmPassword(it) },
                            placeholder = { Text(stringResource(R.string.auth_confirm_password), color = TextMuted) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = BorderSubtle,
                                unfocusedBorderColor = BorderSubtle,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark,
                                cursorColor = AccentBlue
                            ),
                            singleLine = true,
                            enabled = !state.isLoading
                        )
                    }

                    // Error message
                    if (state.error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.error!!,
                            color = TimerRed,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // Submit button
                    Button(
                        onClick = { viewModel.submitEmailAuth() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        enabled = !state.isLoading
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                if (state.isSignUp) stringResource(R.string.auth_sign_up)
                                else stringResource(R.string.auth_sign_in),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Toggle sign up / sign in
                    TextButton(
                        onClick = { viewModel.toggleSignUpSignIn() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (state.isSignUp) stringResource(R.string.auth_has_account)
                            else stringResource(R.string.auth_no_account),
                            color = AccentBlue,
                            fontSize = 14.sp
                        )
                    }

                    // Back to other sign-in options
                    TextButton(
                        onClick = {
                            showEmailForm = false
                            viewModel.updateEmail("")
                            viewModel.updatePassword("")
                            viewModel.updateConfirmPassword("")
                            viewModel.clearError()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.onboarding_auth_other_options),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    // Google Sign-In button
                    OutlinedButton(
                        onClick = { viewModel.signInWithGoogle(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, BorderSubtle),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark),
                        enabled = !state.isLoading
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_google),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.auth_continue_google),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // "or" divider
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(Modifier.weight(1f), color = BorderSubtle)
                        Text(
                            "  ${stringResource(R.string.auth_or)}  ",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                        HorizontalDivider(Modifier.weight(1f), color = BorderSubtle)
                    }

                    Spacer(Modifier.height(24.dp))

                    // Sign in with email button
                    OutlinedButton(
                        onClick = { showEmailForm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, BorderSubtle),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark),
                        enabled = !state.isLoading
                    ) {
                        Icon(
                            Icons.Default.Email,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.onboarding_auth_email),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Error message (e.g. from Google sign-in)
                    if (state.error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.error!!,
                            color = TimerRed,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Loading overlay
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = AccentBlue,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                stringResource(R.string.onboarding_auth_signing_in),
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
