package com.trackspeed.android.ui.screens.referral

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.referral.ContactsService
import com.trackspeed.android.R
import com.trackspeed.android.referral.ReferralContact
import com.trackspeed.android.referral.ReferralService
import com.trackspeed.android.referral.ReferralStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReferralUiState(
    val referralCode: String = "",
    val referralLink: String = "",
    val shareMessage: String = "",
    val stats: ReferralStats = ReferralStats(),
    val isLoading: Boolean = true,
    val copiedToClipboard: Boolean = false,
    val contactsPermissionState: ContactsPermissionState = ContactsPermissionState.Unknown,
    val contacts: List<ReferralContact> = emptyList(),
    val contactSearchQuery: String = "",
    val isContactsLoading: Boolean = false,
    val contactsErrorMessage: String? = null,
    val invitedContactIds: Set<String> = emptySet()
) {
    val filteredContacts: List<ReferralContact>
        get() {
            val query = contactSearchQuery.trim()
            if (query.isEmpty()) return contacts

            val lowerQuery = query.lowercase()
            return contacts.filter { contact ->
                contact.fullName.lowercase().contains(lowerQuery) ||
                    contact.phoneNumber.contains(query)
            }
        }
}

enum class ContactsPermissionState {
    Unknown,
    Granted,
    Denied
}

@HiltViewModel
class ReferralViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val referralService: ReferralService,
    private val contactsService: ContactsService
) : ViewModel() {

    companion object {
        private const val TAG = "ReferralViewModel"
    }

    private val _uiState = MutableStateFlow(ReferralUiState())
    val uiState: StateFlow<ReferralUiState> = _uiState.asStateFlow()

    init {
        loadReferralData()
        syncContactsPermissionState()
    }

    private fun loadReferralData() {
        viewModelScope.launch {
            try {
                val code = referralService.getOrCreateReferralCode()
                val link = referralService.getReferralLink()
                val message = referralService.getShareMessage()

                _uiState.update {
                    it.copy(
                        referralCode = code,
                        referralLink = link,
                        shareMessage = message,
                        isLoading = false
                    )
                }

                // Refresh stats from Supabase
                refreshStats()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load referral data: ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
            }
        }

        // Collect local stats cache
        viewModelScope.launch {
            referralService.stats.collect { stats ->
                _uiState.update { it.copy(stats = stats) }
            }
        }
    }

    private suspend fun refreshStats() {
        try {
            referralService.refreshStats()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh referral stats: ${e.message}")
        }
    }

    fun onCopiedToClipboard() {
        _uiState.update { it.copy(copiedToClipboard = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(copiedToClipboard = false) }
        }
    }

    fun onContactsPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                contactsPermissionState = if (granted) ContactsPermissionState.Granted else ContactsPermissionState.Denied,
                contactsErrorMessage = null
            )
        }
        if (granted) {
            loadContacts()
        }
    }

    fun refreshContactsPermissionState() {
        val hasPermission = contactsService.hasContactsPermission()
        val current = _uiState.value

        when {
            hasPermission && current.contactsPermissionState != ContactsPermissionState.Granted -> {
                _uiState.update { it.copy(contactsPermissionState = ContactsPermissionState.Granted) }
                loadContacts()
            }
            !hasPermission && current.contactsPermissionState == ContactsPermissionState.Granted -> {
                _uiState.update {
                    it.copy(
                        contactsPermissionState = ContactsPermissionState.Denied,
                        contacts = emptyList(),
                        isContactsLoading = false
                    )
                }
            }
        }
    }

    fun onContactSearchChanged(query: String) {
        _uiState.update { it.copy(contactSearchQuery = query) }
    }

    fun loadContacts() {
        if (!contactsService.hasContactsPermission()) {
            _uiState.update {
                it.copy(
                    contactsPermissionState = ContactsPermissionState.Denied,
                    isContactsLoading = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    contactsPermissionState = ContactsPermissionState.Granted,
                    isContactsLoading = true,
                    contactsErrorMessage = null
                )
            }

            try {
                val contacts = contactsService.fetchContacts()
                _uiState.update {
                    it.copy(
                        contacts = contacts,
                        isContactsLoading = false
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load contacts: ${e.message}")
                _uiState.update {
                    it.copy(
                        isContactsLoading = false,
                        contactsErrorMessage = context.getString(R.string.referral_contacts_load_failed)
                    )
                }
            }
        }
    }

    fun onContactInviteLaunched(contactId: String) {
        _uiState.update {
            it.copy(invitedContactIds = it.invitedContactIds + contactId)
        }
    }

    fun contactInviteMessage(): String {
        val current = _uiState.value
        return contactsService.inviteMessage(
            referralCode = current.referralCode,
            referralLink = current.referralLink
        )
    }

    private fun syncContactsPermissionState() {
        if (contactsService.hasContactsPermission()) {
            _uiState.update { it.copy(contactsPermissionState = ContactsPermissionState.Granted) }
            loadContacts()
        }
    }
}
