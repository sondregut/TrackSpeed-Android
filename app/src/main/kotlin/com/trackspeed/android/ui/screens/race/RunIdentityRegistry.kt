package com.trackspeed.android.ui.screens.race

/**
 * Keeps provisional, transport, and cloud run identifiers pointing at one
 * canonical run. Messages and media can overtake each other on different
 * transports, so callers must resolve an identifier before comparing or
 * persisting it.
 */
internal class RunIdentityRegistry(
    private val maximumAliases: Int = 64
) {
    private val aliases = linkedMapOf<String, String>()

    fun resolve(runId: String): String {
        var resolved = runId
        val visited = linkedSetOf<String>()
        while (visited.add(resolved)) {
            val next = aliases[resolved] ?: break
            if (next == resolved) break
            resolved = next
        }

        // Path compression keeps later media/result lookups cheap and also
        // collapses transitive aliases after a canonical identity changes.
        visited.forEach { alias ->
            if (alias != resolved) aliases[alias] = resolved
        }
        trimIfNeeded()
        return resolved
    }

    fun registerAlias(aliasRunId: String, canonicalRunId: String): String {
        val alias = resolve(aliasRunId)
        val canonical = resolve(canonicalRunId)
        if (alias == canonical) return canonical

        aliases[alias] = canonical
        aliases[aliasRunId] = canonical
        aliases.entries
            .filter { (_, target) -> target == alias }
            .map { it.key }
            .forEach { aliases[it] = canonical }
        trimIfNeeded()
        return canonical
    }

    fun isSameRun(first: String, second: String): Boolean =
        resolve(first).equals(resolve(second), ignoreCase = true)

    fun clear() {
        aliases.clear()
    }

    private fun trimIfNeeded() {
        while (aliases.size > maximumAliases) {
            aliases.remove(aliases.keys.first())
        }
    }
}
