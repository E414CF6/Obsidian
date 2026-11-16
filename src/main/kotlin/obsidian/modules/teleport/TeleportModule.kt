package obsidian.modules.teleport

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import kotlin.math.roundToInt

/**
 * Teleportation module providing `/tp`, `/tphere`, `/tppos`, `/tpa*`, `/back`, `/dback`, `/top`, `/jump`,
 * `/spawn`, `/setspawn`, and `/rt`.
 */
class TeleportModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "teleport"
    override val description: String = "Teleportation, TPA requests, back/death-back, spawn, and random teleport"

    val teleportService = TeleportService(context)
    val tpaService = TpaService(context, teleportService)
    val spawnService = SpawnService(context, teleportService)
    val randomTeleportService = RandomTeleportService(context, teleportService)

    override fun listeners(): List<Listener> = listOf(teleportService, tpaService, spawnService)

    override fun onReload() {
        teleportService.reload()
        tpaService.reload()
        spawnService.reload()
        randomTeleportService.reload()
    }

    override fun registerCommands(registrar: Commands) {
        registerTp(registrar)
        registerTpHere(registrar)
        registerTpPos(registrar)
        registerTpaCommands(registrar)
        registerBackCommands(registrar)
        registerTopAndJump(registrar)
        registerSpawnCommands(registrar)
        registerRandomTeleport(registrar)
    }

    private fun registerTp(registrar: Commands) {
        val node = Commands.literal("tp")
            .requires { it.sender.hasPermission("obsidian.command.tp") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val target =
                    ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                        .firstOrNull() ?: return@executes 0
                teleportService.teleport(sender, target.location, TeleportCause.COMMAND).thenAccept { ok ->
                    if (ok) {
                        context.locale.send(
                            sender,
                            "teleport.teleported-to-player",
                            "source" to sender.name,
                            "target" to target.name,
                        )
                    } else {
                        context.locale.send(sender, "teleport.unsafe-location")
                    }
                }
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("destination", ArgumentTypes.player()).requires {
                it.sender.hasPermission("obsidian.command.tp.others") || it.sender.hasPermission(
                    "obsidian.admin"
                )
            }.executes { ctx ->
                val sender = ctx.source.sender
                val source = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                val dest = ctx.getArgument("destination", PlayerSelectorArgumentResolver::class.java)
                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                teleportService.teleport(source, dest.location, TeleportCause.COMMAND).thenAccept { ok ->
                    if (ok) {
                        context.locale.send(
                            sender,
                            "teleport.teleported-to-player",
                            "source" to source.name,
                            "target" to dest.name,
                        )
                    } else {
                        context.locale.send(sender, "teleport.unsafe-location")
                    }
                }
                Command.SINGLE_SUCCESS
            })).build()
        registrar.register(context.plugin.pluginMeta, node, "Teleport to a player", emptyList())
    }

    private fun registerTpHere(registrar: Commands) {
        val node = Commands.literal("tphere")
            .requires { it.sender.hasPermission("obsidian.command.tphere") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                    val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val target =
                        ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                            .firstOrNull() ?: return@executes 0
                    teleportService.teleport(target, sender.location, TeleportCause.COMMAND).thenAccept { ok ->
                        if (ok) {
                            context.locale.send(
                                sender,
                                "teleport.teleported-to-player",
                                "source" to target.name,
                                "target" to sender.name,
                            )
                        }
                    }
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, node, "Teleport a player to you", emptyList())
    }

    private fun registerTpPos(registrar: Commands) {
        val node = Commands.literal("tppos")
            .requires { it.sender.hasPermission("obsidian.command.tppos") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("x", DoubleArgumentType.doubleArg()).then(
                    Commands.argument("y", DoubleArgumentType.doubleArg())
                        .then(
                            Commands.argument("z", DoubleArgumentType.doubleArg()).executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                executeTpPos(player, player.world, ctx)
                            }.then(
                                Commands.argument("world", ArgumentTypes.world()).executes { ctx ->
                                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                                        ?: return@executes 0
                                    val world = ctx.getArgument("world", World::class.java)
                                    executeTpPos(player, world, ctx)
                                })
                        )
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, node, "Teleport to coordinates", emptyList())
    }

    private fun executeTpPos(
        player: Player,
        world: World,
        ctx: com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack>,
    ): Int {
        val x = DoubleArgumentType.getDouble(ctx, "x")
        val y = DoubleArgumentType.getDouble(ctx, "y")
        val z = DoubleArgumentType.getDouble(ctx, "z")
        val target = Location(world, x, y, z, player.location.yaw, player.location.pitch)
        teleportService.teleport(player, target, TeleportCause.COMMAND).thenAccept { ok ->
            if (ok) {
                context.locale.send(
                    player,
                    "teleport.teleported-to-coords",
                    "target" to player.name,
                    "world" to world.name,
                    "x" to x.roundToInt(),
                    "y" to y.roundToInt(),
                    "z" to z.roundToInt(),
                )
            } else {
                context.locale.send(player, "teleport.unsafe-location")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun registerTpaCommands(registrar: Commands) {
        val tpaNode = Commands.literal("tpa")
            .requires { it.sender.hasPermission("obsidian.command.tpa") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                    val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val target =
                        ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                            .firstOrNull() ?: return@executes 0
                    tpaService.sendRequest(sender, target, TpaType.TO_TARGET)
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, tpaNode, "Request to teleport to a player", emptyList())

        val tpaHereNode = Commands.literal("tpahere")
            .requires { it.sender.hasPermission("obsidian.command.tpahere") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                    val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val target =
                        ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                            .firstOrNull() ?: return@executes 0
                    tpaService.sendRequest(sender, target, TpaType.HERE)
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, tpaHereNode, "Request a player to teleport to you", emptyList())

        registerTpaResponseCommand(
            registrar, "tpaccept", "Accept a teleport request", "obsidian.command.tpaccept"
        ) { player, senderName ->
            tpaService.accept(player, senderName)
        }
        registerTpaResponseCommand(
            registrar, "tpdeny", "Deny a teleport request", "obsidian.command.tpdeny"
        ) { player, senderName ->
            tpaService.deny(player, senderName)
        }
    }

    private fun registerTpaResponseCommand(
        registrar: Commands,
        name: String,
        description: String,
        permission: String,
        handler: (Player, String?) -> Unit,
    ) {
        val node = Commands.literal(name)
            .requires { it.sender.hasPermission(permission) || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                handler(player, null)
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("sender", StringArgumentType.word()).suggests { ctx, builder ->
                val player = ctx.source.sender as? Player
                if (player != null) {
                    tpaService.suggestIncomingSenders(player, builder.remaining).forEach { builder.suggest(it) }
                }
                builder.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                handler(player, StringArgumentType.getString(ctx, "sender"))
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, node, description, emptyList())
    }

    private fun registerBackCommands(registrar: Commands) {
        val backNode = Commands.literal("back")
            .requires { it.sender.hasPermission("obsidian.command.back") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val dest = teleportService.getBackLocation(player) ?: run {
                    context.locale.send(player, "teleport.no-back-location")
                    return@executes 0
                }
                context.warmups.runWithWarmup(player, "back") {
                    teleportService.teleport(player, dest, TeleportCause.COMMAND).thenAccept { ok ->
                        if (ok) context.locale.send(player, "teleport.back-teleported")
                        else context.locale.send(player, "teleport.unsafe-location")
                    }
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, backNode, "Teleport to your previous location", emptyList())

        val dbackNode = Commands.literal("dback")
            .requires { it.sender.hasPermission("obsidian.command.dback") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val dest = teleportService.getDeathLocation(player) ?: run {
                    context.locale.send(player, "teleport.no-death-location")
                    return@executes 0
                }
                context.warmups.runWithWarmup(player, "dback") {
                    teleportService.teleport(player, dest, TeleportCause.COMMAND).thenAccept { ok ->
                        if (ok) context.locale.send(player, "teleport.dback-teleported")
                        else context.locale.send(player, "teleport.unsafe-location")
                    }
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, dbackNode, "Teleport to your last death location", emptyList())
    }

    private fun registerTopAndJump(registrar: Commands) {
        val topNode = Commands.literal("top")
            .requires { it.sender.hasPermission("obsidian.command.top") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                SafeLocationFinder.findHighestSafeLocation(player.location).thenAccept { topLoc ->
                    if (topLoc == null) {
                        context.locale.send(player, "teleport.unsafe-location")
                    } else {
                        teleportService.teleport(player, topLoc, TeleportCause.COMMAND, ensureSafe = false)
                            .thenAccept { ok ->
                                if (ok) context.locale.send(player, "teleport.top-teleported")
                            }
                    }
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta, topNode, "Teleport to the highest block at your position", emptyList()
        )

        val jumpNode = Commands.literal("jump")
            .requires { it.sender.hasPermission("obsidian.command.jump") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val maxDist = teleportService.jumpDefaultDistance
                val targetBlock = player.getTargetBlockExact(maxDist, FluidCollisionMode.NEVER)
                if (targetBlock == null || targetBlock.type.isAir) {
                    context.locale.send(player, "teleport.jump-too-far", "max" to maxDist)
                    return@executes 0
                }
                val dest = targetBlock.location.add(0.5, 1.0, 0.5).apply {
                    yaw = player.location.yaw
                    pitch = player.location.pitch
                }
                teleportService.teleport(player, dest, TeleportCause.COMMAND, ensureSafe = true).thenAccept { ok ->
                    if (!ok) context.locale.send(player, "teleport.unsafe-location")
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, jumpNode, "Jump to the block you are looking at", emptyList())
    }

    private fun registerSpawnCommands(registrar: Commands) {
        val spawnNode = Commands.literal("spawn")
            .requires { it.sender.hasPermission("obsidian.command.spawn") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val spawn = spawnService.getSpawnFor(player) ?: run {
                    context.locale.send(player, "teleport.spawn-not-set")
                    return@executes 0
                }
                context.warmups.runWithWarmup(player, "spawn") {
                    teleportService.teleport(player, spawn, TeleportCause.COMMAND, ensureSafe = false)
                        .thenAccept { ok ->
                            if (ok) context.locale.send(player, "teleport.spawn-teleported")
                        }
                }
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.spawn.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val spawn = spawnService.getSpawnFor(target) ?: run {
                            context.locale.send(sender, "teleport.spawn-not-set")
                            return@executes 0
                        }
                        teleportService.teleport(target, spawn, TeleportCause.COMMAND, ensureSafe = false)
                            .thenAccept { ok ->
                                if (ok) {
                                    context.locale.send(target, "teleport.spawn-teleported")
                                    context.locale.send(
                                        sender,
                                        "teleport.teleported-to-player",
                                        "source" to target.name,
                                        "target" to "spawn"
                                    )
                                }
                            }
                        Command.SINGLE_SUCCESS
                    }).build()
        registrar.register(context.plugin.pluginMeta, spawnNode, "Teleport to spawn", emptyList())

        val setSpawnNode = Commands.literal("setspawn")
            .requires { it.sender.hasPermission("obsidian.command.setspawn") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                spawnService.setSpawn(null, player.location)
                context.locale.send(player, "teleport.spawn-set", "group" to "default")
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("group", StringArgumentType.word()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val group = StringArgumentType.getString(ctx, "group")
                    spawnService.setSpawn(group, player.location)
                    context.locale.send(player, "teleport.spawn-set", "group" to group)
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, setSpawnNode, "Set server or group spawn point", emptyList())
    }

    private fun registerRandomTeleport(registrar: Commands) {
        val rtNode = Commands.literal("rt")
            .requires { it.sender.hasPermission("obsidian.command.rt") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeRt(player, player.world)
            }.then(
                Commands.argument("world", ArgumentTypes.world())
                    .requires { it.sender.hasPermission("obsidian.command.rt.world") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val world = ctx.getArgument("world", World::class.java)
                        executeRt(player, world)
                    }).build()

        registrar.register(
            context.plugin.pluginMeta,
            rtNode,
            "Randomly teleport to a safe location",
            if (context.config.registerStandaloneAliases) listOf("rtp") else emptyList(),
        )
    }

    private fun executeRt(player: Player, world: World): Int {
        if (!CommandHelper.checkCooldown(context, player, "rt")) return 0
        context.locale.send(player, "teleport.rt-searching")
        randomTeleportService.randomTeleport(player, world).thenAccept { loc ->
            if (loc == null) {
                context.locale.send(player, "teleport.rt-failed")
            } else {
                context.locale.send(
                    player,
                    "teleport.rt-success",
                    "world" to world.name,
                    "x" to loc.blockX,
                    "y" to loc.blockY,
                    "z" to loc.blockZ,
                )
            }
        }
        return Command.SINGLE_SUCCESS
    }
}
