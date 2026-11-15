package obsidian.modules.protect

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Sign
import org.bukkit.block.data.BlockData
import org.bukkit.block.sign.Side
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.material.Colorable
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class ProtectConfig(
    val apiVersion: Int = 1,
    val defaultRadius: Int = 10,
    val maxRadius: Int = 100,
    val consumerFlushMillis: Long = 1000L,
    val rollbackItems: Boolean = true,
    val rollbackEntities: Boolean = true,
    val logBlockPlace: Boolean = true,
    val logBlockBreak: Boolean = true,
    val logNaturalBreak: Boolean = true,
    val logExplosions: Boolean = true,
    val logPistons: Boolean = true,
    val logBlockBurnIgnite: Boolean = true,
    val logLeafDecay: Boolean = true,
    val logTreeGrowth: Boolean = true,
    val logBuckets: Boolean = true,
    val logLiquidFlow: Boolean = false,
    val logPlayerInteractions: Boolean = true,
    val logContainerTransactions: Boolean = true,
    val logHopperTransactions: Boolean = true,
    val logEntityKills: Boolean = true,
    val logEntitySpawns: Boolean = true,
    val logPlayerMessages: Boolean = true,
    val logPlayerCommands: Boolean = true,
    val logPlayerSessions: Boolean = true,
) {
    companion object {
        fun from(context: ObsidianContext): ProtectConfig {
            val sec = context.config.raw.getConfigurationSection("protect")
            return ProtectConfig(
                defaultRadius = sec?.getInt("default-radius", 10)?.coerceAtLeast(1) ?: 10,
                maxRadius = sec?.getInt("max-radius", 100)?.coerceIn(1, 500) ?: 100,
                consumerFlushMillis = sec?.getLong("consumer-flush-millis", 1000L)?.coerceIn(200L, 10000L) ?: 1000L,
                rollbackItems = sec?.getBoolean("rollback-items", true) ?: true,
                rollbackEntities = sec?.getBoolean("rollback-entities", true) ?: true,
                logBlockPlace = sec?.getBoolean("block-place", true) ?: true,
                logBlockBreak = sec?.getBoolean("block-break", true) ?: true,
                logNaturalBreak = sec?.getBoolean("natural-break", true) ?: true,
                logExplosions = sec?.getBoolean("explosions", true) ?: true,
                logPistons = sec?.getBoolean("pistons", true) ?: true,
                logBlockBurnIgnite = sec?.getBoolean("block-burn", true) ?: true,
                logLeafDecay = sec?.getBoolean("leaf-decay", true) ?: true,
                logTreeGrowth = sec?.getBoolean("tree-growth", true) ?: true,
                logBuckets = sec?.getBoolean("buckets", true) ?: true,
                logLiquidFlow = sec?.getBoolean("liquid-flow", false) ?: false,
                logPlayerInteractions = sec?.getBoolean("player-interactions", true) ?: true,
                logContainerTransactions = sec?.getBoolean("item-transactions", true) ?: true,
                logHopperTransactions = sec?.getBoolean("hopper-transactions", true) ?: true,
                logEntityKills = sec?.getBoolean("entity-kills", true) ?: true,
                logEntitySpawns = sec?.getBoolean("entity-spawns", true) ?: true,
                logPlayerMessages = sec?.getBoolean("player-messages", true) ?: true,
                logPlayerCommands = sec?.getBoolean("player-commands", true) ?: true,
                logPlayerSessions = sec?.getBoolean("player-sessions", true) ?: true,
            )
        }
    }
}

/**
 * CoreProtect-style audit logging, inspector toggle, lookup & serialization service.
 * Registered both in [ObsidianContext] and Bukkit [org.bukkit.plugin.ServicesManager].
 */
