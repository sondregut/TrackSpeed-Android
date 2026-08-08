package com.trackspeed.android.referral

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ReferralContact(
    val id: String,
    val fullName: String,
    val phoneNumber: String,
    val initials: String
) {
    companion object {
        fun makeInitials(name: String): String {
            val parts = name
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }

            return when {
                parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
                parts.size == 1 -> parts.first().first().uppercase()
                else -> "?"
            }
        }
    }
}

@Singleton
class ContactsService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun fetchContacts(): List<ReferralContact> = withContext(Dispatchers.IO) {
        if (!hasContactsPermission()) return@withContext emptyList()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
        val seenContactIds = mutableSetOf<String>()
        val contacts = mutableListOf<ReferralContact>()

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)?.takeIf { it.isNotBlank() } ?: continue
                if (!seenContactIds.add(id)) continue

                val fullName = cursor.getString(nameIndex)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val phoneNumber = cursor.getString(numberIndex)?.trim()?.takeIf { it.isNotBlank() } ?: continue

                contacts += ReferralContact(
                    id = id,
                    fullName = fullName,
                    phoneNumber = phoneNumber,
                    initials = ReferralContact.makeInitials(fullName)
                )
            }
        }

        contacts.sortedBy { it.fullName.lowercase() }
    }

    fun searchContacts(contacts: List<ReferralContact>, query: String): List<ReferralContact> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return contacts

        val lowerQuery = normalizedQuery.lowercase()
        return contacts.filter { contact ->
            contact.fullName.lowercase().contains(lowerQuery) ||
                contact.phoneNumber.contains(normalizedQuery)
        }
    }

    fun inviteMessage(referralCode: String, referralLink: String): String {
        return "Join me on TrackSpeed and use my code $referralCode to get started!\n\n" +
            "Download for iOS: https://apps.apple.com/app/trackspeed/id6757509163\n" +
            "Download for Android: https://play.google.com/store/apps/details?id=com.trackspeed.android\n\n" +
            referralLink
    }
}
