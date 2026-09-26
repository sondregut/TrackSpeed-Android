package com.trackspeed.android.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplicaDetectionConfigurationTest {

    @Test
    fun `valid remote profile overlays bundled defaults`() {
        val profile = ReplicaDetectionConfiguration.resolveRemoteJson(
            rawJson = """
                {
                  "schemaVersion": 1,
                  "pipeline": "replica_v1",
                  "revision": "field-2026-07-21-a",
                  "rolloutPercentage": 100,
                  "parameters": {
                    "diffThreshold": 18,
                    "torsoRunAbsMin": 28,
                    "incoherentSceneMotionGuardEnabled": false
                  }
                }
            """.trimIndent(),
            appVersion = "2.4.0",
            rolloutBucket = 9_999
        )

        assertNotNull(profile)
        profile!!
        assertEquals("field-2026-07-21-a", profile.revision)
        assertEquals("remote", profile.source)
        assertEquals(18, profile.parameters.diffThreshold)
        assertEquals(28, profile.parameters.torsoRunAbsMin)
        assertFalse(profile.parameters.incoherentSceneMotionGuardEnabled)
        assertEquals(
            ReplicaDetectionConfiguration.bundled.parameters.heightFraction,
            profile.parameters.heightFraction
        )
    }

    @Test
    fun `unknown parameter rejects entire profile`() {
        val error = assertThrows(ReplicaDetectionConfiguration.ValidationException::class.java) {
            resolve("""{ "downloadAndExecute": true }""", revision = "bad-key")
        }

        assertEquals("Unknown detection parameter downloadAndExecute", error.message)
    }

    @Test
    fun `invalid threshold relationship rejects entire profile`() {
        assertThrows(ReplicaDetectionConfiguration.ValidationException::class.java) {
            resolve(
                """
                    {
                      "torsoRunAbsMin": 70,
                      "torsoRunAbsMax": 50
                    }
                """.trimIndent(),
                revision = "bad-ranges"
            )
        }
    }

    @Test
    fun `version and rollout can keep bundled profile`() {
        assertNull(
            ReplicaDetectionConfiguration.resolveRemoteJson(
                rawJson = payload(
                    parameters = "{}",
                    revision = "future-only",
                    extraFields = """"minimumAppVersion": "3.0.0","""
                ),
                appVersion = "2.9.9",
                rolloutBucket = 0
            )
        )

        val rolloutPayload = payload(
            parameters = "{}",
            revision = "ten-percent",
            extraFields = """"rolloutPercentage": 10,"""
        )
        assertNotNull(
            ReplicaDetectionConfiguration.resolveRemoteJson(
                rolloutPayload,
                appVersion = "2.4.0",
                rolloutBucket = 999
            )
        )
        assertNull(
            ReplicaDetectionConfiguration.resolveRemoteJson(
                rolloutPayload,
                appVersion = "2.4.0",
                rolloutBucket = 1_000
            )
        )
    }

    @Test
    fun `remote scene motion parameters drive existing compiled guard`() {
        val profile = resolve(
            """{ "sceneMotionMinWidthFraction": 0.95 }""",
            revision = "scene-width-experiment"
        )!!

        assertFalse(
            DetectionEngine.shouldRejectIncoherentSceneMotion(
                blobWidth = 162,
                processWidth = 180,
                noTorsoSupport = true,
                hasValidBodySupport = false,
                gateRowWidth = 2,
                stripWidth = 2f,
                horizontalRun = 2,
                torsoFragmentCount = 10,
                parameters = profile.parameters
            )
        )
    }

    @Test
    fun `stable rollout bucket matches iOS and is bounded`() {
        val first = ReplicaDetectionConfiguration.stableRolloutBucket("device-123")
        val second = ReplicaDetectionConfiguration.stableRolloutBucket("device-123")

        assertEquals(9_582, first)
        assertEquals(first, second)
        assertTrue(first in 0 until 10_000)
    }

    @Test
    fun `wrong optional field types reject entire profile`() {
        listOf(
            """"enabled": "false",""",
            """"rolloutPercentage": "10",""",
            """"minimumAppVersion": 3,""",
            """"expiresAt": 123,"""
        ).forEachIndexed { index, invalidField ->
            assertThrows(ReplicaDetectionConfiguration.ValidationException::class.java) {
                ReplicaDetectionConfiguration.resolveRemoteJson(
                    payload("{}", "invalid-type-$index", invalidField),
                    appVersion = "2.4.0",
                    rolloutBucket = 0
                )
            }
        }
    }

    @Test
    fun `store holds defensive session snapshots`() {
        val mutable = ReplicaDetectionConfiguration.bundled.copy(
            revision = "snapshot-test",
            parameters = ReplicaDetectionConfiguration.Parameters(diffThreshold = 19)
        )
        ReplicaDetectionConfigurationStore.replace(mutable)
        mutable.parameters.diffThreshold = 70

        val snapshot = ReplicaDetectionConfigurationStore.snapshot()
        assertEquals(19, snapshot.parameters.diffThreshold)
        snapshot.parameters.diffThreshold = 42
        assertEquals(19, ReplicaDetectionConfigurationStore.snapshot().parameters.diffThreshold)

        // Restore global state so this test cannot influence other detector tests.
        ReplicaDetectionConfigurationStore.replace(ReplicaDetectionConfiguration.bundled)
    }

    private fun resolve(
        parameters: String,
        revision: String
    ): ReplicaDetectionConfiguration? {
        return ReplicaDetectionConfiguration.resolveRemoteJson(
            rawJson = payload(parameters, revision),
            appVersion = "2.4.0",
            rolloutBucket = 0
        )
    }

    private fun payload(
        parameters: String,
        revision: String,
        extraFields: String = ""
    ): String = """
        {
          "schemaVersion": 1,
          "pipeline": "replica_v1",
          "revision": "$revision",
          $extraFields
          "parameters": $parameters
        }
    """.trimIndent()
}
