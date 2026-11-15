package obsidian.modules.automation

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import obsidian.core.ObsidianContext
import obsidian.core.command.SpecializedCommandExecutor
import obsidian.core.config.YamlConfigFile
import org.bukkit.Bukkit
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ScheduledJob(
    val id: String,
    var enabled: Boolean = true,
    var intervalSeconds: Long = 300L,
    var dailyAt: LocalTime? = null,
    var minPlayers: Int = 0,
    var runPerPlayer: Boolean = false,
    var commands: List<String> = emptyList(),
    var lastRunAtMillis: Long = System.currentTimeMillis(),
    var lastDailyDate: String = "",
)

/**
 * Interval & daily wall-clock task scheduler (`schedules.yml`) running specialized Obsidian actions.
 */
class ScheduleService(private val context: ObsidianContext) {

    private val schedulesFile = YamlConfigFile(context.plugin, "schedules.yml")
    private val jobs = LinkedHashMap<String, ScheduledJob>()
    private var tickTask: ScheduledTask? = null

    init {
        reload()
    }

    fun reload() {
        schedulesFile.reload()
        jobs.clear()
        val now = System.currentTimeMillis()
        val sec = schedulesFile.yaml.getConfigurationSection("schedules")
        sec?.getKeys(false)?.forEach { key ->
            val node = sec.getConfigurationSection(key) ?: return@forEach
            val dailyRaw = node.getString("daily-at")?.takeIf { it.isNotBlank() }
            val dailyTime = dailyRaw?.let { parseTime(it) }
            jobs[key.lowercase()] = ScheduledJob(
                id = key,
                enabled = node.getBoolean("enabled", true),
                intervalSeconds = node.getLong("interval-seconds", 300L).coerceAtLeast(1L),
                dailyAt = dailyTime,
                minPlayers = node.getInt("min-players", 0).coerceAtLeast(0),
                runPerPlayer = node.getBoolean("run-per-player", false),
                commands = node.getStringList("commands"),
                lastRunAtMillis = now,
            )
        }
        startTicker()
    }

    fun startTicker() {
        tickTask?.cancel()
        tickTask = context.scheduler.runGlobalTimer(1L, 1L) {
            evaluateJobs()
        }
    }

    fun shutdown() {
        tickTask?.cancel()
        tickTask = null
    }

    fun getAllJobs(): List<ScheduledJob> = jobs.values.toList()

    fun getJob(id: String): ScheduledJob? = jobs[id.lowercase()]

    fun toggleJob(id: String): Boolean? {
        val job = jobs[id.lowercase()] ?: return null
        job.enabled = !job.enabled
        schedulesFile.yaml.set("schedules.${job.id}.enabled", job.enabled)
        schedulesFile.save()
        return job.enabled
    }

    fun runNow(job: ScheduledJob) {
        if (job.commands.isEmpty()) return
        if (job.runPerPlayer) {
            for (player in Bukkit.getOnlinePlayers()) {
                SpecializedCommandExecutor.executeAll(context, job.commands, player)
            }
        } else {
            SpecializedCommandExecutor.executeAll(context, job.commands, null)
        }
    }

    private fun evaluateJobs() {
        val nowMs = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        val nowTime = LocalTime.now()
        val onlineCount = Bukkit.getOnlinePlayers().size

        for (job in jobs.values) {
            if (!job.enabled || job.commands.isEmpty()) continue
            if (onlineCount < job.minPlayers) continue

            val dailyTarget = job.dailyAt
            if (dailyTarget != null) {
                if (job.lastDailyDate != today && nowTime.hour == dailyTarget.hour && nowTime.minute == dailyTarget.minute) {
                    job.lastDailyDate = today
                    job.lastRunAtMillis = nowMs
                    runNow(job)
                }
            } else if (nowMs - job.lastRunAtMillis >= job.intervalSeconds * 1000L) {
                job.lastRunAtMillis = nowMs
                runNow(job)
            }
        }
    }

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("H:mm")

        fun parseTime(raw: String): LocalTime? = runCatching { LocalTime.parse(raw.trim(), TIME_FMT) }.getOrNull()
    }
}
