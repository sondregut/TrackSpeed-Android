package com.trackspeed.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trackspeed.android.model.StartType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.abs

@Entity(tableName = "athletes")
data class AthleteEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val nickname: String? = null,
    val color: String,
    val photoPath: String? = null,
    val birthdate: Long? = null,
    val gender: String? = null,
    val personalBestsJson: String = "{}",
    val seasonBestsJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: name

    fun personalBests(): Map<String, Double> = decodeBestMap(personalBestsJson)

    fun seasonBests(): Map<String, Double> = decodeBestMap(seasonBestsJson)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun prKey(distance: Double, startType: String): String {
            val distanceLabel = if (abs(distance - distance.toInt()) < 0.0001) {
                "${distance.toInt()}m"
            } else {
                String.format(java.util.Locale.getDefault(), "%.1fm", distance)
            }
            return "${StartType.fromRawValue(startType).rawValue}_$distanceLabel"
        }

        fun encodeBestMap(values: Map<String, Double>): String = json.encodeToString(values)

        fun decodeBestMap(raw: String?): Map<String, Double> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching { json.decodeFromString<Map<String, Double>>(raw) }
                .getOrDefault(emptyMap())
        }
    }
}
