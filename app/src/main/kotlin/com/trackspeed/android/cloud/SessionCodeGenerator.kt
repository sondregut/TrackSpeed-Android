package com.trackspeed.android.cloud

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Generates unique 6-digit numeric session codes for race pairing.
 * Checks uniqueness against existing pairing requests in Supabase.
 */
@Singleton
class SessionCodeGenerator @Inject constructor(
    private val raceEventService: RaceEventService
) {
    companion object {
        private const val CODE_LENGTH = 6
        private const val MAX_ATTEMPTS = 5
    }

    /**
     * Generate a unique 6-digit numeric session code.
     * Verifies the code doesn't already exist as an active pairing request.
     * @throws IllegalStateException if unable to generate a unique code after MAX_ATTEMPTS
     */
    suspend fun generateSessionCode(): String {
        repeat(MAX_ATTEMPTS) {
            val code = buildString {
                repeat(CODE_LENGTH) {
                    append(Random.nextInt(0, 10))
                }
            }
            val existing = raceEventService.getPairingRequest(code)
            if (existing == null) {
                return code
            }
        }
        throw IllegalStateException("Failed to generate unique session code after $MAX_ATTEMPTS attempts")
    }
}
