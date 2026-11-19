package obsidian.modules.moderation

import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Container
import java.util.concurrent.CompletableFuture

data class ScanResult(
    val material: Material,
    val playerMatches: List<Pair<String, Int>>,
    val containerMatches: List<ContainerScanEntry>,
    val totalCount: Long,
)

data class ContainerScanEntry(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val count: Int,
)

/**
 * Scans online player inventories, ender chests, and loaded containers for specific materials.
 */
class ScanService(private val context: ObsidianContext) {

    fun scan(material: Material, minAmount: Int = 1): CompletableFuture<ScanResult> {
        val future = CompletableFuture<ScanResult>()

        context.scheduler.runGlobal {
            val playerMatches = mutableListOf<Pair<String, Int>>()
            var total = 0L

            for (p in Bukkit.getOnlinePlayers()) {
                var pCount = 0
                for (item in p.inventory.contents) {
                    if (item != null && item.type == material) {
                        pCount += item.amount
                    }
                }
                for (item in p.enderChest.contents) {
                    if (item != null && item.type == material) {
                        pCount += item.amount
                    }
                }
                if (pCount >= minAmount) {
                    playerMatches += p.name to pCount
                }
                total += pCount
            }

            val containerMatches = mutableListOf<ContainerScanEntry>()
            for (world in Bukkit.getWorlds()) {
                for (chunk in world.loadedChunks) {
                    for (tile in chunk.tileEntities) {
                        if (tile is Container) {
                            var cCount = 0
                            for (item in tile.inventory.contents) {
                                if (item != null && item.type == material) {
                                    cCount += item.amount
                                }
                            }
                            if (cCount >= minAmount) {
                                val loc = tile.location
                                containerMatches += ContainerScanEntry(
                                    world = world.name,
                                    x = loc.blockX,
                                    y = loc.blockY,
                                    z = loc.blockZ,
                                    count = cCount,
                                )
                            }
                            total += cCount
                        }
                    }
                }
            }

            playerMatches.sortByDescending { it.second }
            containerMatches.sortByDescending { it.count }

            future.complete(ScanResult(material, playerMatches, containerMatches, total))
        }

        return future
    }
}
