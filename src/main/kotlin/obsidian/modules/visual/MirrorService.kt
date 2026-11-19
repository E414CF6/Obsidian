package obsidian.modules.visual

import obsidian.core.ObsidianContext
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ConcurrentHashMap

enum class MirrorMode {
    X,
    Z,
    XZ,
    ROT90,
}

data class MirrorSession(
    val worldName: String,
    val centerX: Int,
    val centerZ: Int,
    val mode: MirrorMode,
)

/**
 * Mirrors block placements and breaks symmetrically around a player's chosen center and axis.
 */
class MirrorService(private val context: ObsidianContext) : Listener {

    private val sessions = ConcurrentHashMap<UUID, MirrorSession>()

    fun setSession(player: Player, mode: MirrorMode?): MirrorSession? {
        if (mode == null) {
            sessions.remove(player.uniqueId)
            return null
        }
        val loc = player.location
        val session = MirrorSession(loc.world.name, loc.blockX, loc.blockZ, mode)
        sessions[player.uniqueId] = session
        return session
    }

    fun getSession(uuid: UUID): MirrorSession? = sessions[uuid]

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val session = sessions[player.uniqueId] ?: return
        val block = event.blockPlaced
        if (!block.world.name.equals(session.worldName, ignoreCase = true)) return

        val targets = computeMirrorCoords(session, block.x, block.y, block.z)
        if (targets.isEmpty()) return

        val blockData = block.blockData.clone()
        val itemType = event.itemInHand.type
        val isCreative = player.gameMode == org.bukkit.GameMode.CREATIVE

        for ((tx, ty, tz) in targets) {
            if (tx == block.x && ty == block.y && tz == block.z) continue
            val loc = Location(block.world, tx.toDouble(), ty.toDouble(), tz.toDouble())
            context.scheduler.runAtLocation(loc) {
                val targetBlock: Block = loc.block
                if (!targetBlock.isReplaceable) return@runAtLocation
                if (!isCreative && itemType != Material.AIR) {
                    if (!consumeOne(player, itemType)) return@runAtLocation
                }
                targetBlock.setBlockData(blockData, true)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val session = sessions[player.uniqueId] ?: return
        val block = event.block
        if (!block.world.name.equals(session.worldName, ignoreCase = true)) return

        val targets = computeMirrorCoords(session, block.x, block.y, block.z)
        for ((tx, ty, tz) in targets) {
            if (tx == block.x && ty == block.y && tz == block.z) continue
            val loc = Location(block.world, tx.toDouble(), ty.toDouble(), tz.toDouble())
            context.scheduler.runAtLocation(loc) {
                val targetBlock = loc.block
                if (!targetBlock.type.isAir) {
                    if (player.gameMode == org.bukkit.GameMode.CREATIVE) {
                        targetBlock.type = Material.AIR
                    } else {
                        targetBlock.breakNaturally(player.inventory.itemInMainHand)
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        sessions.remove(event.player.uniqueId)
    }

    private fun consumeOne(player: Player, material: Material): Boolean {
        val inv = player.inventory
        val slot = inv.first(material)
        if (slot < 0) return false
        val item: ItemStack = inv.getItem(slot) ?: return false
        if (item.amount <= 1) {
            inv.setItem(slot, null)
        } else {
            item.amount -= 1
            inv.setItem(slot, item)
        }
        return true
    }

    private fun computeMirrorCoords(session: MirrorSession, x: Int, y: Int, z: Int): List<Triple<Int, Int, Int>> {
        val dx = x - session.centerX
        val dz = z - session.centerZ
        return when (session.mode) {
            MirrorMode.X -> listOf(Triple(session.centerX - dx, y, z))
            MirrorMode.Z -> listOf(Triple(x, y, session.centerZ - dz))
            MirrorMode.XZ -> listOf(
                Triple(session.centerX - dx, y, z),
                Triple(x, y, session.centerZ - dz),
                Triple(session.centerX - dx, y, session.centerZ - dz),
            )

            MirrorMode.ROT90 -> listOf(
                Triple(session.centerX - dz, y, session.centerZ + dx),
                Triple(session.centerX - dx, y, session.centerZ - dz),
                Triple(session.centerX + dz, y, session.centerZ - dx),
            )
        }
    }
}
