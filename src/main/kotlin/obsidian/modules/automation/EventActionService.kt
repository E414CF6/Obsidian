package obsidian.modules.automation

import obsidian.core.ObsidianContext
import obsidian.core.command.SpecializedCommandExecutor
import obsidian.core.config.YamlConfigFile
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * Executes configured [SpecializedCommandExecutor] hooks on player lifecycle events (`eventactions.yml`).
 */
class EventActionService(private val context: ObsidianContext) : Listener {

    private val file = YamlConfigFile(context.plugin, "eventactions.yml")
    private val actions = LinkedHashMap<String, List<String>>()

    init {
        reload()
    }

    fun reload() {
        file.reload()
        actions.clear()
        val sec = file.yaml.getConfigurationSection("events") ?: return
        for (key in sec.getKeys(false)) {
            val enabled = sec.getBoolean("$key.enabled", true)
            if (enabled) {
                val cmds = sec.getStringList("$key.commands")
                if (cmds.isNotEmpty()) {
                    actions[key.lowercase()] = cmds
                }
            }
        }
    }

    fun dispatchEvent(eventKey: String, player: Player, extra: Map<String, String> = emptyMap()) {
        val cmds = actions[eventKey.lowercase()] ?: return
        if (cmds.isEmpty()) return
        SpecializedCommandExecutor.executeAll(context, cmds, player, extra)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val p = event.player
        if (!p.hasPlayedBefore()) {
            dispatchEvent("first-join", p)
        }
        dispatchEvent("join", p)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        dispatchEvent("quit", event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        dispatchEvent("death", event.entity)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        context.scheduler.runForEntityDelayed(event.player, 1L) {
            if (event.player.isOnline) dispatchEvent("respawn", event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        dispatchEvent(
            "world-change",
            event.player,
            mapOf("from_world" to event.from.name, "to_world" to event.player.world.name),
        )
    }
}
