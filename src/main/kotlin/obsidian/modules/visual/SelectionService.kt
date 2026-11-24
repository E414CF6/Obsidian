package obsidian.modules.visual

import obsidian.core.ObsidianContext
import obsidian.core.database.StoredLocation
import obsidian.core.ui.ActionBarPriority
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Immutable axis-aligned 3D region inside a single world.
 */
data class CuboidRegion(
    val worldName: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) {
    fun widthX(): Int = (maxX - minX) + 1
    fun heightY(): Int = (maxY - minY) + 1
    fun lengthZ(): Int = (maxZ - minZ) + 1
    fun volume(): Long = widthX().toLong() * heightY().toLong() * lengthZ().toLong()

    fun contains(world: String, x: Int, y: Int, z: Int): Boolean =
        worldName.equals(world, ignoreCase = true) && x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    fun contains(location: Location): Boolean {
        val world = location.world ?: return false
        return contains(world.name, location.blockX, location.blockY, location.blockZ)
    }

    fun serialize(): String = "$worldName;$minX;$minY;$minZ;$maxX;$maxY;$maxZ"

    fun centerLocation(): Location? {
        val world = Bukkit.getWorld(worldName) ?: return null
        return Location(
            world,
            (minX + maxX + 1) / 2.0,
            (minY + maxY + 1) / 2.0,
            (minZ + maxZ + 1) / 2.0,
        )
    }

    companion object {
        fun fromStored(a: StoredLocation, b: StoredLocation): CuboidRegion? {
            if (!a.world.equals(b.world, ignoreCase = true)) return null
            val ax = kotlin.math.floor(a.x).toInt()
            val ay = kotlin.math.floor(a.y).toInt()
            val az = kotlin.math.floor(a.z).toInt()
            val bx = kotlin.math.floor(b.x).toInt()
            val by = kotlin.math.floor(b.y).toInt()
            val bz = kotlin.math.floor(b.z).toInt()
            return CuboidRegion(
                worldName = a.world,
                minX = minOf(ax, bx),
                minY = minOf(ay, by),
                minZ = minOf(az, bz),
                maxX = maxOf(ax, bx),
                maxY = maxOf(ay, by),
                maxZ = maxOf(az, bz),
            )
        }

        fun fromLocations(a: Location, b: Location): CuboidRegion? {
            val s1 = StoredLocation.fromBukkit(a) ?: return null
            val s2 = StoredLocation.fromBukkit(b) ?: return null
            return fromStored(s1, s2)
        }

        fun deserialize(raw: String?): CuboidRegion? {
            if (raw.isNullOrBlank()) return null
            val p = raw.split(';')
            if (p.size < 7) return null
            return CuboidRegion(
                worldName = p[0],
                minX = p[1].toIntOrNull() ?: return null,
                minY = p[2].toIntOrNull() ?: return null,
                minZ = p[3].toIntOrNull() ?: return null,
                maxX = p[4].toIntOrNull() ?: return null,
                maxY = p[5].toIntOrNull() ?: return null,
                maxZ = p[6].toIntOrNull() ?: return null,
            )
        }
    }
}

/**
 * Manages per-player `/pos1` / `/pos2` cuboid selections and optional Blaze Rod wand selection.
 */
class SelectionService(private val context: ObsidianContext) : Listener {

    private data class PairSelection(
        var pos1: StoredLocation? = null,
        var pos2: StoredLocation? = null,
    )

    private val selections = ConcurrentHashMap<UUID, PairSelection>()
    private val wandUsers = ConcurrentHashMap.newKeySet<UUID>()

    fun toggleWand(player: Player): Boolean {
        return if (wandUsers.remove(player.uniqueId)) {
            context.actionBar.clearState(player.uniqueId, WAND_STATE_KEY)
            false
        } else {
            wandUsers.add(player.uniqueId)
            context.actionBar.setLocaleState(
                player,
                WAND_STATE_KEY,
                ActionBarPriority.SELECTION_WAND,
                "visual.wand-actionbar"
            )
            true
        }
    }

    fun isWandEnabled(uuid: UUID): Boolean = uuid in wandUsers

    fun setPos1(player: Player, location: Location): StoredLocation? {
        val stored = StoredLocation.fromBukkit(location.toBlockLocation()) ?: return null
        val sel = selections.computeIfAbsent(player.uniqueId) { PairSelection() }
        sel.pos1 = stored
        notifySelectionActionBar(player, "1", stored)
        return stored
    }

