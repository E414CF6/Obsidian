package obsidian.core.command

import obsidian.core.config.ObsidianConfig
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-player command cooldowns configured under `commands.cooldowns`.
 */
class CooldownService(private val config: ObsidianConfig) {

    private var enabled: Boolean = false
    private val configuredSeconds = ConcurrentHashMap<String, Int>()
    private val lastUsedMillis = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()

    init {
        reload()
    }

    fun reload() {
        val raw = config.raw
        enabled = raw.getBoolean("commands.cooldowns.enabled", false)
        configuredSeconds.clear()
        val section = raw.getConfigurationSection("commands.cooldowns.list") ?: return
        for (key in section.getKeys(false)) {
            val sec = section.getInt(key, 0)
            if (sec > 0) {
                configuredSeconds[key.lowercase()] = sec
            }
        }
    }

    fun getConfiguredCooldownSeconds(commandKey: String): Int =
        if (!enabled) 0 else configuredSeconds[commandKey.lowercase()] ?: 0

    /**
     * Returns remaining cooldown in milliseconds, or `0` if the sender can execute [commandKey].
     */
    fun getRemainingMillis(
        sender: CommandSender,
        commandKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        if (!enabled || sender !is Player) return 0L
        val key = commandKey.lowercase()
        if (sender.hasPermission("obsidian.cooldown.bypass.$key") || sender.hasPermission("obsidian.cooldown.bypass.*")) {
            return 0L
        }
        val seconds = configuredSeconds[key] ?: return 0L
        val map = lastUsedMillis[sender.uniqueId] ?: return 0L
        val last = map[key] ?: return 0L
        val elapsed = nowMillis - last
        val required = seconds * 1000L
        return (required - elapsed).coerceAtLeast(0L)
    }

    fun recordUsage(sender: CommandSender, commandKey: String, nowMillis: Long = System.currentTimeMillis()) {
        if (!enabled || sender !is Player) return
        val key = commandKey.lowercase()
        if (!configuredSeconds.containsKey(key)) return
        lastUsedMillis.computeIfAbsent(sender.uniqueId) { ConcurrentHashMap() }[key] = nowMillis
    }

    fun clear(uuid: UUID) {
        lastUsedMillis.remove(uuid)
    }
}
