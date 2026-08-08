package com.trackspeed.android.sync

import java.security.MessageDigest

/**
 * Deterministic tie-break for legacy dual-mode Android pairing.
 *
 * Both phones advertise and scan at the same time. Without a shared ordering,
 * both can discover one another, close their GATT servers, and become clients,
 * leaving nobody to accept the connection. The lower token remains the host;
 * the higher token becomes the client. Explicit Create/Join flows do not use
 * this election and remain fully compatible with iOS.
 */
internal object BleRoleElection {
    const val TOKEN_BYTES = 12

    fun tokenForDeviceId(deviceId: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, TOKEN_BYTES)

    fun shouldBecomeClient(localToken: ByteArray, remoteToken: ByteArray): Boolean? {
        if (localToken.size != TOKEN_BYTES || remoteToken.size != TOKEN_BYTES) return null
        for (index in 0 until TOKEN_BYTES) {
            val local = localToken[index].toInt() and 0xff
            val remote = remoteToken[index].toInt() and 0xff
            if (local != remote) return local > remote
        }
        return null
    }
}
