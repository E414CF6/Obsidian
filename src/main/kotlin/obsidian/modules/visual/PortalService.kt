package obsidian.modules.visual

import obsidian.core.ObsidianContext
import obsidian.core.command.SpecializedCommandExecutor
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.StoredLocation
import obsidian.modules.teleport.TeleportService
import obsidian.modules.warp.WarpService
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class PortalDefinition(
    val name: String,
    val region: CuboidRegion,
    var targetLocation: StoredLocation? = null,
    var targetWarp: String? = null,
    var permission: String? = null,
    var cooldownSeconds: Long = 3L,
    var commands: List<String> = emptyList(),
    var enabled: Boolean = true,
)

/**
 * Manages cuboid portals (`portals.yml`), entry detection, cooldowns, warp/location targets, and specialized commands.
 */
class PortalService(private val context: ObsidianContext) : Listener {

    private val portalsFile = YamlConfigFile(context.plugin, "portals.yml")
    private val portals = LinkedHashMap<String, PortalDefinition>()
    private val triggerCooldowns = ConcurrentHashMap<UUID, Long>()

    init {
        reload()
    }

    fun reload() {
        portalsFile.reload()
        portals.clear()
        val sec = portalsFile.yaml.getConfigurationSection("portals") ?: return
        for (key in sec.getKeys(false)) {
            val node = sec.getConfigurationSection(key) ?: continue
            val region = CuboidRegion.deserialize(node.getString("region")) ?: continue
            val portal = PortalDefinition(
                name = key,
                region = region,
                targetLocation = StoredLocation.deserialize(node.getString("target-location")),
                targetWarp = node.getString("target-warp")?.takeIf { it.isNotBlank() },
                permission = node.getString("permission")?.takeIf { it.isNotBlank() },
                cooldownSeconds = node.getLong("cooldown-seconds", 3L).coerceAtLeast(0L),
                commands = node.getStringList("commands"),
                enabled = node.getBoolean("enabled", true),
            )
            portals[key.lowercase()] = portal
        }
    }

    fun getAllPortals(): List<PortalDefinition> = portals.values.toList()

    fun getPortal(name: String): PortalDefinition? = portals[name.lowercase()]

    fun createOrUpdatePortal(portal: PortalDefinition) {
        portals[portal.name.lowercase()] = portal
        val base = "portals.${portal.name}"
        portalsFile.yaml.set("$base.region", portal.region.serialize())
        portalsFile.yaml.set("$base.target-location", portal.targetLocation?.serialize())
        portalsFile.yaml.set("$base.target-warp", portal.targetWarp)
        portalsFile.yaml.set("$base.permission", portal.permission)
        portalsFile.yaml.set("$base.cooldown-seconds", portal.cooldownSeconds)
        portalsFile.yaml.set("$base.commands", portal.commands)
        portalsFile.yaml.set("$base.enabled", portal.enabled)
        portalsFile.save()
    }

    fun removePortal(name: String): Boolean {
        val removed = portals.remove(name.lowercase()) ?: return false
        portalsFile.yaml.set("portals.${removed.name}", null)
        portalsFile.save()
        return true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.world == to.world &&
            from.blockX == to.blockX &&
            from.blockY == to.blockY &&
            from.blockZ == to.blockZ
        ) {
            return
        }
        if (portals.isEmpty()) return

        val player = event.player
        val worldName = to.world.name
        val bx = to.blockX
        val by = to.blockY
        val bz = to.blockZ

        val matching = portals.values.firstOrNull {
            it.enabled &&
                    it.region.contains(worldName, bx, by, bz) &&
                    !it.region.contains(from.world.name, from.blockX, from.blockY, from.blockZ)
        } ?: return

        val perm = matching.permission
        if (!perm.isNullOrBlank() && !player.hasPermission(perm) && !player.hasPermission("obsidian.admin")) {
            context.locale.send(player, "general.no-permission")
            return
        }

        val now = System.currentTimeMillis()
        val last = triggerCooldowns[player.uniqueId] ?: 0L
        if (now - last < matching.cooldownSeconds * 1000L) return
        triggerCooldowns[player.uniqueId] = now

        triggerPortal(player, matching)
    }

    private fun triggerPortal(player: Player, portal: PortalDefinition) {
        val warpName = portal.targetWarp
        val tpService = context.getService<TeleportService>()
        val warpService = context.getService<WarpService>()

        if (!warpName.isNullOrBlank() && warpService != null) {
            val warp = warpService.getWarp(warpName)
            if (warp != null) {
                warpService.teleportToWarp(player, warp)
            }
        } else {
            val dest = portal.targetLocation?.toBukkitLocation()
            if (dest != null) {
                if (tpService != null) {
                    tpService.teleport(player, dest, TeleportCause.PLUGIN, ensureSafe = true, recordBack = true)
                } else {
                    player.teleportAsync(dest)
                }
            }
        }

        if (portal.commands.isNotEmpty()) {
            SpecializedCommandExecutor.executeAll(
                context,
                portal.commands,
                player,
                mapOf("portal" to portal.name),
            )
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        triggerCooldowns.remove(event.player.uniqueId)
    }
}
