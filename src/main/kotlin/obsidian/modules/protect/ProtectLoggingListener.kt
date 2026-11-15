package obsidian.modules.protect

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import obsidian.core.ObsidianContext
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Creeper
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Wither
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.*
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper/Folia event listener suite capturing CoreProtect-style audit telemetry into
 * [ProtectService]'s non-blocking consumer queue and handling `/co inspect` clicks.
 */
class ProtectLoggingListener(
    private val context: ObsidianContext,
    private val service: ProtectService,
    private val engine: ProtectRollbackEngine,
) : Listener {

    private val plain = PlainTextComponentSerializer.plainText()
    private val openContainerSnapshots = ConcurrentHashMap<UUID, Pair<Location, Map<String, Pair<ItemStack, Int>>>>()
    private val inspectCooldown = ConcurrentHashMap<UUID, Long>()

    // --- Inspector Clicks & Interactive Blocks ---

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerInteractInspect(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val clicked = event.clickedBlock ?: return

        if (service.isInspecting(player)) {
            if (event.action != Action.LEFT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_BLOCK) return
            event.isCancelled = true

            val now = System.currentTimeMillis()
            val prev = inspectCooldown.put(player.uniqueId, now) ?: 0L
            if (now - prev < 150L) return

            if (event.action == Action.RIGHT_CLICK_BLOCK) {
                if (clicked.state is Container) {
                    engine.inspectContainer(player, clicked)
                } else {
                    engine.inspectBlock(player, clicked.getRelative(event.blockFace))
                }
            } else {
                engine.inspectBlock(player, clicked)
            }
            return
        }

        if (event.action == Action.RIGHT_CLICK_BLOCK && service.config.logPlayerInteractions &&
            event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY
        ) {
            if (isInteractiveBlock(clicked.type)) {
                service.logBlockInteraction(player.name, clicked)
            }
        }
    }

    // --- Block Placement / Breaking / Signs / Buckets ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!service.config.logBlockPlace) return
        if (event is BlockMultiPlaceEvent) return
        val replaced = event.blockReplacedState
        val placed = event.blockPlaced
        service.logBlockPlace(
            actor = event.player.name,
            location = placed.location,
            newMaterial = placed.type,
            newBlockData = placed.blockData,
            oldMaterial = replaced.type,
            oldBlockData = replaced.blockData,
            oldExtra = service.encodeExtra(replaced),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockMultiPlace(event: BlockMultiPlaceEvent) {
        if (!service.config.logBlockPlace) return
        val actor = event.player.name
        for (replaced in event.replacedBlockStates) {
            val block = replaced.block
            service.logBlockPlace(
                actor = actor,
                location = block.location,
                newMaterial = block.type,
                newBlockData = block.blockData,
                oldMaterial = replaced.type,
                oldBlockData = replaced.blockData,
                oldExtra = service.encodeExtra(replaced),
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!service.config.logBlockBreak) return
        val block = event.block
        val state = block.state
        val actor = event.player.name
        if (service.config.logContainerTransactions && state is Container) {
            for (item in state.inventory.contents) {
                if (item != null && !item.type.isAir && item.amount > 0) {
                    service.logContainerTransaction(
                        actor,
                        block.location,
                        added = false,
                        item = item,
                        amount = item.amount
                    )
                }
            }
        }
        service.logBlockBreak(
            actor = actor,
            location = block.location,
            oldMaterial = block.type,
            oldBlockData = block.blockData,
            oldExtra = service.encodeExtra(state),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        if (!service.config.logBlockPlace) return
        val block = event.block
        val sign = block.state as? Sign
        val oldExtra = service.encodeExtra(sign)
        val newSideLines = (0..3).map { idx -> event.line(idx)?.let { plain.serialize(it) } ?: "" }
        val otherFront = if (event.side == Side.FRONT) newSideLines else (0..3).map {
            sign?.getSide(Side.FRONT)?.line(it)?.let { c -> plain.serialize(c) } ?: ""
        }
        val otherBack = if (event.side == Side.BACK) newSideLines else (0..3).map {
            sign?.getSide(Side.BACK)?.line(it)?.let { c -> plain.serialize(c) } ?: ""
        }
        val newExtra = service.encodeSignLines(otherFront, otherBack)
        service.logBlockPlace(
            actor = event.player.name,
            location = block.location,
            newMaterial = block.type,
            newBlockData = block.blockData,
            oldMaterial = block.type,
            oldBlockData = block.blockData,
            oldExtra = oldExtra,
            newExtra = newExtra,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (!service.config.logBuckets) return
        val block = event.blockClicked.getRelative(event.blockFace)
        val liquid = when (event.bucket) {
            Material.WATER_BUCKET, Material.AXOLOTL_BUCKET, Material.COD_BUCKET,
            Material.SALMON_BUCKET, Material.TROPICAL_FISH_BUCKET, Material.PUFFERFISH_BUCKET,
            Material.TADPOLE_BUCKET -> Material.WATER

            Material.LAVA_BUCKET -> Material.LAVA
            Material.POWDER_SNOW_BUCKET -> Material.POWDER_SNOW
            else -> Material.AIR
        }
        service.logBlockPlace(
            actor = event.player.name,
            location = block.location,
            newMaterial = liquid,
            newBlockData = null,
            oldMaterial = block.type,
            oldBlockData = block.blockData,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (!service.config.logBuckets) return
        val block = event.blockClicked
        service.logBlockBreak(
            actor = event.player.name,
            location = block.location,
            oldMaterial = block.type,
            oldBlockData = block.blockData,
        )
    }

    // --- World / Natural / Explosion / Piston Events ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (!service.config.logExplosions) return
        val actor = when (val ent = event.entity) {
            is TNTPrimed -> (ent.source as? Player)?.name ?: "#tnt"
            is Creeper -> "#creeper"
            is Wither -> "#wither"
            else -> "#${ent.type.name.lowercase()}"
        }
        for (block in event.blockList()) {
            service.logBlockBreak(actor, block.location, block.type, block.blockData, service.encodeExtra(block.state))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (!service.config.logExplosions) return
        val actor = "#explosion"
        for (block in event.blockList()) {
            service.logBlockBreak(actor, block.location, block.type, block.blockData, service.encodeExtra(block.state))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (!service.config.logBlockBurnIgnite) return
        val block = event.block
        service.logBlockBreak("#fire", block.location, block.type, block.blockData, service.encodeExtra(block.state))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        if (!service.config.logBlockBurnIgnite) return
        val actor = event.player?.name ?: "#${event.cause.name.lowercase()}"
        val block = event.block
        service.logBlockPlace(actor, block.location, Material.FIRE, null, block.type, block.blockData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockFade(event: BlockFadeEvent) {
        if (!service.config.logNaturalBreak) return
        val block = event.block
        service.logBlockTransition("#fade", block, block.state, event.newState.type, event.newState.blockData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockForm(event: BlockFormEvent) {
        if (!service.config.logNaturalBreak) return
        val block = event.block
        service.logBlockTransition("#natural", block, block.state, event.newState.type, event.newState.blockData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityBlockForm(event: EntityBlockFormEvent) {
        if (!service.config.logNaturalBreak) return
        val actor = "#${event.entity.type.name.lowercase()}"
        val block = event.block
        service.logBlockTransition(actor, block, block.state, event.newState.type, event.newState.blockData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockSpread(event: BlockSpreadEvent) {
        if (!service.config.logTreeGrowth) return
        val block = event.block
        service.logBlockTransition("#spread", block, block.state, event.newState.type, event.newState.blockData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        if (!service.config.logLeafDecay) return
        val block = event.block
        service.logBlockBreak("#decay", block.location, block.type, block.blockData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        if (!service.config.logTreeGrowth) return
        val actor = event.player?.name ?: "#grow"
        for (state in event.blocks) {
            val block = state.block
            service.logBlockPlace(actor, state.location, state.type, state.blockData, block.type, block.blockData)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (!service.config.logPistons) return
        val dir = event.direction
        for (block in event.blocks) {
            val dest = block.getRelative(dir)
            service.logBlockPlace("#piston", dest.location, block.type, block.blockData, dest.type, dest.blockData)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (!service.config.logPistons) return
        val dir = event.direction
        for (block in event.blocks) {
            val dest = block.getRelative(dir)
            service.logBlockPlace("#piston", dest.location, block.type, block.blockData, dest.type, dest.blockData)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockFromTo(event: BlockFromToEvent) {
        if (!service.config.logLiquidFlow) return
        val src = event.block
        val to = event.toBlock
        val actor = if (src.type == Material.LAVA) "#lava" else "#water"
        service.logBlockPlace(actor, to.location, src.type, src.blockData, to.type, to.blockData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (!service.config.logNaturalBreak) return
        val actor = (event.entity as? Player)?.name ?: "#${event.entityType.name.lowercase()}"
        val block = event.block
        service.logBlockTransition(actor, block, block.state, event.to, event.blockData)
    }

    // --- Containers & Hoppers ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (!service.config.logContainerTransactions) return
        val player = event.player as? Player ?: return
        val loc = resolveContainerLocation(event.inventory) ?: return
        openContainerSnapshots[player.uniqueId] =
            loc to ProtectService.snapshotInventoryCounts(event.inventory.contents)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (!service.config.logContainerTransactions) return
        val player = event.player as? Player ?: return
        flushContainerDiff(player, event.inventory)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        if (!service.config.logHopperTransactions) return
        val item = event.item
        if (item.type.isAir || item.amount <= 0) return
        resolveContainerLocation(event.source)?.let { srcLoc ->
            service.logContainerTransaction("#hopper", srcLoc, added = false, item = item, amount = item.amount)
        }
        resolveContainerLocation(event.destination)?.let { dstLoc ->
            service.logContainerTransaction("#hopper", dstLoc, added = true, item = item, amount = item.amount)
        }
    }

    // --- Entities ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (!service.config.logEntityKills) return
        val entity = event.entity
        if (entity is Player) return
        val actor = entity.killer?.name
            ?: entity.lastDamageCause?.cause?.name?.let { "#${it.lowercase()}" }
            ?: "#environment"
        service.logEntityKill(actor, entity)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (!service.config.logEntitySpawns) return
        if (event.spawnReason == CreatureSpawnEvent.SpawnReason.NATURAL) return
        val actor = "#${event.spawnReason.name.lowercase()}"
        service.logEntitySpawn(actor, event.entity)
    }

    // --- Chat / Command / Sessions ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAsyncChat(event: AsyncChatEvent) {
        if (!service.config.logPlayerMessages) return
        val p = event.player
        service.logMessage(p.name, p.location, ProtectActions.MSG_CHAT, plain.serialize(event.message()))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!service.config.logPlayerCommands) return
        val p = event.player
        service.logMessage(p.name, p.location, ProtectActions.MSG_COMMAND, event.message)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (!service.config.logPlayerSessions) return
        val p = event.player
        service.logMessage(p.name, p.location, ProtectActions.MSG_SESSION_JOIN, "login")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val p = event.player
        service.setInspecting(p, false)
        inspectCooldown.remove(p.uniqueId)
        openContainerSnapshots.remove(p.uniqueId)
        service.previewSessions.remove(p.uniqueId)

        if (service.config.logPlayerSessions) {
            service.logMessage(p.name, p.location, ProtectActions.MSG_SESSION_QUIT, "logout")
        }
    }

    private fun flushContainerDiff(player: Player, inventory: Inventory) {
        val (loc, before) = openContainerSnapshots.remove(player.uniqueId) ?: return
        val after = ProtectService.snapshotInventoryCounts(inventory.contents)
        val allKeys = LinkedHashSet<String>().apply {
            addAll(before.keys)
            addAll(after.keys)
        }
        for (key in allKeys) {
            val prevCount = before[key]?.second ?: 0
            val nextCount = after[key]?.second ?: 0
            val delta = nextCount - prevCount
            if (delta == 0) continue
            val sampleItem = (after[key]?.first ?: before[key]?.first) ?: continue
            service.logContainerTransaction(
                actor = player.name,
                location = loc,
                added = delta > 0,
                item = sampleItem,
                amount = kotlin.math.abs(delta),
            )
        }
    }

    private fun resolveContainerLocation(inventory: Inventory): Location? {
        val holder = inventory.holder
        if (holder is DoubleChest) {
            return (holder.leftSide as? BlockInventoryHolder)?.block?.location ?: holder.location
        }
        if (holder is BlockInventoryHolder) {
            return holder.block.location
        }
        return inventory.location
    }

    private fun isInteractiveBlock(mat: Material): Boolean =
        Tag.DOORS.isTagged(mat) ||
                Tag.TRAPDOORS.isTagged(mat) ||
                Tag.FENCE_GATES.isTagged(mat) ||
                Tag.BUTTONS.isTagged(mat) ||
                Tag.SHULKER_BOXES.isTagged(mat) ||
                mat == Material.LEVER ||
                mat == Material.CHEST ||
                mat == Material.TRAPPED_CHEST ||
                mat == Material.BARREL ||
                mat == Material.ENDER_CHEST ||
                mat == Material.HOPPER ||
                mat == Material.DROPPER ||
                mat == Material.DISPENSER ||
                mat == Material.FURNACE ||
                mat == Material.BLAST_FURNACE ||
                mat == Material.SMOKER ||
                mat == Material.BREWING_STAND ||
                mat == Material.CRAFTING_TABLE ||
                mat == Material.ANVIL ||
                mat == Material.CHIPPED_ANVIL ||
                mat == Material.DAMAGED_ANVIL ||
                mat == Material.NOTE_BLOCK ||
                mat == Material.JUKEBOX ||
                mat == Material.LECTERN
}
