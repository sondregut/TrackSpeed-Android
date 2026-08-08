package com.trackspeed.android.diagnostics

import android.os.Process
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.cloud.DeviceIdProvider
import com.trackspeed.android.cloud.dto.DeviceLogUploadRecordDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogExporter @Inject constructor(
    private val deviceIdProvider: DeviceIdProvider,
    private val durableUploadQueue: DurableDeviceLogUploadQueue
) {
    enum class TimeWindow(
        val label: String,
        val displayName: String,
        val durationMillis: Long
    ) {
        LAST_5_MINUTES("5min", "Last 5 minutes", 5 * 60 * 1000L),
        LAST_30_MINUTES("30min", "Last 30 minutes", 30 * 60 * 1000L),
        LAST_6_HOURS("6h", "Last 6 hours (since boot)", 6 * 60 * 60 * 1000L)
    }

    suspend fun exportRecent(window: TimeWindow): String = withContext(Dispatchers.IO) {
        val sinceMillis = System.currentTimeMillis() - window.durationMillis
        val lines = collectLogcatLines(sinceMillis)
        if (lines.isEmpty()) {
            throw IllegalStateException("No log entries found in the requested window.")
        }

        val nowMillis = System.currentTimeMillis()
        val data = formatLogExport(
            lines = lines,
            deviceId = deviceIdProvider.deviceId,
            generatedAtMillis = nowMillis
        ).toByteArray(Charsets.UTF_8)
        val path = storagePath(
            deviceId = deviceIdProvider.deviceId,
            label = window.label,
            timestampSeconds = nowMillis / 1000L,
            randomSuffix = UUID.randomUUID().toString().take(8).lowercase(Locale.US)
        )

        val generatedAtIso = isoFormatter.format(Date(nowMillis))
        durableUploadQueue.uploadOrQueue(
            bytes = data,
            storagePath = path,
            localFileName = path.substringAfterLast('/'),
            category = DEVICE_LOG_CATEGORY,
            coalescingKey = path,
            record = DeviceLogUploadRecordDto(
                deviceId = deviceIdProvider.deviceId,
                reason = "manual-logcat-${window.label}",
                storagePath = path,
                localFileName = path.substringAfterLast('/'),
                fileByteCount = data.size,
                localUploadedAt = generatedAtIso
            ),
            createSignedUrl = true
        ) ?: throw IllegalStateException("Device log upload did not return a signed URL.")
    }

    private fun collectLogcatLines(sinceMillis: Long): List<String> {
        val pid = Process.myPid().toString()
        val since = logcatTimestamp(sinceMillis)
        val primary = runLogcat(
            listOf("logcat", "-d", "-v", "threadtime", "--pid", pid, "-T", since)
        )
        if (primary.isNotEmpty()) return primary

        return runLogcat(
            listOf("logcat", "-d", "-v", "threadtime", "-T", since)
        ).filter { line -> line.contains(" $pid ") }
    }

    private fun runLogcat(command: List<String>): List<String> {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { reader ->
                reader.readLines()
            }.also {
                process.destroy()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val DEVICE_LOG_CATEGORY = "device-log"

        private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private val logcatFormatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

        fun formatLogExport(
            lines: List<String>,
            deviceId: String,
            generatedAtMillis: Long,
            subsystem: String = BuildConfig.APPLICATION_ID
        ): String {
            return buildString {
                appendLine("# TrackSpeed log export")
                appendLine("# subsystem : $subsystem")
                appendLine("# device    : $deviceId")
                appendLine("# generated : ${isoFormatter.format(Date(generatedAtMillis))}")
                appendLine("# entries   : ${lines.size}")
                appendLine("# format    : logcat threadtime")
                lines.forEach { appendLine(it) }
            }
        }

        fun storagePath(
            deviceId: String,
            label: String,
            timestampSeconds: Long,
            randomSuffix: String
        ): String {
            return "$deviceId/$timestampSeconds-$label-$randomSuffix.log"
        }

        private fun logcatTimestamp(timeMillis: Long): String {
            return logcatFormatter.format(Date(timeMillis))
        }
    }
}
