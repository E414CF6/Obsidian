package obsidian.modules.visual

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.database.StoredLocation
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import org.bukkit.block.sign.Side
import org.bukkit.entity.Display
import org.bukkit.event.Listener

/**
 * Phase 5 Visual & World-Object module:
 * Cuboid Selection (`/pos1`, `/pos2`, `/sel`), Portals (`/setportal`, `/removeportal`, `/portals`),
 * TextDisplay Holograms (`/hologram`), ArmorStand Editor (`/astand`), and Signs (`/signedit`, `/dsign`).
 */
class VisualModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "visual"
    override val description: String =
        "Selections, portals, TextDisplay holograms, ArmorStand editor, and dynamic signs"

    val selectionService = SelectionService(context)
    val portalService = PortalService(context)
    val hologramService = HologramService(context)
    val armorStandService = ArmorStandService(context)
    val signService = SignService(context)

    override fun onEnable() {
        context.registerService(selectionService)
        context.registerService(portalService)
        context.registerService(hologramService)
        context.registerService(armorStandService)
        context.registerService(signService)
    }

    override fun onDisable() {
        hologramService.shutdown()
        signService.shutdown()
    }

    override fun onReload() {
        portalService.reload()
        hologramService.reload()
        signService.reload()
    }

    override fun listeners(): List<Listener> = listOf(
        selectionService,
        portalService,
        hologramService,
    )

    override fun registerCommands(registrar: Commands) {
        registerSelectionCommands(registrar)
        registerPortalCommands(registrar)
        registerHologramCommands(registrar)
        registerArmorStandCommands(registrar)
        registerSignCommands(registrar)
    }

    private fun registerSelectionCommands(registrar: Commands) {
        val pos1Node = Commands.literal("pos1")
            .requires { it.sender.hasPermission("obsidian.command.pos") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val s = selectionService.setPos1(player, player.location) ?: return@executes 0
                context.locale.send(
                    player,
                    "visual.pos-set",
                    "pos" to "1",
                    "x" to s.x.toInt(),
                    "y" to s.y.toInt(),
                    "z" to s.z.toInt()
                )
                selectionService.getRegion(player)?.let { selectionService.showParticles(player, it) }
                Command.SINGLE_SUCCESS
            }
            .build()
        registrar.register(context.plugin.pluginMeta, pos1Node, "Set selection corner 1", emptyList())

        val pos2Node = Commands.literal("pos2")
            .requires { it.sender.hasPermission("obsidian.command.pos") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val s = selectionService.setPos2(player, player.location) ?: return@executes 0
                context.locale.send(
                    player,
                    "visual.pos-set",
                    "pos" to "2",
                    "x" to s.x.toInt(),
                    "y" to s.y.toInt(),
                    "z" to s.z.toInt()
                )
                selectionService.getRegion(player)?.let { selectionService.showParticles(player, it) }
                Command.SINGLE_SUCCESS
            }
            .build()
        registrar.register(context.plugin.pluginMeta, pos2Node, "Set selection corner 2", emptyList())

        val selNode = Commands.literal("sel")
            .requires { it.sender.hasPermission("obsidian.command.pos") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val region = selectionService.getRegion(player)
                if (region == null) {
                    val wand = selectionService.toggleWand(player)
                    context.locale.send(player, "visual.wand-toggled", "state" to if (wand) "ON" else "OFF")
                } else {
                    selectionService.showParticles(player, region)
                    context.locale.send(
                        player,
                        "visual.selection-info",
                        "world" to region.worldName,
                        "dx" to region.widthX(),
                        "dy" to region.heightY(),
                        "dz" to region.lengthZ(),
                        "volume" to region.volume(),
                    )
                }
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.literal("wand")
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val wand = selectionService.toggleWand(player)
                        context.locale.send(player, "visual.wand-toggled", "state" to if (wand) "ON" else "OFF")
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(
            context.plugin.pluginMeta,
            selNode,
            "Show selection info or toggle Blaze Rod wand",
            emptyList()
        )
    }

    private fun registerPortalCommands(registrar: Commands) {
        val setPortalNode = Commands.literal("setportal")
            .requires { it.sender.hasPermission("obsidian.command.setportal") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("name", StringArgumentType.word())
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val region = selectionService.getRegion(player)
                        if (region == null) {
                            context.locale.send(player, "visual.selection-required")
                            return@executes 0
                        }
                        val name = StringArgumentType.getString(ctx, "name")
                        val existing = portalService.getPortal(name)
                        val def = PortalDefinition(
                            name = name,
                            region = region,
                            targetLocation = existing?.targetLocation ?: StoredLocation.fromBukkit(player.location),
                            targetWarp = existing?.targetWarp,
                            permission = existing?.permission,
                            cooldownSeconds = existing?.cooldownSeconds ?: 3L,
                            commands = existing?.commands ?: emptyList(),
                        )
                        portalService.createOrUpdatePortal(def)
                        context.locale.send(player, "visual.portal-saved", "name" to name)
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("warp", StringArgumentType.word())
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val region = selectionService.getRegion(player)
                                if (region == null) {
                                    context.locale.send(player, "visual.selection-required")
                                    return@executes 0
                                }
                                val name = StringArgumentType.getString(ctx, "name")
                                val warpName = StringArgumentType.getString(ctx, "warp")
                                val def = PortalDefinition(name = name, region = region, targetWarp = warpName)
                                portalService.createOrUpdatePortal(def)
                                context.locale.send(player, "visual.portal-saved", "name" to "$name -> $warpName")
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .build()
        registrar.register(context.plugin.pluginMeta, setPortalNode, "Create or update a cuboid portal", emptyList())

        val removePortalNode = Commands.literal("removeportal")
            .requires { it.sender.hasPermission("obsidian.command.setportal") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("name", StringArgumentType.word())
                    .suggests { _, builder ->
                        portalService.getAllPortals().map { it.name }.forEach(builder::suggest)
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val name = StringArgumentType.getString(ctx, "name")
                        if (portalService.removePortal(name)) {
                            context.locale.send(ctx.source.sender, "visual.portal-removed", "name" to name)
                        } else {
                            context.locale.send(ctx.source.sender, "visual.portal-not-found", "name" to name)
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(context.plugin.pluginMeta, removePortalNode, "Remove a portal", emptyList())

        val portalsNode = Commands.literal("portals")
            .requires { it.sender.hasPermission("obsidian.command.portals") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = ctx.source.sender
                val list = portalService.getAllPortals()
                if (list.isEmpty()) {
                    context.locale.send(sender, "visual.portal-list-empty")
                    return@executes Command.SINGLE_SUCCESS
                }
                context.locale.send(sender, "visual.portal-list-header", "count" to list.size)
                list.forEach { p ->
                    val dest = p.targetWarp?.let { "warp:$it" } ?: p.targetLocation?.world ?: "commands"
                    sender.sendMessage(TextFormatter.format(" <dark_gray>-</dark_gray> <aqua>${p.name}</aqua> <gray>(${p.region.worldName} -> $dest)</gray>"))
                }
                Command.SINGLE_SUCCESS
            }
            .build()
        registrar.register(context.plugin.pluginMeta, portalsNode, "List configured portals", emptyList())
    }

    private fun registerHologramCommands(registrar: Commands) {
        val node = Commands.literal("hologram")
            .requires { it.sender.hasPermission("obsidian.command.hologram") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("list")
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        val all = hologramService.getAllHolograms()
                        if (all.isEmpty()) {
                            context.locale.send(sender, "visual.hologram-list-empty")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        context.locale.send(sender, "visual.hologram-list-header", "count" to all.size)
                        all.forEach { h ->
                            sender.sendMessage(TextFormatter.format(" <dark_gray>-</dark_gray> <aqua>${h.id}</aqua> <gray>(${h.location.world}, ${h.lines.size} lines)</gray>"))
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("create")
                    .then(
                        Commands.argument("id", StringArgumentType.word())
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val id = StringArgumentType.getString(ctx, "id")
                                val loc = StoredLocation.fromBukkit(player.location.clone().add(0.0, 2.0, 0.0))
                                    ?: return@executes 0
                                hologramService.createHologram(
                                    id,
                                    loc,
                                    listOf(
                                        "<gradient:#7c3aed:#06b6d4><bold>$id</bold></gradient>",
                                        "<gray>Online: <aqua>{online}</aqua></gray>"
                                    )
                                )
                                context.locale.send(player, "visual.hologram-created", "id" to id)
                                Command.SINGLE_SUCCESS
                            }
                            .then(
                                Commands.argument("text", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val player =
                                            CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                        val id = StringArgumentType.getString(ctx, "id")
                                        val text = StringArgumentType.getString(ctx, "text")
                                        val loc = StoredLocation.fromBukkit(player.location.clone().add(0.0, 2.0, 0.0))
                                            ?: return@executes 0
                                        hologramService.createHologram(id, loc, listOf(text))
                                        context.locale.send(player, "visual.hologram-created", "id" to id)
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
            .then(
                Commands.literal("delete")
                    .then(
                        Commands.argument("id", StringArgumentType.word())
                            .suggests { _, b ->
                                hologramService.getAllHolograms().map { it.id }.forEach(b::suggest)
                                b.buildFuture()
                            }
                            .executes { ctx ->
                                val id = StringArgumentType.getString(ctx, "id")
                                if (hologramService.deleteHologram(id)) {
                                    context.locale.send(ctx.source.sender, "visual.hologram-deleted", "id" to id)
                                } else {
                                    context.locale.send(ctx.source.sender, "visual.hologram-not-found", "id" to id)
                                }
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("addline")
                    .then(
                        Commands.argument("id", StringArgumentType.word())
                            .then(
                                Commands.argument("text", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val id = StringArgumentType.getString(ctx, "id")
                                        val def = hologramService.getHologram(id)
                                        if (def == null) {
                                            context.locale.send(
                                                ctx.source.sender,
                                                "visual.hologram-not-found",
                                                "id" to id
                                            )
                                            return@executes 0
                                        }
                                        def.lines += StringArgumentType.getString(ctx, "text")
                                        hologramService.saveAndRefresh(def)
                                        context.locale.send(
                                            ctx.source.sender,
                                            "visual.hologram-updated",
                                            "id" to def.id
                                        )
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
            .then(
                Commands.literal("setline")
                    .then(
                        Commands.argument("id", StringArgumentType.word())
                            .then(
                                Commands.argument("line", IntegerArgumentType.integer(1, 30))
                                    .then(
                                        Commands.argument("text", StringArgumentType.greedyString())
                                            .executes { ctx ->
                                                val id = StringArgumentType.getString(ctx, "id")
                                                val lineIdx = IntegerArgumentType.getInteger(ctx, "line") - 1
                                                val def = hologramService.getHologram(id)
                                                if (def == null) {
                                                    context.locale.send(
                                                        ctx.source.sender,
                                                        "visual.hologram-not-found",
                                                        "id" to id
                                                    )
                                                    return@executes 0
                                                }
                                                while (def.lines.size <= lineIdx) def.lines += ""
                                                def.lines[lineIdx] = StringArgumentType.getString(ctx, "text")
                                                hologramService.saveAndRefresh(def)
                                                context.locale.send(
                                                    ctx.source.sender,
                                                    "visual.hologram-updated",
                                                    "id" to def.id
                                                )
                                                Command.SINGLE_SUCCESS
                                            }
                                    )
                            )
                    )
            )
            .then(
                Commands.literal("removeline")
                    .then(
                        Commands.argument("id", StringArgumentType.word())
                            .then(
                                Commands.argument("line", IntegerArgumentType.integer(1, 30))
                                    .executes { ctx ->
                                        val id = StringArgumentType.getString(ctx, "id")
                                        val lineIdx = IntegerArgumentType.getInteger(ctx, "line") - 1
                                        val def = hologramService.getHologram(id)
                                        if (def == null) {
                                            context.locale.send(
                                                ctx.source.sender,
                                                "visual.hologram-not-found",
                                                "id" to id
                                            )
                                            return@executes 0
                                        }
                                        if (lineIdx in def.lines.indices) {
                                            def.lines.removeAt(lineIdx)
                                            hologramService.saveAndRefresh(def)
                                        }
                                        context.locale.send(
                                            ctx.source.sender,
                                            "visual.hologram-updated",
                                            "id" to def.id
                                        )
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
            .then(
                Commands.literal("movehere")
                    .then(
                        Commands.argument("id", StringArgumentType.word())
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val id = StringArgumentType.getString(ctx, "id")
                                val def = hologramService.getHologram(id)
                                if (def == null) {
                                    context.locale.send(player, "visual.hologram-not-found", "id" to id)
                                    return@executes 0
                                }
                                val newLoc = StoredLocation.fromBukkit(player.location.clone().add(0.0, 2.0, 0.0))
                                    ?: return@executes 0
                                hologramService.moveHologram(def, newLoc)
                                context.locale.send(player, "visual.hologram-updated", "id" to def.id)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("billboard")
                    .then(
                        Commands.argument("id", StringArgumentType.word())
                            .then(
                                Commands.argument("mode", StringArgumentType.word())
                                    .executes { ctx ->
                                        val id = StringArgumentType.getString(ctx, "id")
                                        val def = hologramService.getHologram(id)
                                        if (def == null) {
                                            context.locale.send(
                                                ctx.source.sender,
                                                "visual.hologram-not-found",
                                                "id" to id
                                            )
                                            return@executes 0
                                        }
                                        val mode = runCatching {
                                            Display.Billboard.valueOf(
                                                StringArgumentType.getString(ctx, "mode").uppercase()
                                            )
                                        }.getOrNull()
                                        if (mode == null) {
                                            context.locale.send(
                                                ctx.source.sender,
                                                "general.invalid-usage",
                                                "usage" to "/hologram billboard <id> <CENTER|VERTICAL|HORIZONTAL|FIXED>"
                                            )
                                            return@executes 0
                                        }
                                        def.billboard = mode
                                        hologramService.moveHologram(def, def.location)
                                        context.locale.send(
                                            ctx.source.sender,
                                            "visual.hologram-updated",
                                            "id" to def.id
                                        )
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
            .build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Manage TextDisplay holograms",
            if (context.config.registerStandaloneAliases) listOf("holo", "holos") else emptyList(),
        )
    }

    private fun registerArmorStandCommands(registrar: Commands) {
        val node = Commands.literal("astand")
            .requires { it.sender.hasPermission("obsidian.command.astand") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val stand = armorStandService.findTargetArmorStand(player)
                if (stand == null) {
                    context.locale.send(player, "visual.astand-not-found")
                    return@executes 0
                }
                armorStandService.openEditor(player, stand)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("flag", StringArgumentType.word())
                    .suggests { _, b ->
                        listOf(
                            "small",
                            "arms",
                            "baseplate",
                            "visible",
                            "gravity",
                            "marker",
                            "glowing",
                            "customname",
                            "resetpose"
                        )
                            .forEach(b::suggest)
                        b.buildFuture()
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val stand = armorStandService.findTargetArmorStand(player)
                        if (stand == null) {
                            context.locale.send(player, "visual.astand-not-found")
                            return@executes 0
                        }
                        val flag = StringArgumentType.getString(ctx, "flag")
                        if (flag.equals("resetpose", ignoreCase = true)) {
                            armorStandService.resetPose(stand)
                            context.locale.send(player, "visual.astand-updated", "flag" to "pose", "state" to "RESET")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        val result = armorStandService.applyFlag(stand, flag)
                        if (result == null) {
                            context.locale.send(
                                player,
                                "general.invalid-usage",
                                "usage" to "/astand [small|arms|baseplate|visible|gravity|marker|glowing|resetpose]"
                            )
                            return@executes 0
                        }
                        context.locale.send(
                            player,
                            "visual.astand-updated",
                            "flag" to result.first,
                            "state" to if (result.second) "ON" else "OFF"
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Edit targeted ArmorStand properties",
            if (context.config.registerStandaloneAliases) listOf("armorstand") else emptyList(),
        )
    }

    private fun registerSignCommands(registrar: Commands) {
        val signEditNode = Commands.literal("signedit")
            .requires { it.sender.hasPermission("obsidian.command.signedit") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("line", IntegerArgumentType.integer(1, 4))
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val sign = signService.findTargetSign(player)
                        if (sign == null) {
                            context.locale.send(player, "visual.sign-not-found")
                            return@executes 0
                        }
                        val line = IntegerArgumentType.getInteger(ctx, "line")
                        signService.editSignLine(sign, Side.FRONT, line, "")
                        context.locale.send(player, "visual.sign-updated", "line" to line)
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("text", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val sign = signService.findTargetSign(player)
                                if (sign == null) {
                                    context.locale.send(player, "visual.sign-not-found")
                                    return@executes 0
                                }
                                val line = IntegerArgumentType.getInteger(ctx, "line")
                                val text = StringArgumentType.getString(ctx, "text")
                                signService.editSignLine(sign, Side.FRONT, line, text)
                                context.locale.send(player, "visual.sign-updated", "line" to line)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .build()
        registrar.register(
            context.plugin.pluginMeta,
            signEditNode,
            "Edit a sign line in place",
            if (context.config.registerStandaloneAliases) listOf("se") else emptyList(),
        )

        val dsignNode = Commands.literal("dsign")
            .requires { it.sender.hasPermission("obsidian.command.dsign") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("list")
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        val all = signService.getAllDynamicSigns()
                        if (all.isEmpty()) {
                            context.locale.send(sender, "visual.dsign-list-empty")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        context.locale.send(sender, "visual.dsign-list-header", "count" to all.size)
                        all.forEach { d ->
                            sender.sendMessage(TextFormatter.format(" <dark_gray>-</dark_gray> <aqua>${d.key}</aqua>"))
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("set")
                    .then(
                        Commands.argument("line", IntegerArgumentType.integer(1, 4))
                            .then(
                                Commands.argument("template", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val player =
                                            CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                        val sign = signService.findTargetSign(player)
                                        if (sign == null) {
                                            context.locale.send(player, "visual.sign-not-found")
                                            return@executes 0
                                        }
                                        val lineIdx = IntegerArgumentType.getInteger(ctx, "line") - 1
                                        val template = StringArgumentType.getString(ctx, "template")
                                        val existing = signService.getDynamicSign(sign)
                                        if (existing == null) {
                                            val front = MutableList(4) { "" }
                                            front[lineIdx] = template
                                            signService.registerDynamicSign(sign, front, List(4) { "" })
                                        } else {
                                            existing.frontTemplates[lineIdx] = template
                                            signService.saveAndRefresh(existing)
                                        }
                                        context.locale.send(player, "visual.dsign-updated", "line" to (lineIdx + 1))
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
            .then(
                Commands.literal("remove")
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val sign = signService.findTargetSign(player)
                        if (sign == null) {
                            context.locale.send(player, "visual.sign-not-found")
                            return@executes 0
                        }
                        if (signService.removeDynamicSign(sign)) {
                            context.locale.send(player, "visual.dsign-removed")
                        } else {
                            context.locale.send(player, "visual.sign-not-found")
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(
            context.plugin.pluginMeta,
            dsignNode,
            "Manage placeholder-powered dynamic signs",
            emptyList()
        )
    }
}
