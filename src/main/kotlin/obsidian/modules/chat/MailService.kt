package obsidian.modules.chat

import net.kyori.adventure.text.event.ClickEvent
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class MailEntry(
    val sender: String,
    val message: String,
    val timestamp: Long,
    var read: Boolean = false,
)

/**
 * Persists offline/online player mail in `plugins/Obsidian/mail.yml` and notifies players on join.
 */
class MailService(private val context: ObsidianContext) : Listener {

    private val file = File(context.plugin.dataFolder, "mail.yml")
    private val mailboxes = ConcurrentHashMap<UUID, MutableList<MailEntry>>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    fun load() {
        mailboxes.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("mail") ?: return
        for (uuidStr in section.getKeys(false)) {
            val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: continue
            val list = mutableListOf<MailEntry>()
            val entries = section.getMapList(uuidStr)
            for (raw in entries) {
                val sender = raw["sender"]?.toString() ?: "Server"
                val message = raw["message"]?.toString() ?: continue
                val timestamp = (raw["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                val read = (raw["read"] as? Boolean) ?: false
                list += MailEntry(sender, message, timestamp, read)
            }
            if (list.isNotEmpty()) {
                mailboxes[uuid] = list
            }
        }
    }

    fun save() {
        val yaml = YamlConfiguration()
        for ((uuid, entries) in mailboxes) {
            val serialized = entries.map {
                mapOf(
                    "sender" to it.sender,
                    "message" to it.message,
                    "timestamp" to it.timestamp,
                    "read" to it.read,
                )
            }
            yaml.set("mail.$uuid", serialized)
        }
        runCatching {
            file.parentFile?.mkdirs()
            yaml.save(file)
        }
    }

    fun sendMail(targetUuid: UUID, senderName: String, message: String) {
        val list = mailboxes.computeIfAbsent(targetUuid) { mutableListOf() }
        synchronized(list) {
            list += MailEntry(senderName, message, System.currentTimeMillis(), read = false)
        }
        context.scheduler.runAsync { save() }

        val online = Bukkit.getPlayer(targetUuid)
        if (online != null && online.isOnline) {
            context.scheduler.runForEntity(online) {
                context.locale.send(online, "chat.mail-received", "sender" to senderName)
            }
        }
    }

    fun sendMailAll(senderName: String, message: String): Int {
        val targets = linkedSetOf<UUID>()
        Bukkit.getOnlinePlayers().forEach { targets += it.uniqueId }
        Bukkit.getOfflinePlayers().forEach { targets += it.uniqueId }
        for (uuid in targets) {
            val list = mailboxes.computeIfAbsent(uuid) { mutableListOf() }
            synchronized(list) {
                list += MailEntry(senderName, message, System.currentTimeMillis(), read = false)
            }
        }
        context.scheduler.runAsync { save() }
        return targets.size
    }

    fun readMail(uuid: UUID): List<MailEntry> {
        val list = mailboxes[uuid] ?: return emptyList()
        val copy: List<MailEntry>
        synchronized(list) {
            copy = list.map { it.copy() }
            list.forEach { it.read = true }
        }
        if (copy.isNotEmpty()) {
            context.scheduler.runAsync { save() }
        }
        return copy
    }

    fun clearMail(uuid: UUID): Int {
        val removed = mailboxes.remove(uuid)?.size ?: 0
        if (removed > 0) {
            context.scheduler.runAsync { save() }
        }
        return removed
    }

    fun formatTimestamp(timestamp: Long): String = synchronized(dateFormat) { dateFormat.format(Date(timestamp)) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val list = mailboxes[player.uniqueId] ?: return
        val unread = synchronized(list) { list.count { !it.read } }
        if (unread <= 0) return

        context.scheduler.runForEntityDelayed(player, 20L) {
            if (!player.isOnline) return@runForEntityDelayed
            val comp = context.locale.prefixedComponent("chat.mail-unread-join", mapOf("count" to unread.toString()))
                .append(
                    TextFormatter.format(" <click:run_command:'/mail read'><aqua>[Read]</aqua></click>")
                        .clickEvent(ClickEvent.runCommand("/mail read"))
                )
            player.sendMessage(comp)
        }
    }
}