class ProtectService(
    private val context: ObsidianContext,
    val repository: ProtectRepository,
    val queue: ProtectQueueService,
) {
    @Volatile
    var config: ProtectConfig = ProtectConfig.from(context)
        private set

    val inspectingPlayers = ConcurrentHashMap.newKeySet<UUID>()
    val pageSessions = ConcurrentHashMap<String, ProtectLookupPageSession>()
    val undoHistory = ConcurrentHashMap<String, ProtectUndoBatch>()
    val previewSessions = ConcurrentHashMap<UUID, ProtectPreviewSession>()

    private val plain = PlainTextComponentSerializer.plainText()

    fun reloadConfig() {
        config = ProtectConfig.from(context)
        queue.start(config.consumerFlushMillis)
    }

    fun shutdown() {
        inspectingPlayers.clear()
        previewSessions.clear()
        pageSessions.clear()
        undoHistory.clear()
        queue.stop()
        queue.flushAllBlocking()
    }

    fun isInspecting(player: Player): Boolean = inspectingPlayers.contains(player.uniqueId)

    fun setInspecting(player: Player, enabled: Boolean) {
        if (enabled) inspectingPlayers.add(player.uniqueId) else inspectingPlayers.remove(player.uniqueId)
    }

    fun toggleInspect(player: Player): Boolean {
        return if (inspectingPlayers.remove(player.uniqueId)) {
            false
        } else {
            inspectingPlayers.add(player.uniqueId)
            true
        }
    }

    // --- Public programmatic logging API (CoreProtectAPI parity) ---

    fun logBlockBreak(
        actor: String,
        location: Location,
        oldMaterial: Material,
        oldBlockData: BlockData? = null,
        oldExtra: String? = null
    ) {
        val world = location.world?.name ?: return
        queue.enqueueBlock(
            ProtectBlockEntry(
                time = System.currentTimeMillis(),
                actor = actor,
                world = world,
                x = location.blockX,
                y = location.blockY,
                z = location.blockZ,
                action = ProtectActions.BLOCK_BREAK,
                oldMaterial = oldMaterial.name,
                oldBlockData = oldBlockData?.asString,
                oldExtra = oldExtra,
                newMaterial = Material.AIR.name,
                newBlockData = null,
                newExtra = null,
            )
        )
    }

    fun logBlockPlace(
        actor: String,
        location: Location,
        newMaterial: Material,
        newBlockData: BlockData? = null,
        oldMaterial: Material = Material.AIR,
        oldBlockData: BlockData? = null,
        oldExtra: String? = null,
        newExtra: String? = null,
    ) {
        val world = location.world?.name ?: return
        queue.enqueueBlock(
            ProtectBlockEntry(
                time = System.currentTimeMillis(),
                actor = actor,
                world = world,
                x = location.blockX,
                y = location.blockY,
                z = location.blockZ,
                action = ProtectActions.BLOCK_PLACE,
                oldMaterial = oldMaterial.name,
                oldBlockData = oldBlockData?.asString,
                oldExtra = oldExtra,
                newMaterial = newMaterial.name,
                newBlockData = newBlockData?.asString,
                newExtra = newExtra,
            )
        )
    }

    fun logBlockTransition(
        actor: String, block: Block, oldState: BlockState, newMaterial: Material, newBlockData: BlockData?
    ) {
        val world = block.world.name
        val oldMat = oldState.type
        val action = when {
            newMaterial.isAir && !oldMat.isAir -> ProtectActions.BLOCK_BREAK
            else -> ProtectActions.BLOCK_PLACE
        }
        queue.enqueueBlock(
            ProtectBlockEntry(
                time = System.currentTimeMillis(),
                actor = actor,
                world = world,
                x = block.x,
                y = block.y,
                z = block.z,
                action = action,
                oldMaterial = oldMat.name,
                oldBlockData = oldState.blockData.asString,
                oldExtra = encodeExtra(oldState),
                newMaterial = newMaterial.name,
                newBlockData = newBlockData?.asString,
                newExtra = null,
            )
        )
    }

    fun logBlockInteraction(actor: String, block: Block) {
        queue.enqueueBlock(
            ProtectBlockEntry(
                time = System.currentTimeMillis(),
                actor = actor,
                world = block.world.name,
                x = block.x,
                y = block.y,
                z = block.z,
                action = ProtectActions.BLOCK_INTERACT,
                oldMaterial = block.type.name,
                oldBlockData = null,
                newMaterial = block.type.name,
                newBlockData = null,
            )
        )
    }

    fun logContainerTransaction(actor: String, location: Location, added: Boolean, item: ItemStack, amount: Int) {
        if (amount <= 0 || item.type.isAir) return
        val world = location.world?.name ?: return
        val unit = item.clone().apply { setAmount(1) }
        val encoded = Base64.getEncoder().encodeToString(unit.serializeAsBytes())
        queue.enqueueContainer(
            ProtectContainerEntry(
                time = System.currentTimeMillis(),
                actor = actor,
                world = world,
                x = location.blockX,
                y = location.blockY,
                z = location.blockZ,
                action = if (added) ProtectActions.CONTAINER_ADD else ProtectActions.CONTAINER_REMOVE,
                material = item.type.name,
                amount = amount,
                itemBase64 = encoded,
            )
        )
    }

    fun logEntityKill(actor: String, entity: LivingEntity) {
        val loc = entity.location
        val world = loc.world?.name ?: return
        queue.enqueueEntity(
            ProtectEntityEntry(
                time = System.currentTimeMillis(),
                actor = actor,
                world = world,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                action = ProtectActions.ENTITY_KILL,
                entityType = entity.type.name,
                entitySnapshot = encodeEntitySnapshot(entity),
            )
        )
    }

    fun logEntitySpawn(actor: String, entity: Entity) {
        val loc = entity.location
        val world = loc.world?.name ?: return
        queue.enqueueEntity(
            ProtectEntityEntry(
                time = System.currentTimeMillis(),
                actor = actor,
                world = world,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                action = ProtectActions.ENTITY_SPAWN,
                entityType = entity.type.name,
                entitySnapshot = encodeEntitySnapshot(entity),
            )
        )
    }

    fun logMessage(actor: String, location: Location, kind: Int, message: String) {
        val world = location.world?.name ?: Bukkit.getWorlds().firstOrNull()?.name ?: "world"
        queue.enqueueMessage(
            ProtectMessageEntry(
                time = System.currentTimeMillis(),
                actor = actor,
                world = world,
                x = location.blockX,
                y = location.blockY,
                z = location.blockZ,
                kind = kind,
                message = message,
            )
        )
    }

    // --- Serialization helpers ---

    fun encodeExtra(state: BlockState?): String? {
        if (state is Sign) {
            return encodeSignLines(
                front = (0..3).map { plain.serialize(state.getSide(Side.FRONT).line(it)) },
                back = (0..3).map { plain.serialize(state.getSide(Side.BACK).line(it)) },
            )
        }
        return null
    }

    fun encodeSignLines(front: List<String>, back: List<String>): String {
        val all = (0..3).map { front.getOrElse(it) { "" } } + (0..3).map { back.getOrElse(it) { "" } }
        return "SIGN:" + all.joinToString("|") {
            Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun decodeSignLines(raw: String?): Pair<List<String>, List<String>>? {
        if (raw.isNullOrBlank() || !raw.startsWith("SIGN:")) return null
        val parts = raw.removePrefix("SIGN:").split('|')
        val decoded = (0..7).map { idx ->
            val b64 = parts.getOrNull(idx).orEmpty()
            if (b64.isEmpty()) "" else String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8)
        }
        return decoded.subList(0, 4) to decoded.subList(4, 8)
    }

    fun applyExtra(block: Block, extra: String?) {
        val signLines = decodeSignLines(extra) ?: return
        val sign = block.state as? Sign ?: return
        val (front, back) = signLines
        for (i in 0..3) {
            sign.getSide(Side.FRONT).line(i, TextFormatter.format(front[i]))
            sign.getSide(Side.BACK).line(i, TextFormatter.format(back[i]))
        }
        sign.update(true, false)
    }

    fun encodeEntitySnapshot(entity: Entity): String {
        val map = LinkedHashMap<String, String>()
        entity.customName()?.let { map["name"] = plain.serialize(it) }
        if (entity is Ageable && !entity.isAdult) map["baby"] = "1"
        if (entity is Colorable) entity.color?.let { map["color"] = it.name }
        if (entity is Tameable && entity.isTamed) {
            entity.ownerUniqueId?.let { map["owner"] = it.toString() }
        }
        return map.entries.joinToString(";") { (k, v) ->
            "$k=${Base64.getEncoder().encodeToString(v.toByteArray(StandardCharsets.UTF_8))}"
        }
    }

    fun spawnSnapshotEntity(world: World, loc: Location, typeName: String, snapshot: String?): Entity? {
        val type = runCatching { EntityType.valueOf(typeName.uppercase()) }.getOrNull() ?: return null
        val entity = world.spawnEntity(loc.clone().add(0.5, 0.0, 0.5), type)
        if (!snapshot.isNullOrBlank()) {
            for (part in snapshot.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq)
                val value = String(Base64.getDecoder().decode(part.substring(eq + 1)), StandardCharsets.UTF_8)
                when (key) {
                    "name" -> {
                        entity.customName(TextFormatter.format(value))
                        entity.isCustomNameVisible = true
                    }

                    "baby" -> if (entity is Ageable && value == "1") entity.setBaby()
                    "color" -> if (entity is Colorable) runCatching { entity.color = DyeColor.valueOf(value) }
                    "owner" -> if (entity is Tameable) {
                        runCatching {
                            entity.isTamed = true
                            entity.owner = Bukkit.getOfflinePlayer(UUID.fromString(value))
                        }
                    }
                }
            }
        }
        return entity
    }

    fun decodeItemStack(itemBase64: String, amount: Int): ItemStack? {
        return runCatching {
            val bytes = Base64.getDecoder().decode(itemBase64)
            ItemStack.deserializeBytes(bytes).apply { setAmount(amount.coerceAtLeast(1)) }
        }.getOrNull()
    }

    companion object {
        fun snapshotInventoryCounts(contents: Array<out ItemStack?>): Map<String, Pair<ItemStack, Int>> {
            val counts = LinkedHashMap<String, Pair<ItemStack, Int>>()
            for (item in contents) {
                if (item == null || item.type.isAir || item.amount <= 0) continue
                val unit = item.clone().apply { amount = 1 }
                val key = Base64.getEncoder().encodeToString(unit.serializeAsBytes())
                val prev = counts[key]
                counts[key] = unit to ((prev?.second ?: 0) + item.amount)
            }
            return counts
        }
    }
}
