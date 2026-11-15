package obsidian.core.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Helpers for building Paper Brigadier commands with unified permission, cooldown, and target checks.
 */
object CommandHelper {

    fun registerWithOptionalAliases(
        registrar: Commands,
        context: ObsidianContext,
        nodeBuilder: (String) -> LiteralArgumentBuilder<CommandSourceStack>,
        primaryName: String,
        description: String,
        aliases: List<String> = emptyList(),
    ) {
        val node = nodeBuilder(primaryName).build()
        val standaloneAliases = if (context.config.registerStandaloneAliases) aliases else emptyList()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            description,
            standaloneAliases,
        )
    }

    fun requirePlayer(context: ObsidianContext, sender: CommandSender): Player? {
        if (sender is Player) return sender
        context.locale.send(sender, "general.player-only")
        return null
    }

    fun hasPermission(context: ObsidianContext, sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || sender.hasPermission("obsidian.admin")) return true
        context.locale.send(sender, "general.no-permission")
        return false
    }

    /**
     * Checks cooldown for [commandKey]; returns `true` if execution may proceed and records usage when [recordOnPass] is true.
     */
    fun checkCooldown(
        context: ObsidianContext,
        sender: CommandSender,
        commandKey: String,
        recordOnPass: Boolean = true,
    ): Boolean {
        val remaining = context.cooldowns.getRemainingMillis(sender, commandKey)
        if (remaining > 0L) {
            context.locale.send(sender, "general.cooldown-active", "time" to TextFormatter.formatDuration(remaining))
            return false
        }
        if (recordOnPass) {
            context.cooldowns.recordUsage(sender, commandKey)
        }
        return true
    }

    fun resolveTargetPlayer(
        context: ObsidianContext,
        sender: CommandSender,
        targetName: String?,
        othersPermission: String,
    ): Player? {
        if (targetName == null) {
            return requirePlayer(context, sender)
        }
        if (sender is Player && sender.name.equals(targetName, ignoreCase = true)) {
            return sender
        }
        if (!hasPermission(context, sender, othersPermission)) {
            return null
        }
        val target = Bukkit.getPlayerExact(targetName) ?: Bukkit.getPlayer(targetName)
        if (target == null) {
            context.locale.send(sender, "general.player-not-found", "player" to targetName)
            return null
        }
        return target
    }

    fun suggestOnlinePlayers(sender: CommandSender, prefix: String): List<String> {
        val viewer = sender as? Player
        return Bukkit.getOnlinePlayers().asSequence().filter { viewer == null || viewer.canSee(it) }.map { it.name }
            .filter { it.startsWith(prefix, ignoreCase = true) }.sorted().toList()
    }

    private class AsyncResultBox<T>(val value: T)

    /**
     * Executes [asyncWork] on IO and invokes [onResult] synchronously before return for Console/RCON callers
     * (so RCON captures command responses), or non-blockingly on global thread for online players.
     */
    fun <T> dispatchAsync(
        context: ObsidianContext,
        sender: CommandSender,
        asyncWork: suspend () -> T,
        onResult: (T) -> Unit,
    ) {
        if (sender !is Player) {
            val box = runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(5000L) { AsyncResultBox(asyncWork()) }
            }
            if (box != null) {
                onResult(box.value)
            } else {
                sender.sendMessage(TextFormatter.format("<red>Command timed out while querying database.</red>"))
            }
            return
        }
        context.scheduler.asyncScope.launch {
            val result = asyncWork()
            context.scheduler.runGlobal {
                onResult(result)
            }
        }
    }

    /**
     * Registers `/obsidian info`, `/obsidian reload`, and `/obsidian modules` administrative commands.
     */
    fun registerRootCommand(registrar: Commands, context: ObsidianContext) {
        val sendInfo: (CommandSender) -> Int = { sender ->
            val active = context.modules.activeModules().joinToString(", ") { it.id }
            sender.sendMessage(
                context.locale.prefix().append(
                    TextFormatter.format(
                        "<gray>Obsidian <white>v${context.plugin.pluginMeta.version}</white> | DB: <white>${context.database.dialect}</white> | Lang: <white>${context.config.language}</white> | Modules: <aqua>$active</aqua></gray>"
                    )
                )
            )
            Command.SINGLE_SUCCESS
        }

        val root = Commands.literal("obsidian").requires { it.sender.hasPermission("obsidian.command") }
            .executes { ctx -> sendInfo(ctx.source.sender) }.then(
                Commands.literal("info").executes { ctx -> sendInfo(ctx.source.sender) }).then(
                Commands.literal("version").executes { ctx -> sendInfo(ctx.source.sender) })
            .then(Commands.literal("reload").requires { it.sender.hasPermission("obsidian.admin") }.executes { ctx ->
                context.reloadAll()
                context.locale.send(ctx.source.sender, "general.reloaded")
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("modules").requires { it.sender.hasPermission("obsidian.admin") }.executes { ctx ->
                val sender = ctx.source.sender
                context.modules.activeModules().forEach { mod ->
                    sender.sendMessage(
                        TextFormatter.format(" <dark_gray>•</dark_gray> <aqua>${mod.id}</aqua> <gray>- ${mod.description}</gray>")
                    )
                }
                Command.SINGLE_SUCCESS
            }).build()

        registrar.register(
            context.plugin.pluginMeta,
            root,
            "Obsidian core management command",
            listOf("obs"),
        )
    }

    /**
     * Helper to build a simple literal command with an optional trailing greedy `args` string.
     */
    fun simpleArgsCommand(
        name: String,
        permission: String,
        executor: (CommandContext<CommandSourceStack>, Array<String>) -> Int,
        tabSuggester: ((CommandSender, Array<String>) -> List<String>)? = null,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal(name)
            .requires { it.sender.hasPermission(permission) || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx -> executor(ctx, emptyArray()) }
            .then(Commands.argument("args", StringArgumentType.greedyString()).suggests { ctx, builder ->
                if (tabSuggester != null) {
                    val raw = builder.remaining
                    val parts = if (raw.isEmpty()) arrayOf("") else raw.split(" ").toTypedArray()
                    val lastPrefix = parts.lastOrNull() ?: ""
                    val startOffset = builder.start + raw.length - lastPrefix.length
                    val sub = builder.createOffset(startOffset)
                    tabSuggester(ctx.source.sender, parts).forEach { sub.suggest(it) }
                    return@suggests sub.buildFuture()
                }
                builder.buildFuture()
            }.executes { ctx ->
                val raw = StringArgumentType.getString(ctx, "args").trim()
                val parts = if (raw.isEmpty()) emptyArray() else raw.split(Regex("\\s+")).toTypedArray()
                executor(ctx, parts)
            })
    }

    fun playerArg(name: String = "target") = Commands.argument(name, ArgumentTypes.player())
}
