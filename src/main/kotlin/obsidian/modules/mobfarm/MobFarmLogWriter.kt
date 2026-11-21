package obsidian.modules.mobfarm

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Asynchronous daily file logger recording bulk MobFarm entity cleanups.
 */
class MobFarmLogWriter(private val baseDir: File) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "obsidian-mobfarm-logger").apply { isDaemon = true }
    }

    private var currentDateKey: String? = null
    private var writer: BufferedWriter? = null

    fun logIfThresholdMet(settings: MobFarmSettings, totalCleaned: Int, actor: String?, breakdown: String) {
        if (!shouldLog(settings.logEnabled, settings.logFrom, totalCleaned)) return
        val folder = File(baseDir, settings.logFolder)
        val nowMs = System.currentTimeMillis()
        val dateKey = DATE_FORMATTER.format(Instant.ofEpochMilli(nowMs))
        val line = formatLogLine(nowMs, actor, totalCleaned, breakdown)

        executor.execute {
            runCatching {
                writeLine(folder, dateKey, line)
            }
        }
    }

    internal fun writeLine(folder: File, dateKey: String, line: String) {
        if (!folder.exists()) {
            folder.mkdirs()
        }
        if (currentDateKey != dateKey || writer == null) {
            closeWriter()
            val file = File(folder, "$dateKey.log")
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8))
            currentDateKey = dateKey
        }
        writer?.apply {
            append(line)
            newLine()
            flush()
        }
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        currentDateKey = null
    }

    fun shutdown() {
        executor.execute { closeWriter() }
        executor.shutdown()
        runCatching { executor.awaitTermination(2L, TimeUnit.SECONDS) }
    }

    companion object {
        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy_MM_dd").withZone(ZoneId.systemDefault())
        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        fun shouldLog(enabled: Boolean, minCount: Int, totalCleaned: Int): Boolean =
            enabled && totalCleaned >= minCount

        fun formatLogLine(timestampMs: Long, actor: String?, totalCleaned: Int, breakdown: String): String {
            val time = TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMs))
            val source = actor?.takeIf { it.isNotBlank() } ?: "AutoCleaner"
            return "$time [$source] Removed $totalCleaned: $breakdown"
        }
    }
}
