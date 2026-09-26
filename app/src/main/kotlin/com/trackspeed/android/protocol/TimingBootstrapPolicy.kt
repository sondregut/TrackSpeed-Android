package com.trackspeed.android.protocol

/** Narrow exception for CoreBluetooth's phone-local peripheral identifier. */
object TimingBootstrapPolicy {
    fun acceptsPhysicallyTargetedRoleRequest(
        payload: TimingPayload,
        senderId: String,
        hasDirectEncryptedGattRoute: Boolean
    ): Boolean {
        val request = payload as? TimingPayload.RoleRequest ?: return false
        return hasDirectEncryptedGattRoute &&
            request.deviceId.equals(senderId, ignoreCase = true)
    }

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
