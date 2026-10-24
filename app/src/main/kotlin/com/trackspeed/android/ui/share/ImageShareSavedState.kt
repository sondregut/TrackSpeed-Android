package com.trackspeed.android.ui.share

import androidx.lifecycle.SavedStateHandle
import com.trackspeed.android.ui.components.ShareCardTheme

/** Only Bundle-safe primitives are retained. In particular, hidden photos/names stay hidden after process recreation. */
internal class ImageShareSavedState(private val state: SavedStateHandle) {
    fun runOptions(): RunImageOptions = RunImageOptions(
        format = RunImageFormat.entries.firstOrNull { it.name == state.get<String>("imageShareFormat") } ?: RunImageFormat.STORY,
        showSplits = state.get<Boolean>("imageShareSplits") ?: true,
        showPhotos = state.get<Boolean>("imageSharePhotos") ?: true,
        showStartType = state.get<Boolean>("imageShareStartType") ?: true,
        showName = state.get<Boolean>("imageShareName") ?: true
    )
    fun sessionOptions(): SessionImageOptions = SessionImageOptions(
        showSplits = state.get<Boolean>("imageShareSplits") ?: true,
        showPhotos = state.get<Boolean>("imageSharePhotos") ?: true,
        showName = state.get<Boolean>("imageShareName") ?: true
    )
    fun theme(): ShareCardTheme = ShareCardTheme.entries.firstOrNull {
        it.name == state.get<String>("imageShareTheme")
    } ?: ShareCardTheme.MIDNIGHT

    fun save(options: RunImageOptions) {
        state["imageShareFormat"] = options.format.name
        state["imageShareSplits"] = options.showSplits
        state["imageSharePhotos"] = options.showPhotos
        state["imageShareStartType"] = options.showStartType
        state["imageShareName"] = options.showName
    }
    fun save(options: SessionImageOptions) {
        state["imageShareSplits"] = options.showSplits
        state["imageSharePhotos"] = options.showPhotos
        state["imageShareName"] = options.showName
    }
    fun save(theme: ShareCardTheme) { state["imageShareTheme"] = theme.name }
}
