package com.trackspeed.android.ui.share

/** Released134 semantics: a shared footer never attributes mixed/partly unnamed results to one person. */
internal sealed interface SessionShareNaming {
    data object None : SessionShareNaming
    data class Footer(val name: String) : SessionShareNaming
    data object PerRow : SessionShareNaming
}

internal fun sessionShareNaming(names: List<String?>): SessionShareNaming {
    val cleaned = names.map { it?.trim().orEmpty() }
    val distinct = cleaned.filter { it.isNotEmpty() }.toSet()
    return when {
        distinct.isEmpty() -> SessionShareNaming.None
        distinct.size == 1 && cleaned.none(String::isEmpty) -> SessionShareNaming.Footer(distinct.single())
        else -> SessionShareNaming.PerRow
    }
}
