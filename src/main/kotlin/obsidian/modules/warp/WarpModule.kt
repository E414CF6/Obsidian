package obsidian.modules.warp

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import obsidian.modules.teleport.TeleportModule
import org.bukkit.entity.Player

/**
 * Warps module providing `/warp`, `/warps`, `/setwarp`, and `/removewarp` (`/delwarp`).
 */
class WarpModule(
    private val context: ObsidianContext,
    private val teleportModule: TeleportModule,
) : ObsidianModule {

    override val id: String = "warp"
    override val description: String = "Server warps with permission checks and GUI"

    val warpService = WarpService(context, teleportModule.teleportService)

    override fun onEnable() {
        warpService.loadAllBlocking()
    }

    override fun onReload() {
        warpService.reload()
    }

    override fun registerCommands(registrar: Commands) {
        registerWarp(registrar)
        registerWarps(registrar)
        registerSetWarp(registrar)
        registerRemoveWarp(registrar)
    }

    private fun registerWarp(registrar: Commands) {
        val node = Commands.literal("warp")
            .requires { it.sender.hasPermission("obsidian.command.warp") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                showWarpList(player)
            }
            .then(
                Commands.argument("name", StringArgumentType.word())
                    .suggests { ctx, builder ->
                        warpService.getAccessibleWarps(ctx.source.sender)
                            .map { it.name }
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val name = StringArgumentType.getString(ctx, "name")
                        val warp = warpService.getWarp(name)
                        if (warp == null) {
                            context.locale.send(player, "warp.not-found", "warp" to name)
                            return@executes 0
                        }
                        warpService.teleportToWarp(player, warp)
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(context.plugin.pluginMeta, node, "Teleport to a server warp", emptyList())
    }

    private fun registerWarps(registrar: Commands) {
        val node = Commands.literal("warps")
            .requires { it.sender.hasPermission("obsidian.command.warp.showlist") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                showWarpList(player)
            }
            .build()
        registrar.register(context.plugin.pluginMeta, node, "Browse available server warps", emptyList())
    }

    private fun registerSetWarp(registrar: Commands) {
        val node = Commands.literal("setwarp")
            .requires { it.sender.hasPermission("obsidian.command.setwarp") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("name", StringArgumentType.word())
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val name = StringArgumentType.getString(ctx, "name")
                        warpService.setWarp(player, name)
                        context.locale.send(player, "warp.set", "warp" to name)
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("group", StringArgumentType.word())
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val name = StringArgumentType.getString(ctx, "name")
                                val group = StringArgumentType.getString(ctx, "group")
                                warpService.setWarp(player, name, group = group)
                                context.locale.send(player, "warp.set", "warp" to name)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .build()
        registrar.register(context.plugin.pluginMeta, node, "Create or update a server warp", emptyList())
    }

    private fun registerRemoveWarp(registrar: Commands) {
        val node = Commands.literal("removewarp")
            .requires { it.sender.hasPermission("obsidian.command.removewarp") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("name", StringArgumentType.word())
                    .suggests { ctx, builder ->
                        warpService.getAccessibleWarps(ctx.source.sender)
                            .map { it.name }
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        val name = StringArgumentType.getString(ctx, "name")
                        if (warpService.deleteWarp(name)) {
                            context.locale.send(sender, "warp.removed", "warp" to name)
                        } else {
                            context.locale.send(sender, "warp.not-found", "warp" to name)
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Delete a server warp",
            if (context.config.registerStandaloneAliases) listOf("delwarp") else emptyList(),
        )
    }

    private fun showWarpList(player: Player): Int {
        val list = warpService.getAccessibleWarps(player)
        if (list.isEmpty()) {
            context.locale.send(player, "warp.none")
            return 0
        }
        if (warpService.useGui) {
            warpService.openWarpsGui(player)
        } else {
            val links = list.joinToString("<gray>, </gray>") { w ->
                "<click:run_command:'/warp ${w.name}'><hover:show_text:'<green>Click to warp</green>'><aqua>${w.name}</aqua></hover></click>"
            }
            player.sendMessage(context.locale.prefix().append(TextFormatter.format(links)))
        }
        return Command.SINGLE_SUCCESS
    }
}
