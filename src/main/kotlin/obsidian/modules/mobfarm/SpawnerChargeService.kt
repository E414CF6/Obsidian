package obsidian.modules.mobfarm

import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import obsidian.core.database.SpawnerChargeRecord
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.CreatureSpawner
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * CMI-style Silk Touch spawner mining with per-player charge quotas, chunk spawner density limits,
 * and spawner property editing (`SpawnerChargeManager` equivalent).
 */
class SpawnerChargeService(private val context: ObsidianContext) : Listener {

    private val cache = ConcurrentHashMap<UUID, Int>()

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var defaultCharges: Int = 3
        private set

    @Volatile
    var maxCharges: Int = 100
        private set

    @Volatile
    var silkTouchRequired: Boolean = true
        private set

    @Volatile
    var dropChance: Double = 100.0
        private set

    @Volatile
    var maxSpawnersPerChunk: Int = 0
        private set

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        enabled = raw.getBoolean("spawners.charge.enabled", true)
        defaultCharges = raw.getInt("spawners.charge.default-charges", 3).coerceAtLeast(0)
        maxCharges = raw.getInt("spawners.charge.max-charges", 100).coerceAtLeast(1)
        silkTouchRequired = raw.getBoolean("spawners.charge.silk-touch-required", true)
        dropChance = raw.getDouble("spawners.charge.drop-chance", 100.0).coerceIn(0.0, 100.0)
        maxSpawnersPerChunk = raw.getInt("spawners.charge.max-spawners-per-chunk", 0).coerceAtLeast(0)
    }

    fun getCharges(uuid: UUID): Int = cache[uuid] ?: defaultCharges

    fun setCharges(uuid: UUID, amount: Int): Int {
        val clamped = amount.coerceIn(0, maxCharges)
        cache[uuid] = clamped
        persistAsync(uuid, clamped)
        return clamped
    }

    fun addCharges(uuid: UUID, delta: Int): Int {
        val next = (getCharges(uuid) + delta).coerceIn(0, maxCharges)
        cache[uuid] = next
        persistAsync(uuid, next)
        return next
    }

    private fun persistAsync(uuid: UUID, charges: Int) {
        context.scheduler.asyncScope.launch {
            context.spawnerChargeRepository.upsert(SpawnerChargeRecord(uuid, charges, System.currentTimeMillis()))
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpawnerBreak(event: BlockBreakEvent) {
        if (!enabled) return
        val block = event.block
        if (block.type != Material.SPAWNER) return
        val player = event.player
        if (player.gameMode == GameMode.CREATIVE) return

        val tool = player.inventory.itemInMainHand
        if (silkTouchRequired && !tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            return
        }
        if (!player.hasPermission("obsidian.spawner.silktouch") && !player.hasPermission("obsidian.admin")) {
            return
        }

        val bypassCharges = player.hasPermission("obsidian.spawner.charges.bypass") ||
            player.hasPermission("obsidian.admin")
        val current = getCharges(player.uniqueId)
        if (!bypassCharges && current <= 0) {
            event.isCancelled = true
            context.locale.send(player, "mobfarm.spawner-no-charges")
            return
        }

        val spawner = block.state as? CreatureSpawner ?: return
        val spawnedType = spawner.spawnedType ?: EntityType.PIG

        if (!bypassCharges) {
            val remaining = setCharges(player.uniqueId, current - 1)
            context.locale.send(player, "mobfarm.spawner-charge-used", "charges" to remaining)
        }

        event.isDropItems = false
        event.expToDrop = 0

        if (dropChance < 100.0 && Random.nextDouble(0.0, 100.0) > dropChance) {
            return
        }

        val drop = ItemStack(Material.SPAWNER, 1).apply {
            val meta = itemMeta as? BlockStateMeta
            if (meta != null) {
                val bs = meta.blockState as? CreatureSpawner
                if (bs != null) {
                    bs.spawnedType = spawnedType
                    meta.blockState = bs
                }
                meta.displayName(Component.text("${spawnedType.name.lowercase().replaceFirstChar { it.uppercase() }} Spawner"))
                itemMeta = meta
            }
        }
        block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), drop)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpawnerPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        if (block.type != Material.SPAWNER) return
        val player = event.player

        if (maxSpawnersPerChunk > 0 &&
            !player.hasPermission("obsidian.mobfarm.bypasslimit.*") &&
            !player.hasPermission("obsidian.admin")
        ) {
            val count = block.chunk.tileEntities.count { it is CreatureSpawner }
            if (count > maxSpawnersPerChunk) {
                event.isCancelled = true
                context.locale.send(player, "mobfarm.spawner-chunk-limit", "limit" to maxSpawnersPerChunk)
                return
            }
        }

        val meta = event.itemInHand.itemMeta as? BlockStateMeta ?: return
        val sourceSpawner = meta.blockState as? CreatureSpawner ?: return
        val type = sourceSpawner.spawnedType ?: return
        val placedState = block.state as? CreatureSpawner ?: return
        placedState.spawnedType = type
        placedState.update(true, false)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        context.scheduler.asyncScope.launch {
            val rec = context.spawnerChargeRepository.findByUuid(uuid)
            if (rec != null) {
                cache[uuid] = rec.charges
            } else {
                cache.putIfAbsent(uuid, defaultCharges)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        cache.remove(event.player.uniqueId)
    }
}
