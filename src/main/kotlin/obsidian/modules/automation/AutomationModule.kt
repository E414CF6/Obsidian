package obsidian.modules.automation

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.command.SpecializedCommandExecutor
import obsidian.core.database.DatabaseBackupAndMigrationService
import obsidian.core.database.DatabaseDialect
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import obsidian.integration.DiscordWebhookService
import obsidian.integration.LuckPermsHook
import org.bukkit.Bukkit
import org.bukkit.Material
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
    val consoleFilterService = ConsoleFilterService(context)
    val dbToolsService = DatabaseBackupAndMigrationService(context)

    override fun onEnable() {
        context.registerService(scheduleService)
        context.registerService(eventActionService)
        context.registerService(afkService)
        context.registerService(discordService)
        context.registerService(luckPermsHook)
        context.registerService(aliasAndCostService)
        context.registerService(consoleFilterService)
        context.registerService(dbToolsService)
        consoleFilterService.start()
    }

    override fun onDisable() {
        consoleFilterService.shutdown()
        scheduleService.shutdown()
        afkService.shutdown()
    }

    override fun onReload() {
        scheduleService.reload()
        eventActionService.reload()
        afkService.reload()
        discordService.reload()
        aliasAndCostService.reload()
        consoleFilterService.reload()
    }

    override fun listeners(): List<Listener> = listOf(
        eventActionService,
        afkService,
        aliasAndCostService,
    )

    override fun registerCommands(registrar: Commands) {
        registerScheduleCommands(registrar)
        registerAfkCommand(registrar)
        registerStatusCommand(registrar)
        registerCleanupCommands(registrar)
        registerConditionalAndPermCommands(registrar)
        registerAliasEditor(registrar)
        registerTier4DatabaseCommands(registrar)
    }

    private fun registerScheduleCommands(registrar: Commands) {
        val node = Commands.literal("schedule")
            .requires { it.sender.hasPermission("obsidian.command.schedule") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                openScheduleGui(player)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.literal("gui").executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    openScheduleGui(player)
                    Command.SINGLE_SUCCESS
                }
            )
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
                        val state = context.locale.raw(if (job.enabled) "general.state-on" else "general.state-off")
                        val timing = job.dailyAt?.let {
                            context.locale.raw("automation.schedule-timing-daily", "time" to it)
                        } ?: context.locale.raw("automation.schedule-timing-interval", "seconds" to job.intervalSeconds)
                        context.locale.sendRaw(
                            sender,
                            "automation.schedule-entry",
                            "id" to job.id,
                            "state" to state,
                            "timing" to timing,
                            "commands" to job.commands.size,
                        )
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

    private fun openScheduleGui(player: Player) {
        val all = scheduleService.getAllJobs()
        if (all.isEmpty()) {
            context.locale.send(player, "automation.schedule-empty")
            return
        }
        val window = MenuWindow(
            context.locale.component("automation.schedule-gui-title", mapOf("count" to all.size.toString())),
            5
        )
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))
        var slot = 10
        for (job in all) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++
            if (slot >= 35) break
            val state = context.locale.raw(if (job.enabled) "general.state-on" else "general.state-off")
            val timing = job.dailyAt?.let {
                context.locale.raw("automation.schedule-timing-daily", "time" to it)
            } ?: context.locale.raw("automation.schedule-timing-interval", "seconds" to job.intervalSeconds)
            val item = MenuItems.item(
                if (job.enabled) Material.CLOCK else Material.GRAY_DYE,
                "<aqua>${job.id}</aqua> <gray>[$state<gray>]",
                listOf(
                    timing,
                    context.locale.raw("automation.schedule-gui-cmds", "count" to job.commands.size),
                    "",
                    context.locale.raw("automation.schedule-gui-toggle"),
                    context.locale.raw("automation.schedule-gui-run"),
                ),
            )
            window.set(slot++, item) { click ->
                if (click.clickType.isRightClick) {
                    scheduleService.runNow(job)
                    context.locale.send(click.player, "automation.schedule-ran", "id" to job.id)
                    context.actionBar.pushTransientLocale(click.player, "automation.schedule-ran", "id" to job.id)
                } else {
                    val next = scheduleService.toggleJob(job.id)
                    if (next != null) {
                        context.locale.send(
                            click.player,
                            "automation.schedule-toggled",
                            "id" to job.id,
                            "state" to if (next) "ON" else "OFF",
                        )
                        openScheduleGui(click.player)
                    }
                }
            }
        }
        window.open(player)
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

                context.locale.sendRaw(sender, "automation.status-header")
                context.locale.sendRaw(
                    sender,
                    "automation.status-tps",
                    "tps1" to "%.2f".format(tps.getOrElse(0) { 20.0 }),
                    "tps5" to "%.2f".format(tps.getOrElse(1) { 20.0 }),
                    "tps15" to "%.2f".format(tps.getOrElse(2) { 20.0 }),
                    "mspt" to "%.2f".format(mspt),
                )
                context.locale.sendRaw(
                    sender,
                    "automation.status-memory",
                    "used" to usedMb,
                    "max" to maxMb,
                    "uptime" to TextFormatter.formatDuration(uptimeSec * 1000L),
                )
                context.locale.sendRaw(
                    sender,
                    "automation.status-world",
                    "online" to Bukkit.getOnlinePlayers().size,
                    "max" to Bukkit.getMaxPlayers(),
                    "chunks" to chunks,
                    "entities" to entities,
                )
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Show server TPS, MSPT, memory, and loaded world metrics",
            listOf("tps"),
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
            listOf("cleanitems"),
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
                        context.locale.sendRaw(
                            ctx.source.sender,
                            "automation.alias-entry",
                            "name" to name,
                            "commands" to cmds.joinToString(" && "),
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
            listOf("alias"),
        )
    }

    private fun registerTier4DatabaseCommands(registrar: Commands) {
        // /database <stats|backup>
        val databaseNode = Commands.literal("database")
            .requires { it.sender.hasPermission("obsidian.command.database") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("stats").executes { ctx ->
                    val sender = ctx.source.sender
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = { dbToolsService.collectStats() },
                        onResult = { stats ->
                            val total = stats.values.sum()
                            context.locale.sendRaw(
                                sender,
                                "automation.database-stats-header",
                                "dialect" to context.database.dialect.name,
                                "total" to total,
                            )
                            val summary = stats.entries.joinToString(", ") { "${it.key}=${it.value}" }
                            context.locale.sendRaw(sender, "automation.database-stats-body", "tables" to summary)
                        },
                    )
                    Command.SINGLE_SUCCESS
                }
            ).then(
                Commands.literal("backup").executes { ctx ->
                    val sender = ctx.source.sender
                    context.locale.send(sender, "automation.database-backup-start")
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = { dbToolsService.createBackup() },
                        onResult = { file ->
                            context.locale.send(
                                sender,
                                "automation.database-backup-done",
                                "file" to file.name,
                                "bytes" to file.length(),
                            )
                        },
                    )
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            databaseNode,
            "Inspect database stats or create a backup",
            emptyList()
        )

        // /migratedatabase <sqlite|mysql|mariadb>
        val migrateNode = Commands.literal("migratedatabase")
            .requires { it.sender.hasPermission("obsidian.command.migratedatabase") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("dialect", StringArgumentType.word()).suggests { _, b ->
                    listOf("sqlite", "mysql", "mariadb").filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val sender = ctx.source.sender
                    val raw = StringArgumentType.getString(ctx, "dialect")
                    val target = when (raw.lowercase()) {
                        "sqlite" -> DatabaseDialect.SQLITE
                        "mysql" -> DatabaseDialect.MYSQL
                        "mariadb" -> DatabaseDialect.MARIADB
                        else -> {
                            context.locale.send(sender, "automation.migrate-invalid-dialect", "dialect" to raw)
                            return@executes 0
                        }
                    }
                    if (target == context.database.dialect) {
                        context.locale.send(sender, "automation.migrate-same-dialect", "dialect" to target.name)
                        return@executes 0
                    }
                    context.locale.send(sender, "automation.migrate-start", "dialect" to target.name)
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = { dbToolsService.migrateTo(target) },
                        onResult = { report ->
                            context.locale.send(
                                sender,
                                "automation.migrate-done",
                                "tables" to report.tablesCopied,
                                "rows" to report.rowsCopied,
                                "dialect" to report.targetDialect.name,
                            )
                        },
                    )
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            migrateNode,
            "Copy Obsidian database records to another configured backend",
            emptyList()
        )

        // /importfrom <essentials|cmi>
        val importNode = Commands.literal("importfrom")
            .requires { it.sender.hasPermission("obsidian.command.importfrom") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("source", StringArgumentType.word()).suggests { _, b ->
                    listOf("essentials", "cmi").filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val sender = ctx.source.sender
                    val source = StringArgumentType.getString(ctx, "source").lowercase()
                    if (source != "essentials" && source != "essentialsx" && source != "cmi") {
                        context.locale.send(sender, "automation.import-invalid-source", "source" to source)
                        return@executes 0
                    }
                    context.locale.send(sender, "automation.import-start", "source" to source)
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = { dbToolsService.importFrom(source) },
                        onResult = { report ->
                            context.locale.send(
                                sender,
                                "automation.import-done",
                                "source" to report.source,
                                "users" to report.usersImported,
                                "homes" to report.homesImported,
                                "warps" to report.warpsImported,
                            )
                        },
                    )
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            importNode,
            "Import player data, homes, and warps from EssentialsX or CMI",
            emptyList()
        )
    }
}
