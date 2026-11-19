package obsidian.modules.moderation

import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLoginEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class StaffNote(
    val author: String,
    val text: String,
    val timestamp: Long,
)

/**
 * Manages server maintenance mode and per-player staff notes (`notes.yml`).
 */
class MaintenanceAndNoteService(private val context: ObsidianContext) : Listener {

    @Volatile
    var maintenanceEnabled: Boolean = false
        private set

    @Volatile
    var maintenanceReason: String = "Server is currently under maintenance."
        private set

    private val notesFile = File(context.plugin.dataFolder, "notes.yml")
    private val notes = ConcurrentHashMap<UUID, MutableList<StaffNote>>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    fun load() {
        notes.clear()
        if (!notesFile.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(notesFile)
        maintenanceEnabled = yaml.getBoolean("maintenance.enabled", false)
        maintenanceReason = yaml.getString("maintenance.reason", maintenanceReason) ?: maintenanceReason

        val sec = yaml.getConfigurationSection("notes") ?: return
        for (uuidStr in sec.getKeys(false)) {
            val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: continue
            val list = mutableListOf<StaffNote>()
            for (raw in sec.getMapList(uuidStr)) {
                val author = raw["author"]?.toString() ?: "Staff"
                val text = raw["text"]?.toString() ?: continue
                val timestamp = (raw["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                list += StaffNote(author, text, timestamp)
            }
            if (list.isNotEmpty()) notes[uuid] = list
        }
    }

    fun save() {
        val yaml = YamlConfiguration()
        yaml.set("maintenance.enabled", maintenanceEnabled)
        yaml.set("maintenance.reason", maintenanceReason)
        for ((uuid, list) in notes) {
            yaml.set(
                "notes.$uuid",
                list.map { mapOf("author" to it.author, "text" to it.text, "timestamp" to it.timestamp) })
        }
        runCatching {
            notesFile.parentFile?.mkdirs()
            yaml.save(notesFile)
        }
    }

    fun setMaintenance(enabled: Boolean, reason: String? = null) {
        maintenanceEnabled = enabled
        if (!reason.isNullOrBlank()) maintenanceReason = reason
        context.scheduler.runAsync { save() }
        if (enabled) {
            val kickMsg = TextFormatter.format(maintenanceReason)
            for (player in Bukkit.getOnlinePlayers()) {
                if (!player.hasPermission("obsidian.maintenance.bypass") && !player.hasPermission("obsidian.admin")) {
                    context.scheduler.runForEntity(player) {
                        player.kick(kickMsg)
                    }
                }
            }
        }
    }

    fun addNote(uuid: UUID, author: String, text: String) {
        val list = notes.computeIfAbsent(uuid) { mutableListOf() }
        synchronized(list) {
            list += StaffNote(author, text, System.currentTimeMillis())
        }
        context.scheduler.runAsync { save() }
    }

    fun getNotes(uuid: UUID): List<StaffNote> {
        val list = notes[uuid] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    fun clearNotes(uuid: UUID): Int {
        val count = notes.remove(uuid)?.size ?: 0
        if (count > 0) context.scheduler.runAsync { save() }
        return count
    }

    fun formatDate(ts: Long): String = synchronized(dateFormat) { dateFormat.format(Date(ts)) }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onLogin(event: PlayerLoginEvent) {
        if (!maintenanceEnabled) return
        val player = event.player
        if (player.hasPermission("obsidian.maintenance.bypass") || player.hasPermission("obsidian.admin")) return
        event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, TextFormatter.format(maintenanceReason))
    }
}
