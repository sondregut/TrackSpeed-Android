package com.trackspeed.android.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.trackspeed.android.data.local.dao.RunDao
import com.trackspeed.android.data.local.dao.TrainingSessionDao
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.model.StartType
import com.trackspeed.android.util.HistoryDistanceFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CsvExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: TrainingSessionDao,
    private val runDao: RunDao
) {

    companion object {
        private const val EXPORTS_DIR = "exports"
        private const val CSV_HEADER =
            "Session Date,Distance Label,Distance (m),Run #,Time (s),Speed (m/s),Athlete,Start Type,Mode"

        private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    /**
     * Export a single session with all its runs to a CSV file.
     * Returns a content URI via FileProvider, or null if the session was not found.
     */
    suspend fun exportSession(sessionId: String): Uri? {
        val session = sessionDao.getSession(sessionId) ?: return null
        val runs = runDao.getRunsForSession(sessionId).first()

        val fileName = "sprint_session_${fileDateFormat.format(Date(session.date))}.csv"
        return writeCsvFile(fileName) { writer ->
            writer.appendLine(CSV_HEADER)
            writeSessionRows(writer, session, runs)
        }
    }

    /**
     * Export all sessions with all their runs to a single CSV file.
     * Returns a content URI via FileProvider, or null if there are no sessions.
     */
    suspend fun exportAllSessions(): Uri? {
        val sessions = sessionDao.getAllSessions().first()
        if (sessions.isEmpty()) return null

        val fileName = "sprint_sessions_export_${fileDateFormat.format(Date())}.csv"

        // Collect all runs upfront (suspend calls can't happen inside writeCsvFile's lambda)
        val sessionRuns = sessions
            .sortedByDescending { it.date }
            .map { session -> session to runDao.getRunsForSession(session.id).first() }

        return writeCsvFile(fileName) { writer ->
            writer.appendLine(CSV_HEADER)
            for ((session, runs) in sessionRuns) {
                writeSessionRows(writer, session, runs)
            }
        }
    }

    private fun writeSessionRows(
        writer: FileWriter,
        session: TrainingSessionEntity,
        runs: List<RunEntity>
    ) {
        val sessionDate = isoDateFormat.format(Date(session.date))
        for (run in runs.sortedBy { it.runNumber }) {
            val speed = if (run.timeSeconds > 0.0) {
                String.format(Locale.US, "%.2f", run.distance / run.timeSeconds)
            } else {
                "0.00"
            }

            writer.appendLine(
                buildIosCsvRow(
                    sessionDate,
                    HistoryDistanceFormatter.labelForMeters(run.distance),
                    HistoryDistanceFormatter.csvNumericMeters(run.distance),
                    run.runNumber.toString(),
                    String.format(Locale.US, "%.3f", run.timeSeconds),
                    speed,
                    run.athleteName ?: "Unknown",
                    StartType.fromRawValue(run.startType).displayName,
                    if (run.numberOfPhones == 1) "1-Phone" else "2-Phone"
                )
            )
        }
    }

    private fun buildIosCsvRow(
        sessionDate: String,
        distanceLabel: String,
        distanceMeters: String,
        runNumber: String,
        timeSeconds: String,
        speedMetersPerSecond: String,
        athlete: String,
        startType: String,
        mode: String
    ): String {
        return listOf(
            quoteCsvValue(sessionDate),
            quoteCsvValue(distanceLabel),
            distanceMeters,
            runNumber,
            timeSeconds,
            speedMetersPerSecond,
            quoteCsvValue(athlete),
            escapeCsvValue(startType),
            escapeCsvValue(mode)
        ).joinToString(",")
    }

    private fun escapeCsvValue(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun quoteCsvValue(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun writeCsvFile(fileName: String, writeContent: (FileWriter) -> Unit): Uri {
        val exportsDir = File(context.cacheDir, EXPORTS_DIR)
        if (!exportsDir.exists()) {
            exportsDir.mkdirs()
        }

        val file = File(exportsDir, fileName)
        FileWriter(file).use { writer ->
            writeContent(writer)
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}

/**
 * Launch a share intent for a CSV file URI.
 */
fun shareCsv(context: Context, uri: Uri) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "Export CSV")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
