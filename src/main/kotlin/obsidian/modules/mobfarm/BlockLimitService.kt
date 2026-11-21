package obsidian.modules.mobfarm

import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent

/**
 * Enforces per-chunk block placement and piston-transfer limits for configured materials
 * (e.g. HOPPER, PISTON, STICKY_PISTON, OBSERVER).
 */
class BlockLimitService(
    private val context: ObsidianContext,
    private val config: MobFarmConfig,
) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val settings = config.settings
        if (!settings.blockTypesLimit) return

        val block = event.block
        val world = block.world
        if (settings.isIgnoredWorld(world.name)) return

        val mat = block.type
        val limit = settings.blockLimits[mat] ?: return

        val player = event.player
        if (player.hasPermission("obsidian.admin") ||
            player.hasPermission("obsidian.mobfarm.bypasslimit.${mat.name.lowercase()}") ||
            player.hasPermission("obsidian.mobfarm.bypasslimit.*")
        ) {
            return
        }

        val count = countMaterialInChunk(block.chunk, world, mat, block.y, limit)
        if (count > limit) {
            event.isCancelled = true
            context.locale.send(
                player,
                "mobfarm.block-limit",
                "type" to mat.name.lowercase().replace('_', ' '),
                "amount" to limit,
            )
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (checkPistonCrossChunkMove(event.direction, event.blocks)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (!event.isSticky) return
        if (checkPistonCrossChunkMove(event.direction, event.blocks)) {
            event.isCancelled = true
        }
    }

    private fun checkPistonCrossChunkMove(direction: BlockFace, movedBlocks: List<Block>): Boolean {
        val settings = config.settings
        if (!settings.blockTypesLimit || !settings.blockTypesCheckPistonMove) return false
        if (direction.modY != 0 || movedBlocks.isEmpty()) return false

        val world = movedBlocks.first().world
        if (settings.isIgnoredWorld(world.name)) return false

        val incomingByChunk = HashMap<ChunkCoord, MutableMap<Material, Int>>()
        val sampleYByChunk = HashMap<ChunkCoord, Int>()

        for (block in movedBlocks) {
            val mat = block.type
            if (!settings.blockLimits.containsKey(mat)) continue

            val srcChunkX = block.x shr 4
            val srcChunkZ = block.z shr 4
            val dstX = block.x + direction.modX
            val dstZ = block.z + direction.modZ
            val dstChunkX = dstX shr 4
            val dstChunkZ = dstZ shr 4

            if (srcChunkX != dstChunkX || srcChunkZ != dstChunkZ) {
                val coord = ChunkCoord(dstChunkX, dstChunkZ)
                val matCounts = incomingByChunk.getOrPut(coord) { HashMap() }
                matCounts[mat] = (matCounts[mat] ?: 0) + 1
                sampleYByChunk.putIfAbsent(coord, block.y)
            }
        }

        if (incomingByChunk.isEmpty()) return false

        for ((coord, matCounts) in incomingByChunk) {
            if (!world.isChunkLoaded(coord.x, coord.z)) continue
            if (!Bukkit.isOwnedByCurrentRegion(world, coord.x, coord.z)) continue
            val chunk = world.getChunkAt(coord.x, coord.z)
            val centerY = sampleYByChunk[coord] ?: 64
            for ((mat, incoming) in matCounts) {
                val limit = settings.blockLimits[mat] ?: continue
                val existing = countMaterialInChunk(chunk, world, mat, centerY, limit)
                if (existing + incoming > limit) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Counts blocks of [material] in [chunk], scanning outwards from [hintY] first
     * so dense horizontal layers hit the [stopThreshold] early.
     */
    private fun countMaterialInChunk(
        chunk: Chunk,
        world: World,
        material: Material,
        hintY: Int,
        stopThreshold: Int,
    ): Int {
        val minY = world.minHeight
        val maxY = world.maxHeight - 1
        val clampedHint = hintY.coerceIn(minY, maxY)
        val bandStart = (clampedHint - 4).coerceAtLeast(minY)
        val bandEnd = (clampedHint + 4).coerceAtMost(maxY)

        var count = 0
        // Fast pass near placement Y
        for (x in 0..15) {
            for (z in 0..15) {
                for (y in bandStart..bandEnd) {
                    if (chunk.getBlock(x, y, z).type == material) {
                        count++
                        if (count > stopThreshold) return count
                    }
                }
            }
        }

        // Remaining vertical range bounded by world heightmap
        val baseX = chunk.x shl 4
        val baseZ = chunk.z shl 4
        for (x in 0..15) {
            for (z in 0..15) {
                val highest = world.getHighestBlockYAt(baseX + x, baseZ + z).coerceAtMost(maxY)
                for (y in minY until bandStart) {
                    if (chunk.getBlock(x, y, z).type == material) {
                        count++
                        if (count > stopThreshold) return count
                    }
                }
                for (y in (bandEnd + 1)..highest) {
                    if (chunk.getBlock(x, y, z).type == material) {
                        count++
                        if (count > stopThreshold) return count
                    }
                }
            }
        }
        return count
    }
}
