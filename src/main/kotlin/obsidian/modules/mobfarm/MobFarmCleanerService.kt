package obsidian.modules.mobfarm

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.*
import org.bukkit.entity.*
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class CleanMode {
    MANUAL,
    AUTO,
    SPAWNING,
}

data class DenseChunkInfo(
    val worldName: String,
    val chunkX: Int,
    val chunkZ: Int,
    val totalCount: Int,
    val monsterCount: Int,
    val animalCount: Int,
    val typeCounts: Map<String, Int> = emptyMap(),
    val adjacentCount: Int = 0,
    val adjacentTypeCounts: Map<String, Int> = emptyMap(),
)

data class CleanFeedback(
    val cleaned: Map<EntityType, Int>,
    val chunksChecked: Int,
) {
    val totalCleaned: Int get() = cleaned.values.sum()
}

data class SpawnCheckResult(
    val cancelOriginal: Boolean,
    val lowestMax: Int,
    val removedExisting: Int,
    val disableAiOnOriginal: Boolean,
)

data class LimitEntry(
    val key: String,
    val count: Int,
    val max: Int,
)

data class LimitsReport(
    val rangeChunksWidth: Int,
    val totalChunks: Int,
    val groupLimits: List<LimitEntry>,
    val typeLimits: List<LimitEntry>,
)

data class EntityCandidate(
    val index: Int,
    val typeName: String,
    val ticksLived: Int,
    val exempt: Boolean,
)

data class ExcessSelection(
    val toRemoveIndices: List<Int>,
    val toDisableAiIndices: List<Int>,
    val cancelSpawningOriginal: Boolean,
    val disableAiOnSpawningOriginal: Boolean,
)

/**
 * Core MobFarmManager entity counting, exemption evaluation, spawn gating,
 * manual/auto chunk cleaning, wolf control, and dense-chunk reporting service.
 */