    fun setPos2(player: Player, location: Location): StoredLocation? {
        val stored = StoredLocation.fromBukkit(location.toBlockLocation()) ?: return null
        val sel = selections.computeIfAbsent(player.uniqueId) { PairSelection() }
        sel.pos2 = stored
        notifySelectionActionBar(player, "2", stored)
        return stored
    }

    private fun notifySelectionActionBar(player: Player, posNum: String, loc: StoredLocation) {
        val region = getRegion(player)
        if (region != null) {
            context.actionBar.pushTransientLocale(
                player,
                "visual.pos-region-actionbar",
                "pos" to posNum,
                "dx" to region.widthX(),
                "dy" to region.heightY(),
                "dz" to region.lengthZ(),
                "volume" to region.volume(),
            )
        } else {
            context.actionBar.pushTransientLocale(
                player,
                "visual.pos-actionbar",
                "pos" to posNum,
                "x" to loc.x.toInt(),
                "y" to loc.y.toInt(),
                "z" to loc.z.toInt(),
            )
        }
    }

    fun getRegion(player: Player): CuboidRegion? {
        val sel = selections[player.uniqueId] ?: return null
        val a = sel.pos1 ?: return null
        val b = sel.pos2 ?: return null
        return CuboidRegion.fromStored(a, b)
    }

    fun showParticles(player: Player, region: CuboidRegion) {
        val world = Bukkit.getWorld(region.worldName) ?: return
        if (player.world != world) return
        val maxSpan = maxOf(region.widthX(), region.heightY(), region.lengthZ())
        val step = (maxSpan / 24).coerceAtLeast(1)
        val dust = Particle.DustOptions(Color.fromRGB(0x06, 0xB6, 0xD4), 1.0f)

        val xMin = region.minX.toDouble()
        val xMax = region.maxX + 1.0
        val yMin = region.minY.toDouble()
        val yMax = region.maxY + 1.0
        val zMin = region.minZ.toDouble()
        val zMax = region.maxZ + 1.0

        var x = xMin
        while (x <= xMax) {
            for (y in doubleArrayOf(yMin, yMax)) {
                for (z in doubleArrayOf(zMin, zMax)) {
                    player.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, dust)
                }
            }
            x += step
        }
        var y = yMin
        while (y <= yMax) {
            for (xi in doubleArrayOf(xMin, xMax)) {
                for (zi in doubleArrayOf(zMin, zMax)) {
                    player.spawnParticle(Particle.DUST, xi, y, zi, 1, 0.0, 0.0, 0.0, 0.0, dust)
                }
            }
            y += step
        }
        var z = zMin
        while (z <= zMax) {
            for (xi in doubleArrayOf(xMin, xMax)) {
                for (yi in doubleArrayOf(yMin, yMax)) {
                    player.spawnParticle(Particle.DUST, xi, yi, z, 1, 0.0, 0.0, 0.0, 0.0, dust)
                }
            }
            z += step
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onWandClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!isWandEnabled(player.uniqueId)) return
        if (player.inventory.itemInMainHand.type != Material.BLAZE_ROD) return
        val block = event.clickedBlock ?: return

        when (event.action) {
            Action.LEFT_CLICK_BLOCK -> {
                event.isCancelled = true
                val s = setPos1(player, block.location) ?: return
                context.locale.send(
                    player,
                    "visual.pos-set",
                    "pos" to "1",
                    "x" to s.x.toInt(),
                    "y" to s.y.toInt(),
                    "z" to s.z.toInt(),
                )
            }

            Action.RIGHT_CLICK_BLOCK -> {
                event.isCancelled = true
                val s = setPos2(player, block.location) ?: return
                context.locale.send(
                    player,
                    "visual.pos-set",
                    "pos" to "2",
                    "x" to s.x.toInt(),
                    "y" to s.y.toInt(),
                    "z" to s.z.toInt(),
                )
                getRegion(player)?.let { showParticles(player, it) }
            }

            else -> Unit
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        selections.remove(event.player.uniqueId)
        wandUsers.remove(event.player.uniqueId)
        context.actionBar.clearState(event.player.uniqueId, WAND_STATE_KEY)
    }

    companion object {
        private const val WAND_STATE_KEY = "selection_wand"
    }
}
