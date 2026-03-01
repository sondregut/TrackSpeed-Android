package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.auth.AuthViewModel
import com.trackspeed.android.ui.screens.settings.LanguagePickerDialog
import com.trackspeed.android.ui.screens.settings.applyLanguage
import com.trackspeed.android.ui.screens.settings.getCurrentLanguageTag
import com.trackspeed.android.ui.screens.settings.getLanguageDisplayName
import com.trackspeed.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeStep(
    onGetStarted: () -> Unit,
    onJoinSession: () -> Unit = {},
    onSignIn: () -> Unit,
    onDebugSkip: () -> Unit = {},
    onDebugPaywall: () -> Unit = {}
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(getCurrentLanguageTag()) }
    var showSignInSheet by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "welcome_fade"
    )
    val offsetY by animateFloatAsState(
        targetValue = if (appeared) 0f else 20f,
        animationSpec = tween(durationMillis = 600),
        label = "welcome_offset"
    )

    LaunchedEffect(Unit) { appeared = true }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background image with blur (matching iOS SprintFinishBackground fallback)
        Image(
            painter = painterResource(R.drawable.sprint_finish_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(6.dp)
        )

        // Dark gradient overlay matching iOS: 0.4 -> 0.6 -> 0.85
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            BackgroundGradientTop.copy(alpha = 0.4f),
                            BackgroundGradientBottom.copy(alpha = 0.6f),
                            BackgroundGradientBottom.copy(alpha = 0.85f)
                        )
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar: debug skip (left) + language picker (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Debug skip (subtle, matching iOS forward.fill at 0.3 opacity)
                IconButton(onClick = onDebugSkip) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Language button (matching iOS ultraThinMaterial pill)
                FilledTonalButton(
                    onClick = { showLanguagePicker = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        getLanguageDisplayName(currentLanguage).take(12),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Logo and title (matching iOS VStack spacing: lg)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.offset(y = offsetY.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.app_logo),
                    contentDescription = stringResource(R.string.onboarding_welcome_logo_description),
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(22.dp)),
                    alpha = alpha
                )
                Spacer(Modifier.height(20.dp))
                // iOS: "Welcome to" in title2, textSecondary
                Text(
                    text = "Welcome to",
                    fontSize = 22.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.offset(y = offsetY.dp)
                )
                Spacer(Modifier.height(4.dp))
                // iOS: "TrackSpeed" in title1, text
                Text(
                    text = stringResource(R.string.onboarding_welcome_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.offset(y = offsetY.dp)
                )
                Spacer(Modifier.height(12.dp))
                // iOS: "Professional sprint timing using your iPhone" in body, textSecondary
                Text(
                    text = stringResource(R.string.onboarding_welcome_tagline),
                    fontSize = 16.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.offset(y = offsetY.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // Buttons (matching iOS: centered 260dp width, pill shape)
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .offset(y = offsetY.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Get Started - iOS: width 260, height 50, full rounded, onboardingAccent
                Button(
                    onClick = onGetStarted,
                    modifier = Modifier
                        .width(260.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(
                        stringResource(R.string.onboarding_welcome_get_started),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Join Session - iOS: outlined pill with antenna icon
                OutlinedButton(
                    onClick = onJoinSession,
                    modifier = Modifier
                        .width(260.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(2.dp, AccentBlue)
                ) {
                    Icon(
                        Icons.Default.SensorsOff,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.onboarding_welcome_join_session),
                        color = AccentBlue,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Sign in link - iOS: underlined, textSecondary — opens bottom sheet
                TextButton(onClick = { showSignInSheet = true }) {
                    Text(
                        stringResource(R.string.onboarding_welcome_sign_in),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textDecoration = TextDecoration.Underline
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
        }

        // Language picker dialog
        if (showLanguagePicker) {
            LanguagePickerDialog(
                currentLanguage = currentLanguage,
                onLanguageSelected = { tag ->
                    applyLanguage(tag)
                    currentLanguage = tag
                },
                onDismiss = { showLanguagePicker = false }
            )
        }

        // Sign-in bottom sheet (matching iOS SignInSheet with .presentationDetents([.medium, .large]))
        if (showSignInSheet) {
            SignInBottomSheet(
                onDismiss = { showSignInSheet = false },
                onAuthenticated = {
                    showSignInSheet = false
                    onSignIn()
                }
            )
        }
    }
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
                        placeholder = { Text("Email", color = TextMuted) },
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
                        placeholder = { Text("Password", color = TextMuted) },
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
                            placeholder = { Text("Confirm Password", color = TextMuted) },
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