class MobFarmCleanerService(
    private val context: ObsidianContext,
    private val config: MobFarmConfig,
    private val logWriter: MobFarmLogWriter,
) {

    private val pickedUpItemEntities = LinkedHashMap<UUID, Long>(128, 0.75f, true)
    private var autoCleanTask: ScheduledTask? = null

    fun recordPickedUpItem(entityId: UUID) {
        synchronized(pickedUpItemEntities) {
            pickedUpItemEntities[entityId] = System.currentTimeMillis()
            while (pickedUpItemEntities.size > MAX_PICKUP_CACHE) {
                val eldest = pickedUpItemEntities.entries.iterator().next().key
                pickedUpItemEntities.remove(eldest)
            }
        }
    }

    fun clearPickedUpItem(entityId: UUID) {
        synchronized(pickedUpItemEntities) {
            pickedUpItemEntities.remove(entityId)
        }
    }

    fun hasPickedUpItem(entityId: UUID): Boolean {
        synchronized(pickedUpItemEntities) {
            return pickedUpItemEntities.containsKey(entityId)
        }
    }

    fun startOrRestartAutoClean() {
        stopAutoClean()
        val settings = config.settings
        if (!settings.autoCleanUse) return
        val intervalSec = settings.autoCleanIntervalSeconds.toLong().coerceIn(15L, 3600L)
        autoCleanTask = context.scheduler.runGlobalTimer(intervalSec, intervalSec) {
            runAutoCleanCycle()
        }
    }

    fun stopAutoClean() {
        autoCleanTask?.cancel()
        autoCleanTask = null
    }

    /**
     * Synchronously evaluates spawn limits on the spawning entity's region thread.
     */
    fun evaluateSpawn(spawningEntity: Entity): SpawnCheckResult {
        val settings = config.settings
        val loc = spawningEntity.location
        val world = loc.world ?: return SpawnCheckResult(false, -1, 0, false)
        if (settings.isIgnoredWorld(world.name)) return SpawnCheckResult(false, -1, 0, false)

        val centerChunkX = loc.blockX shr 4
        val centerChunkZ = loc.blockZ shr 4
        if (settings.isExcludedChunk(world.name, centerChunkX, centerChunkZ)) {
            return SpawnCheckResult(false, -1, 0, false)
        }

        val typeName = spawningEntity.type.name
        val typeRule = settings.getEntityTypeRule(typeName)
        val groupRule = settings.getGroupForEntity(typeName, world.name)

        val typeLimit = if (typeRule != null && typeRule.use) typeRule.limitForWorld(world.name) else -1
        val groupLimit = if (groupRule != null && groupRule.use) groupRule.limit else -1
        if (typeLimit < 0 && groupLimit < 0) {
            return SpawnCheckResult(false, -1, 0, false)
        }

        val radius = if (settings.groupChunks) settings.groupChunksRadius else 1
        val coords = MobFarmConfig.resolveGroupChunks(centerChunkX, centerChunkZ, radius)
        val entities = collectEntitiesInRegionSync(world, coords, settings)

        val candidates = ArrayList<EntityCandidate>()
        val entityRefs = ArrayList<Entity>()
        val targetTypesLower = buildSet {
            add(typeName.lowercase())
            if (groupRule != null && groupRule.use && groupRule.limit > -1) {
                addAll(groupRule.types)
            }
        }

        for (e in entities) {
            if (shouldSkipCounting(e)) continue
            val eName = e.type.name
            if (eName.lowercase() !in targetTypesLower) continue
            val exempt = isExemptEntity(e, settings)
            val idx = entityRefs.size
            entityRefs += e
            candidates += EntityCandidate(idx, eName, e.ticksLived, exempt)
        }

        var cancelOriginal = false
        var disableAiOnOriginal = false
        var lowestMax = -1
        val toRemove = LinkedHashSet<Entity>()
        val toDisableAi = LinkedHashSet<Entity>()

        // Check group limit first
        if (groupRule != null && groupRule.use && groupLimit > -1) {
            lowestMax = if (lowestMax < 0) groupLimit else minOf(lowestMax, groupLimit)
            val groupCandidates = candidates.filter { it.typeName.lowercase() in groupRule.types }
            val selection = selectExcess(
                candidates = groupCandidates,
                limit = groupLimit,
                mode = CleanMode.SPAWNING,
                removeExisting = groupRule.removeExisting ?: settings.removeExisting,
                disableAi = false,
                maxToRemove = Int.MAX_VALUE,
            )
            if (selection.cancelSpawningOriginal) cancelOriginal = true
            selection.toRemoveIndices.forEach { toRemove += entityRefs[it] }
        }

        // Check entity type limit
        if (typeRule != null && typeRule.use && typeLimit > -1) {
            lowestMax = if (lowestMax < 0) typeLimit else minOf(lowestMax, typeLimit)
            val typeCandidates = candidates.filter { it.typeName.equals(typeName, ignoreCase = true) }
            val selection = selectExcess(
                candidates = typeCandidates,
                limit = typeLimit,
                mode = CleanMode.SPAWNING,
                removeExisting = typeRule.removeExisting ?: settings.removeExisting,
                disableAi = typeRule.disableAi,
                maxToRemove = Int.MAX_VALUE,
            )
            if (selection.cancelSpawningOriginal) cancelOriginal = true
            if (selection.disableAiOnSpawningOriginal) disableAiOnOriginal = true
            selection.toRemoveIndices.forEach { toRemove += entityRefs[it] }
            selection.toDisableAiIndices.forEach { toDisableAi += entityRefs[it] }
        }

        for (e in toRemove) {
            if (e.isValid) e.remove()
        }
        for (e in toDisableAi) {
            disableEntityAi(e)
        }

        return SpawnCheckResult(
            cancelOriginal = cancelOriginal,
            lowestMax = lowestMax,
            removedExisting = toRemove.size,
            disableAiOnOriginal = disableAiOnOriginal,
        )
    }

    /**
     * Cleans chunks around the given [players] using Folia-safe per-location scheduling.
     */
    fun cleanAroundPlayers(
        players: Collection<Player>,
        mode: CleanMode,
        radiusOverride: Int? = null,
        groupChunksOverride: Boolean? = null,
    ): CompletableFuture<CleanFeedback> {
        val settings = config.settings
        val groupChunks = groupChunksOverride ?: when (mode) {
            CleanMode.AUTO -> settings.autoCleanGroupChunks
            CleanMode.MANUAL -> settings.commandsCleanGroupChunks
            CleanMode.SPAWNING -> settings.groupChunks
        }
        val radius = (radiusOverride ?: when (mode) {
            CleanMode.AUTO -> settings.autoCleanChunkRadius
            CleanMode.MANUAL -> settings.commandsCleanGroupChunksRadius
            CleanMode.SPAWNING -> settings.groupChunksRadius
        }).coerceIn(1, 3)

        val maxRemoved = when (mode) {
            CleanMode.AUTO -> settings.autoCleanMaxRemoved
            CleanMode.MANUAL -> settings.commandsCleanMaxRemoved
            CleanMode.SPAWNING -> Int.MAX_VALUE
        }

        val targetCenters = LinkedHashMap<String, Location>()
        if (groupChunks) {
            for (player in players) {
                val loc = player.location
                val world = loc.world ?: continue
                if (settings.isIgnoredWorld(world.name)) continue
                val cx = loc.blockX shr 4
                val cz = loc.blockZ shr 4
                if (settings.isExcludedChunk(world.name, cx, cz)) continue
                targetCenters["${world.name}:$cx:$cz"] = Location(world, (cx * 16 + 8).toDouble(), 64.0, (cz * 16 + 8).toDouble())
            }
        } else {
            val expand = when (mode) {
                CleanMode.AUTO -> (radiusOverride ?: settings.autoCleanExpandRange).coerceIn(1, 3) - 1
                else -> radius - 1
            }
            for (player in players) {
                val loc = player.location
                val world = loc.world ?: continue
                if (settings.isIgnoredWorld(world.name)) continue
                val px = loc.blockX shr 4
                val pz = loc.blockZ shr 4
                for (dx in -expand..expand) {
                    for (dz in -expand..expand) {
                        val cx = px + dx
                        val cz = pz + dz
                        if (settings.isExcludedChunk(world.name, cx, cz)) continue
                        targetCenters["${world.name}:$cx:$cz"] = Location(world, (cx * 16 + 8).toDouble(), 64.0, (cz * 16 + 8).toDouble())
                    }
                }
            }
        }

        if (targetCenters.isEmpty()) {
            return CompletableFuture.completedFuture(CleanFeedback(emptyMap(), 0))
        }

        val groupRadius = if (groupChunks) radius else 1
        val futures = ArrayList<CompletableFuture<Map<EntityType, Int>>>(targetCenters.size)
        val totalChunksEvaluated = AtomicInteger(0)

        for (center in targetCenters.values) {
            val future = CompletableFuture<Map<EntityType, Int>>()
            futures += future
            context.scheduler.runAtLocation(center) {
                runCatching {
                    val world = center.world!!
                    val cx = center.blockX shr 4
                    val cz = center.blockZ shr 4
                    val coords = MobFarmConfig.resolveGroupChunks(cx, cz, groupRadius)
                    totalChunksEvaluated.addAndGet(coords.size)
                    cleanCoordsSync(world, coords, mode, maxRemoved, settings)
                }.onSuccess { removedMap ->
                    future.complete(removedMap)
                }.onFailure { err ->
                    context.plugin.logger.warning("MobFarm clean error at ${center.world?.name}: ${err.message}")
                    future.complete(emptyMap())
                }
            }
        }

        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            val merged = LinkedHashMap<EntityType, Int>()
            for (f in futures) {
                for ((type, count) in f.join()) {
                    merged[type] = (merged[type] ?: 0) + count
                }
            }
            CleanFeedback(merged, totalChunksEvaluated.get())
        }
    }

    /**
     * Cleans a single chunk synchronously on the caller's region thread (used by `/mobclean`).
     */
    fun cleanSingleChunkSync(chunk: Chunk, mode: CleanMode = CleanMode.MANUAL): Int {
        val settings = config.settings
        val world = chunk.world
        if (settings.isIgnoredWorld(world.name) || settings.isExcludedChunk(world.name, chunk.x, chunk.z)) {
            return 0
        }
        val removed = cleanCoordsSync(
            world = world,
            coords = listOf(ChunkCoord(chunk.x, chunk.z)),
            mode = mode,
            maxRemoved = settings.commandsCleanMaxRemoved,
            settings = settings,
        )
        return removed.values.sum()
    }

    private fun cleanCoordsSync(
        world: World,
        coords: List<ChunkCoord>,
        mode: CleanMode,
        maxRemoved: Int,
        settings: MobFarmSettings,
    ): Map<EntityType, Int> {
        val entities = collectEntitiesInRegionSync(world, coords, settings)
        if (entities.isEmpty()) return emptyMap()

        // Wolf control
        if (settings.wolfControlEnabled && mode != CleanMode.SPAWNING) {
            enforceWolfControl(entities, settings.wolfControlMax)
        }

        val candidates = ArrayList<EntityCandidate>(entities.size)
        val entityRefs = ArrayList<Entity>(entities.size)

        for (e in entities) {
            if (shouldSkipCounting(e)) continue
            val exempt = isExemptEntity(e, settings)
            val idx = entityRefs.size
            entityRefs += e
            candidates += EntityCandidate(idx, e.type.name, e.ticksLived, exempt)
        }

        val toRemoveIndices = LinkedHashSet<Int>()
        val toDisableAiIndices = LinkedHashSet<Int>()
        var remainingBudget = maxRemoved

        // Group checks
        for (group in settings.entityGroups.values) {
            if (!group.use || group.limit < 0 || !group.appliesToWorld(world.name)) continue
            if (remainingBudget <= 0) break

            val groupCandidates = candidates.filter { it.typeName.lowercase() in group.types && it.index !in toRemoveIndices }
            val selection = selectExcess(
                candidates = groupCandidates,
                limit = group.limit,
                mode = mode,
                removeExisting = group.removeExisting ?: settings.removeExisting,
                disableAi = false,
                maxToRemove = remainingBudget,
            )
            for (idx in selection.toRemoveIndices) {
                val typeRule = settings.getEntityTypeRule(entityRefs[idx].type.name)
                if (mode == CleanMode.AUTO && typeRule != null && !typeRule.autoClean) continue
                if (mode == CleanMode.MANUAL && typeRule != null && !typeRule.manualClean) continue
                if (toRemoveIndices.add(idx)) {
                    remainingBudget--
                }
            }
        }

        // Per-type checks
        val byType = candidates.filter { it.index !in toRemoveIndices }.groupBy { it.typeName.uppercase() }
        for ((typeName, list) in byType) {
            if (remainingBudget <= 0) break
            val rule = settings.getEntityTypeRule(typeName) ?: continue
            if (!rule.use) continue
            if (mode == CleanMode.AUTO && !rule.autoClean) continue
            if (mode == CleanMode.MANUAL && !rule.manualClean) continue

            val limit = rule.limitForWorld(world.name)
            if (limit < 0) continue

            val selection = selectExcess(
                candidates = list,
                limit = limit,
                mode = mode,
                removeExisting = rule.removeExisting ?: settings.removeExisting,
                disableAi = rule.disableAi,
                maxToRemove = remainingBudget,
            )
            for (idx in selection.toRemoveIndices) {
                if (toRemoveIndices.add(idx)) {
                    remainingBudget--
                }
            }
            toDisableAiIndices.addAll(selection.toDisableAiIndices)
        }

        val removedCounts = LinkedHashMap<EntityType, Int>()
        for (idx in toRemoveIndices) {
            val entity = entityRefs[idx]
            if (entity.isValid) {
                val type = entity.type
                entity.remove()
                removedCounts[type] = (removedCounts[type] ?: 0) + 1
            }
        }

        for (idx in toDisableAiIndices) {
            if (idx !in toRemoveIndices) {
                disableEntityAi(entityRefs[idx])
            }
        }

        return removedCounts
    }

    private fun enforceWolfControl(entities: List<Entity>, maxStanding: Int) {
        val wolvesByOwner = HashMap<UUID, MutableList<Wolf>>()
        for (e in entities) {
            if (e !is Wolf || !e.isTamed || e.isSitting) continue
            val ownerId = e.ownerUniqueId ?: continue
            wolvesByOwner.getOrPut(ownerId) { ArrayList() }.add(e)
        }

        for ((ownerId, wolves) in wolvesByOwner) {
            if (wolves.size <= maxStanding) continue
            val excess = wolves.size - maxStanding
            val sorted = wolves.sortedBy { it.ticksLived }
            var seated = 0
            for (i in 0 until excess) {
                val wolf = sorted[i]
                if (wolf.isValid && !wolf.isSitting) {
                    wolf.isSitting = true
                    seated++
                }
            }
            if (seated > 0) {
                Bukkit.getPlayer(ownerId)?.let { owner ->
                    context.scheduler.runForEntity(owner) {
                        context.locale.send(owner, "mobfarm.wolf-count", "amount" to seated)
                    }
                }
            }
        }
    }

    /**
     * Collects a Folia-safe snapshot of dense chunks across loaded worlds or around [centerPlayer].
     */
    fun collectDenseChunksAsync(
        worldFilter: World? = null,
        centerPlayer: Player? = null,
        range: Int? = null,
        includeNonLiving: Boolean = false,
        limit: Int = 15,
    ): CompletableFuture<List<DenseChunkInfo>> {
        val settings = config.settings
        val targets = ArrayList<Pair<World, ChunkCoord>>()

        if (centerPlayer != null && range != null) {
            val r = (range - 1).coerceIn(0, 8)
            val world = centerPlayer.world
            val cx = centerPlayer.location.blockX shr 4
            val cz = centerPlayer.location.blockZ shr 4
            for (dx in -r..r) {
                for (dz in -r..r) {
                    targets += world to ChunkCoord(cx + dx, cz + dz)
                }
            }
        } else {
            val worlds = if (worldFilter != null) listOf(worldFilter) else Bukkit.getWorlds()
            for (world in worlds) {
                if (settings.excludeDisabledFromList && settings.isIgnoredWorld(world.name)) continue
                for (chunk in world.loadedChunks) {
                    targets += world to ChunkCoord(chunk.x, chunk.z)
                }
            }
        }

        if (targets.isEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
        }

        val chunkSnapshots = ConcurrentHashMap<String, DenseChunkInfo>()
        val futures = ArrayList<CompletableFuture<Void>>(targets.size)

        for ((world, coord) in targets) {
            val f = CompletableFuture<Void>()
            futures += f
            val loc = Location(world, (coord.x * 16 + 8).toDouble(), 64.0, (coord.z * 16 + 8).toDouble())
            context.scheduler.runAtLocation(loc) {
                runCatching {
                    if (world.isChunkLoaded(coord.x, coord.z)) {
                        val chunk = world.getChunkAt(coord.x, coord.z)
                        val info = snapshotChunk(chunk, includeNonLiving)
                        if (info.totalCount > 0) {
                            chunkSnapshots["${world.name}:${coord.x}:${coord.z}"] = info
                        }
                    }
                }
                f.complete(null)
            }
        }

        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            val sorted = chunkSnapshots.values.sortedByDescending { it.totalCount }.take(limit)
            sorted.mapIndexed { idx, info ->
                if (idx < 5) {
                    var near = 0
                    val nearTypes = HashMap<String, Int>()
                    for (dx in -1..1) {
                        for (dz in -1..1) {
                            if (dx == 0 && dz == 0) continue
                            val neighbor = chunkSnapshots["${info.worldName}:${info.chunkX + dx}:${info.chunkZ + dz}"]
                            if (neighbor != null) {
                                near += neighbor.totalCount
                                for ((typeName, count) in neighbor.typeCounts) {
                                    nearTypes[typeName] = (nearTypes[typeName] ?: 0) + count
                                }
                            }
                        }
                    }
                    info.copy(adjacentCount = near, adjacentTypeCounts = nearTypes)
                } else {
                    info
                }
            }
        }
    }

    /**
     * Inspects group and per-type counts vs limits around [location] for `/mfm limits`.
     */
    fun inspectLimitsAsync(location: Location): CompletableFuture<LimitsReport> {
        val settings = config.settings
        val world = location.world ?: return CompletableFuture.completedFuture(LimitsReport(1, 1, emptyList(), emptyList()))
        val radius = if (settings.autoCleanGroupChunks) settings.autoCleanChunkRadius else 1
        val width = radius * 2 - 1
        val totalChunks = width * width
        val cx = location.blockX shr 4
        val cz = location.blockZ shr 4
        val coords = MobFarmConfig.resolveGroupChunks(cx, cz, radius)

        val future = CompletableFuture<LimitsReport>()
        context.scheduler.runAtLocation(location) {
            val entities = collectEntitiesInRegionSync(world, coords, settings)
            val byType = HashMap<String, Int>()
            val byGroup = HashMap<String, Int>()

            for (e in entities) {
                if (shouldSkipCounting(e)) continue
                val typeName = e.type.name
                byType[typeName] = (byType[typeName] ?: 0) + 1
                val group = settings.getGroupForEntity(typeName, world.name)
                if (group != null && group.use && group.limit >= 0) {
                    byGroup[group.groupName] = (byGroup[group.groupName] ?: 0) + 1
                }
            }

            val groupEntries = byGroup.entries.mapNotNull { (gName, count) ->
                val max = settings.entityGroups[gName]?.limit ?: -1
                if (max >= 0) LimitEntry(gName, count, max) else null
            }.sortedBy { it.key }

            val typeEntries = byType.entries.mapNotNull { (tName, count) ->
                val rule = settings.getEntityTypeRule(tName) ?: return@mapNotNull null
                val max = rule.limitForWorld(world.name)
                if (rule.use && max >= 0) LimitEntry(tName, count, max) else null
            }.sortedBy { it.key }

            future.complete(LimitsReport(width, totalChunks, groupEntries, typeEntries))
        }
        return future
    }

    private fun runAutoCleanCycle() {
        val settings = config.settings
        val online = Bukkit.getOnlinePlayers().toList()
        if (online.isEmpty()) return

        cleanAroundPlayers(online, CleanMode.AUTO).thenAccept { feedback ->
            if (feedback.totalCleaned <= 0) return@thenAccept

            val breakdownEntries = feedback.cleaned.entries
                .sortedByDescending { it.value }
                .take(20)
            val plainSummary = breakdownEntries.joinToString(", ") { "${formatEntityName(it.key)}:${it.value}" }

            logWriter.logIfThresholdMet(settings, feedback.totalCleaned, null, plainSummary)

            if (settings.autoCleanInform) {
                val hoverText = breakdownEntries.joinToString("\n") { (type, count) ->
                    context.locale.raw("mobfarm.autoclean-hover-entry")
                        .replace("{type}", formatEntityName(type))
                        .replace("{amount}", count.toString())
                }
                val baseComp = context.locale.prefixedComponent(
                    "mobfarm.autoclean-inform",
                    mapOf("amount" to feedback.totalCleaned.toString()),
                )
                val msg: Component = baseComp.hoverEvent(HoverEvent.showText(TextFormatter.format(hoverText)))

                context.scheduler.runGlobal {
                    for (player in Bukkit.getOnlinePlayers()) {
                        if (player.hasPermission("obsidian.mobfarm.cleaninform") || player.hasPermission("obsidian.admin")) {
                            player.sendMessage(msg)
                        }
                    }
                }
            }
        }
    }

    private fun snapshotChunk(chunk: Chunk, includeNonLiving: Boolean): DenseChunkInfo {
        var monsters = 0
        var animals = 0
        var total = 0
        val typeCounts = HashMap<String, Int>()
        for (e in chunk.entities) {
            if (e.type == EntityType.PLAYER) continue
            if (!includeNonLiving && e !is LivingEntity) continue
            total++
            if (e is Monster) monsters++
            else if (e is Animals) animals++
            val name = e.type.name
            typeCounts[name] = (typeCounts[name] ?: 0) + 1
        }
        return DenseChunkInfo(
            worldName = chunk.world.name,
            chunkX = chunk.x,
            chunkZ = chunk.z,
            totalCount = total,
            monsterCount = monsters,
            animalCount = animals,
            typeCounts = typeCounts,
        )
    }

    private fun collectEntitiesInRegionSync(
        world: World,
        coords: List<ChunkCoord>,
        settings: MobFarmSettings,
    ): List<Entity> {
        val result = ArrayList<Entity>()
        for (coord in coords) {
            if (settings.isExcludedChunk(world.name, coord.x, coord.z)) continue
            if (!world.isChunkLoaded(coord.x, coord.z)) continue
            if (!Bukkit.isOwnedByCurrentRegion(world, coord.x, coord.z)) continue
            val chunk = world.getChunkAt(coord.x, coord.z)
            for (e in chunk.entities) {
                result += e
            }
        }
        return result
    }

    private fun shouldSkipCounting(entity: Entity): Boolean {
        if (entity.type == EntityType.PLAYER) return true
        if (entity is ArmorStand && entity.isInvisible) return true
        if (entity is ItemFrame && !entity.isVisible) return true
        return false
    }

    private fun isExemptEntity(entity: Entity, settings: MobFarmSettings): Boolean {
        if (entity is ArmorStand && settings.ignoreFullArmorStands && hasArmorStandEquipment(entity)) {
            return true
        }
        if (entity is ItemFrame && settings.ignoreFullItemFrames && entity.item.type != Material.AIR) {
            return true
        }
        if (settings.ignoreEntityWithPickedItem && entity is LivingEntity && hasPickedUpItem(entity.uniqueId)) {
            return true
        }
        if (settings.ignoreTamedAnimals && entity is Tameable && entity.isTamed) {
            val keepHorseCheck = entity !is Horse || entity.passengers.any { it is Player }
            val notCamel = entity !is Camel
            if (keepHorseCheck && notCamel) return true
        }
        if (settings.ignoreBabyAnimals && entity is Ageable && !entity.isAdult) {
            if (entity !is Horse || entity.passengers.any { it is Player }) {
                return true
            }
        }
        if (settings.ignoreNamedAnimals && entity.customName() != null) {
            return true
        }
        if (settings.ignoreWithSaddle) {
            if (entity is Steerable && entity.hasSaddle()) return true
            if (entity is AbstractHorse && entity.inventory.saddle != null) return true
        }
        return false
    }

    private fun hasArmorStandEquipment(stand: ArmorStand): Boolean {
        val eq = stand.equipment
        return eq.helmet.type != Material.AIR ||
            eq.chestplate.type != Material.AIR ||
            eq.leggings.type != Material.AIR ||
            eq.boots.type != Material.AIR ||
            eq.itemInMainHand.type != Material.AIR ||
            eq.itemInOffHand.type != Material.AIR
    }

    private fun disableEntityAi(entity: Entity) {
        if (entity is Mob) {
            entity.isAware = false
        } else if (entity is LivingEntity) {
            entity.setAI(false)
        }
    }

    companion object {
        private const val MAX_PICKUP_CACHE = 500

        fun formatEntityName(type: EntityType): String =
            type.name.lowercase().split('_').joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercase() }
            }

        /**
         * Pure selection logic determining which candidates should be removed or have AI disabled.
         */
        fun selectExcess(
            candidates: List<EntityCandidate>,
            limit: Int,
            mode: CleanMode,
            removeExisting: Boolean,
            disableAi: Boolean,
            maxToRemove: Int,
        ): ExcessSelection {
            if (limit < 0) {
                return ExcessSelection(emptyList(), emptyList(), cancelSpawningOriginal = false, disableAiOnSpawningOriginal = false)
            }

            val sorted = candidates.sortedBy { it.ticksLived }

            if (mode == CleanMode.SPAWNING) {
                if (sorted.size < limit) {
                    return ExcessSelection(emptyList(), emptyList(), cancelSpawningOriginal = false, disableAiOnSpawningOriginal = false)
                }
                if (disableAi) {
                    return ExcessSelection(emptyList(), emptyList(), cancelSpawningOriginal = false, disableAiOnSpawningOriginal = true)
                }
                val excess = (sorted.size - limit).coerceAtLeast(0)
                val removable = if (removeExisting && excess > 0) {
                    sorted.asSequence()
                        .filter { !it.exempt }
                        .take(minOf(excess, maxToRemove))
                        .map { it.index }
                        .toList()
                } else {
                    emptyList()
                }
                return ExcessSelection(
                    toRemoveIndices = removable,
                    toDisableAiIndices = emptyList(),
                    cancelSpawningOriginal = true,
                    disableAiOnSpawningOriginal = false,
                )
            }

            // MANUAL or AUTO
            if (sorted.size <= limit) {
                return ExcessSelection(emptyList(), emptyList(), cancelSpawningOriginal = false, disableAiOnSpawningOriginal = false)
            }
            val excess = sorted.size - limit
            if (disableAi) {
                val aiTargets = sorted.asSequence()
                    .filter { !it.exempt }
                    .take(excess)
                    .map { it.index }
                    .toList()
                return ExcessSelection(emptyList(), aiTargets, cancelSpawningOriginal = false, disableAiOnSpawningOriginal = false)
            }
            if (!removeExisting) {
                return ExcessSelection(emptyList(), emptyList(), cancelSpawningOriginal = false, disableAiOnSpawningOriginal = false)
            }
            val removable = sorted.asSequence()
                .filter { !it.exempt }
                .take(minOf(excess, maxToRemove))
                .map { it.index }
                .toList()
            return ExcessSelection(removable, emptyList(), cancelSpawningOriginal = false, disableAiOnSpawningOriginal = false)
        }
    }
}
