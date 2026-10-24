package com.trackspeed.android.cloud

import com.trackspeed.android.cloud.dto.ProfileDto
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestValues
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProfileWritePayloadTest {
    @Test fun manualClearExplicitlyNullsOnlyThePRFields() {
        val original = Json.encodeToJsonElement(ProfileDto(supabaseUserId = "test-user", fullName = "Synthetic Athlete",
            primaryEvent = "100m", personalRecord = 10.5, flyingPrDistance = "10m", flyingPr = 1.1,
            photoUrl = "existing-photo", onboardingCompleted = true)).jsonObject
        val payload = profileWritePayload(original, ProfilePersonalBestValues(primaryEvent = SportDiscipline.MIDDLE_800M))
        assertEquals(JsonPrimitive("800m"), payload["primary_event"])
        assertEquals(JsonNull, payload["personal_record"])
        assertEquals(JsonNull, payload["flying_pr_distance"])
        assertEquals(JsonNull, payload["flying_pr"])
        assertEquals(JsonPrimitive("existing-photo"), payload["photo_url"])
        val changed = setOf("primary_event", "personal_record", "flying_pr_distance", "flying_pr")
        original.filterKeys { it !in changed }.forEach { (key, value) -> assertEquals(key, value, payload[key]) }
        assertFalse(payload.containsKey("pending_profile_pr_revision"))
        assertTrue(Json.encodeToString(payload).contains("\"personal_record\":null"))
    }

    @Test fun populatedValuesPreservePrecisionAndUnrelatedDefaultsCannotBecomeClears() {
        val base = Json { encodeDefaults = true }.encodeToJsonElement(ProfileDto(
            supabaseUserId = "test-user", fullName = "Synthetic Athlete", onboardingCompleted = true)).jsonObject
        assertEquals(JsonNull, base["photo_url"])
        val values = ProfilePersonalBestValues(SportDiscipline.SPRINT_200M, 21.123456789,
            FlyingDistance.METERS_20, 2.0123456789)
        val payload = profileWritePayload(base, values)
        assertFalse(payload.containsKey("photo_url"))
        assertFalse(payload.containsKey("date_of_birth"))
        assertFalse(payload.containsKey("email"))
        assertEquals("test-user", payload.getValue("supabase_user_id").jsonPrimitive.content)
        assertEquals(21.123456789, payload.getValue("personal_record").jsonPrimitive.double, 0.0)
        assertEquals(2.0123456789, payload.getValue("flying_pr").jsonPrimitive.double, 0.0)
        assertEquals("20m", payload.getValue("flying_pr_distance").jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidManualValuesCannotProduceAWritePayload() {
        profileWritePayload(buildJsonObject { put("supabase_user_id", "test-user") },
            ProfilePersonalBestValues(personalRecordSeconds = Double.NaN))
    }
}
