package obsidian.modules.interaction

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
import obsidian.core.text.TextFormatter
import org.bukkit.event.Listener

/**
 * Phase 5 Interaction & Player Action module:
 * `/sit`, `/ride`, `/shakeitoff`, `/glow`, `/scale`, `/ic` (InteractiveCommands), and `/attachcommand`.
 */
class InteractionModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "interaction"
    override val description: String =
        "Sitting, riding, player glow/scale, interactive blocks/entities, and item-attached commands"

    val seatService = SeatAndPoseService(context)
    val icService = InteractiveCommandService(context)
    val attachedService = AttachedCommandService(context)

    override fun onEnable() {
        context.registerService(seatService)
        context.registerService(icService)
        context.registerService(attachedService)
    }

    override fun onDisable() {
        seatService.shutdownAll()
    }

    override fun onReload() {
        icService.reload()
    }

    override fun listeners(): List<Listener> = listOf(
        seatService,
        icService,
        attachedService,
    )

    override fun registerCommands(registrar: Commands) {
        registerSitAndRide(registrar)
        registerAppearanceCommands(registrar)
        registerInteractiveCommands(registrar)
        registerAttachedCommands(registrar)
    }

    private fun registerSitAndRide(registrar: Commands) {
        val sitNode = Commands.literal("sit")
            .requires { it.sender.hasPermission("obsidian.command.sit") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val sitting = seatService.toggleSit(player)
                context.locale.send(player, if (sitting) "interaction.sit-on" else "interaction.sit-off")
                Command.SINGLE_SUCCESS
            }
            .build()
        registrar.register(context.plugin.pluginMeta, sitNode, "Sit down on your current location", emptyList())

        val rideNode = Commands.literal("ride")
            .requires { it.sender.hasPermission("obsidian.command.ride") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val hit = player.rayTraceEntities(5)?.hitEntity
                if (hit == null || !seatService.rideTarget(player, hit)) {
                    context.locale.send(player, "interaction.ride-no-target")
                    return@executes 0
                }
                context.locale.send(player, "interaction.ride-started", "target" to hit.name)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("target", ArgumentTypes.player())
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        if (!seatService.rideTarget(player, target)) {
                            context.locale.send(player, "interaction.ride-no-target")
                            return@executes 0
                        }
                        context.locale.send(player, "interaction.ride-started", "target" to target.name)
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(context.plugin.pluginMeta, rideNode, "Ride a targeted entity or player", emptyList())

        val shakeNode = Commands.literal("shakeitoff")
            .requires { it.sender.hasPermission("obsidian.command.shakeitoff") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val ejected = seatService.shakeOffPassengers(player)
                context.locale.send(player, "interaction.passengers-ejected", "count" to ejected)
                Command.SINGLE_SUCCESS
            }
            .build()
        registrar.register(context.plugin.pluginMeta, shakeNode, "Eject riders from your head", emptyList())
    }

    private fun registerAppearanceCommands(registrar: Commands) {
        val glowNode = Commands.literal("glow")
            .requires { it.sender.hasPermission("obsidian.command.glow") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val next = !player.isGlowing
                player.isGlowing = next
                context.locale.send(player, "interaction.glow-toggled", "state" to if (next) "ON" else "OFF")
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.glow.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val next = !target.isGlowing
                        target.isGlowing = next
                        context.locale.send(
                            ctx.source.sender,
                            "interaction.glow-toggled",
                            "state" to "${target.name}: ${if (next) "ON" else "OFF"}"
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(context.plugin.pluginMeta, glowNode, "Toggle player glow effect", emptyList())

        val scaleNode = Commands.literal("scale")
            .requires { it.sender.hasPermission("obsidian.command.scale") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("reset")
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        seatService.setScale(player, 1.0)
                        context.locale.send(player, "interaction.scale-set", "value" to "1.0")
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.argument("value", DoubleArgumentType.doubleArg(0.25, 4.0))
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val v = DoubleArgumentType.getDouble(ctx, "value")
                        val applied = seatService.setScale(player, v) ?: return@executes 0
                        context.locale.send(player, "interaction.scale-set", "value" to "%.2f".format(applied))
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("target", ArgumentTypes.player())
                            .requires {
                                it.sender.hasPermission("obsidian.command.scale.others") || it.sender.hasPermission(
                                    "obsidian.admin"
                                )
                            }
                            .executes { ctx ->
                                val v = DoubleArgumentType.getDouble(ctx, "value")
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                val applied = seatService.setScale(target, v) ?: return@executes 0
                                context.locale.send(
                                    ctx.source.sender,
                                    "interaction.scale-set",
                                    "value" to "${target.name} -> %.2f".format(applied)
                                )
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .build()
        registrar.register(context.plugin.pluginMeta, scaleNode, "Adjust player entity scale", emptyList())
    }

    private fun registerInteractiveCommands(registrar: Commands) {
        val node = Commands.literal("ic")
            .requires { it.sender.hasPermission("obsidian.command.ic") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("list")
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        val all = icService.getAll()
                        if (all.isEmpty()) {
                            context.locale.send(sender, "interaction.ic-list-empty")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        context.locale.send(sender, "interaction.ic-list-header", "count" to all.size)
                        all.forEach { b ->
                            sender.sendMessage(TextFormatter.format(" <dark_gray>-</dark_gray> <aqua>${b.id}</aqua> <gray>(${b.type}, ${b.commands.size} cmds)</gray>"))
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("addblock")
                    .then(
                        Commands.argument("command", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val block = player.getTargetBlockExact(6)
                                if (block == null) {
                                    context.locale.send(player, "interaction.ic-no-target")
                                    return@executes 0
                                }
                                val cmd = StringArgumentType.getString(ctx, "command")
                                val binding = icService.addBlockCommand(block, cmd)
                                context.locale.send(player, "interaction.ic-added", "id" to binding.id)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("addentity")
                    .then(
                        Commands.argument("command", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val hit = player.rayTraceEntities(6)?.hitEntity
                                if (hit == null) {
                                    context.locale.send(player, "interaction.ic-no-target")
                                    return@executes 0
                                }
                                val cmd = StringArgumentType.getString(ctx, "command")
                                val binding = icService.addEntityCommand(hit, cmd)
                                context.locale.send(player, "interaction.ic-added", "id" to binding.id)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("remove")
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val hitEntity = player.rayTraceEntities(6)?.hitEntity
                        val removed = if (hitEntity != null && icService.getByEntity(hitEntity) != null) {
                            icService.removeByTargetKey(hitEntity.uniqueId.toString())
                        } else {
                            val block = player.getTargetBlockExact(6)
                            if (block != null) icService.removeByTargetKey(InteractiveCommandService.blockKey(block.location)) else false
                        }
                        if (removed) {
                            context.locale.send(player, "interaction.ic-removed", "id" to "target")
                        } else {
                            context.locale.send(player, "interaction.ic-no-target")
                        }
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("id", StringArgumentType.word())
                            .executes { ctx ->
                                val id = StringArgumentType.getString(ctx, "id")
                                if (icService.removeById(id)) {
                                    context.locale.send(ctx.source.sender, "interaction.ic-removed", "id" to id)
                                } else {
                                    context.locale.send(ctx.source.sender, "interaction.ic-no-target")
                                }
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .build()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Bind interactive commands to blocks or entities",
            emptyList()
        )
    }

    private fun registerAttachedCommands(registrar: Commands) {
        val node = Commands.literal("attachcommand")
            .requires { it.sender.hasPermission("obsidian.command.attachcommand") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("add")
                    .then(
                        Commands.argument("command", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val item = player.inventory.itemInMainHand
                                if (item.type.isAir) {
                                    context.locale.send(player, "interaction.item-required")
                                    return@executes 0
                                }
                                val cmds =
                                    attachedService.addCommand(item, StringArgumentType.getString(ctx, "command"))
                                context.locale.send(player, "interaction.attached-added", "count" to cmds.size)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("list")
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val item = player.inventory.itemInMainHand
                        val cmds = attachedService.getCommands(item)
                        if (cmds.isEmpty()) {
                            context.locale.send(player, "interaction.attached-empty")
                        } else {
                            cmds.forEachIndexed { idx, cmd ->
                                player.sendMessage(TextFormatter.format(" <gray>${idx + 1}.</gray> <aqua>$cmd</aqua>"))
                            }
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("clear")
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val item = player.inventory.itemInMainHand
                        if (item.type.isAir) {
                            context.locale.send(player, "interaction.item-required")
                            return@executes 0
                        }
                        attachedService.clearCommands(item)
                        context.locale.send(player, "interaction.attached-cleared")
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("cooldown")
                    .then(
                        Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val item = player.inventory.itemInMainHand
                                if (item.type.isAir) {
                                    context.locale.send(player, "interaction.item-required")
                                    return@executes 0
                                }
                                val sec = IntegerArgumentType.getInteger(ctx, "seconds").toLong()
                                attachedService.setCooldown(item, sec)
                                context.locale.send(player, "interaction.attached-cooldown", "seconds" to sec)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("uses")
                    .then(
                        Commands.argument("count", IntegerArgumentType.integer(0, 10000))
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val item = player.inventory.itemInMainHand
                                if (item.type.isAir) {
                                    context.locale.send(player, "interaction.item-required")
                                    return@executes 0
                                }
                                val uses = IntegerArgumentType.getInteger(ctx, "count")
                                attachedService.setUses(item, uses)
                                context.locale.send(player, "interaction.attached-uses", "uses" to uses)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .build()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Attach commands to the held item via PDC",
            if (context.config.registerStandaloneAliases) listOf("ac", "attachcmd") else emptyList(),
        )
    }
}
