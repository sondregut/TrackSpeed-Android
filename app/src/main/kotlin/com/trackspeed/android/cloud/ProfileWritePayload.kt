package com.trackspeed.android.cloud

import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestValues
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only an explicit queued manual PR edit may clear these nullable profile fields.
 * Preserve every other populated field from the configured DTO serializer; do
 * not turn unrelated default-null properties (photo, birthday, etc.) into clears.
 */
internal fun profileWritePayload(serializedProfile: JsonObject, values: ProfilePersonalBestValues): JsonObject {
    require(values.isValid) { "Invalid profile personal best values" }
    return buildJsonObject {
        serializedProfile.forEach { (key, value) -> if (value != JsonNull) put(key, value) }
        put("primary_event", values.primaryEvent.rawValue)
        put("personal_record", values.personalRecordSeconds?.let(::JsonPrimitive) ?: JsonNull)
        put("flying_pr_distance", values.flyingDistance?.rawValue?.let(::JsonPrimitive) ?: JsonNull)
        put("flying_pr", values.flyingPrSeconds?.let(::JsonPrimitive) ?: JsonNull)
    }
}
