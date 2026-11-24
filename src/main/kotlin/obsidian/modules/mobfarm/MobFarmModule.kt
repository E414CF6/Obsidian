package obsidian.modules.mobfarm

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * MobFarm management module absorbing MobFarmManager:
 * per-entity/group limits, auto-cleaner, dynamic hopper throttling,
 * redstone activity tracking, chunk block limits, and wolf control.
 */
class MobFarmModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "mobfarm"
    override val description: String =
        "Entity farm limits, auto-cleaner, hopper throttling, redstone tracking, and chunk block limits"

    val farmConfig = MobFarmConfig(context)
    val logWriter = MobFarmLogWriter(context.plugin.dataFolder)
    val cleanerService = MobFarmCleanerService(context, farmConfig, logWriter)
    val hopperControl = HopperControlService(context, farmConfig)
    val redstoneTracker = RedstoneTrackerService { farmConfig.settings }
    val blockLimitService = BlockLimitService(context, farmConfig)
    private val farmListener = MobFarmListener(context, farmConfig, cleanerService)

    override fun onEnable() {
        farmConfig.reload()
        context.registerService(farmConfig)
        context.registerService(cleanerService)
        context.registerService(hopperControl)
        context.registerService(redstoneTracker)
        context.registerService(blockLimitService)

        redstoneTracker.start()
        cleanerService.startOrRestartAutoClean()
    }

    override fun onDisable() {
        cleanerService.stopAutoClean()
        redstoneTracker.shutdown()
        hopperControl.clear()
        logWriter.shutdown()
    }

    override fun onReload() {
        farmConfig.reload()
        cleanerService.startOrRestartAutoClean()
    }

    override fun listeners(): List<Listener> = listOf(
        farmListener,
        hopperControl,
        redstoneTracker,
        blockLimitService,
    )

    override fun registerCommands(registrar: Commands) {
        registerMfmCommand(registrar)
        registerLegacyAliases(registrar)
    }

    private fun registerMfmCommand(registrar: Commands) {
        val subcommands = listOf("clean", "list", "limits", "hoppers", "redstone", "reload", "help")
        val builder: (String) -> LiteralArgumentBuilder<CommandSourceStack> = { name ->
            Commands.literal(name)
                .requires {
                    it.sender.hasPermission("obsidian.mobfarm.command") ||
                        it.sender.hasPermission("obsidian.admin")
                }
                .executes { ctx ->
                    sendHelp(ctx.source.sender)
                    Command.SINGLE_SUCCESS
                }
                .then(
                    Commands.argument("args", StringArgumentType.greedyString()).suggests { _, sb ->
                            val input = sb.remainingLowerCase
                            val parts = input.split(" ")
                            if (parts.size <= 1) {
                                subcommands
                                    .filter { it.startsWith(parts.firstOrNull().orEmpty()) }
                                    .forEach(sb::suggest)
                            } else {
                                suggestSubArgs(parts).forEach { suggestion ->
                                    val prefix = input.substringBeforeLast(' ', "")
                                    sb.suggest(if (prefix.isEmpty()) suggestion else "$prefix $suggestion")
                                }
                            }
                            sb.buildFuture()
                        }
                        .executes { ctx ->
                            val raw = StringArgumentType.getString(ctx, "args").trim()
                            val args = if (raw.isEmpty()) emptyList() else raw.split(Regex("\\s+"))
                            dispatchMfm(ctx.source.sender, args)
                            Command.SINGLE_SUCCESS
                        }
                )
        }

        CommandHelper.registerWithOptionalAliases(
            registrar = registrar,
            context = context,
            nodeBuilder = builder,
            primaryName = "mfm",
            description = "MobFarmManager control, diagnostics, and cleanup commands",
            aliases = listOf("mobfarm", "mobfarmmanager"),
        )
    }

    private fun registerLegacyAliases(registrar: Commands) {
        val mobCleanNode = Commands.literal("mobclean")
            .requires {
                it.sender.hasPermission("obsidian.command.mobclean") ||
                    it.sender.hasPermission("obsidian.mobfarm.command.clean") ||
                    it.sender.hasPermission("obsidian.admin")
            }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val count = cleanerService.cleanSingleChunkSync(player.location.chunk, CleanMode.MANUAL)
                context.locale.send(player, "automation.mobclean-done", "count" to count)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("radius", IntegerArgumentType.integer(1, 5)).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val radius = IntegerArgumentType.getInteger(ctx, "radius")
                    val pChunk = player.location.chunk
                    val world = player.world
                    for (dx in -radius..radius) {
                        for (dz in -radius..radius) {
                            val chunk = world.getChunkAt(pChunk.x + dx, pChunk.z + dz)
                            val centerBlock = chunk.getBlock(
                                8,
                                player.location.blockY.coerceIn(world.minHeight, world.maxHeight - 1),
                                8,
                            )
                            context.scheduler.runForLocation(centerBlock.location) {
                                cleanerService.cleanSingleChunkSync(chunk, CleanMode.MANUAL)
                            }
                        }
                    }
                    context.locale.send(player, "automation.mobclean-radius-scheduled", "radius" to radius)
                    Command.SINGLE_SUCCESS
                }
            )
            .build()

        registrar.register(
            context.plugin.pluginMeta,
            mobCleanNode,
            "Cull excess mobs in current or surrounding chunks based on farm limits",
            listOf("mfmclean", "cleanmobs"),
        )

        val mobReportNode = Commands.literal("mobreport")
            .requires {
                it.sender.hasPermission("obsidian.command.mobreport") ||
                    it.sender.hasPermission("obsidian.mobfarm.command.list") ||
                    it.sender.hasPermission("obsidian.admin")
            }
            .executes { ctx ->
                val sender = ctx.source.sender
                val world = (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: return@executes 0
                val future = cleanerService.collectDenseChunksAsync(worldFilter = world, includeNonLiving = false, limit = 10)
                awaitOrDispatch(sender, future) { list ->
                    if (list.isEmpty()) {
                        context.locale.send(sender, "automation.mobreport-empty")
                        return@awaitOrDispatch
                    }
                    context.locale.send(sender, "automation.mobreport-header", "world" to world.name)
                    for (chunk in list) {
                        val blockX = chunk.chunkX * 16 + 8
                        val blockZ = chunk.chunkZ * 16 + 8
                        context.locale.send(
                            sender,
                            "automation.mobreport-entry",
                            "world" to chunk.worldName,
                            "chunkX" to chunk.chunkX,
                            "chunkZ" to chunk.chunkZ,
                            "x" to blockX,
                            "z" to blockZ,
                            "total" to chunk.totalCount,
                            "monsters" to chunk.monsterCount,
                            "animals" to chunk.animalCount,
                        )
                    }
                }
                Command.SINGLE_SUCCESS
            }
            .build()

        registrar.register(
            context.plugin.pluginMeta,
            mobReportNode,
            "Report top densest mob chunks causing potential lag",
            listOf("mfmreport", "lagchunks"),
        )
    }

    private fun dispatchMfm(sender: CommandSender, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "help"
        val rest = if (args.size > 1) args.drop(1) else emptyList()

        when (sub) {
            "clean" -> handleClean(sender, rest)
            "list" -> handleList(sender, rest)
            "limits" -> handleLimits(sender)
            "hoppers" -> handleHoppers(sender, rest)
            "redstone" -> handleRedstone(sender)
            "reload" -> handleReload(sender)
            else -> sendHelp(sender)
        }
    }

    private fun handleClean(sender: CommandSender, args: List<String>) {
        if (!hasAnyPerm(sender, "obsidian.mobfarm.command.clean", "obsidian.command.mobclean")) return
        val settings = farmConfig.settings
        val radiusArg = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 3)
        val radius = radiusArg ?: if (settings.commandsCleanGroupChunks) settings.commandsCleanGroupChunksRadius else 2
        val startMs = System.currentTimeMillis()

        val future = cleanerService.cleanAroundPlayers(
            players = Bukkit.getOnlinePlayers(),
            mode = CleanMode.MANUAL,
            radiusOverride = radius,
            groupChunksOverride = settings.commandsCleanGroupChunks,
        )

        awaitOrDispatch(sender, future) { feedback ->
            val elapsed = (System.currentTimeMillis() - startMs).coerceIn(0L, 60_000L)
            val hoverComp = context.locale.component(
                "mobfarm.clean-hover",
                mapOf("time" to elapsed.toString(), "count" to feedback.chunksChecked.toString()),
            )

            if (feedback.totalCleaned <= 0) {
                val msg = context.locale.prefixedComponent("mobfarm.clean-nothing")
                    .hoverEvent(HoverEvent.showText(hoverComp))
                sender.sendMessage(msg)
                return@awaitOrDispatch
            }

            val listParts = feedback.cleaned.entries
                .sortedByDescending { it.value }
                .joinToString("<gray>, </gray>") { (type, count) ->
                    context.locale.raw("mobfarm.clean-list-entry")
                        .replace("{type}", MobFarmCleanerService.formatEntityName(type))
                        .replace("{amount}", count.toString())
                }

            val doneMsg = context.locale.prefixedComponent(
                "mobfarm.clean-done",
                mapOf(
                    "entities" to feedback.totalCleaned.toString(),
                    "chunks" to feedback.chunksChecked.toString(),
                    "list" to listParts,
                ),
            ).hoverEvent(HoverEvent.showText(hoverComp))

            sender.sendMessage(doneMsg)

            val plainSummary = feedback.cleaned.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${MobFarmCleanerService.formatEntityName(it.key)}:${it.value}" }
            logWriter.logIfThresholdMet(settings, feedback.totalCleaned, sender.name, plainSummary)
        }
    }

    private fun handleList(sender: CommandSender, args: List<String>) {
        if (!hasAnyPerm(sender, "obsidian.mobfarm.command.list", "obsidian.command.mobreport")) return

        var range: Int? = null
        var includeNonLiving = false
        for (token in args) {
            if (token.equals("-all", ignoreCase = true)) {
                includeNonLiving = true
            } else {
                token.toIntOrNull()?.let { range = it.coerceIn(1, 8) }
            }
        }

        val playerCenter = if (range != null) {
            CommandHelper.requirePlayer(context, sender) ?: return
        } else {
            null
        }

        val future = cleanerService.collectDenseChunksAsync(
            worldFilter = null,
            centerPlayer = playerCenter,
            range = range,
            includeNonLiving = includeNonLiving,
            limit = 15,
        )

        awaitOrDispatch(sender, future) { list ->
            if (list.isEmpty()) {
                context.locale.send(sender, "mobfarm.list-empty")
                return@awaitOrDispatch
            }

            context.locale.send(sender, "mobfarm.list-header")
            list.forEachIndexed { idx, info ->
                val bx = info.chunkX * 16 + 8
                val bz = info.chunkZ * 16 + 8
                val tpCmd = "/tppos $bx 64 $bz ${info.worldName}"

                val typesHover = info.typeCounts.entries
                    .sortedByDescending { it.value }
                    .take(20)
                    .joinToString("\n") { (t, c) ->
                        "<white>${t.lowercase().replace('_', ' ').replaceFirstChar { ch -> ch.uppercase() }}</white><gray>: $c</gray>"
                    }
                val baseHover = context.locale.raw("mobfarm.list-hover")
                    .replace("{world}", info.worldName)
                    .replace("{x}", bx.toString())
                    .replace("{y}", "64")
                    .replace("{z}", bz.toString())
                val fullHover = if (typesHover.isEmpty()) baseHover else "$baseHover\n$typesHover"

                var line: Component = context.locale.component(
                    "mobfarm.list-entry",
                    mapOf(
                        "place" to (idx + 1).toString(),
                        "x" to info.chunkX.toString(),
                        "z" to info.chunkZ.toString(),
                        "amount" to info.totalCount.toString(),
                    ),
                )
                    .hoverEvent(HoverEvent.showText(TextFormatter.format(fullHover)))
                    .clickEvent(ClickEvent.suggestCommand(tpCmd))

                if (info.adjacentCount > 0) {
                    var aroundComp = context.locale.component(
                        "mobfarm.list-around",
                        mapOf("near" to info.adjacentCount.toString()),
                    )
                    if (info.adjacentTypeCounts.isNotEmpty()) {
                        val aroundHover = info.adjacentTypeCounts.entries.sortedByDescending { it.value }.take(20)
                            .joinToString("\n") { (t, c) ->
                                "<white>${
                                    t.lowercase().replace('_', ' ').replaceFirstChar { ch -> ch.uppercase() }
                                }</white><gray>: $c</gray>"
                            }
                        aroundComp = aroundComp.hoverEvent(HoverEvent.showText(TextFormatter.format(aroundHover)))
                    }
                    line = line.append(aroundComp)
                }

                sender.sendMessage(line)
            }
        }
    }

    private fun handleLimits(sender: CommandSender) {
        if (!hasAnyPerm(sender, "obsidian.mobfarm.command.limits")) return
        val player = CommandHelper.requirePlayer(context, sender) ?: return
        val showFull = player.hasPermission("obsidian.mobfarm.command.limits.full") ||
            player.hasPermission("obsidian.admin")

        val future = cleanerService.inspectLimitsAsync(player.location)
        awaitOrDispatch(sender, future) { report ->
            context.locale.send(
                player,
                "mobfarm.limits-header",
                "range" to report.rangeChunksWidth,
                "chunks" to report.totalChunks,
            )

            if (report.groupLimits.isEmpty() && report.typeLimits.isEmpty()) {
                context.locale.send(player, "mobfarm.limits-empty")
                return@awaitOrDispatch
            }

            if (report.groupLimits.isNotEmpty()) {
                context.locale.sendRaw(player, "mobfarm.limits-groups")
                val formatted = report.groupLimits.joinToString("  ") { formatLimitEntry(it, showFull) }
                player.sendMessage(TextFormatter.format(" $formatted"))
            }

            if (report.typeLimits.isNotEmpty()) {
                context.locale.sendRaw(player, "mobfarm.limits-types")
                report.typeLimits.chunked(2).forEach { pair ->
                    val row = pair.joinToString("    ") { entry ->
                        val niceName = entry.key.lowercase().split('_').joinToString(" ") { p ->
                            p.replaceFirstChar { c -> c.uppercase() }
                        }
                        formatLimitEntry(entry.copy(key = niceName), showFull)
                    }
                    player.sendMessage(TextFormatter.format(" $row"))
                }
            }
        }
    }

    private fun handleHoppers(sender: CommandSender, args: List<String>) {
        if (!hasAnyPerm(sender, "obsidian.mobfarm.command.hoppers")) return

        val arg = args.firstOrNull()?.lowercase()
        if (arg != null) {
            val next = when (arg) {
                "toggle" -> !farmConfig.isHopperControlActive
                "on", "true" -> true
                "off", "false" -> false
                else -> null
            }
            if (next != null) {
                farmConfig.setHopperControlEnabledRuntime(next)
                context.locale.send(
                    sender,
                    "mobfarm.hoppers-toggled",
                    "state" to if (next) "ON" else "OFF",
                )
                return
            }
        }

        if (!farmConfig.isHopperControlActive) {
            context.locale.send(sender, "mobfarm.hoppers-disabled")
            return
        }

        val top = hopperControl.getTopHopperChunks(20)
        if (top.isEmpty()) {
            context.locale.send(sender, "mobfarm.hoppers-empty")
            return
        }

        context.locale.send(sender, "mobfarm.hoppers-header")
        top.forEachIndexed { idx, summary ->
            val bx = summary.chunkX * 16 + 8
            val bz = summary.chunkZ * 16 + 8
            val tpCmd = "/tppos $bx ${summary.sampleY} $bz ${summary.worldName}"
            val hover = context.locale.component("mobfarm.hoppers-hover", mapOf("world" to summary.worldName))
            val line = context.locale.component(
                "mobfarm.hoppers-entry",
                mapOf(
                    "place" to (idx + 1).toString(),
                    "x" to summary.chunkX.toString(),
                    "z" to summary.chunkZ.toString(),
                    "amount" to summary.hopperCount.toString(),
                ),
            )
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand(tpCmd))
            sender.sendMessage(line)
        }
    }

    private fun handleRedstone(sender: CommandSender) {
        if (!hasAnyPerm(sender, "obsidian.mobfarm.command.redstone")) return
        val settings = farmConfig.settings
        if (!settings.redstoneTrack) {
            context.locale.send(sender, "mobfarm.redstone-disabled")
            return
        }

        val windowSec = (settings.redstoneWindowMillis / 1000L).toString()
        val top = redstoneTracker.getTopActive(settings.redstoneWindowMillis, 10)
        if (top.isEmpty()) {
            context.locale.send(sender, "mobfarm.redstone-empty", "window" to windowSec)
            return
        }

        context.locale.send(sender, "mobfarm.redstone-header", "window" to windowSec)
        top.forEachIndexed { idx, activity ->
            val worldName = Bukkit.getWorld(activity.worldId)?.name ?: activity.worldId.toString()
            val bx = activity.chunkX * 16 + 8
            val bz = activity.chunkZ * 16 + 8
            val tpCmd = "/tppos $bx 100 $bz $worldName"
            val hover = context.locale.component("mobfarm.redstone-hover", mapOf("world" to worldName))
            val line = context.locale.component(
                "mobfarm.redstone-entry",
                mapOf(
                    "place" to (idx + 1).toString(),
                    "x" to activity.chunkX.toString(),
                    "z" to activity.chunkZ.toString(),
                    "amount" to activity.count.toString(),
                ),
            )
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand(tpCmd))
            sender.sendMessage(line)
        }
    }

    private fun handleReload(sender: CommandSender) {
        if (!hasAnyPerm(sender, "obsidian.mobfarm.command.reload")) return
        onReload()
        context.locale.send(sender, "mobfarm.reloaded")
    }

    private fun sendHelp(sender: CommandSender) {
        context.locale.sendRaw(sender, "mobfarm.help-header")
        val entries = listOf(
            "clean [radius]" to "Clean overcrowded chunks around online players",
            "list [range] [-all]" to "List chunks with the highest entity counts",
            "limits" to "Show current chunk/area entity counts against limits",
            "hoppers [toggle|on|off]" to "List active hopper chunks or toggle throttling",
            "redstone" to "List chunks with highest redstone activity",
            "reload" to "Reload mobfarm.yml configuration",
        )
        for ((cmd, desc) in entries) {
            context.locale.sendRaw(sender, "mobfarm.help-line", "cmd" to cmd, "desc" to desc)
        }
    }

    private fun formatLimitEntry(entry: LimitEntry, showFull: Boolean): String {
        val color = when {
            entry.count > entry.max -> "red"
            entry.count == entry.max -> "white"
            else -> "green"
        }
        val base = "<$color>${entry.key}</$color>"
        return if (showFull) {
            "$base<gray>: ${entry.count}</gray><dark_gray>/</dark_gray><gray>${entry.max}</gray>"
        } else {
            base
        }
    }

    private fun hasAnyPerm(sender: CommandSender, vararg perms: String): Boolean {
        if (sender.hasPermission("obsidian.admin")) return true
        if (perms.any { sender.hasPermission(it) }) return true
        context.locale.send(sender, "general.no-permission")
        return false
    }

    private fun <T> awaitOrDispatch(
        sender: CommandSender,
        future: CompletableFuture<T>,
        onComplete: (T) -> Unit,
    ) {
        if (sender !is Player) {
            val result = runCatching { future.get(5L, TimeUnit.SECONDS) }.getOrNull()
            if (result != null) {
                onComplete(result)
            } else {
                context.locale.send(sender, "mobfarm.timeout")
            }
            return
        }
        future.thenAccept { result ->
            context.scheduler.runGlobal {
                onComplete(result)
            }
        }
    }

    private fun suggestSubArgs(parts: List<String>): List<String> {
        val sub = parts.firstOrNull()?.lowercase() ?: return emptyList()
        val last = parts.lastOrNull().orEmpty()
        val candidates = when (sub) {
            "clean" -> listOf("1", "2", "3")
            "list" -> listOf("3", "5", "-all")
            "hoppers" -> listOf("toggle", "on", "off")
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(last, ignoreCase = true) }
    }
}
