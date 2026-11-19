package obsidian.modules.automation

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.command.SpecializedCommandExecutor
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import obsidian.integration.DiscordWebhookService
import obsidian.integration.LuckPermsHook
import org.bukkit.Bukkit
import org.bukkit.entity.Animals
import org.bukkit.entity.Item
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import java.lang.management.ManagementFactory

/**
 * Phase 6 Automation, AFK, Server Maintenance, and External Hooks module:
 * `/schedule`, `/afk`, `/status` (`/tps`), `/groundclean`, `/killall`, `/checkperm`, `/ifonline`, `/ifoffline`.
 */
class AutomationModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "automation"
    override val description: String =
        "Scheduled tasks, event hooks, AFK detection, server status/cleanup, and LuckPerms/Discord hooks"

    val scheduleService = ScheduleService(context)
    val eventActionService = EventActionService(context)
    val afkService = AfkService(context)
    val discordService = DiscordWebhookService(context)
    val luckPermsHook = LuckPermsHook()
    val aliasAndCostService = AliasAndCostService(context)
    val mobFarmLimiterService = MobFarmLimiterService(context)

    override fun onEnable() {
        context.registerService(scheduleService)
        context.registerService(eventActionService)
        context.registerService(afkService)
        context.registerService(discordService)
        context.registerService(luckPermsHook)
        context.registerService(aliasAndCostService)
        context.registerService(mobFarmLimiterService)
    }

    override fun onDisable() {
        scheduleService.shutdown()
        afkService.shutdown()
    }

    override fun onReload() {
        scheduleService.reload()
        eventActionService.reload()
        afkService.reload()
        discordService.reload()
        aliasAndCostService.reload()
        mobFarmLimiterService.reload()
    }

    override fun listeners(): List<Listener> = listOf(
        eventActionService,
        afkService,
        aliasAndCostService,
        mobFarmLimiterService,
    )

    override fun registerCommands(registrar: Commands) {
        registerScheduleCommands(registrar)
        registerAfkCommand(registrar)
        registerStatusCommand(registrar)
        registerCleanupCommands(registrar)
        registerConditionalAndPermCommands(registrar)
        registerAliasEditor(registrar)
    }

    private fun registerScheduleCommands(registrar: Commands) {
        val node = Commands.literal("schedule")
            .requires { it.sender.hasPermission("obsidian.command.schedule") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("list").executes { ctx ->
                    val sender = ctx.source.sender
                    val all = scheduleService.getAllJobs()
                    if (all.isEmpty()) {
                        context.locale.send(sender, "automation.schedule-empty")
                        return@executes Command.SINGLE_SUCCESS
                    }
                    context.locale.send(sender, "automation.schedule-header", "count" to all.size)
                    all.forEach { job ->
                        val state = if (job.enabled) "<green>ON</green>" else "<red>OFF</red>"
                        val timing = job.dailyAt?.let { "daily $it" } ?: "every ${job.intervalSeconds}s"
                        sender.sendMessage(TextFormatter.format(" <dark_gray>-</dark_gray> <aqua>${job.id}</aqua> [$state] <gray>($timing, ${job.commands.size} cmds)</gray>"))
                    }
                    Command.SINGLE_SUCCESS
                })
            .then(Commands.literal("run").then(Commands.argument("id", StringArgumentType.word()).suggests { _, b ->
                scheduleService.getAllJobs().map { it.id }.forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val id = StringArgumentType.getString(ctx, "id")
                val job = scheduleService.getJob(id)
                if (job == null) {
                    context.locale.send(ctx.source.sender, "automation.schedule-not-found", "id" to id)
                    return@executes 0
                }
                scheduleService.runNow(job)
                context.locale.send(ctx.source.sender, "automation.schedule-ran", "id" to job.id)
                Command.SINGLE_SUCCESS
            }))
            .then(Commands.literal("toggle").then(Commands.argument("id", StringArgumentType.word()).suggests { _, b ->
                scheduleService.getAllJobs().map { it.id }.forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val id = StringArgumentType.getString(ctx, "id")
                val next = scheduleService.toggleJob(id)
                if (next == null) {
                    context.locale.send(ctx.source.sender, "automation.schedule-not-found", "id" to id)
                    return@executes 0
                }
                context.locale.send(
                    ctx.source.sender,
                    "automation.schedule-toggled",
                    "id" to id,
                    "state" to if (next) "ON" else "OFF"
                )
                Command.SINGLE_SUCCESS
            })).then(
                Commands.literal("reload").executes { ctx ->
                    scheduleService.reload()
                    context.locale.send(ctx.source.sender, "automation.schedule-reloaded")
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, node, "Manage automated schedules", emptyList())
    }

    private fun registerAfkCommand(registrar: Commands) {
        val node = Commands.literal("afk")
            .requires { it.sender.hasPermission("obsidian.command.afk") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val next = !afkService.isAfk(player.uniqueId)
                afkService.setAfk(player, next, null)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    afkService.setAfk(player, true, StringArgumentType.getString(ctx, "reason"))
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, node, "Toggle AFK status", emptyList())
    }

    private fun registerStatusCommand(registrar: Commands) {
        val node = Commands.literal("status")
            .requires { it.sender.hasPermission("obsidian.command.status") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = ctx.source.sender
                val tps = Bukkit.getTPS()
                val mspt = Bukkit.getAverageTickTime()
                val rt = Runtime.getRuntime()
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                val maxMb = rt.maxMemory() / (1024 * 1024)
                val uptimeSec = ManagementFactory.getRuntimeMXBean().uptime / 1000L
                val chunks = Bukkit.getWorlds().sumOf { it.loadedChunks.size }
                val entities = Bukkit.getWorlds().sumOf { it.entityCount }

                sender.sendMessage(TextFormatter.format("<gradient:#7c3aed:#06b6d4><bold>Obsidian Server Status</bold></gradient>"))
                sender.sendMessage(
                    TextFormatter.format(
                        " <gray>TPS (1m/5m/15m):</gray> <green>${"%.2f".format(tps.getOrElse(0) { 20.0 })}</green><gray>, </gray><green>${
                            "%.2f".format(
                                tps.getOrElse(1) { 20.0 })
                        }</green><gray>, </gray><green>${"%.2f".format(tps.getOrElse(2) { 20.0 })}</green> <gray>| MSPT:</gray> <aqua>${
                            "%.2f".format(
                                mspt
                            )
                        }ms</aqua>"
                    )
                )
                sender.sendMessage(
                    TextFormatter.format(
                        " <gray>Memory:</gray> <yellow>${usedMb}MB</yellow> <gray>/</gray> <white>${maxMb}MB</white> <gray>| Uptime:</gray> <aqua>${
                            TextFormatter.formatDuration(
                                uptimeSec * 1000L
                            )
                        }</aqua>"
                    )
                )
                sender.sendMessage(
                    TextFormatter.format(
                        " <gray>Players:</gray> <aqua>${Bukkit.getOnlinePlayers().size}/${Bukkit.getMaxPlayers()}</aqua> <gray>| Chunks:</gray> <white>$chunks</white> <gray>| Entities:</gray> <white>$entities</white>"
                    )
                )
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Show server TPS, MSPT, memory, and loaded world metrics",
            if (context.config.registerStandaloneAliases) listOf("tps") else emptyList(),
        )
    }

    private fun registerCleanupCommands(registrar: Commands) {
        val gcNode = Commands.literal("groundclean")
            .requires { it.sender.hasPermission("obsidian.command.groundclean") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                var removed = 0
                for (world in Bukkit.getWorlds()) {
                    for (entity in world.entities) {
                        if (entity is Item) {
                            context.scheduler.runForEntity(entity) { entity.remove() }
                            removed++
                        }
                    }
                }
                context.locale.send(ctx.source.sender, "automation.groundclean-done", "count" to removed)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            gcNode,
            "Remove dropped ground items across loaded worlds",
            if (context.config.registerStandaloneAliases) listOf("cleanitems") else emptyList(),
        )

        val killAllNode = Commands.literal("killall")
            .requires { it.sender.hasPermission("obsidian.command.killall") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("filter", StringArgumentType.word()).suggests { _, b ->
                listOf("items", "monsters", "animals", "all").forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val filter = StringArgumentType.getString(ctx, "filter").lowercase()
                var removed = 0
                for (world in Bukkit.getWorlds()) {
                    for (entity in world.entities) {
                        if (entity is Player) continue
                        val match = when (filter) {
                            "items" -> entity is Item
                            "monsters", "hostile" -> entity is Monster
                            "animals", "passive" -> entity is Animals
                            "all" -> entity is Monster || entity is Animals || entity is Item
                            else -> entity.type.name.equals(filter, ignoreCase = true)
                        }
                        if (match) {
                            context.scheduler.runForEntity(entity) { entity.remove() }
                            removed++
                        }
                    }
                }
                context.locale.send(
                    ctx.source.sender, "automation.killall-done", "count" to removed, "filter" to filter
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta, killAllNode, "Remove filtered entities in loaded worlds", emptyList()
        )

        val mobCleanNode = Commands.literal("mobclean")
            .requires { it.sender.hasPermission("obsidian.command.mobclean") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val chunk = player.location.chunk
                val count = mobFarmLimiterService.cleanChunk(chunk)
                context.locale.send(player, "automation.mobclean-done", "count" to count)
                Command.SINGLE_SUCCESS
            }.then(
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
                                8
                            )
                            context.scheduler.runForLocation(centerBlock.location) {
                                mobFarmLimiterService.cleanChunk(chunk)
                            }
                        }
                    }
                    context.locale.send(player, "automation.mobclean-radius-scheduled", "radius" to radius)
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            mobCleanNode,
            "Cull excess mobs in current or surrounding chunks based on farm limits",
            listOf("mfmclean", "cleanmobs"),
        )

        val mobReportNode = Commands.literal("mobreport")
            .requires { it.sender.hasPermission("obsidian.command.mobreport") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = ctx.source.sender
                val world = (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: return@executes 0
                val list = mobFarmLimiterService.getTopDenseChunks(world, 10)
                if (list.isEmpty()) {
                    context.locale.send(sender, "automation.mobreport-empty")
                    return@executes 0
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
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            mobReportNode,
            "Report top densest mob chunks causing potential lag",
            listOf("mfmreport", "lagchunks"),
        )
    }

    private fun registerConditionalAndPermCommands(registrar: Commands) {
        val checkPermNode = Commands.literal("checkperm")
            .requires { it.sender.hasPermission("obsidian.command.checkperm") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("permission", StringArgumentType.word()).executes { ctx ->
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
                        val perm = StringArgumentType.getString(ctx, "permission")
                        val has = target.hasPermission(perm)
                        context.locale.send(
                            ctx.source.sender,
                            "automation.checkperm-result",
                            "target" to target.name,
                            "permission" to perm,
                            "state" to if (has) "TRUE" else "FALSE",
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta, checkPermNode, "Inspect an online player's permission value", emptyList()
        )

        val ifOnlineNode = Commands.literal("ifonline")
            .requires { it.sender.hasPermission("obsidian.command.ifonline") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("player", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.then(
                    Commands.argument("command", StringArgumentType.greedyString()).executes { ctx ->
                        val name = StringArgumentType.getString(ctx, "player")
                        val online = Bukkit.getPlayerExact(name)
                        val rawCmd = StringArgumentType.getString(ctx, "command")
                        if (online != null && online.isOnline) {
                            SpecializedCommandExecutor.executeAll(context, listOf(rawCmd), online)
                            context.locale.send(
                                ctx.source.sender, "automation.condition-ran", "player" to online.name
                            )
                        } else {
                            context.locale.send(
                                ctx.source.sender, "automation.condition-skipped", "player" to name
                            )
                        }
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta, ifOnlineNode, "Execute a command only if player is online", emptyList()
        )

        val ifOfflineNode = Commands.literal("ifoffline")
            .requires { it.sender.hasPermission("obsidian.command.ifoffline") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("player", StringArgumentType.word()).then(
                    Commands.argument("command", StringArgumentType.greedyString()).executes { ctx ->
                        val name = StringArgumentType.getString(ctx, "player")
                        val online = Bukkit.getPlayerExact(name)
                        val rawCmd = StringArgumentType.getString(ctx, "command")
                        if (online == null || !online.isOnline) {
                            SpecializedCommandExecutor.executeAll(
                                context, listOf(rawCmd), null, mapOf("player" to name)
                            )
                            context.locale.send(ctx.source.sender, "automation.condition-ran", "player" to name)
                        } else {
                            context.locale.send(
                                ctx.source.sender, "automation.condition-skipped", "player" to name
                            )
                        }
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta, ifOfflineNode, "Execute a command only if player is offline", emptyList()
        )
    }

    private fun registerAliasEditor(registrar: Commands) {
        val node = Commands.literal("aliaseditor")
            .requires { it.sender.hasPermission("obsidian.command.aliaseditor") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.literal("list").executes { ctx ->
                val all = aliasAndCostService.getAliases()
                if (all.isEmpty()) {
                    context.locale.send(ctx.source.sender, "automation.alias-empty")
                } else {
                    context.locale.send(ctx.source.sender, "automation.alias-header", "count" to all.size)
                    for ((name, cmds) in all) {
                        ctx.source.sender.sendMessage(
                            TextFormatter.format(
                                "<gray>-</gray> <aqua>/$name</aqua> <dark_gray>→</dark_gray> <white>${
                                    cmds.joinToString(
                                        " && "
                                    )
                                }</white>"
                            )
                        )
                    }
                }
                Command.SINGLE_SUCCESS
            })
            .then(
                Commands.literal("create").then(
                    Commands.argument("name", StringArgumentType.word()).then(
                        Commands.argument("action", StringArgumentType.greedyString()).executes { ctx ->
                            val name = StringArgumentType.getString(ctx, "name")
                            val action = StringArgumentType.getString(ctx, "action")
                            aliasAndCostService.setAlias(name, listOf(action))
                            context.locale.send(
                                ctx.source.sender,
                                "automation.alias-created",
                                "name" to name,
                                "action" to action
                            )
                            Command.SINGLE_SUCCESS
                        }
                    )
                ))
            .then(
                Commands.literal("delete").then(
                    Commands.argument("name", StringArgumentType.word()).suggests { _, b ->
                        aliasAndCostService.getAliases().keys.filter { it.startsWith(b.remaining, true) }
                            .forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val name = StringArgumentType.getString(ctx, "name")
                        if (aliasAndCostService.removeAlias(name)) {
                            context.locale.send(ctx.source.sender, "automation.alias-deleted", "name" to name)
                            Command.SINGLE_SUCCESS
                        } else {
                            context.locale.send(ctx.source.sender, "automation.alias-not-found", "name" to name)
                            0
                        }
                    }
                ))
            .build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Manage custom command aliases",
            if (context.config.registerStandaloneAliases) listOf("alias") else emptyList(),
        )
    }
}
