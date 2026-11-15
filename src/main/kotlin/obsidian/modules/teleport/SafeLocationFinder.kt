package obsidian.modules.teleport

import org.bukkit.ChunkSnapshot
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

/**
 * Asynchronous chunk-snapshot-backed safe landing search for teleportation.
 */
object SafeLocationFinder {

    private val hazardousBlocks = setOf(
        Material.LAVA,
        Material.FIRE,
        Material.SOUL_FIRE,
        Material.MAGMA_BLOCK,
        Material.CACTUS,
        Material.SWEET_BERRY_BUSH,
        Material.CAMPFIRE,
        Material.SOUL_CAMPFIRE,
        Material.WITHER_ROSE,
        Material.POWDER_SNOW,
        Material.POINTED_DRIPSTONE,
        Material.VOID_AIR,
    )

    fun isHazardous(material: Material): Boolean = material in hazardousBlocks

    fun isPassableForBody(
        material: Material,
        isSolidFn: (Material) -> Boolean = { it.isSolid },
    ): Boolean =
        material != Material.LAVA && material != Material.WATER && !isHazardous(material) && !isSolidFn(material)

    fun isSafeFloor(
        material: Material,
        isSolidFn: (Material) -> Boolean = { it.isSolid },
    ): Boolean = !isHazardous(material) && isSolidFn(material)

    fun isSafeStandingColumn(
        floor: Material,
        feet: Material,
        head: Material,
        isSolidFn: (Material) -> Boolean = { it.isSolid },
    ): Boolean =
        isSafeFloor(floor, isSolidFn) && isPassableForBody(feet, isSolidFn) && isPassableForBody(head, isSolidFn)

    /**
     * Checks whether [location] falls inside the world's border.
     */
    fun isInsideWorldBorder(location: Location): Boolean {
        val world = location.world ?: return false
        val border = world.worldBorder
        val half = border.size / 2.0
        val center = border.center
        return abs(location.x - center.x) <= half && abs(location.z - center.z) <= half
    }

    /**
     * Resolves a safe landing spot near [target] asynchronously using chunk snapshots.
     */
    fun findSafeLocation(target: Location, downThenUp: Boolean): CompletableFuture<Location?> {
        val world = target.world ?: return CompletableFuture.completedFuture(null)
        if (!isInsideWorldBorder(target)) return CompletableFuture.completedFuture(null)

        val chunkX = target.blockX shr 4
        val chunkZ = target.blockZ shr 4
        val localX = target.blockX and 15
        val localZ = target.blockZ and 15

        return world.getChunkAtAsync(chunkX, chunkZ, true).thenApplyAsync { chunk ->
            val snapshot = chunk.getChunkSnapshot(true, false, false)
            val maxY = if (world.environment == World.Environment.NETHER) {
                minOf(world.maxHeight - 2, 125)
            } else {
                world.maxHeight - 2
            }
            val minY = world.minHeight + 1
            val startY = target.blockY.coerceIn(minY, maxY)

            val foundY =
                searchColumn(snapshot, localX, localZ, startY, minY, maxY, downThenUp) ?: return@thenApplyAsync null

            Location(
                world,
                target.blockX + 0.5,
                foundY.toDouble(),
                target.blockZ + 0.5,
                target.yaw,
                target.pitch,
            )
        }
    }

    internal fun highestStandingSearchStartY(highestBlockY: Int, isNether: Boolean, maxHeight: Int): Int {
        return if (isNether) {
            minOf(maxHeight - 2, 125)
        } else {
            // ChunkSnapshot.getHighestBlockYAt returns the highest non-air block Y, so feet stand at +1.
            (highestBlockY + 1).coerceAtMost(maxHeight - 2)
        }
    }

    /**
     * Finds the highest safe surface Y at the X/Z coordinates of [location].
     */
    fun findHighestSafeLocation(location: Location): CompletableFuture<Location?> {
        val world = location.world ?: return CompletableFuture.completedFuture(null)
        val chunkX = location.blockX shr 4
        val chunkZ = location.blockZ shr 4
        val localX = location.blockX and 15
        val localZ = location.blockZ and 15

        return world.getChunkAtAsync(chunkX, chunkZ, true).thenApplyAsync { chunk ->
            val snapshot = chunk.getChunkSnapshot(true, false, false)
            val topY = highestStandingSearchStartY(
                highestBlockY = snapshot.getHighestBlockYAt(localX, localZ),
                isNether = world.environment == World.Environment.NETHER,
                maxHeight = world.maxHeight,
            )
            val minY = world.minHeight + 1

            for (y in topY downTo minY) {
                val floor = snapshot.getBlockType(localX, y - 1, localZ)
                val feet = snapshot.getBlockType(localX, y, localZ)
                val head = snapshot.getBlockType(localX, y + 1, localZ)
                if (isSafeStandingColumn(floor, feet, head)) {
                    return@thenApplyAsync Location(
                        world,
                        location.blockX + 0.5,
                        y.toDouble(),
                        location.blockZ + 0.5,
                        location.yaw,
                        location.pitch,
                    )
                }
            }
            null
        }
    }

    private fun searchColumn(
        snapshot: ChunkSnapshot,
        x: Int,
        z: Int,
        startY: Int,
        minY: Int,
        maxY: Int,
        downThenUp: Boolean,
    ): Int? {
        fun isSafeAt(y: Int): Boolean {
            if (y < minY || y > maxY) return false
            return isSafeStandingColumn(
                floor = snapshot.getBlockType(x, y - 1, z),
                feet = snapshot.getBlockType(x, y, z),
                head = snapshot.getBlockType(x, y + 1, z),
            )
        }

        if (isSafeAt(startY)) return startY

        val firstRange = if (downThenUp) (startY - 1 downTo minY) else (startY + 1..maxY)
        val secondRange = if (downThenUp) (startY + 1..maxY) else (startY - 1 downTo minY)

        for (y in firstRange) if (isSafeAt(y)) return y
        for (y in secondRange) if (isSafeAt(y)) return y
        return null
    }
}
