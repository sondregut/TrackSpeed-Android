package com.trackspeed.android.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.trackspeed.android.data.local.dao.RunDao
import com.trackspeed.android.data.local.dao.TrainingSessionDao
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            "Session Date,Session Name,Distance (m),Start Type,Run #,Athlete,Time (s),Speed (m/s),Personal Best,Season Best"

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    /**
     * Export a single session with all its runs to a CSV file.
     * Returns a content URI via FileProvider, or null if the session was not found.
     */
    suspend fun exportSession(sessionId: String): Uri? {
        val session = sessionDao.getSession(sessionId) ?: return null
        val runs = runDao.getRunsForSession(sessionId).first()

        val fileName = buildFileName(session.name, session.date)
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

        val timestamp = fileTimestampFormat.format(Date())
        val fileName = "TrackSpeed_AllSessions_$timestamp.csv"

        // Collect all runs upfront (suspend calls can't happen inside writeCsvFile's lambda)
        val sessionRuns = sessions.map { session ->
            session to runDao.getRunsForSession(session.id).first()
        }

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
        val sessionDate = dateFormat.format(Date(session.date))
        val sessionName = session.name ?: ""

        if (runs.isEmpty()) {
            // Write a session-only row with empty run fields
            writer.appendLine(
                buildCsvRow(
                    sessionDate,
                    sessionName,
                    formatDistance(session.distance),
                    session.startType,
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
                )
            )
            return
        }

        for (run in runs) {
            val speed = if (run.timeSeconds > 0) {
                String.format(Locale.US, "%.2f", run.distance / run.timeSeconds)
            } else {
                ""
            }

            writer.appendLine(
                buildCsvRow(
                    sessionDate,
                    sessionName,
                    formatDistance(session.distance),
                    session.startType,
                    run.runNumber.toString(),
                    run.athleteName ?: "",
                    String.format(Locale.US, "%.2f", run.timeSeconds),
                    speed,
                    if (run.isPersonalBest) "Yes" else "No",
                    if (run.isSeasonBest) "Yes" else "No"
                )
            )
        }
    }

    private fun buildCsvRow(vararg values: String): String {
        return values.joinToString(",") { escapeCsvValue(it) }
    }

    private fun escapeCsvValue(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun formatDistance(distance: Double): String {
        return if (distance == distance.toLong().toDouble()) {
            distance.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", distance)
        }
    }

    private fun buildFileName(sessionName: String?, sessionDate: Long): String {
        val timestamp = fileTimestampFormat.format(Date(sessionDate))
        val safeName = sessionName
            ?.replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            ?.trim()
            ?.replace(" ", "_")

        return if (!safeName.isNullOrBlank()) {
            "TrackSpeed_${safeName}_$timestamp.csv"
        } else {
            "TrackSpeed_Session_$timestamp.csv"
        }
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
