package obsidian.modules.mobfarm

import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.vehicle.VehicleCreateEvent

/**
 * Handles entity spawn limits, mob-build prevention, item-frame/vehicle placement limits,
 * and picked-up item entity tracking for MobFarm.
 */
class MobFarmListener(
    private val context: ObsidianContext,
    private val config: MobFarmConfig,
    private val cleaner: MobFarmCleanerService,
) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        val settings = config.settings
        val entity = event.entity
        val loc = event.location
        val world = loc.world ?: return

        if (settings.preventIronGolemSpawn &&
            event.spawnReason == SpawnReason.VILLAGE_DEFENSE &&
            entity.type == EntityType.IRON_GOLEM
        ) {
            event.isCancelled = true
            return
        }

        if (settings.isIgnoredWorld(world.name)) return
        val cx = loc.blockX shr 4
        val cz = loc.blockZ shr 4
        if (settings.isExcludedChunk(world.name, cx, cz)) return

        if (settings.preventMobBuild && event.spawnReason.name in BUILD_SPAWN_REASONS) {
            val builder = findNearestPlayer(loc, 30.0)
            val buildPermKey = entity.type.name.lowercase().replace("_", "")
            if (builder != null && !hasBuildPermission(builder, buildPermKey)) {
                event.isCancelled = true
                return
            }
        }

        if (!settings.checkOnEntitySpawn) return
        if (!settings.isSpawnReasonChecked(event.spawnReason)) return

        val typeName = entity.type.name
        if (event.spawnReason == SpawnReason.NATURAL) {
            val rule = settings.getEntityTypeRule(typeName)
            if (rule != null && !rule.naturalSpawn) {
                event.isCancelled = true
                return
            }
        }

        if (!settings.isTrackable(typeName)) return
        if (entity.isInvisible) return

        val result = cleaner.evaluateSpawn(entity)
        if (result.disableAiOnOriginal) {
            if (entity is Mob) {
                entity.isAware = false
            } else {
                entity.setAI(false)
            }
            return
        }

        if (result.cancelOriginal) {
            if (entity is ArmorStand) {
                val placer = findNearestPlayer(loc, 5.0)
                if (placer != null && hasBypassPermission(placer, "armorstand")) {
                    return
                }
                event.isCancelled = true
                if (placer != null && result.lowestMax > 0) {
                    context.locale.send(placer, "mobfarm.armor-stand-limit", "amount" to result.lowestMax)
                }
            } else {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val settings = config.settings
        if (!settings.checkOnEntitySpawn) return

        val entity = event.entity
        val typeName = entity.type.name
        if (!typeName.contains("ITEM_FRAME")) return

        val loc = entity.location
        val world = loc.world ?: return
        if (settings.isIgnoredWorld(world.name)) return
        if (settings.isExcludedChunk(world.name, loc.blockX shr 4, loc.blockZ shr 4)) return
        if (!settings.isTrackable(typeName)) return

        val player = event.player ?: findNearestPlayer(loc, 5.0)
        val permSuffix = typeName.lowercase().replace("_", "")
        if (player != null && hasBypassPermission(player, permSuffix)) return

        val result = cleaner.evaluateSpawn(entity)
        if (result.cancelOriginal) {
            event.isCancelled = true
            if (player != null && result.lowestMax > 0) {
                context.locale.send(player, "mobfarm.item-frame-limit", "amount" to result.lowestMax)
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onVehicleCreate(event: VehicleCreateEvent) {
        val settings = config.settings
        if (!settings.checkOnEntitySpawn) return

        val vehicle = event.vehicle
        val loc = vehicle.location
        val world = loc.world ?: return
        if (settings.isIgnoredWorld(world.name)) return
        if (settings.isExcludedChunk(world.name, loc.blockX shr 4, loc.blockZ shr 4)) return
        if (!settings.isTrackable(vehicle.type.name)) return

        val nearest = findNearestPlayer(loc, 5.0)
        val permSuffix = vehicle.type.name.lowercase().replace("_", "")
        if (nearest != null && hasBypassPermission(nearest, permSuffix)) return

        val result = cleaner.evaluateSpawn(vehicle)
        if (result.cancelOriginal) {
            event.isCancelled = true
            if (nearest != null) {
                if (result.lowestMax > 0) {
                    context.locale.send(nearest, "mobfarm.general-limit", "amount" to result.lowestMax)
                }
                context.scheduler.runForEntity(nearest) {
                    nearest.updateInventory()
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        if (!config.settings.ignoreEntityWithPickedItem) return
        val entity = event.entity
        if (entity is Player) return
        cleaner.recordPickedUpItem(entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is Player) return
        cleaner.clearPickedUpItem(entity.uniqueId)
    }

    private fun hasBuildPermission(player: Player, entityKey: String): Boolean =
        player.hasPermission("obsidian.admin") ||
            player.hasPermission("obsidian.mobfarm.build.$entityKey") ||
            player.hasPermission("obsidian.mobfarm.build.*")

    private fun hasBypassPermission(player: Player, entityKey: String): Boolean =
        player.hasPermission("obsidian.admin") ||
            player.hasPermission("obsidian.mobfarm.bypasslimit.$entityKey") ||
            player.hasPermission("obsidian.mobfarm.bypasslimit.*")

    private fun findNearestPlayer(loc: Location, maxDistance: Double): Player? {
        val world = loc.world ?: return null
        val maxDistSq = maxDistance * maxDistance
        var best: Player? = null
        var bestDistSq = maxDistSq
        for (player in world.players) {
            if (!Bukkit.isOwnedByCurrentRegion(player)) continue
            val dSq = player.location.distanceSquared(loc)
            if (dSq <= bestDistSq) {
                bestDistSq = dSq
                best = player
            }
        }
        return best
    }

    companion object {
        private val BUILD_SPAWN_REASONS = setOf(
            "BUILD_SNOWMAN",
            "BUILD_IRONGOLEM",
            "BUILD_WITHER",
            "BUILD_COPPERGOLEM",
        )
    }
}
