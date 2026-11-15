package obsidian.modules.teleport

import obsidian.core.ObsidianContext
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom

/**
 * Finds safe random teleport coordinates within world border and configured radius constraints.
 */
class RandomTeleportService(
    private val context: ObsidianContext,
    private val teleportService: TeleportService,
) {
    private var maxTries: Int = 20
    private var minRadius: Int = 100
    private var maxRadius: Int = 5000
    private var excludedBiomes: Set<String> = emptySet()

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        maxTries = raw.getInt("teleport.random.max-tries", 20).coerceIn(1, 100)
        minRadius = raw.getInt("teleport.random.min-radius", 100).coerceAtLeast(0)
        maxRadius = raw.getInt("teleport.random.max-radius", 5000).coerceAtLeast(minRadius + 1)
        excludedBiomes = raw.getStringList("teleport.random.excluded-biomes")
            .map { it.uppercase().replace(" ", "_") }
            .toSet()
    }

    fun randomTeleport(player: Player, world: World = player.world): CompletableFuture<Location?> {
        val center = world.spawnLocation
        val border = world.worldBorder
        val borderHalf = (border.size / 2.0) - 16.0
        val effectiveMax = minOf(maxRadius.toDouble(), borderHalf).toInt().coerceAtLeast(minRadius + 1)

        fun tryAttempt(attempt: Int): CompletableFuture<Location?> {
            if (attempt >= maxTries || !player.isOnline) {
                return CompletableFuture.completedFuture(null)
            }
            val rng = ThreadLocalRandom.current()
            val dx = randomSignedDistance(rng, minRadius, effectiveMax)
            val dz = randomSignedDistance(rng, minRadius, effectiveMax)
            val x = center.blockX + dx
            val z = center.blockZ + dz
            val candidate = Location(world, x + 0.5, 64.0, z + 0.5)
            if (!SafeLocationFinder.isInsideWorldBorder(candidate)) {
                return tryAttempt(attempt + 1)
            }

            return world.getChunkAtAsync(x shr 4, z shr 4, true).thenCompose { chunk ->
                val snapshot = chunk.getChunkSnapshot(true, true, false)
                val lx = x and 15
                val lz = z and 15
                val highestY = if (world.environment == World.Environment.NETHER) {
                    minOf(world.maxHeight - 2, 120)
                } else {
                    snapshot.getHighestBlockYAt(lx, lz)
                }
                val biomeName = snapshot.getBiome(lx, highestY.coerceIn(world.minHeight, world.maxHeight - 1), lz)
                    .key.key.uppercase()
                if (biomeName in excludedBiomes) {
                    return@thenCompose tryAttempt(attempt + 1)
                }

                SafeLocationFinder.findHighestSafeLocation(candidate).thenCompose { safeLoc ->
                    if (safeLoc == null) {
                        tryAttempt(attempt + 1)
                    } else {
                        teleportService.teleport(
                            player = player,
                            destination = safeLoc,
                            cause = TeleportCause.COMMAND,
                            ensureSafe = false,
                            recordBack = true,
                        ).thenApply { ok -> if (ok) safeLoc else null }
                    }
                }
            }
        }

        return tryAttempt(0)
    }

    private fun randomSignedDistance(rng: ThreadLocalRandom, min: Int, max: Int): Int {
        val dist = rng.nextInt(min, max + 1)
        return if (rng.nextBoolean()) dist else -dist
    }
}
