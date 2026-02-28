package com.trackspeed.android.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) onAuthSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .statusBarsPadding()
            .padding(horizontal = 32.dp)
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Color.White)
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            if (state.isSignUp) stringResource(R.string.auth_create_account) else stringResource(R.string.auth_welcome_back),
            fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White
        )
        Spacer(Modifier.height(32.dp))

        // Google Sign-In
        Button(
            onClick = { viewModel.signInWithGoogle(context) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            enabled = !state.isLoading
        ) {
            Text(stringResource(R.string.auth_continue_google), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = Color(0xFF3A3A3C))
            Text("  ${stringResource(R.string.auth_or)}  ", color = Color(0xFF8E8E93), fontSize = 14.sp)
            HorizontalDivider(Modifier.weight(1f), color = Color(0xFF3A3A3C))
        }
        Spacer(Modifier.height(24.dp))

        // Email
        OutlinedTextField(
            value = state.email,
            onValueChange = { viewModel.updateEmail(it) },
            label = { Text(stringResource(R.string.auth_email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0A84FF), unfocusedBorderColor = Color(0xFF3A3A3C),
                focusedLabelColor = Color(0xFF0A84FF), unfocusedLabelColor = Color(0xFF8E8E93)
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
                        tint = Color(0xFF8E8E93)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0A84FF), unfocusedBorderColor = Color(0xFF3A3A3C),
                focusedLabelColor = Color(0xFF0A84FF), unfocusedLabelColor = Color(0xFF8E8E93)
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
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF0A84FF), unfocusedBorderColor = Color(0xFF3A3A3C),
                    focusedLabelColor = Color(0xFF0A84FF), unfocusedLabelColor = Color(0xFF8E8E93)
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
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
                color = Color(0xFF0A84FF)
            )
        }
    }
}
