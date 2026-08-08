package com.trackspeed.android.videooverlay

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
@UnstableApi
class VideoOverlayExportService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun importVideo(sourceUri: Uri): ImportedVideo = withContext(Dispatchers.IO) {
        val dir = overlayCacheDir()
        val target = File(dir, "trackspeed_import_${UUID.randomUUID()}.mp4")
        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Could not open selected video." }
            target.outputStream().use { output -> input.copyTo(output) }
        }

        val targetUri = Uri.fromFile(target)
        val metadata = readMetadata(targetUri)
        ImportedVideo(
            uri = targetUri,
            file = target,
            durationSeconds = metadata.durationSeconds,
            width = metadata.width,
            height = metadata.height,
            rotationDegrees = metadata.rotationDegrees,
            hasAudio = metadata.hasAudio
        )
    }

    suspend fun export(
        snapshot: VideoOverlaySnapshot,
        onProgress: (Double) -> Unit
    ): File = withContext(Dispatchers.Main) {
        coroutineScope {
            val output = File(overlayCacheDir(), "trackspeed_overlay_${UUID.randomUUID()}.mp4")
            val overlay = RaceOverlayCanvasOverlay(snapshot)
            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(snapshot.sourceUri))
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(OverlayEffect(listOf(overlay)))
                    )
                )
                .setFrameRate(30)
                .build()

            val transformer = Transformer.Builder(context).build()
            val progressHolder = ProgressHolder()
            var progressJob: Job? = null

            suspendCancellableCoroutine { continuation ->
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressJob?.cancel()
                        onProgress(1.0)
                        transformer.removeListener(this)
                        if (continuation.isActive) {
                            continuation.resume(output)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        progressJob?.cancel()
                        transformer.removeListener(this)
                        if (continuation.isActive) {
                            continuation.resumeWithException(exportException)
                        }
                    }
                }

                transformer.addListener(listener)
                progressJob = launch {
                    while (isActive) {
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((progressHolder.progress / 100.0).coerceIn(0.0, 1.0))
                        }
                        delay(100)
                    }
                }

                continuation.invokeOnCancellation {
                    progressJob?.cancel()
                    transformer.removeListener(listener)
                    runCatching { transformer.cancel() }
                }

                runCatching {
                    transformer.start(editedMediaItem, output.absolutePath)
                }.onFailure { error ->
                    progressJob?.cancel()
                    transformer.removeListener(listener)
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        }
    }

    suspend fun saveToMediaStore(file: File): Uri = withContext(Dispatchers.IO) {
        require(file.exists()) { "The exported file is no longer available. Try exporting again." }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TrackSpeed")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create a video in the media library.")

        try {
            resolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Could not open the media library output file." }
                file.inputStream().use { input -> input.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    fun shareUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun overlayCacheDir(): File {
        return File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
    }

    private fun readMetadata(uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            VideoMetadata(
                durationSeconds = durationMs / 1000.0,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            )
        } finally {
            retriever.release()
        }
    }

    private data class VideoMetadata(
        val durationSeconds: Double,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val hasAudio: Boolean
    )

    private companion object {
        const val CACHE_DIR = "video_overlay"
    }
}
