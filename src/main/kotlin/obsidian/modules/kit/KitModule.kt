package obsidian.modules.kit

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.DurationParser
import obsidian.core.text.TextFormatter
import obsidian.modules.economy.EconomyService
import org.bukkit.entity.Player
import org.bukkit.event.Listener

/**
 * Kits management module (`/kit`, `/kits`, `/kiteditor`).
 */
class KitModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "kit"
    override val description: String = "Configurable kits with cooldowns, costs, and GUI"

    val kitService = KitService(context)

    override fun onEnable() {
        context.registerService(kitService)
    }

    override fun onReload() {
        kitService.reload()
    }

    override fun listeners(): List<Listener> = listOf(kitService)

    override fun registerCommands(registrar: Commands) {
        val kitNode = Commands.literal("kit")
            .requires { it.sender.hasPermission("obsidian.command.kit") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                kitService.openKitsGui(player, 1)
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("name", StringArgumentType.word()).suggests { ctx, builder ->
                val sender = ctx.source.sender
                kitService.getAllKits().asSequence()
                    .filter { sender !is Player || kitService.canAccess(sender, it) }.map { it.name }
                    .filter { it.startsWith(builder.remaining, ignoreCase = true) }.forEach(builder::suggest)
                builder.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val name = StringArgumentType.getString(ctx, "name")
                val kit = kitService.getKit(name)
                if (kit == null) {
                    context.locale.send(player, "kit.not-found", "kit" to name)
                    return@executes 0
                }
                val eco = context.getService<EconomyService>()
                when (val res = kitService.claimKit(player, kit)) {
                    KitService.ClaimResult.Claimed -> context.locale.send(
                        player, "kit.given", "kit" to kit.name
                    )

                    KitService.ClaimResult.NoPermission -> context.locale.send(
                        player, "kit.no-access", "kit" to kit.name
                    )

                    KitService.ClaimResult.MaxUsesReached -> context.locale.send(
                        player, "kit.max-uses-reached", "kit" to kit.name
                    )

                    is KitService.ClaimResult.OnCooldown -> context.locale.send(
                        player,
                        "kit.cooldown",
                        "kit" to kit.name,
                        "time" to TextFormatter.formatDuration(res.remainingMillis),
                    )

                    is KitService.ClaimResult.InsufficientFunds -> context.locale.send(
                        player,
                        "kit.insufficient-funds",
                        "kit" to kit.name,
                        "cost" to (eco?.formatMoney(res.cost) ?: res.cost.toString()),
                    )
                }
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("target", ArgumentTypes.player()).requires {
                it.sender.hasPermission("obsidian.command.kit.others") || it.sender.hasPermission(
                    "obsidian.admin"
                )
            }.executes { ctx ->
                val sender = ctx.source.sender
                val name = StringArgumentType.getString(ctx, "name")
                val kit = kitService.getKit(name)
                if (kit == null) {
                    context.locale.send(sender, "kit.not-found", "kit" to name)
                    return@executes 0
                }
                val resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                val target = resolver.resolve(ctx.source).firstOrNull() ?: return@executes 0
                kitService.claimKit(target, kit, bypassChecks = true)
                context.locale.send(target, "kit.given", "kit" to kit.name)
                Command.SINGLE_SUCCESS
            })).build()

        registrar.register(
            context.plugin.pluginMeta,
            kitNode,
            "Claim or browse kits",
            listOf("kits"),
        )

        val editorNode = Commands.literal("kiteditor")
            .requires { it.sender.hasPermission("obsidian.command.kiteditor") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("create").then(
                    Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val name = StringArgumentType.getString(ctx, "name")
                        kitService.createFromPlayerInventory(name, player)
                        context.locale.send(player, "kit.created", "kit" to name)
                        Command.SINGLE_SUCCESS
                    })
            ).then(
                Commands.literal("delete").then(
                    Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                        val sender = ctx.source.sender
                        val name = StringArgumentType.getString(ctx, "name")
                        if (kitService.deleteKit(name)) {
                            context.locale.send(sender, "kit.deleted", "kit" to name)
                        } else {
                            context.locale.send(sender, "kit.not-found", "kit" to name)
                        }
                        Command.SINGLE_SUCCESS
                    })
            )
            .then(
                Commands.literal("setcooldown").then(
                    Commands.argument("name", StringArgumentType.word()).then(
                        Commands.argument("duration", StringArgumentType.word()).executes { ctx ->
                            val sender = ctx.source.sender
                            val name = StringArgumentType.getString(ctx, "name")
                            val kit = kitService.getKit(name) ?: run {
                                context.locale.send(sender, "kit.not-found", "kit" to name)
                                return@executes 0
                            }
                            val rawDur = StringArgumentType.getString(ctx, "duration")
                            val seconds =
                                if (rawDur == "0") 0L else (DurationParser.parseMillis(rawDur)?.div(1000L)
                                    ?: 0L)
                            kit.cooldownSeconds = seconds
                            kitService.saveKit(kit)
                            context.locale.send(
                                sender, "kit.updated", "kit" to kit.name, "field" to "cooldown"
                            )
                            Command.SINGLE_SUCCESS
                        })
                )
            )
            .then(
                Commands.literal("setcost").then(
                    Commands.argument("name", StringArgumentType.word()).then(
                        Commands.argument("cost", DoubleArgumentType.doubleArg(0.0)).executes { ctx ->
                            val sender = ctx.source.sender
                            val name = StringArgumentType.getString(ctx, "name")
                            val kit = kitService.getKit(name) ?: run {
                                context.locale.send(sender, "kit.not-found", "kit" to name)
                                return@executes 0
                            }
                            kit.cost = DoubleArgumentType.getDouble(ctx, "cost")
                            kitService.saveKit(kit)
                            context.locale.send(sender, "kit.updated", "kit" to kit.name, "field" to "cost")
                            Command.SINGLE_SUCCESS
                        })
                )
            )
            .then(
                Commands.literal("setmaxuses").then(
                    Commands.argument("name", StringArgumentType.word()).then(
                        Commands.argument("uses", IntegerArgumentType.integer(0)).executes { ctx ->
                            val sender = ctx.source.sender
                            val name = StringArgumentType.getString(ctx, "name")
                            val kit = kitService.getKit(name) ?: run {
                                context.locale.send(sender, "kit.not-found", "kit" to name)
                                return@executes 0
                            }
                            kit.maxUses = IntegerArgumentType.getInteger(ctx, "uses")
                            kitService.saveKit(kit)
                            context.locale.send(
                                sender, "kit.updated", "kit" to kit.name, "field" to "max-uses"
                            )
                            Command.SINGLE_SUCCESS
                        })
                )
            ).then(
                Commands.literal("seticon").then(
                    Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val name = StringArgumentType.getString(ctx, "name")
                        val kit = kitService.getKit(name) ?: run {
                            context.locale.send(player, "kit.not-found", "kit" to name)
                            return@executes 0
                        }
                        val hand = player.inventory.itemInMainHand
                        if (!hand.type.isAir) {
                            kit.iconMaterial = hand.type
                            kitService.saveKit(kit)
                            context.locale.send(player, "kit.updated", "kit" to kit.name, "field" to "icon")
                        }
                        Command.SINGLE_SUCCESS
                    })
            ).build()

        registrar.register(context.plugin.pluginMeta, editorNode, "Create or modify server kits", emptyList())

        val cdResetNode = Commands.literal("kitcdreset")
            .requires { it.sender.hasPermission("obsidian.command.kitcdreset") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("kit", StringArgumentType.word()).suggests { _, b ->
                        kitService.getAllKits().map { it.name }.filter { it.startsWith(b.remaining, true) }
                            .forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val kitName = StringArgumentType.getString(ctx, "kit")
                        val kit = kitService.getKit(kitName) ?: run {
                            context.locale.send(ctx.source.sender, "kit.not-found", "kit" to kitName)
                            return@executes 0
                        }
                        kitService.resetCooldown(target.uniqueId, kit.name)
                        context.locale.send(
                            ctx.source.sender,
                            "kit.cd-reset",
                            "target" to target.name,
                            "kit" to kit.name
                        )
                        Command.SINGLE_SUCCESS
                    }
                )).build()
        registrar.register(context.plugin.pluginMeta, cdResetNode, "Reset a player's kit cooldown", emptyList())

        val usageResetNode = Commands.literal("kitusagereset")
            .requires { it.sender.hasPermission("obsidian.command.kitusagereset") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("kit", StringArgumentType.word()).suggests { _, b ->
                        kitService.getAllKits().map { it.name }.filter { it.startsWith(b.remaining, true) }
                            .forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val kitName = StringArgumentType.getString(ctx, "kit")
                        val kit = kitService.getKit(kitName) ?: run {
                            context.locale.send(ctx.source.sender, "kit.not-found", "kit" to kitName)
                            return@executes 0
                        }
                        kitService.resetUsage(target.uniqueId, kit.name)
                        context.locale.send(
                            ctx.source.sender,
                            "kit.usage-reset",
                            "target" to target.name,
                            "kit" to kit.name
                        )
                        Command.SINGLE_SUCCESS
                    }
                )).build()
        registrar.register(context.plugin.pluginMeta, usageResetNode, "Reset a player's kit usage count", emptyList())
    }
}
