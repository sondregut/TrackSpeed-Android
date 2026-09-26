package com.trackspeed.android.cloud

internal fun Throwable.safeCloudErrorCode(): String {
    val text = listOfNotNull(message, localizedMessage)
        .joinToString(" ")
        .lowercase()

    return when {
        "anonymous_provider_disabled" in text -> "anonymous_provider_disabled"
        "invalid_credentials" in text -> "invalid_credentials"
        "email not confirmed" in text -> "email_not_confirmed"
        "user already registered" in text -> "user_already_registered"
        "weak_password" in text -> "weak_password"
        "row-level security" in text || "42501" in text || "rls" in text -> "rls_denied"
        "jwt" in text && ("expired" in text || "invalid" in text) -> "auth_token_invalid"
        "schema cache" in text -> "schema_cache"
        "could not find" in text && "column" in text -> "missing_column"
        "timeout" in text || "timed out" in text -> "timeout"
        "unable to resolve host" in text || "failed to connect" in text -> "network_unavailable"
        "unauthorized" in text || "forbidden" in text -> "auth_denied"
        else -> this::class.java.simpleName.ifBlank { "error" }
    }
}
