package obsidian.modules.teleport

import obsidian.core.ObsidianContext
import obsidian.core.database.StoredLocation
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates safe asynchronous teleportation, `/back` & `/dback` tracking, and post-teleport invulnerability.
 */
class TeleportService(private val context: ObsidianContext) : Listener {

    var safeDownThenUp: Boolean = false
        private set
    var invulnerabilitySeconds: Int = 3
        private set
    var jumpDefaultDistance: Int = 50
        private set
    var backMinDistance: Double = 5.0
        private set
    var backBlacklistedWorlds: Set<String> = emptySet()
        private set

    private val invulnerableUntil = ConcurrentHashMap<UUID, Long>()

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        safeDownThenUp = raw.getBoolean("teleport.safe-location-down-then-up", false)
        invulnerabilitySeconds = raw.getInt("teleport.invulnerability-seconds", 3).coerceIn(0, 60)
        jumpDefaultDistance = raw.getInt("teleport.jump-default-distance", 50).coerceIn(5, 500)
        backMinDistance = raw.getDouble("teleport.back.min-distance", 5.0).coerceAtLeast(0.0)
        backBlacklistedWorlds = raw.getStringList("teleport.back.blacklisted-worlds")
            .map { it.lowercase() }
            .toSet()
    }

    /**
     * Teleports [player] to [destination], optionally finding a safe landing spot and recording `/back`.
     */
    fun teleport(
        player: Player,
        destination: Location,
        cause: TeleportCause = TeleportCause.PLUGIN,
        ensureSafe: Boolean = true,
        recordBack: Boolean = true,
    ): CompletableFuture<Boolean> {
        val origin = player.location.clone()
        val shouldCheckSafe = ensureSafe && player.hasPermission("obsidian.teleport.safe")
        val targetFuture: CompletableFuture<Location?> = if (shouldCheckSafe) {
            SafeLocationFinder.findSafeLocation(destination, safeDownThenUp)
        } else {
            CompletableFuture.completedFuture(destination)
        }

        return targetFuture.thenCompose { safeLoc ->
            if (safeLoc == null) {
                return@thenCompose CompletableFuture.completedFuture(false)
            }
            if (recordBack) {
                recordBackLocation(player, origin, safeLoc)
            }
            val result = CompletableFuture<Boolean>()
            context.scheduler.runForEntity(player, retired = { result.complete(false) }) {
                player.teleportAsync(safeLoc, cause).whenComplete { success, err ->
                    if (err == null && success == true) {
                        grantInvulnerability(player.uniqueId)
                        result.complete(true)
                    } else {
                        result.complete(false)
                    }
                }
            }
            result
        }
    }

    fun recordBackLocation(player: Player, origin: Location, destination: Location? = null) {
        val worldName = origin.world?.name ?: return
        if (worldName.lowercase() in backBlacklistedWorlds) return
        if (destination != null && origin.world == destination.world) {
            if (origin.distanceSquared(destination) < backMinDistance * backMinDistance) return
        }
        val stored = StoredLocation.fromBukkit(origin) ?: return
        val profile = context.users.getCached(player)
        profile.backLocation = stored
        profile.markDirty()
    }

    fun getBackLocation(player: Player): Location? =
        context.users.getCached(player).backLocation?.toBukkitLocation()

    fun getDeathLocation(player: Player): Location? =
        context.users.getCached(player).deathLocation?.toBukkitLocation()

    private fun grantInvulnerability(uuid: UUID) {
        if (invulnerabilitySeconds <= 0) return
        invulnerableUntil[uuid] = System.currentTimeMillis() + invulnerabilitySeconds * 1000L
    }

    fun isInvulnerable(uuid: UUID): Boolean {
        val until = invulnerableUntil[uuid] ?: return false
        if (System.currentTimeMillis() > until) {
            invulnerableUntil.remove(uuid)
            return false
        }
        return true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val loc = player.location
        val worldName = loc.world?.name ?: return
        if (worldName.lowercase() in backBlacklistedWorlds) return
        val stored = StoredLocation.fromBukkit(loc) ?: return
        val profile = context.users.getCached(player)
        profile.deathLocation = stored
        profile.backLocation = stored
        profile.markDirty()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (isInvulnerable(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        val damager = event.damager as? Player ?: return
        invulnerableUntil.remove(damager.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        invulnerableUntil.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        invulnerableUntil.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        invulnerableUntil.remove(event.player.uniqueId)
    }
}
