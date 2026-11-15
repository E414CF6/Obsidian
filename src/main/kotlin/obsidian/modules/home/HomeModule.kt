package obsidian.modules.home

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import obsidian.modules.teleport.TeleportModule
import org.bukkit.entity.Player
import org.bukkit.event.Listener

/**
 * Homes module providing `/sethome`, `/home`, `/homes`, and `/removehome` (`/delhome`).
 */
class HomeModule(
    private val context: ObsidianContext,
    private val teleportModule: TeleportModule,
) : ObsidianModule {

    override val id: String = "home"
    override val description: String = "Player personal homes with permission limits and GUI"

    val homeService = HomeService(context, teleportModule.teleportService)

    override fun listeners(): List<Listener> = listOf(homeService)

    override fun onReload() {
        homeService.reload()
    }

    override fun registerCommands(registrar: Commands) {
        registerSetHome(registrar)
        registerHome(registrar)
        registerHomes(registrar)
        registerRemoveHome(registrar)
    }

    private fun registerSetHome(registrar: Commands) {
        val node = Commands.literal("sethome")
            .requires { it.sender.hasPermission("obsidian.command.sethome") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                handleSetHome(player, null, override = false)
            }
            .then(
                Commands.argument("name", StringArgumentType.word())
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        handleSetHome(player, StringArgumentType.getString(ctx, "name"), override = false)
                    }
                    .then(
                        Commands.literal("-override")
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                handleSetHome(player, StringArgumentType.getString(ctx, "name"), override = true)
                            }
                    )
            )
            .build()
        registrar.register(context.plugin.pluginMeta, node, "Set a personal home location", emptyList())
    }

    private fun handleSetHome(player: Player, name: String?, override: Boolean): Int {
        when (val result = homeService.setHome(player, name, override)) {
            is HomeService.SetHomeResult.Saved -> {
                context.locale.send(player, "home.set", "home" to result.name)
            }

            is HomeService.SetHomeResult.ConfirmOverride -> {
                context.locale.send(player, "home.confirm-override", "home" to result.name)
            }

            is HomeService.SetHomeResult.LimitReached -> {
                context.locale.send(
                    player,
                    "home.limit-reached",
                    "current" to result.current,
                    "max" to result.max,
                )
            }

            HomeService.SetHomeResult.InvalidLocation -> {
                context.locale.send(player, "teleport.unsafe-location")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun registerHome(registrar: Commands) {
        val node = Commands.literal("home")
            .requires { it.sender.hasPermission("obsidian.command.home") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val list = homeService.getHomes(player.uniqueId)
                if (list.isEmpty()) {
                    context.locale.send(player, "home.none")
                    return@executes 0
                }
                if (list.size == 1) {
                    homeService.teleportToHome(player, list.first())
                } else if (homeService.useGui) {
                    homeService.openHomesGui(player)
                } else {
                    sendHomeTextList(player)
                }
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("name", StringArgumentType.word())
                    .suggests { ctx, builder ->
                        val player = ctx.source.sender as? Player
                        if (player != null) {
                            homeService.getHomes(player.uniqueId)
                                .map { it.name }
                                .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                                .forEach { builder.suggest(it) }
                        }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val name = StringArgumentType.getString(ctx, "name")
                        val record = homeService.getHome(player.uniqueId, name)
                        if (record == null) {
                            context.locale.send(player, "home.not-found", "home" to name)
                            return@executes 0
                        }
                        homeService.teleportToHome(player, record)
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(context.plugin.pluginMeta, node, "Teleport to a home", emptyList())
    }

    private fun registerHomes(registrar: Commands) {
        val node = Commands.literal("homes")
            .requires { it.sender.hasPermission("obsidian.command.home.showlist") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val list = homeService.getHomes(player.uniqueId)
                if (list.isEmpty()) {
                    context.locale.send(player, "home.none")
                    return@executes 0
                }
                if (homeService.useGui) {
                    homeService.openHomesGui(player)
                } else {
                    sendHomeTextList(player)
                }
                Command.SINGLE_SUCCESS
            }
            .build()
        registrar.register(context.plugin.pluginMeta, node, "List saved homes", emptyList())
    }

    private fun registerRemoveHome(registrar: Commands) {
        val node = Commands.literal("removehome")
            .requires { it.sender.hasPermission("obsidian.command.removehome") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("name", StringArgumentType.word())
                    .suggests { ctx, builder ->
                        val player = ctx.source.sender as? Player
                        if (player != null) {
                            homeService.getHomes(player.uniqueId)
                                .map { it.name }
                                .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                                .forEach { builder.suggest(it) }
                        }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val name = StringArgumentType.getString(ctx, "name")
                        if (homeService.deleteHome(player, name)) {
                            context.locale.send(player, "home.removed", "home" to name)
                        } else {
                            context.locale.send(player, "home.not-found", "home" to name)
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Delete a personal home",
            if (context.config.registerStandaloneAliases) listOf("delhome") else emptyList(),
        )
    }

    private fun sendHomeTextList(player: Player) {
        val homes = homeService.getHomes(player.uniqueId)
        val links = homes.joinToString("<gray>, </gray>") { h ->
            "<click:run_command:'/home ${h.name}'><hover:show_text:'<green>Click to teleport</green>'><aqua>${h.name}</aqua></hover></click>"
        }
        player.sendMessage(context.locale.prefix().append(TextFormatter.format(links)))
    }
}
