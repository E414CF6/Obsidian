package obsidian.modules.teleport

import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.StoredLocation
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause

/**
 * Manages main and permission-group spawns (`spawns.yml`), first-join spawn, and respawn routing.
 */
class SpawnService(
    private val context: ObsidianContext,
    private val teleportService: TeleportService,
) : Listener {

    private val spawnsFile = YamlConfigFile(context.plugin, "spawns.yml")
    private val groupSpawns = LinkedHashMap<String, StoredLocation>()
    private var mainSpawn: StoredLocation? = null

    var teleportOnFirstJoin: Boolean = true
        private set
    var teleportOnRespawn: Boolean = false
        private set

    init {
        reload()
    }

    fun reload() {
        spawnsFile.reload()
        val rawConfig = context.config.raw
        teleportOnFirstJoin = rawConfig.getBoolean("spawn.teleport-on-first-join", true)
        teleportOnRespawn = rawConfig.getBoolean("spawn.teleport-on-respawn", false)

        groupSpawns.clear()
        mainSpawn = StoredLocation.deserialize(spawnsFile.yaml.getString("main"))
        val groupsSection = spawnsFile.yaml.getConfigurationSection("groups")
        if (groupsSection != null) {
            for (key in groupsSection.getKeys(false)) {
                val loc = StoredLocation.deserialize(groupsSection.getString(key))
                if (loc != null) groupSpawns[key.lowercase()] = loc
            }
        }
    }

    fun setSpawn(group: String?, location: Location) {
        val stored = StoredLocation.fromBukkit(location) ?: return
        if (group == null || group.equals("default", ignoreCase = true)) {
            mainSpawn = stored
            spawnsFile.yaml.set("main", stored.serialize())
        } else {
            val key = group.lowercase()
            groupSpawns[key] = stored
            spawnsFile.yaml.set("groups.$key", stored.serialize())
        }
        spawnsFile.save()
    }

    fun getSpawnFor(player: Player): Location? {
        for ((group, loc) in groupSpawns) {
            if (player.hasPermission("obsidian.spawngroup.$group")) {
                loc.toBukkitLocation()?.let { return it }
            }
        }
        return mainSpawn?.toBukkitLocation()
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onFirstJoin(event: PlayerJoinEvent) {
        if (!teleportOnFirstJoin || event.player.hasPlayedBefore()) return
        val spawn = getSpawnFor(event.player) ?: return
        context.scheduler.runForEntity(event.player) {
            teleportService.teleport(event.player, spawn, TeleportCause.PLUGIN, ensureSafe = false, recordBack = false)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(event: PlayerRespawnEvent) {
        if (!teleportOnRespawn) return
        if (event.isBedSpawn || event.isAnchorSpawn) return
        val spawn = getSpawnFor(event.player) ?: return
        event.respawnLocation = spawn
    }
}
