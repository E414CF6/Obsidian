package obsidian.modules.protect

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.ServicePriority
import java.util.*

/**
 * CoreProtect-inspired audit logging, inspection, lookup, rollback/restore, and purge module (`protect`).
 */
class ProtectModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "protect"
    override val description: String =
        "Block, container, entity & chat logging, inspection, lookup, rollback/restore, and purge"

    val repository = ProtectRepository(context.database)
    val queue = ProtectQueueService(context, repository)
    val service = ProtectService(context, repository, queue)
    val engine = ProtectRollbackEngine(context, service)
    private val listener = ProtectLoggingListener(context, service, engine)

    private val subcommands = listOf(
        "help", "inspect", "i", "lookup", "l", "page", "gui", "near",
        "rollback", "rb", "restore", "rs", "apply", "cancel", "undo",
        "purge", "status", "consumer", "tp", "teleport", "reload",
    )

    override fun onEnable() {
        context.registerService(service)
        Bukkit.getServicesManager().register(
            ProtectService::class.java,
            service,
            context.plugin,
            ServicePriority.Normal,
        )
        queue.start(service.config.consumerFlushMillis)
    }

    override fun onReload() {
        service.reloadConfig()
    }

    override fun onDisable() {
        service.shutdown()
    }

    override fun listeners(): List<Listener> = listOf(listener)

    override fun registerCommands(registrar: Commands) {
        val builder: (String) -> LiteralArgumentBuilder<CommandSourceStack> = { name ->
            Commands.literal(name).requires {
                it.sender.hasPermission("obsidian.protect.co") || it.sender.hasPermission("obsidian.admin")
            }.executes { ctx ->
                sendHelp(ctx.source.sender, emptyList())
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("args", StringArgumentType.greedyString()).suggests { ctx, sb ->
                val input = sb.remainingLowerCase
                val parts = input.split(" ")
                if (parts.size <= 1) {
                    subcommands.filter { it.startsWith(parts.firstOrNull().orEmpty()) }.forEach(sb::suggest)
                } else {
                    suggestFilterTokens(ctx.source.sender, parts.last()).forEach { suggestion ->
                        val prefix = input.substringBeforeLast(' ', "")
                        sb.suggest(if (prefix.isEmpty()) suggestion else "$prefix $suggestion")
                    }
                }
                sb.buildFuture()
            }.executes { ctx ->
                val raw = StringArgumentType.getString(ctx, "args")
                dispatchCommand(
                    ctx.source.sender, raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
                Command.SINGLE_SUCCESS
            })
        }

        CommandHelper.registerWithOptionalAliases(
            registrar = registrar,
            context = context,
            nodeBuilder = builder,
            primaryName = "co",
            description = "Obsidian Protect lookup, inspect, rollback, restore, and purge",
            aliases = listOf("core", "coreprotect"),
        )
    }

    fun dispatchCommand(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sendHelp(sender, emptyList())
            return
        }
        val sub = args[0].lowercase(Locale.ROOT)
        val tail = args.drop(1)

        when (sub) {
            "help", "?" -> sendHelp(sender, tail)

            "inspect", "i", "inspector" -> {
                if (!checkPerm(sender, "obsidian.protect.inspect")) return
                val player = CommandHelper.requirePlayer(context, sender) ?: return
                val modeArg = tail.firstOrNull()?.lowercase(Locale.ROOT)
                val targetState = when (modeArg) {
                    "on", "enable", "true" -> true
                    "off", "disable", "false" -> false
                    else -> !service.isInspecting(player)
                }
                service.setInspecting(player, targetState)
                context.locale.send(
                    player,
                    if (targetState) "protect.inspector-on" else "protect.inspector-off",
                )
            }

            "lookup", "l" -> {
                if (!checkPerm(sender, "obsidian.protect.lookup")) return
                if (tail.size == 1 && tail[0].toIntOrNull() != null) {
                    engine.showPage(sender, tail[0].toInt())
                    return
                }
                val parsed = parseForSender(sender, tail, defaultRadius = service.config.defaultRadius) ?: return
                engine.performLookup(sender, parsed)
            }

            "page" -> {
                if (!checkPerm(sender, "obsidian.protect.lookup")) return
                val page = tail.firstOrNull()?.toIntOrNull()
                if (page == null || page < 1) {
                    context.locale.send(sender, "protect.usage-page")
                    return
                }
                engine.showPage(sender, page)
            }

            "gui" -> {
                if (!checkPerm(sender, "obsidian.protect.lookup")) return
                val player = CommandHelper.requirePlayer(context, sender) ?: return
                val page = tail.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                engine.openSessionGui(player, page)
            }

            "near" -> {
                if (!checkPerm(sender, "obsidian.protect.lookup")) return
                val radius = tail.firstOrNull()?.toIntOrNull()?.coerceIn(1, service.config.maxRadius) ?: 5
                val parsed = parseForSender(sender, listOf("r:$radius"), defaultRadius = radius) ?: return
                engine.performLookup(sender, parsed)
            }

            "rollback", "rb", "ro" -> {
                if (!checkPerm(sender, "obsidian.protect.rollback")) return
                if (tail.isEmpty()) {
                    context.locale.send(sender, "protect.usage-rollback")
                    return
                }
                val parsed = parseForSender(sender, tail, defaultRadius = service.config.defaultRadius) ?: return
                if (parsed.sinceMillis == null && parsed.untilMillis == null && parsed.users.isEmpty() && !parsed.useSelection) {
                    context.locale.send(sender, "protect.rollback-missing-filter")
                    return
                }
                engine.performRollbackOrRestore(sender, parsed, restoreMode = false)
            }

            "restore", "rs", "re" -> {
                if (!checkPerm(sender, "obsidian.protect.restore")) return
                if (tail.isEmpty()) {
                    context.locale.send(sender, "protect.usage-restore")
                    return
                }
                val parsed = parseForSender(sender, tail, defaultRadius = service.config.defaultRadius) ?: return
                if (parsed.sinceMillis == null && parsed.untilMillis == null && parsed.users.isEmpty() && !parsed.useSelection) {
                    context.locale.send(sender, "protect.rollback-missing-filter")
                    return
                }
                engine.performRollbackOrRestore(sender, parsed, restoreMode = true)
            }

            "apply" -> {
                if (!checkPerm(sender, "obsidian.protect.rollback")) return
                val player = CommandHelper.requirePlayer(context, sender) ?: return
                engine.applyPreviewSession(player)
            }

            "cancel" -> {
                if (!checkPerm(sender, "obsidian.protect.rollback")) return
                val player = CommandHelper.requirePlayer(context, sender) ?: return
                engine.cancelPreview(player)
            }

            "undo" -> {
                if (!checkPerm(sender, "obsidian.protect.rollback")) return
                engine.performUndo(sender)
            }

            "purge" -> {
                if (!checkPerm(sender, "obsidian.protect.purge")) return
                val parsed = parseForSender(sender, tail, defaultRadius = null) ?: return
                val cutoff = parsed.sinceMillis
                if (cutoff == null) {
                    context.locale.send(sender, "protect.usage-purge")
                    return
                }
                val vacuum = tail.any { it.equals("#optimize", true) || it.equals("#vacuum", true) }
                CommandHelper.dispatchAsync(context, sender, {
                    queue.flushSync()
                    repository.purgeBefore(
                        cutoffMillis = cutoff,
                        world = parsed.world,
                        centerX = parsed.centerX,
                        centerY = parsed.centerY,
                        centerZ = parsed.centerZ,
                        radius = parsed.radius,
                        vacuumSqlite = vacuum,
                    )
                }) { res ->
                    context.locale.send(
                        sender,
                        "protect.purge-done",
                        "total" to res.total,
                        "blocks" to res.deletedBlocks,
                        "containers" to res.deletedContainers,
                        "entities" to res.deletedEntities,
                        "messages" to res.deletedMessages,
                    )
                }
            }

            "status", "stats", "version" -> {
                if (!checkPerm(sender, "obsidian.protect.status")) return
                CommandHelper.dispatchAsync(context, sender, {
                    repository.tableCounts()
                }) { counts ->
                    val state = if (queue.paused.get()) "PAUSED" else "ACTIVE"
                    context.locale.send(
                        sender,
                        "protect.status",
                        "state" to state,
                        "pending" to queue.pendingCount(),
                        "flushed" to queue.totalFlushed(),
                        "blocks" to counts.blocks,
                        "containers" to counts.containers,
                        "entities" to counts.entities,
                        "messages" to counts.messages,
                    )
                }
            }

            "consumer" -> {
                if (!checkPerm(sender, "obsidian.protect.consumer")) return
                when (tail.firstOrNull()?.lowercase(Locale.ROOT) ?: "status") {
                    "pause" -> {
                        queue.paused.set(true)
                        context.locale.send(sender, "protect.consumer-state", "state" to "PAUSED")
                    }

                    "resume", "unpause" -> {
                        queue.paused.set(false)
                        context.locale.send(sender, "protect.consumer-state", "state" to "ACTIVE")
                    }

                    "flush" -> {
                        CommandHelper.dispatchAsync(context, sender, {
                            queue.flushSync()
                        }) { flushed ->
                            context.locale.send(sender, "protect.consumer-flushed", "count" to flushed)
                        }
                    }

                    else -> {
                        val state = context.locale.raw(
                            if (queue.paused.get()) "protect.consumer-paused" else "protect.consumer-active"
                        )
                        context.locale.send(
                            sender,
                            "protect.consumer-status",
                            "state" to state,
                            "pending" to queue.pendingBreakdown(),
                            "flushed" to queue.totalFlushed(),
                        )
                    }
                }
            }

            "tp", "teleport" -> {
                if (!checkPerm(sender, "obsidian.protect.teleport")) return
                val player = CommandHelper.requirePlayer(context, sender) ?: return
                if (tail.size < 4) {
                    context.locale.send(sender, "protect.usage-tp")
                    return
                }
                val world = Bukkit.getWorld(tail[0])
                val x = tail[1].toDoubleOrNull()
                val y = tail[2].toDoubleOrNull()
                val z = tail[3].toDoubleOrNull()
                if (world == null || x == null || y == null || z == null) {
                    context.locale.send(sender, "protect.invalid-tp-target")
                    return
                }
                player.teleportAsync(Location(world, x + 0.5, y, z + 0.5))
                context.locale.send(
                    player,
                    "protect.teleported",
                    "world" to world.name,
                    "x" to x.toInt(),
                    "y" to y.toInt(),
                    "z" to z.toInt()
                )
            }

            "reload" -> {
                if (!checkPerm(sender, "obsidian.protect.reload")) return
                context.reloadAll()
                context.locale.send(sender, "general.reloaded")
            }

            else -> {
                context.locale.send(sender, "protect.unknown-subcommand", "sub" to sub)
            }
        }
    }

    private fun parseForSender(sender: CommandSender, args: List<String>, defaultRadius: Int?): ProtectQuery? {
        val player = sender as? Player
        val loc = player?.location
        val parsed = ProtectCommandParser.parseFilterArgs(
            tokens = args,
            defaultWorld = loc?.world?.name,
            centerX = loc?.blockX,
            centerY = loc?.blockY,
            centerZ = loc?.blockZ,
            defaultRadius = defaultRadius,
            maxRadius = service.config.maxRadius,
        ).getOrElse { err ->
            context.locale.send(
                sender,
                "protect.error-wrapper",
                "message" to TextFormatter.escape(err.message ?: context.locale.raw("protect.invalid-arguments")),
            )
            return null
        }

        return engine.attachSelectionIfRequested(sender, parsed).getOrElse { err ->
            context.locale.send(
                sender,
                "protect.error-wrapper",
                "message" to TextFormatter.escape(err.message ?: context.locale.raw("protect.selection-error")),
            )
            return null
        }
    }

    private fun checkPerm(sender: CommandSender, permission: String): Boolean =
        CommandHelper.hasPermission(context, sender, permission)

    private fun suggestFilterTokens(sender: CommandSender, currentToken: String): List<String> {
        val lower = currentToken.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("u:") -> {
                val typed = currentToken.substring(2)
                CommandHelper.suggestOnlinePlayers(sender, typed).map { "u:$it" }
            }

            lower.startsWith("t:") -> listOf(
                "t:5m",
                "t:30m",
                "t:1h",
                "t:6h",
                "t:1d",
                "t:7d",
                "t:30d"
            ).filter { it.startsWith(lower) }

            lower.startsWith("r:") -> listOf(
                "r:5",
                "r:10",
                "r:25",
                "r:50",
                "r:#global",
                "r:#selection"
            ).filter { it.startsWith(lower) }

            lower.startsWith("a:") -> listOf(
                "a:block", "a:+block", "a:-block", "a:container", "a:+container", "a:-container",
                "a:click", "a:kill", "a:spawn", "a:chat", "a:command", "a:session", "a:sign",
            ).filter { it.startsWith(lower) }

            else -> listOf(
                "u:",
                "t:",
                "r:",
                "a:",
                "i:",
                "e:",
                "f:",
                "#preview",
                "#count",
                "#global",
                "#selection"
            ).filter { it.startsWith(lower) }
        }
    }

    private fun sendHelp(sender: CommandSender, args: List<String>) {
        val topic = args.firstOrNull()?.lowercase(Locale.ROOT)
        context.locale.sendRaw(sender, "protect.help-header")
        val lines = if (topic == "params") {
            context.locale.rawList("protect.help-params")
        } else {
            context.locale.rawList("protect.help-commands")
        }
        lines.forEach { sender.sendMessage(TextFormatter.format(it)) }
    }
}
