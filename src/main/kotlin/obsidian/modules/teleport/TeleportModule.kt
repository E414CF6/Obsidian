package obsidian.modules.teleport

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.ui.DialogBuilder
import org.bukkit.Bukkit
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
        registerMassAndNearTeleport(registrar)
        registerTier3Teleport(registrar)
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
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                openTpPosDialog(player)
                Command.SINGLE_SUCCESS
            }
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

    private fun openTpPosDialog(player: Player) {
        val worlds = Bukkit.getWorlds().map { w ->
            SingleOptionDialogInput.OptionEntry.create(
                w.name,
                obsidian.core.text.TextFormatter.format("<white>${w.name}</white>"),
                w == player.world,
            )
        }
        val inputs = listOf(
            DialogInput.text("x", context.locale.component("teleport.tppos-label-x"))
                .initial(player.location.blockX.toString()).width(180).build(),
            DialogInput.text("y", context.locale.component("teleport.tppos-label-y"))
                .initial(player.location.blockY.toString()).width(180).build(),
            DialogInput.text("z", context.locale.component("teleport.tppos-label-z"))
                .initial(player.location.blockZ.toString()).width(180).build(),
            DialogInput.singleOption("world", context.locale.component("teleport.tppos-label-world"), worlds)
                .width(180).build(),
        )
        DialogBuilder.form(
            context = context,
            player = player,
            title = context.locale.component("teleport.tppos-dialog-title"),
            inputs = inputs,
            submitLabel = context.locale.component("general.dialog-confirm"),
            cancelLabel = context.locale.component("general.dialog-cancel"),
        ) { p, response ->
            val x = response.getText("x")?.trim()?.toDoubleOrNull()
            val y = response.getText("y")?.trim()?.toDoubleOrNull()
            val z = response.getText("z")?.trim()?.toDoubleOrNull()
            val worldName = response.getText("world")?.trim()
            val world = worldName?.let { Bukkit.getWorld(it) } ?: p.world
            if (x == null || y == null || z == null) {
                context.locale.send(
                    p,
                    "general.invalid-number",
                    "input" to "${response.getText("x")}, ${response.getText("y")}, ${response.getText("z")}"
                )
                return@form
            }
            val dest = Location(world, x, y, z, p.location.yaw, p.location.pitch)
            teleportService.teleport(p, dest, TeleportCause.COMMAND).thenAccept { ok ->
                if (ok) {
                    context.locale.send(
                        p,
                        "teleport.teleported-to-coords",
                        "target" to p.name,
                        "world" to world.name,
                        "x" to x.roundToInt(),
                        "y" to y.roundToInt(),
                        "z" to z.roundToInt(),
                    )
                    context.actionBar.pushTransientLocale(
                        p,
                        "teleport.actionbar-teleported",
                        "target" to "${world.name} (${x.roundToInt()}, ${y.roundToInt()}, ${z.roundToInt()})",
                    )
                } else {
                    context.locale.send(p, "teleport.unsafe-location")
                }
            }
        }
    }

    private fun registerTpaCommands(registrar: Commands) {
        val tpaNode = Commands.literal("tpa")
            .requires { it.sender.hasPermission("obsidian.command.tpa") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                tpaService.openPlayerSelectorGui(sender, TpaType.TO_TARGET)
                Command.SINGLE_SUCCESS
            }
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
            .executes { ctx ->
                val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                tpaService.openPlayerSelectorGui(sender, TpaType.HERE)
                Command.SINGLE_SUCCESS
            }
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
        registerTpaResponseCommand(
            registrar, "tpaprompt", "Open TPA confirmation dialog", "obsidian.command.tpaccept"
        ) { player, senderName ->
            tpaService.openPromptDialog(player, senderName)
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
                        if (ok) {
                            context.locale.send(player, "teleport.back-teleported")
                            context.actionBar.pushTransientLocale(player, "teleport.actionbar-back")
                        } else context.locale.send(player, "teleport.unsafe-location")
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
                        if (ok) {
                            context.locale.send(player, "teleport.dback-teleported")
                            context.actionBar.pushTransientLocale(player, "teleport.actionbar-dback")
                        } else context.locale.send(player, "teleport.unsafe-location")
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
                            if (ok) {
                                context.locale.send(player, "teleport.spawn-teleported")
                                context.actionBar.pushTransientLocale(player, "teleport.actionbar-spawn")
                            }
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
            listOf("rtp"),
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
                context.actionBar.pushTransientLocale(
                    player,
                    "teleport.actionbar-teleported",
                    "target" to "${world.name} (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})",
                )
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun registerMassAndNearTeleport(registrar: Commands) {
        // /tpaall
        val tpaAllNode = Commands.literal("tpaall")
            .requires { it.sender.hasPermission("obsidian.command.tpaall") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                var sent = 0
                for (target in Bukkit.getOnlinePlayers()) {
                    if (target.uniqueId == sender.uniqueId) continue
                    tpaService.sendRequest(sender, target, TpaType.HERE)
                    sent++
                }
                context.locale.send(sender, "teleport.tpaall-sent", "count" to sent)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            tpaAllNode,
            "Send tpahere request to all online players",
            emptyList()
        )

        // /tpall
        val tpAllNode = Commands.literal("tpall")
            .requires { it.sender.hasPermission("obsidian.command.tpall") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val dest = sender.location.clone()
                var count = 0
                for (target in Bukkit.getOnlinePlayers()) {
                    if (target.uniqueId == sender.uniqueId) continue
                    teleportService.teleport(target, dest, TeleportCause.COMMAND, ensureSafe = false)
                    count++
                }
                context.locale.send(sender, "teleport.tpall-done", "count" to count)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, tpAllNode, "Teleport all online players to you", emptyList())

        // /near [radius]
        val nearNode = Commands.literal("near")
            .requires { it.sender.hasPermission("obsidian.command.near") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeNear(player, 200)
            }.then(
                Commands.argument("radius", IntegerArgumentType.integer(1, 2000))
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val radius = IntegerArgumentType.getInteger(ctx, "radius")
                        executeNear(player, radius)
                    }).build()
        registrar.register(context.plugin.pluginMeta, nearNode, "List nearby players", emptyList())
    }

    private fun executeNear(player: Player, radius: Int): Int {
        val origin = player.location
        val maxDistSq = radius.toDouble() * radius.toDouble()
        val nearby = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != player.uniqueId && it.world == origin.world }
            .mapNotNull { other ->
                val distSq = origin.distanceSquared(other.location)
                if (distSq <= maxDistSq) other.name to kotlin.math.sqrt(distSq).toInt() else null
            }
            .sortedBy { it.second }
        if (nearby.isEmpty()) {
            context.locale.send(player, "teleport.near-none", "radius" to radius)
        } else {
            val formatted = nearby.joinToString(", ") { "${it.first} (${it.second}m)" }
            context.locale.send(player, "teleport.near-list", "radius" to radius, "players" to formatted)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun registerTier3Teleport(registrar: Commands) {
        // /tpo <target> [destination]
        val tpoNode = Commands.literal("tpo")
            .requires { it.sender.hasPermission("obsidian.command.tpo") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                    val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val target =
                        ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                            .firstOrNull() ?: return@executes 0
                    teleportService.teleport(sender, target.location, TeleportCause.COMMAND, ensureSafe = false)
                        .thenAccept { ok ->
                            if (ok) {
                                context.locale.send(
                                    sender,
                                    "teleport.teleported-to-player",
                                    "source" to sender.name,
                                    "target" to target.name,
                                )
                            }
                        }
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("destination", ArgumentTypes.player())
                        .requires { it.sender.hasPermission("obsidian.command.tp.others") || it.sender.hasPermission("obsidian.admin") }
                        .executes { ctx ->
                            val target =
                                ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                            val dest =
                                ctx.getArgument("destination", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                            teleportService.teleport(target, dest.location, TeleportCause.COMMAND, ensureSafe = false)
                                .thenAccept { ok ->
                                    if (ok) {
                                        context.locale.send(
                                            ctx.source.sender,
                                            "teleport.teleported-to-player",
                                            "source" to target.name,
                                            "target" to dest.name,
                                        )
                                    }
                                }
                            Command.SINGLE_SUCCESS
                        }
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, tpoNode, "Override safe teleport to a player", emptyList())

        // /tpohere <target>
        val tpoHereNode = Commands.literal("tpohere")
            .requires { it.sender.hasPermission("obsidian.command.tpohere") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                    val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val target =
                        ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                            .firstOrNull() ?: return@executes 0
                    teleportService.teleport(target, sender.location, TeleportCause.COMMAND, ensureSafe = false)
                        .thenAccept { ok ->
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
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            tpoHereNode,
            "Override safe teleport a player to you",
            emptyList()
        )

        // /tpopos <x> <y> <z> [world]
        val tpoPosNode = Commands.literal("tpopos")
            .requires { it.sender.hasPermission("obsidian.command.tpo") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("x", DoubleArgumentType.doubleArg()).then(
                    Commands.argument("y", DoubleArgumentType.doubleArg()).then(
                        Commands.argument("z", DoubleArgumentType.doubleArg()).executes { ctx ->
                            val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                            val x = DoubleArgumentType.getDouble(ctx, "x")
                            val y = DoubleArgumentType.getDouble(ctx, "y")
                            val z = DoubleArgumentType.getDouble(ctx, "z")
                            val dest = Location(player.world, x, y, z, player.location.yaw, player.location.pitch)
                            teleportService.teleport(player, dest, TeleportCause.COMMAND, ensureSafe = false)
                                .thenAccept { ok ->
                                    if (ok) {
                                        context.locale.send(
                                            player,
                                            "teleport.tppos-teleported",
                                            "world" to player.world.name,
                                            "x" to x.roundToInt(),
                                            "y" to y.roundToInt(),
                                            "z" to z.roundToInt(),
                                        )
                                    }
                                }
                            Command.SINGLE_SUCCESS
                        }.then(
                            Commands.argument("world", ArgumentTypes.world()).executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val x = DoubleArgumentType.getDouble(ctx, "x")
                                val y = DoubleArgumentType.getDouble(ctx, "y")
                                val z = DoubleArgumentType.getDouble(ctx, "z")
                                val world = ctx.getArgument("world", World::class.java)
                                val dest = Location(world, x, y, z, player.location.yaw, player.location.pitch)
                                teleportService.teleport(player, dest, TeleportCause.COMMAND, ensureSafe = false)
                                    .thenAccept { ok ->
                                        if (ok) {
                                            context.locale.send(
                                                player,
                                                "teleport.tppos-teleported",
                                                "world" to world.name,
                                                "x" to x.roundToInt(),
                                                "y" to y.roundToInt(),
                                                "z" to z.roundToInt(),
                                            )
                                        }
                                    }
                                Command.SINGLE_SUCCESS
                            }
                        )
                    )
                )
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            tpoPosNode,
            "Override safe teleport to exact coordinates",
            emptyList()
        )

        // /tpbypass
        val tpBypassNode = Commands.literal("tpbypass")
            .requires { it.sender.hasPermission("obsidian.teleport.bypass") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val enabled = teleportService.toggleSafeBypass(player.uniqueId)
                context.locale.send(
                    player,
                    "teleport.tpbypass-toggled",
                    "state" to if (enabled) "ON" else "OFF",
                )
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            tpBypassNode,
            "Toggle bypassing safe teleport adjustments",
            emptyList()
        )

        // /tpallworld <world>
        val tpAllWorldNode = Commands.literal("tpallworld")
            .requires { it.sender.hasPermission("obsidian.command.tpall") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("world", ArgumentTypes.world()).executes { ctx ->
                    val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val sourceWorld = ctx.getArgument("world", World::class.java)
                    val dest = sender.location.clone()
                    var count = 0
                    for (target in Bukkit.getOnlinePlayers()) {
                        if (target.uniqueId != sender.uniqueId && target.world == sourceWorld) {
                            teleportService.teleport(target, dest, TeleportCause.COMMAND, ensureSafe = false)
                            count++
                        }
                    }
                    context.locale.send(
                        sender,
                        "teleport.tpallworld-done",
                        "count" to count,
                        "world" to sourceWorld.name,
                    )
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            tpAllWorldNode,
            "Teleport all players from a specific world to you",
            emptyList()
        )

        // /down
        val downNode = Commands.literal("down")
            .requires { it.sender.hasPermission("obsidian.command.down") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val origin = player.location
                val world = origin.world ?: return@executes 0
                context.scheduler.runAtLocation(origin) {
                    val bx = origin.blockX
                    val bz = origin.blockZ
                    var found: Location? = null
                    for (y in (origin.blockY - 2) downTo (world.minHeight + 1)) {
                        val floor = world.getBlockAt(bx, y - 1, bz)
                        val feet = world.getBlockAt(bx, y, bz)
                        val head = world.getBlockAt(bx, y + 1, bz)
                        if (floor.type.isSolid && feet.isPassable && head.isPassable) {
                            found = Location(world, bx + 0.5, y.toDouble(), bz + 0.5, origin.yaw, origin.pitch)
                            break
                        }
                    }
                    if (found == null) {
                        context.locale.send(player, "teleport.down-none")
                    } else {
                        teleportService.teleport(player, found, TeleportCause.COMMAND, ensureSafe = false)
                            .thenAccept { ok -> if (ok) context.locale.send(player, "teleport.down-teleported") }
                    }
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            downNode,
            "Teleport down to the nearest cavity below you",
            emptyList()
        )

        // /setfirstspawn
        val setFirstSpawnNode = Commands.literal("setfirstspawn")
            .requires { it.sender.hasPermission("obsidian.command.setspawn") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                spawnService.setFirstSpawn(player.location)
                context.locale.send(player, "teleport.firstspawn-set")
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            setFirstSpawnNode,
            "Set the spawn point for first-time players",
            emptyList()
        )

        // /resetback [player]
        val resetBackNode = Commands.literal("resetback")
            .requires { it.sender.hasPermission("obsidian.command.back") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                teleportService.resetBack(player)
                context.locale.send(player, "teleport.back-reset", "target" to player.name)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.tp.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        teleportService.resetBack(target)
                        context.locale.send(ctx.source.sender, "teleport.back-reset", "target" to target.name)
                        Command.SINGLE_SUCCESS
                    }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            resetBackNode,
            "Clear stored /back and /dback locations",
            emptyList()
        )

        // /setrt [minRadius] [maxRadius]
        val setRtNode = Commands.literal("setrt")
            .requires { it.sender.hasPermission("obsidian.command.setrt") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                randomTeleportService.setWorldRtpConfig(player.world, player.location)
                context.locale.send(player, "teleport.setrt-done", "world" to player.world.name)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("minRadius", IntegerArgumentType.integer(0)).then(
                    Commands.argument("maxRadius", IntegerArgumentType.integer(1)).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val minR = IntegerArgumentType.getInteger(ctx, "minRadius")
                        val maxR = IntegerArgumentType.getInteger(ctx, "maxRadius")
                        randomTeleportService.setWorldRtpConfig(player.world, player.location, minR, maxR)
                        context.locale.send(player, "teleport.setrt-done", "world" to player.world.name)
                        Command.SINGLE_SUCCESS
                    }
                )
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            setRtNode,
            "Set random teleport center and radius for the current world",
            emptyList()
        )
    }
}
