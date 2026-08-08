package com.trackspeed.android.ui.screens.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.cloud.AuthState
import com.trackspeed.android.cloud.DeviceIdProvider
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.repository.AuthRepository
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.ui.util.formatDistance
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class PersonalBest(
    val distance: Double,
    val timeSeconds: Double
) {
    val distanceLabel: String
        get() = formatDistance(distance)
}

data class ProfileUiState(
    val userName: String = "",
    val avatarPhotoPath: String? = null,
    val totalSessions: Int = 0,
    val totalRuns: Int = 0,
    val bestTimeSeconds: Double? = null,
    val personalBests: List<PersonalBest> = emptyList(),
    val isProUser: Boolean = false,
    val athleteCount: Int = 0,
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val showDeleteConfirmation: Boolean = false,
    val showSignOutConfirmation: Boolean = false,
    val accountActionError: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val subscriptionManager: SubscriptionManager,
    private val athleteDao: AthleteDao,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val deviceIdProvider: DeviceIdProvider
) : ViewModel() {

    private val _showDeleteConfirmation = MutableStateFlow(false)
    private val _showSignOutConfirmation = MutableStateFlow(false)
    private val _accountActionError = MutableStateFlow<String?>(null)

    private val athleteCount: StateFlow<Int> =
        athleteDao.getAthleteCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    private val totalSessions: StateFlow<Int> =
        sessionRepository.getTotalSessionCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    private val totalRuns: StateFlow<Int> =
        sessionRepository.getTotalRunCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    private val bestOverallTime: StateFlow<Double?> =
        sessionRepository.getGlobalBestTime()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    private val personalBests: StateFlow<List<PersonalBest>> =
        sessionRepository.getDistinctDistances()
            .flatMapLatest { distances ->
                if (distances.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val flows = distances.map { distance ->
                        sessionRepository.getPersonalBestFlow(distance)
                            .map { time ->
                                time?.let { PersonalBest(distance, it) }
                            }
                    }
                    combine(flows) { results ->
                        results.filterNotNull().sortedBy { it.distance }
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    private val userName: StateFlow<String> =
        settingsRepository.userName
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ""
            )

    private val avatarPhotoPath: StateFlow<String?> =
        settingsRepository.avatarPhotoPath
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    val uiState: StateFlow<ProfileUiState> =
        combine(
            combine(
                totalSessions,
                totalRuns,
                bestOverallTime,
                personalBests,
                subscriptionManager.isProUser
            ) { values ->
                ProfileUiState(
                    totalSessions = values[0] as Int,
                    totalRuns = values[1] as Int,
                    bestTimeSeconds = values[2] as Double?,
                    personalBests = @Suppress("UNCHECKED_CAST") (values[3] as List<PersonalBest>),
                    isProUser = values[4] as Boolean
                )
            },
            combine(
                athleteCount,
                userName,
                avatarPhotoPath,
                _showDeleteConfirmation,
                _showSignOutConfirmation,
                _accountActionError,
                authRepository.authState
            ) { values ->
                ProfileAccountExtra(
                    athleteCount = values[0] as Int,
                    userName = values[1] as String,
                    avatarPhotoPath = values[2] as String?,
                    showDeleteConfirmation = values[3] as Boolean,
                    showSignOutConfirmation = values[4] as Boolean,
                    accountActionError = values[5] as String?,
                    authState = values[6] as AuthState
                )
            }
        ) { state, extra ->
            val authenticated = extra.authState as? AuthState.Authenticated
            state.copy(
                athleteCount = extra.athleteCount,
                userName = extra.userName,
                avatarPhotoPath = extra.avatarPhotoPath,
                showDeleteConfirmation = extra.showDeleteConfirmation,
                showSignOutConfirmation = extra.showSignOutConfirmation,
                accountActionError = extra.accountActionError,
                isSignedIn = authenticated != null && !authenticated.isAnonymous,
                userEmail = authenticated?.takeUnless { it.isAnonymous }?.email
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProfileUiState()
        )

    fun updateUserName(name: String) {
        viewModelScope.launch {
            settingsRepository.setUserName(name)
        }
    }

    fun onAvatarPhotoSelected(uri: Uri) {
        viewModelScope.launch {
            val savedPath = saveAvatarPhoto(uri)
            if (savedPath != null) {
                settingsRepository.setAvatarPhotoPath(savedPath)
            }
        }
    }

    fun onAvatarPhotoCaptured(success: Boolean, photoUri: Uri?) {
        if (success && photoUri != null) {
            viewModelScope.launch {
                val savedPath = saveAvatarPhoto(photoUri)
                if (savedPath != null) {
                    settingsRepository.setAvatarPhotoPath(savedPath)
                }
            }
        }
    }

    fun removeAvatarPhoto() {
        viewModelScope.launch {
            val currentPath = avatarPhotoPath.value
            if (currentPath != null) {
                withContext(Dispatchers.IO) {
                    File(currentPath).delete()
                }
            }
            settingsRepository.setAvatarPhotoPath(null)
        }
    }

    fun showDeleteAccountDialog() {
        _showDeleteConfirmation.value = true
    }

    fun dismissDeleteAccountDialog() {
        _showDeleteConfirmation.value = false
    }

    fun showSignOutDialog() {
        _showSignOutConfirmation.value = true
    }

    fun dismissSignOutDialog() {
        _showSignOutConfirmation.value = false
    }

    fun confirmSignOut() {
        _showSignOutConfirmation.value = false
        viewModelScope.launch {
            runCatching {
                authRepository.signOut()
            }.onFailure {
                _accountActionError.value = "Unable to sign out. Please try again."
            }
        }
    }

    fun confirmDeleteAccount() {
        _showDeleteConfirmation.value = false
        viewModelScope.launch {
            runCatching {
                authRepository.deleteAccount(deviceIdProvider.deviceId)
            }.onFailure {
                _accountActionError.value = "Unable to delete account. Please try again."
            }
        }
    }

    fun dismissAccountActionError() {
        _accountActionError.value = null
    }

    /**
     * Creates a temporary file URI for camera capture.
     * Returns the URI to pass to TakePicture contract.
     */
    fun createTempPhotoUri(): Uri {
        val dir = File(context.filesDir, "avatar")
        dir.mkdirs()
        val file = File(dir, "capture_temp.jpg")
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private suspend fun saveAvatarPhoto(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val original = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (original == null) return@withContext null

                // Resize to max 400x400
                val resized = resizeBitmap(original, 400)

                val dir = File(context.filesDir, "avatar")
                dir.mkdirs()
                val file = File(dir, "profile_photo.jpg")
                FileOutputStream(file).use { out ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }

                if (resized !== original) resized.recycle()
                original.recycle()

                file.absolutePath
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap

        val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}

private data class ProfileAccountExtra(
    val athleteCount: Int,
    val userName: String,
    val avatarPhotoPath: String?,
    val showDeleteConfirmation: Boolean,
    val showSignOutConfirmation: Boolean,
    val accountActionError: String?,
    val authState: AuthState
)
