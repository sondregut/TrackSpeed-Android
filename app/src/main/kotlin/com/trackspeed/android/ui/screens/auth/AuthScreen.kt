package com.trackspeed.android.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    onBack: (() -> Unit)? = null,
    startInSignInMode: Boolean = false,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (startInSignInMode) viewModel.setSignInMode()
    }

    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) onAuthSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .statusBarsPadding()
            .padding(horizontal = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextPrimary)
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            if (state.isSignUp) stringResource(R.string.auth_create_account) else stringResource(R.string.auth_welcome_back),
            fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.isSignUp) "Create an account to sync your data and unlock cloud features."
            else "Sign in to access your account.",
            fontSize = 15.sp, color = TextSecondary, lineHeight = 22.sp
        )
        Spacer(Modifier.height(32.dp))

        // Google Sign-In
        OutlinedButton(
            onClick = { viewModel.signInWithGoogle(context) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark),
            enabled = !state.isLoading
        ) {
            Image(
                painter = painterResource(R.drawable.ic_google),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.auth_continue_google), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = BorderSubtle)
            Text("  ${stringResource(R.string.auth_or)}  ", color = TextSecondary, fontSize = 14.sp)
            HorizontalDivider(Modifier.weight(1f), color = BorderSubtle)
        }
        Spacer(Modifier.height(24.dp))

        // Email
        OutlinedTextField(
            value = state.email,
            onValueChange = { viewModel.updateEmail(it) },
            label = { Text(stringResource(R.string.auth_email)) },
            placeholder = { Text("you@example.com", color = TextMuted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentNavy, unfocusedBorderColor = BorderSubtle,
                focusedLabelColor = AccentNavy, unfocusedLabelColor = TextSecondary,
                cursorColor = AccentNavy
            ),
            singleLine = true,
            enabled = !state.isLoading
        )
        Spacer(Modifier.height(16.dp))

        // Password
        OutlinedTextField(
            value = state.password,
            onValueChange = { viewModel.updatePassword(it) },
            label = { Text(stringResource(R.string.auth_password)) },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = stringResource(R.string.auth_toggle_password),
                        tint = TextSecondary
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentNavy, unfocusedBorderColor = BorderSubtle,
                focusedLabelColor = AccentNavy, unfocusedLabelColor = TextSecondary,
                cursorColor = AccentNavy
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
                label = { Text(stringResource(R.string.auth_confirm_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentNavy, unfocusedBorderColor = BorderSubtle,
                    focusedLabelColor = AccentNavy, unfocusedLabelColor = TextSecondary,
                    cursorColor = AccentNavy
                ),
                singleLine = true,
                enabled = !state.isLoading
            )
        }

        // Error
        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error!!, color = Color(0xFFFF453A), fontSize = 14.sp)
        }

        Spacer(Modifier.height(24.dp))

        // Submit
        Button(
            onClick = { viewModel.submitEmailAuth() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy),
            enabled = !state.isLoading
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(
                    if (state.isSignUp) stringResource(R.string.auth_sign_up) else stringResource(R.string.auth_sign_in),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = { viewModel.toggleSignUpSignIn() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (state.isSignUp) stringResource(R.string.auth_has_account)
                else stringResource(R.string.auth_no_account),
                color = AccentNavy
            )
        }
    }
}
