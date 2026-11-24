package obsidian.modules.teleport

import net.kyori.adventure.bossbar.BossBar
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.StoredLocation
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Finds safe random teleport coordinates within world border and configured radius constraints.
 */
class RandomTeleportService(
    private val context: ObsidianContext,
    private val teleportService: TeleportService,
) {
    private val rtpFile = YamlConfigFile(context.plugin, "rtp.yml")
    private val customCenters = ConcurrentHashMap<String, StoredLocation>()
    private val customRadii = ConcurrentHashMap<String, Pair<Int, Int>>()

    private var maxTries: Int = 20
    private var minRadius: Int = 100
    private var maxRadius: Int = 5000
    private var excludedBiomes: Set<String> = emptySet()

    init {
        reload()
    }

    fun reload() {
        rtpFile.reload()
        val raw = context.config.raw
        maxTries = raw.getInt("teleport.random.max-tries", 20).coerceIn(1, 100)
        minRadius = raw.getInt("teleport.random.min-radius", 100).coerceAtLeast(0)
        maxRadius = raw.getInt("teleport.random.max-radius", 5000).coerceAtLeast(minRadius + 1)
        excludedBiomes =
            raw.getStringList("teleport.random.excluded-biomes").map { it.uppercase().replace(" ", "_") }.toSet()

        customCenters.clear()
        customRadii.clear()
        rtpFile.yaml.getConfigurationSection("worlds")?.let { sec ->
            for (worldKey in sec.getKeys(false)) {
                val center = StoredLocation.deserialize(sec.getString("$worldKey.center"))
                if (center != null) {
                    customCenters[worldKey.lowercase()] = center
                }
                if (sec.contains("$worldKey.min-radius") || sec.contains("$worldKey.max-radius")) {
                    val minR = sec.getInt("$worldKey.min-radius", minRadius).coerceAtLeast(0)
                    val maxR = sec.getInt("$worldKey.max-radius", maxRadius).coerceAtLeast(minR + 1)
                    customRadii[worldKey.lowercase()] = minR to maxR
                }
            }
        }
    }

    fun setWorldRtpConfig(world: World, center: Location, minR: Int? = null, maxR: Int? = null) {
        val key = world.name.lowercase()
        val stored = StoredLocation.fromBukkit(center) ?: return
        customCenters[key] = stored
        rtpFile.yaml.set("worlds.$key.center", stored.serialize())
        if (minR != null || maxR != null) {
            val resolvedMin = (minR ?: customRadii[key]?.first ?: minRadius).coerceAtLeast(0)
            val resolvedMax = (maxR ?: customRadii[key]?.second ?: maxRadius).coerceAtLeast(resolvedMin + 1)
            customRadii[key] = resolvedMin to resolvedMax
            rtpFile.yaml.set("worlds.$key.min-radius", resolvedMin)
            rtpFile.yaml.set("worlds.$key.max-radius", resolvedMax)
        }
        rtpFile.save()
    }

    fun randomTeleport(player: Player, world: World = player.world): CompletableFuture<Location?> {
        val key = world.name.lowercase()
        val center = customCenters[key]?.toBukkitLocation() ?: world.spawnLocation
        val (worldMinR, worldMaxR) = customRadii[key] ?: (minRadius to maxRadius)
        val border = world.worldBorder
        val borderHalf = (border.size / 2.0) - 16.0
        val effectiveMax = minOf(worldMaxR.toDouble(), borderHalf).toInt().coerceAtLeast(worldMinR + 1)

        val barId = "rtp_${player.uniqueId}"

        fun tryAttempt(attempt: Int): CompletableFuture<Location?> {
            if (attempt >= maxTries || !player.isOnline) {
                context.bossBar.hide(player, barId)
                return CompletableFuture.completedFuture(null)
            }
            val progress = ((attempt + 1).toFloat() / maxTries.toFloat()).coerceIn(0f, 1f)
            context.bossBar.showStatic(
                player = player,
                id = barId,
                title = context.locale.component(
                    "teleport.rt-bossbar",
                    mapOf("attempt" to (attempt + 1).toString(), "max" to maxTries.toString()),
                ),
                progress = progress,
                color = BossBar.Color.GREEN,
            )
            val rng = ThreadLocalRandom.current()
            val dx = randomSignedDistance(rng, worldMinR, effectiveMax)
            val dz = randomSignedDistance(rng, worldMinR, effectiveMax)
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
                val biomeName = snapshot.getBiome(
                    lx,
                    highestY.coerceIn(world.minHeight, world.maxHeight - 1),
                    lz
                ).key.key.uppercase()
                if (biomeName in excludedBiomes) {
                    return@thenCompose tryAttempt(attempt + 1)
                }

                SafeLocationFinder.findHighestSafeLocation(candidate).thenCompose { safeLoc ->
                    if (safeLoc == null) {
                        tryAttempt(attempt + 1)
                    } else {
                        context.bossBar.hide(player, barId)
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
