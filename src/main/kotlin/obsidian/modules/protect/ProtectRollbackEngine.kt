package obsidian.modules.protect

import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.text.TextFormatter
import obsidian.modules.visual.SelectionService
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.data.BlockData
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.math.max
import kotlin.math.min

/**
 * Executes CoreProtect-style inspect, lookup, paged output, preview, rollback, restore, undo,
 * and purge workflows safely on IO + Paper region/global schedulers.
 */
class ProtectRollbackEngine(
    private val context: ObsidianContext,
    private val service: ProtectService,
) {
    private val repository = service.repository
    private val linesPerPage = 7

    fun attachSelectionIfRequested(sender: CommandSender, query: ProtectQuery): Result<ProtectQuery> {
        if (!query.useSelection) return Result.success(query)
        val player = sender as? Player
            ?: return Result.failure(IllegalArgumentException("#selection / #worldedit requires an in-game player selection."))
        val selectionService = context.getService<SelectionService>()
            ?: return Result.failure(IllegalStateException("Visual selection service is not active."))
        val region = selectionService.getRegion(player)
            ?: return Result.failure(IllegalStateException("Please select an area first using /pos1 and /pos2."))
        return Result.success(
            query.copy(
                world = region.worldName,
                cuboid = ProtectCuboid(
                    world = region.worldName,
                    minX = min(region.minX, region.maxX),
                    minY = min(region.minY, region.maxY),
                    minZ = min(region.minZ, region.maxZ),
                    maxX = max(region.minX, region.maxX),
                    maxY = max(region.minY, region.maxY),
                    maxZ = max(region.minZ, region.maxZ),
                ),
            )
        )
    }

    fun inspectBlock(player: Player, block: Block) {
        val world = block.world.name
        val x = block.x
        val y = block.y
        val z = block.z
        CommandHelper.dispatchAsync(context, player, {
            service.queue.flushSync()
            repository.inspectBlock(world, x, y, z, 50)
        }) { entries ->
            if (entries.isEmpty()) {
                context.locale.send(player, "protect.no-data-location", "x" to x, "y" to y, "z" to z)
                return@dispatchAsync
            }
            val lines = entries.map { e ->
                val verb = when (e.action) {
                    ProtectActions.BLOCK_BREAK -> "broke"
                    ProtectActions.BLOCK_PLACE -> "placed"
                    else -> "clicked"
                }
                val subject = if (e.action == ProtectActions.BLOCK_BREAK) e.oldMaterial else e.newMaterial
                ProtectLookupLine(
                    time = e.time,
                    actor = e.actor,
                    verb = verb,
                    subject = subject.lowercase(),
                    world = e.world,
                    x = e.x,
                    y = e.y,
                    z = e.z,
                    rolledBack = e.rolledBack,
                )
            }
            val session = ProtectLookupPageSession(lines, "Block ($x, $y, $z)")
            service.pageSessions[senderKey(player)] = session
            sendPagedLines(player, session, 1)
        }
    }

    fun inspectContainer(player: Player, block: Block) {
        val world = block.world.name
        val x = block.x
        val y = block.y
        val z = block.z
        CommandHelper.dispatchAsync(context, player, {
            service.queue.flushSync()
            repository.inspectContainer(world, x, y, z, 50)
        }) { entries ->
            if (entries.isEmpty()) {
                // Fallback to block inspection if container history has no entries yet
                inspectBlock(player, block)
                return@dispatchAsync
            }
            val lines = entries.map { e ->
                val verb = if (e.action == ProtectActions.CONTAINER_ADD) "added" else "removed"
                ProtectLookupLine(
                    time = e.time,
                    actor = e.actor,
                    verb = verb,
                    subject = "x${e.amount} ${e.material.lowercase()}",
                    world = e.world,
                    x = e.x,
                    y = e.y,
                    z = e.z,
                    rolledBack = e.rolledBack,
                )
            }
            val session = ProtectLookupPageSession(lines, "Container ($x, $y, $z)")
            service.pageSessions[senderKey(player)] = session
            sendPagedLines(player, session, 1)
        }
    }

    fun performLookup(sender: CommandSender, query: ProtectQuery) {
        CommandHelper.dispatchAsync(context, sender, {
            service.queue.flushSync()
            collectLookupLines(query)
        }) { lines ->
            if (lines.isEmpty()) {
                context.locale.send(sender, "protect.no-results")
                return@dispatchAsync
            }
            if (query.countOnly) {
                context.locale.send(sender, "protect.lookup-count", "count" to lines.size)
                return@dispatchAsync
            }
            val session = ProtectLookupPageSession(lines, "Lookup")
            service.pageSessions[senderKey(sender)] = session
            sendPagedLines(sender, session, query.page)
        }
    }

    fun showPage(sender: CommandSender, page: Int) {
        val session = service.pageSessions[senderKey(sender)]
        if (session == null || session.lines.isEmpty()) {
            context.locale.send(sender, "protect.no-page-session")
            return
        }
        sendPagedLines(sender, session, page)
    }

    fun sendPagedLines(sender: CommandSender, session: ProtectLookupPageSession, requestedPage: Int) {
        val total = session.lines.size
        val maxPage = ((total - 1) / linesPerPage) + 1
        val page = requestedPage.coerceIn(1, maxPage)
        val from = (page - 1) * linesPerPage
        val to = min(total, from + linesPerPage)

        sender.sendMessage(
            TextFormatter.format("<dark_gray>-----</dark_gray> <gradient:#7c3aed:#06b6d4><bold>Obsidian Protect</bold></gradient> <gray>(${session.headerLabel})</gray> <dark_gray>-----</dark_gray>")
        )
        for (idx in from until to) {
            val line = session.lines[idx]
            val age = ProtectCommandParser.formatRelativeTime(line.time)
            val actionColor = when {
                line.verb in setOf("broke", "removed", "killed") -> "red"
                line.verb in setOf("placed", "added", "spawned") -> "green"
                else -> "aqua"
            }
            val strikeOpen = if (line.rolledBack) "<strikethrough><dark_gray>" else ""
            val strikeClose = if (line.rolledBack) "</dark_gray></strikethrough>" else ""
            val detailSuffix =
                if (line.detail.isNotBlank()) " <dark_gray>(${TextFormatter.escape(line.detail)})</dark_gray>" else ""
            val tpCmd = "/co tp ${line.world} ${line.x} ${line.y} ${line.z}"
            val coordsPart =
                "<click:run_command:'$tpCmd'><hover:show_text:'<gray>클릭하여 이동: ${line.world} (${line.x}, ${line.y}, ${line.z})</gray>'><gray>(${line.x}/${line.y}/${line.z}/${line.world})</gray></hover></click>"
            sender.sendMessage(
                TextFormatter.format(
                    "$strikeOpen<gray>$age</gray> <dark_gray>-</dark_gray> <aqua>${TextFormatter.escape(line.actor)}</aqua> <$actionColor>${line.verb}</$actionColor> <white>${
                        TextFormatter.escape(
                            line.subject
                        )
                    }</white>$detailSuffix $coordsPart$strikeClose"
                )
            )
        }
        val prevPart =
            if (page > 1) "<click:run_command:'/co page ${page - 1}'><aqua>◀ 이전</aqua></click>" else "<dark_gray>◀ 이전</dark_gray>"
        val nextPart =
            if (page < maxPage) "<click:run_command:'/co page ${page + 1}'><aqua>다음 ▶</aqua></click>" else "<dark_gray>다음 ▶</dark_gray>"
        sender.sendMessage(
            TextFormatter.format("$prevPart <dark_gray>|</dark_gray> <gray>Page <white>$page/$maxPage</white> (${total}건)</gray> <dark_gray>|</dark_gray> $nextPart")
        )
    }

    fun performRollbackOrRestore(sender: CommandSender, query: ProtectQuery, restoreMode: Boolean) {
        val player = sender as? Player
        if (query.preview && player == null) {
            context.locale.send(sender, "general.player-only")
            return
        }

        CommandHelper.dispatchAsync(context, sender, {
            service.queue.flushSync()
            val targetRolledBack = restoreMode
            val blocks = repository.queryBlocks(
                query,
                rolledBackFilter = targetRolledBack,
                ascending = restoreMode,
                limit = 10_000
            )
            val containers = if (service.config.rollbackItems) {
                repository.queryContainers(
                    query,
                    rolledBackFilter = targetRolledBack,
                    ascending = restoreMode,
                    limit = 10_000
                )
            } else {
                emptyList()
            }
            val entities = if (service.config.rollbackEntities) {
                repository.queryEntities(
                    query,
                    rolledBackFilter = targetRolledBack,
                    ascending = restoreMode,
                    limit = 2_000
                )
            } else {
                emptyList()
            }
            Triple(blocks, containers, entities)
        }) { (blocks, containers, entities) ->
            if (blocks.isEmpty() && containers.isEmpty() && entities.isEmpty()) {
                context.locale.send(sender, "protect.no-results")
                return@dispatchAsync
            }

            if (query.preview && player != null) {
                applyPreview(player, query, restoreMode, blocks)
                return@dispatchAsync
            }

            applyWorldChanges(sender, restoreMode, blocks, containers, entities)
        }
    }

    private fun applyPreview(
        player: Player,
        query: ProtectQuery,
        restoreMode: Boolean,
        blocks: List<ProtectBlockEntry>,
    ) {
        cancelPreview(player, silent = true)
        val previewMap = LinkedHashMap<Location, BlockData>()
        var changed = 0
        for (entry in blocks) {
            if (entry.action == ProtectActions.BLOCK_INTERACT) continue
            val world = Bukkit.getWorld(entry.world) ?: continue
            val loc = Location(world, entry.x.toDouble(), entry.y.toDouble(), entry.z.toDouble())
            val data = resolveTargetBlockData(entry, restoreMode)
            previewMap[loc] = data
            player.sendBlockChange(loc, data)
            changed++
        }
        service.previewSessions[player.uniqueId] =
            ProtectPreviewSession(query.copy(preview = false), restoreMode, previewMap)
        context.locale.send(player, "protect.preview-started", "count" to changed)
    }

    fun applyPreviewSession(player: Player) {
        val session = service.previewSessions.remove(player.uniqueId)
        if (session == null) {
            context.locale.send(player, "protect.no-preview")
            return
        }
        cancelVisualPreviewOnly(player, session)
        performRollbackOrRestore(player, session.query, session.restoreMode)
    }

    fun cancelPreview(player: Player, silent: Boolean = false) {
        val session = service.previewSessions.remove(player.uniqueId)
        if (session == null) {
            if (!silent) context.locale.send(player, "protect.no-preview")
            return
        }
        cancelVisualPreviewOnly(player, session)
        if (!silent) {
            context.locale.send(player, "protect.preview-cancelled")
        }
    }

    private fun cancelVisualPreviewOnly(player: Player, session: ProtectPreviewSession) {
        for (loc in session.previewBlocks.keys) {
            player.sendBlockChange(loc, loc.block.blockData)
        }
    }

    fun performUndo(sender: CommandSender) {
        val undo = service.undoHistory.remove(senderKey(sender))
        if (undo == null) {
            context.locale.send(sender, "protect.nothing-to-undo")
            return
        }
        val reverseRestore = !undo.wasRestore
        CommandHelper.dispatchAsync(context, sender, {
            val allBlocks = repository.queryBlocksByIds(undo.blockIds, ascending = reverseRestore)
            val allContainers = repository.queryContainersByIds(undo.containerIds, ascending = reverseRestore)
            val allEntities = repository.queryEntitiesByIds(undo.entityIds, ascending = reverseRestore)
            Triple(allBlocks, allContainers, allEntities)
        }) { (blocks, containers, entities) ->
            if (blocks.isEmpty() && containers.isEmpty() && entities.isEmpty()) {
                context.locale.send(sender, "protect.nothing-to-undo")
                return@dispatchAsync
            }
            applyWorldChanges(sender, reverseRestore, blocks, containers, entities)
        }
    }

    private fun applyWorldChanges(
        sender: CommandSender,
        restoreMode: Boolean,
        blocks: List<ProtectBlockEntry>,
        containers: List<ProtectContainerEntry>,
        entities: List<ProtectEntityEntry>,
    ) {
        val startedAt = System.currentTimeMillis()
        var blocksChanged = 0
        val modifiedBlockIds = mutableListOf<Long>()
        val modifiedContainerIds = mutableListOf<Long>()
        val modifiedEntityIds = mutableListOf<Long>()
        var itemsModified = 0
        var entitiesRestored = 0

        for (entry in blocks) {
            if (entry.action == ProtectActions.BLOCK_INTERACT) continue
            val world = Bukkit.getWorld(entry.world) ?: continue
            val targetData = resolveTargetBlockData(entry, restoreMode)
            val targetExtra = if (restoreMode) entry.newExtra else entry.oldExtra
            val loc = Location(world, entry.x.toDouble(), entry.y.toDouble(), entry.z.toDouble())
            context.scheduler.runAtLocation(loc) {
                val block = world.getBlockAt(entry.x, entry.y, entry.z)
                block.setBlockData(targetData, false)
                if (targetExtra != null) {
                    service.applyExtra(block, targetExtra)
                }
            }
            modifiedBlockIds += entry.id
            blocksChanged++
        }

        for (entry in containers) {
            val world = Bukkit.getWorld(entry.world) ?: continue
            val loc = Location(world, entry.x.toDouble(), entry.y.toDouble(), entry.z.toDouble())
            val addItem = if (restoreMode) {
                entry.action == ProtectActions.CONTAINER_ADD
            } else {
                entry.action == ProtectActions.CONTAINER_REMOVE
            }
            val item = service.decodeItemStack(entry.itemBase64, entry.amount) ?: continue
            context.scheduler.runAtLocation(loc) {
                val container = world.getBlockAt(entry.x, entry.y, entry.z).state as? Container ?: return@runAtLocation
                if (addItem) {
                    container.inventory.addItem(item)
                } else {
                    container.inventory.removeItem(item)
                }
            }
            modifiedContainerIds += entry.id
            itemsModified += entry.amount
        }

        for (entry in entities) {
            val world = Bukkit.getWorld(entry.world) ?: continue
            if (!restoreMode && entry.action == ProtectActions.ENTITY_KILL) {
                val loc = Location(world, entry.x.toDouble(), entry.y.toDouble(), entry.z.toDouble())
                context.scheduler.runAtLocation(loc) {
                    service.spawnSnapshotEntity(world, loc, entry.entityType, entry.entitySnapshot)
                }
                modifiedEntityIds += entry.id
                entitiesRestored++
            }
        }

        val newRolledBackFlag = !restoreMode
        context.scheduler.runAsync {
            kotlinx.coroutines.runBlocking {
                repository.setBlocksRolledBack(modifiedBlockIds, newRolledBackFlag)
                repository.setContainersRolledBack(modifiedContainerIds, newRolledBackFlag)
                repository.setEntitiesRolledBack(modifiedEntityIds, newRolledBackFlag)
            }
        }

        service.undoHistory[senderKey(sender)] = ProtectUndoBatch(
            wasRestore = restoreMode,
            blockIds = modifiedBlockIds,
            containerIds = modifiedContainerIds,
            entityIds = modifiedEntityIds,
            revertRestoreItems = !restoreMode,
        )

        val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000.0).coerceAtLeast(0.01)
        val key = if (restoreMode) "protect.restore-done" else "protect.rollback-done"
        context.locale.send(
            sender,
            key,
            "blocks" to blocksChanged,
            "items" to itemsModified,
            "entities" to entitiesRestored,
            "seconds" to String.format(java.util.Locale.US, "%.2f", elapsedSec),
        )
    }

    private fun resolveTargetBlockData(entry: ProtectBlockEntry, restoreMode: Boolean): BlockData {
        val rawData = if (restoreMode) entry.newBlockData else entry.oldBlockData
        val rawMat = if (restoreMode) entry.newMaterial else entry.oldMaterial
        if (!rawData.isNullOrBlank()) {
            val parsed = runCatching { Bukkit.createBlockData(rawData) }.getOrNull()
            if (parsed != null) return parsed
        }
        val mat = runCatching { Material.valueOf(rawMat.uppercase()) }.getOrNull() ?: Material.AIR
        return mat.createBlockData()
    }

    private suspend fun collectLookupLines(query: ProtectQuery): List<ProtectLookupLine> {
        val lines = mutableListOf<ProtectLookupLine>()
        val actions = query.actions

        val includeBlocks = actions.isEmpty() || actions.any {
            it == ProtectFilterAction.BLOCK_BREAK || it == ProtectFilterAction.BLOCK_PLACE ||
                    it == ProtectFilterAction.INTERACT || it == ProtectFilterAction.SIGN
        }
        val includeContainers = actions.isEmpty() || actions.any {
            it == ProtectFilterAction.CONTAINER_ADD || it == ProtectFilterAction.CONTAINER_REMOVE
        }
        val includeEntities = actions.isEmpty() || actions.any {
            it == ProtectFilterAction.ENTITY_KILL || it == ProtectFilterAction.ENTITY_SPAWN
        }
        val includeMessages = actions.any {
            it == ProtectFilterAction.CHAT || it == ProtectFilterAction.COMMAND || it == ProtectFilterAction.SESSION
        }

        if (includeBlocks) {
            for (b in repository.queryBlocks(query, limit = 500)) {
                val verb = when (b.action) {
                    ProtectActions.BLOCK_BREAK -> "broke"
                    ProtectActions.BLOCK_PLACE -> "placed"
                    else -> "clicked"
                }
                val subject = if (b.action == ProtectActions.BLOCK_BREAK) b.oldMaterial else b.newMaterial
                val detail = service.decodeSignLines(b.newExtra ?: b.oldExtra)
                    ?.first
                    ?.filter { it.isNotBlank() }
                    ?.joinToString(" / ")
                    .orEmpty()
                lines += ProtectLookupLine(
                    b.time,
                    b.actor,
                    verb,
                    subject.lowercase(),
                    b.world,
                    b.x,
                    b.y,
                    b.z,
                    detail,
                    b.rolledBack
                )
            }
        }
        if (includeContainers) {
            for (c in repository.queryContainers(query, limit = 500)) {
                val verb = if (c.action == ProtectActions.CONTAINER_ADD) "added" else "removed"
                lines += ProtectLookupLine(
                    c.time,
                    c.actor,
                    verb,
                    "x${c.amount} ${c.material.lowercase()}",
                    c.world,
                    c.x,
                    c.y,
                    c.z,
                    "",
                    c.rolledBack
                )
            }
        }
        if (includeEntities) {
            for (e in repository.queryEntities(query, limit = 500)) {
                val verb = if (e.action == ProtectActions.ENTITY_KILL) "killed" else "spawned"
                lines += ProtectLookupLine(
                    e.time,
                    e.actor,
                    verb,
                    e.entityType.lowercase(),
                    e.world,
                    e.x,
                    e.y,
                    e.z,
                    "",
                    e.rolledBack
                )
            }
        }
        if (includeMessages) {
            for (m in repository.queryMessages(query, limit = 500)) {
                val (verb, subject) = when (m.kind) {
                    ProtectActions.MSG_CHAT -> "chat" to m.message
                    ProtectActions.MSG_COMMAND -> "cmd" to m.message
                    ProtectActions.MSG_SESSION_JOIN -> "joined" to "session"
                    else -> "quit" to "session"
                }
                lines += ProtectLookupLine(m.time, m.actor, verb, subject, m.world, m.x, m.y, m.z)
            }
        }

        return lines.sortedByDescending { it.time }
    }

    private fun senderKey(sender: CommandSender): String =
        if (sender is Player) sender.uniqueId.toString() else "CONSOLE"
}
