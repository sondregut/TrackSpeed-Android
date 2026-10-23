package com.trackspeed.android.protocol

/** Narrow exception for CoreBluetooth's phone-local peripheral identifier. */
object TimingBootstrapPolicy {
    fun acceptsPhysicallyTargetedRoleRequest(
        payload: TimingPayload,
        senderId: String,
        hasDirectEncryptedGattRoute: Boolean
    ): Boolean {
        val request = payload as? TimingPayload.RoleRequest ?: return false
        // iOS JoinSessionView/RaceSession use a fresh sender UUID, while
        // roleRequest.deviceId is the persistent DeviceIdentity. Equality is
        // not part of the v7 wire contract. The direct encrypted route is the
        // bootstrap authority; the transport separately binds the announcement
        // to that route and rejects conflicting live peer identities.
        return hasDirectEncryptedGattRoute && request.deviceId.isNotBlank() &&
            (request.deviceId.equals(senderId, ignoreCase = true) ||
                (isCanonicalUuid(request.deviceId) && isCanonicalUuid(senderId)))
    }

    private fun isCanonicalUuid(value: String): Boolean =
        runCatching { java.util.UUID.fromString(value).toString().equals(value, ignoreCase = true) }
            .getOrDefault(false)

    fun isPreSessionRoleAssignment(sequence: Long): Boolean =
        sequence == TimingProtocolSequence.PRE_SESSION_ROLE_ASSIGNMENT

    fun acceptsPhysicallyTargetedPreSessionRoleAssignment(
        payload: TimingPayload,
        sequence: Long,
        hasDirectEncryptedGattRoute: Boolean
    ): Boolean =
        hasDirectEncryptedGattRoute &&
            payload is TimingPayload.RoleAssigned &&
            isPreSessionRoleAssignment(sequence)

    /**
     * Convert CoreBluetooth's phone-local target back to this app's stable ID
     * after the encrypted client route has proved which physical phone received
     * the one-time bootstrap. This keeps the higher-level target filter strict
     * without rejecting a bootstrap that the transport already authenticated.
     */
    fun normalizePreSessionRoleAssignmentTarget(
        message: TimingMessage,
        localDeviceId: String,
        hasDirectEncryptedGattRoute: Boolean
    ): TimingMessage {
        if (!acceptsPhysicallyTargetedPreSessionRoleAssignment(
                payload = message.payload,
                sequence = message.seq,
                hasDirectEncryptedGattRoute = hasDirectEncryptedGattRoute
            )
        ) {
            return message
        }

        val assignment = message.payload as TimingPayload.RoleAssigned
        val normalizedEnvelopeTarget = message.targetDeviceId?.let { localDeviceId }
        val normalizedPayloadTarget = assignment.targetDeviceId?.let { localDeviceId }
        if (message.targetDeviceId == normalizedEnvelopeTarget &&
            assignment.targetDeviceId == normalizedPayloadTarget
        ) {
            return message
        }

        return message.copy(
            targetDeviceId = normalizedEnvelopeTarget,
            payload = assignment.copy(targetDeviceId = normalizedPayloadTarget)
        )
    }
}
