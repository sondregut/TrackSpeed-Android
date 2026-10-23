package com.trackspeed.android.sync

/** Stable app identities belong to one encrypted GATT route until it closes. */
internal class BlePeerIdentityBindings {
    private val stableIdsByRoute = mutableMapOf<String, String>()

    @Synchronized
    fun bind(route: String, stableId: String, localStableId: String): Boolean {
        if (route.isBlank() || stableId.isBlank() || stableId.equals(localStableId, true)) return false
        val existing = stableIdsByRoute[route]
        if (existing != null && !existing.equals(stableId, true)) return false
        if (stableIdsByRoute.any { (otherRoute, id) -> otherRoute != route && id.equals(stableId, true) }) return false
        stableIdsByRoute[route] = existing ?: stableId
        return true
    }

    @Synchronized fun stableId(route: String): String? = stableIdsByRoute[route]
    @Synchronized fun remove(route: String) { stableIdsByRoute.remove(route) }
    @Synchronized fun clear() { stableIdsByRoute.clear() }
}
