package obsidian.modules.mobfarm

import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Hopper
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.world.ChunkUnloadEvent
import java.util.concurrent.ConcurrentHashMap

data class BlockPosKey(val x: Int, val y: Int, val z: Int)

data class HopperChunkSummary(
    val worldName: String,
    val chunkX: Int,
    val chunkZ: Int,
    val hopperCount: Int,
)

class HopperTrackerEntry(
    var triggerCount: Int = 0,
    var triggerCountSinceLastUpdate: Int = 0,
    var cooldown: Int = 8,
    var lastTransferMs: Long = 0L,
)

/**
 * Dynamically throttles heavily-used hoppers using Paper's native [Hopper.setTransferCooldown].
 * Avoids NMS/reflection while remaining Folia region-thread safe.
 */
class HopperControlService(
    private val context: ObsidianContext,
    private val config: MobFarmConfig,
) : Listener {

    private val trackedByWorld = ConcurrentHashMap<String, ConcurrentHashMap<ChunkCoord, ConcurrentHashMap<BlockPosKey, HopperTrackerEntry>>>()

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        if (!config.isHopperControlActive) return
        if (event.item.type == Material.AIR) return

        val source = event.source
        if (source.type != InventoryType.HOPPER) return

        // Ignore hopper minecarts and non-block holders
        val holder = source.getHolder(false)
        if (holder is Entity) return

        val loc = source.location ?: return
        val world = loc.world ?: return
        val settings = config.settings
        if (settings.isIgnoredWorld(world.name)) return

        val chunkCoord = ChunkCoord(loc.blockX shr 4, loc.blockZ shr 4)
        val blockKey = BlockPosKey(loc.blockX, loc.blockY, loc.blockZ)

        val worldMap = trackedByWorld.computeIfAbsent(world.name) { ConcurrentHashMap() }
        val chunkMap = worldMap.computeIfAbsent(chunkCoord) { ConcurrentHashMap() }
        val entry = chunkMap.computeIfAbsent(blockKey) {
            HopperTrackerEntry(cooldown = settings.hopperControlDefault)
        }

        val now = System.currentTimeMillis()
        val appliedCooldown = synchronized(entry) {
            updateEntryCooldown(
                entry = entry,
                nowMs = now,
                defaultCooldown = settings.hopperControlDefault,
                slowestRate = settings.hopperControlSlowestRate,
                adjustEvery = settings.hopperControlAdjustEvery,
            )
        }

        // Vanilla resets cooldown to 8 immediately after InventoryMoveItemEvent returns;
        // schedule on the next tick at the hopper's region so our throttled cooldown sticks.
        if (appliedCooldown != VANILLA_HOPPER_COOLDOWN) {
            val blockLoc = Location(world, loc.blockX.toDouble(), loc.blockY.toDouble(), loc.blockZ.toDouble())
            context.scheduler.runAtLocation(blockLoc) {
                val state = blockLoc.block.getState(false)
                if (state is Hopper) {
                    state.setTransferCooldown(appliedCooldown)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val worldMap = trackedByWorld[event.world.name] ?: return
        worldMap.remove(ChunkCoord(event.chunk.x, event.chunk.z))
    }

    fun getTopHopperChunks(limit: Int = 20): List<HopperChunkSummary> {
        val list = ArrayList<HopperChunkSummary>()
        for ((worldName, chunks) in trackedByWorld) {
            if (Bukkit.getWorld(worldName) == null) continue
            for ((coord, hoppers) in chunks) {
                val count = hoppers.size
                if (count > 0) {
                    list += HopperChunkSummary(worldName, coord.x, coord.z, count)
                }
            }
        }
        return list.sortedByDescending { it.hopperCount }.take(limit)
    }

    fun clear() {
        trackedByWorld.clear()
    }

    companion object {
        const val VANILLA_HOPPER_COOLDOWN = 8

        /**
         * Advances trigger counters and returns the cooldown to apply after this transfer.
         */
        fun updateEntryCooldown(
            entry: HopperTrackerEntry,
            nowMs: Long,
            defaultCooldown: Int,
            slowestRate: Int,
            adjustEvery: Int,
        ): Int {
            entry.triggerCount++
            if (entry.triggerCount > 1) {
                entry.triggerCountSinceLastUpdate++
                if (entry.triggerCountSinceLastUpdate >= adjustEvery) {
                    var cd = if (entry.cooldown <= 0) VANILLA_HOPPER_COOLDOWN else entry.cooldown
                    cd = if (nowMs - entry.lastTransferMs > cd * 50L + 100L) {
                        defaultCooldown
                    } else {
                        (cd + 1).coerceAtMost(slowestRate)
                    }
                    entry.cooldown = cd
                    entry.triggerCountSinceLastUpdate = 0
                }
            }
            entry.lastTransferMs = nowMs
            return entry.cooldown
        }
    }
}
