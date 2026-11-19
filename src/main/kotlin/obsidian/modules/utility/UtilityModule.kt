package obsidian.modules.utility

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
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.SkullMeta

/**
 * Essential server and player utility commands (`/gm`, `/fly`, `/god`, `/heal`, `/feed`, `/speed`,
 * `/repair`, `/workbench`, `/ender`, `/hat`, `/sudo`, `/ptime`, `/pweather`, `/clear`, `/give`, `/item`).
 */
class UtilityModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "utility"
    override val description: String = "Core player and administrative utility commands"

    private val flightAndCombatService = FlightAndCombatService(context)
    private val godListener = GodModeListener(context, flightAndCombatService)
    private val shulkerBoxService = ShulkerBoxService(context)
    val savedItemService = SavedItemService(context)
    val customRecipeService = CustomRecipeService(context)
    val scavengerService = ScavengerService(context)

    override fun listeners(): List<Listener> = listOf(godListener, shulkerBoxService, flightAndCombatService)

    override fun onEnable() {
        context.registerService(savedItemService)
        context.registerService(customRecipeService)
        context.registerService(scavengerService)
        flightAndCombatService.start()
    }

    override fun onDisable() {
        flightAndCombatService.stop()
        shulkerBoxService.closeAll()
        customRecipeService.unregisterAll()
    }

    override fun onReload() {
        savedItemService.load()
        customRecipeService.reload()
    }

    override fun registerCommands(registrar: Commands) {
        registerGameMode(registrar)
        registerToggleCommands(registrar)
        registerFlightAndQolCommands(registrar)
        registerHealAndFeed(registrar)
        registerSpeedCommands(registrar)
        registerRepair(registrar)
        registerInventoryAndMenuCommands(registrar)
        registerVirtualWorkstations(registrar)
        registerExtendedItemCommands(registrar)
        registerSavedItems(registrar)
        registerCustomRecipe(registrar)
        registerScavenger(registrar)
        registerInfoCommands(registrar)
        registerAdminUtils(registrar)
        registerPersonalEnvironmentCommands(registrar)
        registerGiveAndItem(registrar)
        registerSudo(registrar)
    }

    private fun registerGameMode(registrar: Commands) {
        val gmNode = Commands.literal("gm")
            .requires { it.sender.hasPermission("obsidian.command.gm") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("mode", StringArgumentType.word()).suggests { _, builder ->
                listOf(
                    "survival",
                    "creative",
                    "adventure",
                    "spectator"
                ).filter { it.startsWith(builder.remaining, ignoreCase = true) }.forEach { builder.suggest(it) }
                builder.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val mode = UtilityHelpers.parseGameMode(StringArgumentType.getString(ctx, "mode"))
                    ?: return@executes sendUsage(
                        ctx.source.sender, "/gm <survival|creative|adventure|spectator> [player]"
                    )
                applyGameMode(ctx.source.sender, player, mode)
            }.then(Commands.argument("target", ArgumentTypes.player()).requires {
                it.sender.hasPermission("obsidian.command.gm.others") || it.sender.hasPermission(
                    "obsidian.admin"
                )
            }.executes { ctx ->
                val mode = UtilityHelpers.parseGameMode(StringArgumentType.getString(ctx, "mode"))
                    ?: return@executes sendUsage(ctx.source.sender, "/gm <mode> [player]")
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                applyGameMode(ctx.source.sender, target, mode)
            })).build()

        registrar.register(
            context.plugin.pluginMeta,
            gmNode,
            "Change game mode",
            if (context.config.registerStandaloneAliases) listOf("gamemode") else emptyList(),
        )

        if (context.config.registerStandaloneAliases) {
            mapOf(
                "gms" to GameMode.SURVIVAL,
                "gmc" to GameMode.CREATIVE,
                "gma" to GameMode.ADVENTURE,
                "gmsp" to GameMode.SPECTATOR,
            ).forEach { (alias, mode) ->
                val aliasNode = Commands.literal(alias)
                    .requires { it.sender.hasPermission("obsidian.command.gm") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        applyGameMode(ctx.source.sender, player, mode)
                    }.then(Commands.argument("target", ArgumentTypes.player()).requires {
                        it.sender.hasPermission("obsidian.command.gm.others") || it.sender.hasPermission(
                            "obsidian.admin"
                        )
                    }.executes { ctx ->
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
                        applyGameMode(ctx.source.sender, target, mode)
                    }).build()
                registrar.register(
                    context.plugin.pluginMeta, aliasNode, "Shortcut for /gm ${mode.name.lowercase()}", emptyList()
                )
            }
        }
    }

    private fun applyGameMode(sender: CommandSender, target: Player, mode: GameMode): Int {
        target.gameMode = mode
        val modeName = mode.name.lowercase()
        context.locale.send(sender, "utility.gamemode-set", "target" to target.name, "gamemode" to modeName)
        if (sender != target) {
            context.locale.send(target, "utility.gamemode-changed", "gamemode" to modeName)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun registerToggleCommands(registrar: Commands) {
        registerTargetPlayerCommand(registrar, "fly", "Toggle flight mode", "obsidian.command.fly") { sender, target ->
            val newState = !target.allowFlight
            target.allowFlight = newState
            if (!newState) target.isFlying = false
            val profile = context.users.getCached(target)
            profile.flyMode = newState
            profile.markDirty()
            context.locale.send(
                sender, "utility.fly-toggled", "target" to target.name, "state" to if (newState) "ON" else "OFF"
            )
        }

        registerTargetPlayerCommand(registrar, "god", "Toggle god mode", "obsidian.command.god") { sender, target ->
            val profile = context.users.getCached(target)
            val newState = !profile.godMode
            profile.godMode = newState
            profile.markDirty()
            context.locale.send(
                sender, "utility.god-toggled", "target" to target.name, "state" to if (newState) "ON" else "OFF"
            )
        }
    }

    private fun registerHealAndFeed(registrar: Commands) {
        registerTargetPlayerCommand(
            registrar,
            "heal",
            "Restore health and clear negative effects",
            "obsidian.command.heal",
            checkCooldown = true
        ) { sender, target ->
            val maxHp = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            target.health = maxHp
            target.foodLevel = 20
            target.saturation = 10f
            target.fireTicks = 0
            context.locale.send(sender, "utility.healed", "target" to target.name)
        }

        registerTargetPlayerCommand(
            registrar, "feed", "Restore hunger and saturation", "obsidian.command.feed", checkCooldown = true
        ) { sender, target ->
            target.foodLevel = 20
            target.saturation = 10f
            target.exhaustion = 0f
            context.locale.send(sender, "utility.fed", "target" to target.name)
        }
    }

    private fun registerSpeedCommands(registrar: Commands) {
        registerSpeedLiteral(registrar, "speed", null, "Set walk or fly speed based on current state")
        registerSpeedLiteral(registrar, "walkspeed", false, "Set player walk speed (0-10)")
        registerSpeedLiteral(registrar, "flyspeed", true, "Set player fly speed (0-10)")
    }

    private fun registerSpeedLiteral(
        registrar: Commands,
        name: String,
        forcedFly: Boolean?,
        description: String,
    ) {
        val node = Commands.literal(name)
            .requires { it.sender.hasPermission("obsidian.command.$name") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0, 10.0)).executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val amount = DoubleArgumentType.getDouble(ctx, "amount")
                applySpeed(ctx.source.sender, player, amount, forcedFly ?: player.isFlying)
            }.then(Commands.argument("target", ArgumentTypes.player()).requires {
                it.sender.hasPermission("obsidian.command.$name.others") || it.sender.hasPermission(
                    "obsidian.admin"
                )
            }.executes { ctx ->
                val amount = DoubleArgumentType.getDouble(ctx, "amount")
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                applySpeed(ctx.source.sender, target, amount, forcedFly ?: target.isFlying)
            })).build()
        registrar.register(context.plugin.pluginMeta, node, description, emptyList())
    }

    private fun applySpeed(sender: CommandSender, target: Player, amount: Double, fly: Boolean): Int {
        val scaled = UtilityHelpers.scaleSpeedToBukkit(amount, fly)
        if (fly) {
            target.flySpeed = scaled
        } else {
            target.walkSpeed = scaled
        }
        context.locale.send(
            sender,
            "utility.speed-set",
            "target" to target.name,
            "type" to if (fly) "fly" else "walk",
            "speed" to amount,
        )
        return Command.SINGLE_SUCCESS
    }

    private fun registerRepair(registrar: Commands) {
        val node = Commands.literal("repair")
            .requires { it.sender.hasPermission("obsidian.command.repair") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeRepair(ctx.source.sender, player, "hand")
            }.then(Commands.argument("scope", StringArgumentType.word()).suggests { _, builder ->
                listOf("hand", "offhand", "armor", "all").filter {
                    it.startsWith(
                        builder.remaining,
                        ignoreCase = true
                    )
                }.forEach { builder.suggest(it) }
                builder.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeRepair(ctx.source.sender, player, StringArgumentType.getString(ctx, "scope"))
            }.then(Commands.argument("target", ArgumentTypes.player()).requires {
                it.sender.hasPermission("obsidian.command.repair.others") || it.sender.hasPermission(
                    "obsidian.admin"
                )
            }.executes { ctx ->
                val scope = StringArgumentType.getString(ctx, "scope")
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                executeRepair(ctx.source.sender, target, scope)
            })).build()
        registrar.register(context.plugin.pluginMeta, node, "Repair damaged items", emptyList())
    }

    private fun executeRepair(sender: CommandSender, target: Player, scopeRaw: String): Int {
        val scope = scopeRaw.lowercase()
        if (!CommandHelper.hasPermission(context, sender, "obsidian.command.repair.$scope")) return 0
        val inv = target.inventory
        val repairedCount = when (scope) {
            "hand" -> if (UtilityHelpers.repairItem(inv.itemInMainHand)) 1 else 0
            "offhand" -> if (UtilityHelpers.repairItem(inv.itemInOffHand)) 1 else 0
            "armor" -> inv.armorContents.count { UtilityHelpers.repairItem(it) }
            "all" -> inv.contents.count { UtilityHelpers.repairItem(it) }
            else -> return sendUsage(sender, "/repair [hand|offhand|armor|all] [player]")
        }
        if (repairedCount == 0) {
            context.locale.send(sender, "utility.nothing-to-repair")
        } else {
            context.locale.send(sender, "utility.repaired", "amount" to repairedCount, "target" to target.name)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun registerInventoryAndMenuCommands(registrar: Commands) {
        registerTargetPlayerCommand(
            registrar,
            "workbench",
            "Open crafting workbench",
            "obsidian.command.workbench",
            aliases = listOf("wb", "craft"),
        ) { sender, target ->
            MenuType.CRAFTING.builder().checkReachable(false).build(target).open()
            if (sender != target) context.locale.send(sender, "utility.workbench-opened", "target" to target.name)
        }

        val enderNode = Commands.literal("ender")
            .requires { it.sender.hasPermission("obsidian.command.ender") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                player.openInventory(player.enderChest)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.ender.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val viewer = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        viewer.openInventory(target.enderChest)
                        context.locale.send(viewer, "utility.ender-opened", "target" to target.name)
                        Command.SINGLE_SUCCESS
                    }).build()
        registrar.register(
            context.plugin.pluginMeta,
            enderNode,
            "Open ender chest",
            if (context.config.registerStandaloneAliases) listOf("enderchest", "ec") else emptyList(),
        )

        val hatNode = Commands.literal("hat")
            .requires { it.sender.hasPermission("obsidian.command.hat") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val hand = player.inventory.itemInMainHand
                if (hand.type.isAir) {
                    context.locale.send(player, "utility.hat-invalid")
                    return@executes 0
                }
                val currentHelmet = player.inventory.helmet
                player.inventory.setHelmet(hand.clone())
                player.inventory.setItemInMainHand(currentHelmet)
                context.locale.send(player, "utility.hat-equipped")
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, hatNode, "Wear held item as hat", emptyList())

        val clearNode = Commands.literal("clear")
            .requires { it.sender.hasPermission("obsidian.command.clear") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                player.inventory.clear()
                context.locale.send(player, "utility.cleared", "scope" to "inventory", "target" to player.name)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.clear.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        target.inventory.clear()
                        context.locale.send(
                            ctx.source.sender, "utility.cleared", "scope" to "inventory", "target" to target.name
                        )
                        Command.SINGLE_SUCCESS
                    }).build()
        registrar.register(context.plugin.pluginMeta, clearNode, "Clear player inventory", emptyList())
    }

    private fun registerPersonalEnvironmentCommands(registrar: Commands) {
        val ptimeNode = Commands.literal("ptime")
            .requires { it.sender.hasPermission("obsidian.command.ptime") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("value", StringArgumentType.word()).suggests { _, builder ->
                listOf("day", "noon", "sunset", "night", "midnight", "sunrise", "reset").filter {
                    it.startsWith(
                        builder.remaining,
                        ignoreCase = true
                    )
                }.forEach { builder.suggest(it) }
                builder.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                applyPTime(ctx.source.sender, player, StringArgumentType.getString(ctx, "value"))
            }.then(Commands.argument("target", ArgumentTypes.player()).requires {
                it.sender.hasPermission("obsidian.command.ptime.others") || it.sender.hasPermission(
                    "obsidian.admin"
                )
            }.executes { ctx ->
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                applyPTime(ctx.source.sender, target, StringArgumentType.getString(ctx, "value"))
            })).build()
        registrar.register(context.plugin.pluginMeta, ptimeNode, "Set personal player time", emptyList())

        val pweatherNode = Commands.literal("pweather")
            .requires { it.sender.hasPermission("obsidian.command.pweather") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("value", StringArgumentType.word()).suggests { _, builder ->
                listOf("sun", "rain", "reset").filter { it.startsWith(builder.remaining, ignoreCase = true) }
                    .forEach { builder.suggest(it) }
                builder.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                applyPWeather(ctx.source.sender, player, StringArgumentType.getString(ctx, "value"))
            }.then(Commands.argument("target", ArgumentTypes.player()).requires {
                it.sender.hasPermission("obsidian.command.pweather.others") || it.sender.hasPermission(
                    "obsidian.admin"
                )
            }.executes { ctx ->
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                applyPWeather(ctx.source.sender, target, StringArgumentType.getString(ctx, "value"))
            })).build()
        registrar.register(context.plugin.pluginMeta, pweatherNode, "Set personal player weather", emptyList())
    }

    private fun applyPTime(sender: CommandSender, target: Player, value: String): Int {
        if (value.equals("reset", ignoreCase = true)) {
            target.resetPlayerTime()
            context.locale.send(sender, "utility.ptime-set", "target" to target.name, "value" to "reset")
            return Command.SINGLE_SUCCESS
        }
        val ticks = UtilityHelpers.parsePersonalTime(value) ?: return sendUsage(
            sender,
            "/ptime <day|noon|night|midnight|ticks|reset> [player]"
        )
        target.setPlayerTime(ticks, false)
        context.locale.send(sender, "utility.ptime-set", "target" to target.name, "value" to value)
        return Command.SINGLE_SUCCESS
    }

    private fun applyPWeather(sender: CommandSender, target: Player, value: String): Int {
        if (value.equals("reset", ignoreCase = true)) {
            target.resetPlayerWeather()
            context.locale.send(sender, "utility.pweather-set", "target" to target.name, "value" to "reset")
            return Command.SINGLE_SUCCESS
        }
        val weather = UtilityHelpers.parsePersonalWeather(value) ?: return sendUsage(
            sender,
            "/pweather <sun|rain|reset> [player]"
        )
        target.setPlayerWeather(weather)
        context.locale.send(sender, "utility.pweather-set", "target" to target.name, "value" to value)
        return Command.SINGLE_SUCCESS
    }

    private fun registerGiveAndItem(registrar: Commands) {
        val giveNode = Commands.literal("give")
            .requires { it.sender.hasPermission("obsidian.command.give") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player())
                    .then(
                        Commands.argument("item", ArgumentTypes.itemStack()).executes { ctx ->
                            val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                .resolve(ctx.source).firstOrNull() ?: return@executes 0
                            val stack = ctx.getArgument("item", ItemStack::class.java)
                            giveItem(ctx.source.sender, target, stack, 1)
                        }.then(
                            Commands.argument("amount", IntegerArgumentType.integer(1, 2304)).executes { ctx ->
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                val stack = ctx.getArgument("item", ItemStack::class.java)
                                val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                giveItem(ctx.source.sender, target, stack, amount)
                            })
                    )
            ).build()
        registrar.register(context.plugin.pluginMeta, giveNode, "Give an item to a player", emptyList())

        val itemNode = Commands.literal("item")
            .requires { it.sender.hasPermission("obsidian.command.item") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("item", ArgumentTypes.itemStack()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val stack = ctx.getArgument("item", ItemStack::class.java)
                    giveItem(ctx.source.sender, player, stack, 1)
                }.then(
                    Commands.argument("amount", IntegerArgumentType.integer(1, 2304)).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val stack = ctx.getArgument("item", ItemStack::class.java)
                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                        giveItem(ctx.source.sender, player, stack, amount)
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            itemNode,
            "Give an item to yourself",
            if (context.config.registerStandaloneAliases) listOf("i") else emptyList(),
        )
    }

    private fun giveItem(sender: CommandSender, target: Player, template: ItemStack, amount: Int): Int {
        if (template.type == Material.AIR) {
            context.locale.send(sender, "utility.invalid-item", "item" to "air")
            return 0
        }
        val toGive = template.clone().apply { this.amount = amount }
        val overflow = target.inventory.addItem(toGive)
        overflow.values.forEach { target.world.dropItemNaturally(target.location, it) }
        context.locale.send(
            sender,
            "utility.item-given",
            "target" to target.name,
            "item" to template.type.name.lowercase(),
            "amount" to amount,
        )
        return Command.SINGLE_SUCCESS
    }

    private fun registerSudo(registrar: Commands) {
        val sudoNode = Commands.literal("sudo")
            .requires { it.sender.hasPermission("obsidian.command.sudo") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("command", StringArgumentType.greedyString()).executes { ctx ->
                        val sender = ctx.source.sender
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
                        val rawCmd = StringArgumentType.getString(ctx, "command").trim()
                        if (rawCmd.startsWith("c:", ignoreCase = true)) {
                            target.chat(rawCmd.substring(2))
                        } else {
                            val clean = rawCmd.removePrefix("/")
                            Bukkit.dispatchCommand(target, clean)
                        }
                        context.locale.send(
                            sender, "utility.sudo-executed", "target" to target.name, "command" to rawCmd
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta, sudoNode, "Force a player to execute a command or chat", emptyList()
        )
    }

    private fun registerVirtualWorkstations(registrar: Commands) {
        val stations = listOf(
            Triple("anvil", "Open virtual anvil", MenuType.ANVIL to emptyList<String>()),
            Triple("grindstone", "Open virtual grindstone", MenuType.GRINDSTONE to emptyList()),
            Triple("smithingtable", "Open virtual smithing table", MenuType.SMITHING to listOf("smithing")),
            Triple("stonecutter", "Open virtual stonecutter", MenuType.STONECUTTER to emptyList()),
            Triple(
                "cartographytable",
                "Open virtual cartography table",
                MenuType.CARTOGRAPHY_TABLE to listOf("cartography")
            ),
            Triple("loom", "Open virtual loom", MenuType.LOOM to emptyList()),
        )
        for ((name, desc, menuPair) in stations) {
            val (menuType, aliases) = menuPair
            registerTargetPlayerCommand(
                registrar,
                name,
                desc,
                "obsidian.command.$name",
                aliases = aliases,
            ) { sender, target ->
                menuType.builder().checkReachable(false).build(target).open()
                if (sender != target) context.locale.send(
                    sender,
                    "utility.station-opened",
                    "station" to name,
                    "target" to target.name
                )
            }
        }
    }

    private fun registerExtendedItemCommands(registrar: Commands) {
        // /dispose (alias: trash)
        val disposeNode = Commands.literal("dispose")
            .requires { it.sender.hasPermission("obsidian.command.dispose") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val inv = Bukkit.createInventory(null, 54, context.locale.component("utility.dispose-title"))
                player.openInventory(inv)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            disposeNode,
            "Open a disposal inventory",
            if (context.config.registerStandaloneAliases) listOf("trash") else emptyList(),
        )

        // /more [amount]
        val moreNode = Commands.literal("more")
            .requires { it.sender.hasPermission("obsidian.command.more") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeMore(player, null)
            }.then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400)).executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeMore(player, IntegerArgumentType.getInteger(ctx, "amount"))
            }).build()
        registrar.register(context.plugin.pluginMeta, moreNode, "Refill held item stack", emptyList())

        // /condense [hand|all]
        val condenseNode = Commands.literal("condense")
            .requires { it.sender.hasPermission("obsidian.command.condense") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeCondense(player, handOnly = false)
            }.then(Commands.argument("scope", StringArgumentType.word()).suggests { _, b ->
                listOf("all", "hand").filter { it.startsWith(b.remaining, true) }.forEach { b.suggest(it) }
                b.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val scope = StringArgumentType.getString(ctx, "scope")
                executeCondense(player, handOnly = scope.equals("hand", ignoreCase = true))
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            condenseNode,
            "Condense items into blocks",
            if (context.config.registerStandaloneAliases) listOf("compact") else emptyList(),
        )

        // /uncondense [hand|all]
        val uncondenseNode = Commands.literal("uncondense")
            .requires { it.sender.hasPermission("obsidian.command.uncondense") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeUncondense(player, handOnly = false)
            }.then(Commands.argument("scope", StringArgumentType.word()).suggests { _, b ->
                listOf("all", "hand").filter { it.startsWith(b.remaining, true) }.forEach { b.suggest(it) }
                b.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val scope = StringArgumentType.getString(ctx, "scope")
                executeUncondense(player, handOnly = scope.equals("hand", ignoreCase = true))
            }).build()
        registrar.register(context.plugin.pluginMeta, uncondenseNode, "Uncondense blocks into items", emptyList())

        // /itemname <name|clear>
        val itemNameNode = Commands.literal("itemname")
            .requires { it.sender.hasPermission("obsidian.command.itemname") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("name", StringArgumentType.greedyString()).executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val hand = requireHeldItem(player) ?: return@executes 0
                val raw = StringArgumentType.getString(ctx, "name")
                val meta = hand.itemMeta ?: return@executes 0
                if (raw.equals("clear", ignoreCase = true) || raw.equals("reset", ignoreCase = true)) {
                    meta.displayName(null)
                    hand.itemMeta = meta
                    context.locale.send(player, "utility.itemname-cleared")
                } else {
                    meta.displayName(TextFormatter.format(raw))
                    hand.itemMeta = meta
                    context.locale.send(player, "utility.itemname-set", "name" to raw)
                }
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            itemNameNode,
            "Rename held item",
            if (context.config.registerStandaloneAliases) listOf("rename") else emptyList(),
        )

        // /itemlore <add|set|remove|clear> ...
        val itemLoreNode = Commands.literal("itemlore")
            .requires { it.sender.hasPermission("obsidian.command.itemlore") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.literal("clear").executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val hand = requireHeldItem(player) ?: return@executes 0
                val meta = hand.itemMeta ?: return@executes 0
                meta.lore(null)
                hand.itemMeta = meta
                context.locale.send(player, "utility.itemlore-cleared")
                Command.SINGLE_SUCCESS
            })
            .then(
                Commands.literal("add")
                    .then(Commands.argument("text", StringArgumentType.greedyString()).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val hand = requireHeldItem(player) ?: return@executes 0
                        val meta = hand.itemMeta ?: return@executes 0
                        val lore = meta.lore()?.toMutableList() ?: mutableListOf()
                        lore += TextFormatter.format(StringArgumentType.getString(ctx, "text"))
                        meta.lore(lore)
                        hand.itemMeta = meta
                        context.locale.send(player, "utility.itemlore-updated")
                        Command.SINGLE_SUCCESS
                    })
            )
            .then(
                Commands.literal("set").then(
                    Commands.argument("line", IntegerArgumentType.integer(1, 50))
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes { ctx ->
                            val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                            val hand = requireHeldItem(player) ?: return@executes 0
                            val meta = hand.itemMeta ?: return@executes 0
                            val lineIdx = IntegerArgumentType.getInteger(ctx, "line") - 1
                            val lore = meta.lore()?.toMutableList() ?: mutableListOf()
                            while (lore.size <= lineIdx) lore += net.kyori.adventure.text.Component.empty()
                            lore[lineIdx] = TextFormatter.format(StringArgumentType.getString(ctx, "text"))
                            meta.lore(lore)
                            hand.itemMeta = meta
                            context.locale.send(player, "utility.itemlore-updated")
                            Command.SINGLE_SUCCESS
                        })
                )
            )
            .then(
                Commands.literal("remove")
                    .then(Commands.argument("line", IntegerArgumentType.integer(1, 50)).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val hand = requireHeldItem(player) ?: return@executes 0
                        val meta = hand.itemMeta ?: return@executes 0
                        val lineIdx = IntegerArgumentType.getInteger(ctx, "line") - 1
                        val lore = meta.lore()?.toMutableList() ?: mutableListOf()
                        if (lineIdx in lore.indices) {
                            lore.removeAt(lineIdx)
                            meta.lore(if (lore.isEmpty()) null else lore)
                            hand.itemMeta = meta
                        }
                        context.locale.send(player, "utility.itemlore-updated")
                        Command.SINGLE_SUCCESS
                    })
            )
            .build()
        registrar.register(context.plugin.pluginMeta, itemLoreNode, "Edit held item lore", emptyList())

        // /hideflags [all|clear|<flag>]
        val hideFlagsNode = Commands.literal("hideflags")
            .requires { it.sender.hasPermission("obsidian.command.hideflags") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeHideFlags(player, "all")
            }.then(Commands.argument("flag", StringArgumentType.word()).suggests { _, b ->
                (listOf("all", "clear") + ItemFlag.entries.map { it.name.lowercase() })
                    .filter { it.startsWith(b.remaining, true) }
                    .forEach { b.suggest(it) }
                b.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeHideFlags(player, StringArgumentType.getString(ctx, "flag"))
            }).build()
        registrar.register(context.plugin.pluginMeta, hideFlagsNode, "Toggle item flags on held item", emptyList())

        // /unbreakable [true|false]
        val unbreakableNode = Commands.literal("unbreakable")
            .requires { it.sender.hasPermission("obsidian.command.unbreakable") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                executeUnbreakable(player, null)
            }.then(Commands.argument("state", StringArgumentType.word()).suggests { _, b ->
                listOf("true", "false").filter { it.startsWith(b.remaining, true) }.forEach { b.suggest(it) }
                b.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val arg = StringArgumentType.getString(ctx, "state")
                val state = arg.equals("true", true) || arg.equals("on", true)
                executeUnbreakable(player, state)
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            unbreakableNode,
            "Toggle unbreakable tag on held item",
            emptyList()
        )

        // /dye <color|hex|clear>
        val dyeNode = Commands.literal("dye")
            .requires { it.sender.hasPermission("obsidian.command.dye") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("color", StringArgumentType.word()).suggests { _, b ->
                listOf(
                    "clear",
                    "red",
                    "blue",
                    "green",
                    "yellow",
                    "orange",
                    "purple",
                    "pink",
                    "white",
                    "black",
                    "#FF5500"
                )
                    .filter { it.startsWith(b.remaining, true) }.forEach { b.suggest(it) }
                b.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val hand = requireHeldItem(player) ?: return@executes 0
                val meta = hand.itemMeta as? LeatherArmorMeta ?: run {
                    context.locale.send(player, "utility.dye-invalid")
                    return@executes 0
                }
                val raw = StringArgumentType.getString(ctx, "color")
                if (raw.equals("clear", true) || raw.equals("reset", true)) {
                    meta.setColor(null)
                    hand.itemMeta = meta
                    context.locale.send(player, "utility.dye-cleared")
                    return@executes Command.SINGLE_SUCCESS
                }
                val color =
                    UtilityHelpers.parseColor(raw) ?: return@executes sendUsage(player, "/dye <color|#RRGGBB|clear>")
                meta.setColor(color)
                hand.itemMeta = meta
                context.locale.send(player, "utility.dye-set", "color" to raw)
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, dyeNode, "Dye held leather armor", emptyList())

        // /head [player]
        val headNode = Commands.literal("head")
            .requires { it.sender.hasPermission("obsidian.command.head") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                givePlayerHead(player, player.name)
            }.then(Commands.argument("owner", StringArgumentType.word()).suggests { _, b ->
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(b.remaining, true) }
                    .forEach { b.suggest(it) }
                b.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                givePlayerHead(player, StringArgumentType.getString(ctx, "owner"))
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            headNode,
            "Get a player head",
            if (context.config.registerStandaloneAliases) listOf("skull") else emptyList(),
        )
    }

    private fun requireHeldItem(player: Player): ItemStack? {
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir) {
            context.locale.send(player, "utility.hold-item")
            return null
        }
        return hand
    }

    private fun executeMore(player: Player, amount: Int?): Int {
        val hand = requireHeldItem(player) ?: return 0
        if (amount == null) {
            hand.amount = hand.maxStackSize
            context.locale.send(player, "utility.more-filled", "amount" to hand.maxStackSize)
        } else {
            val max = hand.maxStackSize
            val firstFill = minOf(amount, max)
            hand.amount = firstFill
            var remaining = amount - firstFill
            while (remaining > 0) {
                val add = minOf(remaining, max)
                val stack = hand.clone().apply { this.amount = add }
                player.inventory.addItem(stack)
                remaining -= add
            }
            context.locale.send(player, "utility.more-filled", "amount" to amount)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeCondense(player: Player, handOnly: Boolean): Int {
        val (inputCount, outputCount) = UtilityHelpers.condenseInventory(player.inventory, handOnly)
        if (outputCount <= 0) {
            context.locale.send(player, "utility.condense-nothing")
        } else {
            context.locale.send(player, "utility.condense-done", "items" to inputCount, "blocks" to outputCount)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeUncondense(player: Player, handOnly: Boolean): Int {
        val (blockCount, itemCount) = UtilityHelpers.uncondenseInventory(player.inventory, handOnly)
        if (itemCount <= 0) {
            context.locale.send(player, "utility.uncondense-nothing")
        } else {
            context.locale.send(player, "utility.uncondense-done", "blocks" to blockCount, "items" to itemCount)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeHideFlags(player: Player, arg: String): Int {
        val hand = requireHeldItem(player) ?: return 0
        val meta = hand.itemMeta ?: return 0
        when (arg.lowercase()) {
            "all" -> meta.addItemFlags(*ItemFlag.entries.toTypedArray())
            "clear", "none" -> meta.removeItemFlags(*ItemFlag.entries.toTypedArray())
            else -> {
                val flag = runCatching { ItemFlag.valueOf(arg.uppercase()) }.getOrNull()
                    ?: return sendUsage(player, "/hideflags [all|clear|<ItemFlag>]")
                if (meta.hasItemFlag(flag)) meta.removeItemFlags(flag) else meta.addItemFlags(flag)
            }
        }
        hand.itemMeta = meta
        context.locale.send(player, "utility.hideflags-updated", "flag" to arg)
        return Command.SINGLE_SUCCESS
    }

    private fun executeUnbreakable(player: Player, explicit: Boolean?): Int {
        val hand = requireHeldItem(player) ?: return 0
        val meta = hand.itemMeta ?: return 0
        val next = explicit ?: !meta.isUnbreakable
        meta.isUnbreakable = next
        hand.itemMeta = meta
        context.locale.send(player, "utility.unbreakable-set", "state" to if (next) "on" else "off")
        return Command.SINGLE_SUCCESS
    }

    private fun givePlayerHead(player: Player, ownerName: String): Int {
        val head = ItemStack(Material.PLAYER_HEAD)
        val meta = head.itemMeta as? SkullMeta
        if (meta != null) {
            val target = Bukkit.getOfflinePlayer(ownerName)
            meta.owningPlayer = target
            head.itemMeta = meta
        }
        player.inventory.addItem(head)
        context.locale.send(player, "utility.head-given", "owner" to ownerName)
        return Command.SINGLE_SUCCESS
    }

    private fun registerFlightAndQolCommands(registrar: Commands) {
        // /tfly [player] [duration|0|off]
        val tflyNode = Commands.literal("tfly")
            .requires { it.sender.hasPermission("obsidian.command.tfly") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val rem = flightAndCombatService.getTempFlyRemainingMillis(player.uniqueId)
                when {
                    rem == null -> context.locale.send(player, "utility.tfly-status-off", "target" to player.name)
                    rem < 0L -> context.locale.send(player, "utility.tfly-status-relog", "target" to player.name)
                    else -> context.locale.send(
                        player,
                        "utility.tfly-status",
                        "target" to player.name,
                        "time" to TextFormatter.formatDuration(rem)
                    )
                }
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.tfly.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val rem = flightAndCombatService.getTempFlyRemainingMillis(target.uniqueId)
                        when {
                            rem == null -> context.locale.send(
                                ctx.source.sender,
                                "utility.tfly-status-off",
                                "target" to target.name
                            )

                            rem < 0L -> context.locale.send(
                                ctx.source.sender,
                                "utility.tfly-status-relog",
                                "target" to target.name
                            )

                            else -> context.locale.send(
                                ctx.source.sender,
                                "utility.tfly-status",
                                "target" to target.name,
                                "time" to TextFormatter.formatDuration(rem)
                            )
                        }
                        Command.SINGLE_SUCCESS
                    }
                    .then(Commands.argument("duration", StringArgumentType.word()).suggests { _, b ->
                        listOf("30s", "5m", "30m", "1h", "0", "off").filter { it.startsWith(b.remaining, true) }
                            .forEach { b.suggest(it) }
                        b.buildFuture()
                    }.executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val raw = StringArgumentType.getString(ctx, "duration")
                        applyTempFly(ctx.source.sender, target, raw)
                    })
            )
            .build()
        registrar.register(context.plugin.pluginMeta, tflyNode, "Grant or check temporary flight", emptyList())

        // /tgod [player] [duration|0|off]
        val tgodNode = Commands.literal("tgod")
            .requires { it.sender.hasPermission("obsidian.command.tgod") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val rem = flightAndCombatService.getTempGodRemainingMillis(player.uniqueId)
                when {
                    rem == null -> context.locale.send(player, "utility.tgod-status-off", "target" to player.name)
                    rem < 0L -> context.locale.send(player, "utility.tgod-status-relog", "target" to player.name)
                    else -> context.locale.send(
                        player,
                        "utility.tgod-status",
                        "target" to player.name,
                        "time" to TextFormatter.formatDuration(rem)
                    )
                }
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.tgod.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val rem = flightAndCombatService.getTempGodRemainingMillis(target.uniqueId)
                        when {
                            rem == null -> context.locale.send(
                                ctx.source.sender,
                                "utility.tgod-status-off",
                                "target" to target.name
                            )

                            rem < 0L -> context.locale.send(
                                ctx.source.sender,
                                "utility.tgod-status-relog",
                                "target" to target.name
                            )

                            else -> context.locale.send(
                                ctx.source.sender,
                                "utility.tgod-status",
                                "target" to target.name,
                                "time" to TextFormatter.formatDuration(rem)
                            )
                        }
                        Command.SINGLE_SUCCESS
                    }
                    .then(Commands.argument("duration", StringArgumentType.word()).suggests { _, b ->
                        listOf("30s", "5m", "30m", "1h", "0", "off").filter { it.startsWith(b.remaining, true) }
                            .forEach { b.suggest(it) }
                        b.buildFuture()
                    }.executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val raw = StringArgumentType.getString(ctx, "duration")
                        applyTempGod(ctx.source.sender, target, raw)
                    })
            )
            .build()
        registrar.register(context.plugin.pluginMeta, tgodNode, "Grant or check temporary god mode", emptyList())

        // /flyc (toggle flight charge mode)
        val flycNode = Commands.literal("flyc")
            .requires { it.sender.hasPermission("obsidian.command.flyc") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                if (flightAndCombatService.isFlightChargeMode(player.uniqueId)) {
                    flightAndCombatService.toggleFlightChargeMode(player)
                    context.locale.send(
                        player,
                        "utility.flyc-toggled",
                        "state" to "off",
                        "charge" to flightAndCombatService.getFlightCharge(player).toInt()
                    )
                } else {
                    val enabled = flightAndCombatService.toggleFlightChargeMode(player)
                    if (!enabled) {
                        context.locale.send(player, "utility.flightcharge-empty")
                    } else {
                        context.locale.send(
                            player,
                            "utility.flyc-toggled",
                            "state" to "on",
                            "charge" to flightAndCombatService.getFlightCharge(player).toInt()
                        )
                    }
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, flycNode, "Toggle charge-based flight", emptyList())

        // /flightcharge <check|add|set> [player] [amount]
        val fcNode = Commands.literal("flightcharge")
            .requires { it.sender.hasPermission("obsidian.command.flightcharge") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                context.locale.send(
                    player,
                    "utility.flightcharge-balance",
                    "target" to player.name,
                    "amount" to flightAndCombatService.getFlightCharge(player).toInt()
                )
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.literal("add")
                    .requires {
                        it.sender.hasPermission("obsidian.command.flightcharge.admin") || it.sender.hasPermission(
                            "obsidian.admin"
                        )
                    }
                    .then(
                        Commands.argument("target", ArgumentTypes.player())
                            .then(Commands.argument("amount", DoubleArgumentType.doubleArg(1.0)).executes { ctx ->
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                val add = DoubleArgumentType.getDouble(ctx, "amount")
                                val next = flightAndCombatService.getFlightCharge(target) + add
                                flightAndCombatService.setFlightCharge(target, next)
                                context.locale.send(
                                    ctx.source.sender,
                                    "utility.flightcharge-balance",
                                    "target" to target.name,
                                    "amount" to next.toInt()
                                )
                                Command.SINGLE_SUCCESS
                            })
                    )
            )
            .then(
                Commands.literal("set")
                    .requires {
                        it.sender.hasPermission("obsidian.command.flightcharge.admin") || it.sender.hasPermission(
                            "obsidian.admin"
                        )
                    }
                    .then(
                        Commands.argument("target", ArgumentTypes.player())
                            .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0)).executes { ctx ->
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                val next = DoubleArgumentType.getDouble(ctx, "amount")
                                flightAndCombatService.setFlightCharge(target, next)
                                context.locale.send(
                                    ctx.source.sender,
                                    "utility.flightcharge-balance",
                                    "target" to target.name,
                                    "amount" to next.toInt()
                                )
                                Command.SINGLE_SUCCESS
                            })
                    )
            )
            .build()
        registrar.register(context.plugin.pluginMeta, fcNode, "Manage flight charge seconds", emptyList())

        // /autorecharge
        val autoRechargeNode = Commands.literal("autorecharge")
            .requires { it.sender.hasPermission("obsidian.command.autorecharge") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val next = !flightAndCombatService.isAutoRecharge(player)
                flightAndCombatService.setAutoRecharge(player, next)
                context.locale.send(player, "utility.autorecharge-toggled", "state" to if (next) "on" else "off")
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, autoRechargeNode, "Toggle flight auto-recharge", emptyList())

        // /options
        val optionsNode = Commands.literal("options")
            .requires { it.sender.hasPermission("obsidian.command.options") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                flightAndCombatService.openOptionsGui(player)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, optionsNode, "Open personal options GUI", emptyList())
    }

    private fun applyTempFly(sender: CommandSender, target: Player, raw: String): Int {
        if (raw.equals("off", true) || raw.equals("clear", true)) {
            flightAndCombatService.setTempFly(target, null)
            context.locale.send(sender, "utility.tfly-disabled", "target" to target.name)
            return Command.SINGLE_SUCCESS
        }
        if (raw == "0" || raw.equals("relog", true)) {
            flightAndCombatService.setTempFly(target, 0L)
            context.locale.send(sender, "utility.tfly-set-relog", "target" to target.name)
            return Command.SINGLE_SUCCESS
        }
        val millis = DurationParser.parseMillis(raw) ?: return sendUsage(sender, "/tfly <player> <duration|0|off>")
        flightAndCombatService.setTempFly(target, millis)
        context.locale.send(
            sender,
            "utility.tfly-set",
            "target" to target.name,
            "time" to TextFormatter.formatDuration(millis)
        )
        return Command.SINGLE_SUCCESS
    }

    private fun applyTempGod(sender: CommandSender, target: Player, raw: String): Int {
        if (raw.equals("off", true) || raw.equals("clear", true)) {
            flightAndCombatService.setTempGod(target, null)
            context.locale.send(sender, "utility.tgod-disabled", "target" to target.name)
            return Command.SINGLE_SUCCESS
        }
        if (raw == "0" || raw.equals("relog", true)) {
            flightAndCombatService.setTempGod(target, 0L)
            context.locale.send(sender, "utility.tgod-set-relog", "target" to target.name)
            return Command.SINGLE_SUCCESS
        }
        val millis = DurationParser.parseMillis(raw) ?: return sendUsage(sender, "/tgod <player> <duration|0|off>")
        flightAndCombatService.setTempGod(target, millis)
        context.locale.send(
            sender,
            "utility.tgod-set",
            "target" to target.name,
            "time" to TextFormatter.formatDuration(millis)
        )
        return Command.SINGLE_SUCCESS
    }

    private fun registerTargetPlayerCommand(
        registrar: Commands,
        name: String,
        description: String,
        permission: String,
        aliases: List<String> = emptyList(),
        checkCooldown: Boolean = false,
        action: (CommandSender, Player) -> Unit,
    ) {
        val node = Commands.literal(name)
            .requires { it.sender.hasPermission(permission) || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                if (checkCooldown && !CommandHelper.checkCooldown(context, ctx.source.sender, name)) return@executes 0
                action(ctx.source.sender, player)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("$permission.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        if (checkCooldown && !CommandHelper.checkCooldown(
                                context, ctx.source.sender, name
                            )
                        ) return@executes 0
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        action(ctx.source.sender, target)
                        Command.SINGLE_SUCCESS
                    }).build()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            description,
            if (context.config.registerStandaloneAliases) aliases else emptyList(),
        )
    }

    private fun registerSavedItems(registrar: Commands) {
        // /saveditems <save|get|give|list|remove>
        val node = Commands.literal("saveditems")
            .requires { it.sender.hasPermission("obsidian.command.saveditems") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.literal("save").then(Commands.argument("id", StringArgumentType.word()).executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val hand = requireHeldItem(player) ?: return@executes 0
                val id = StringArgumentType.getString(ctx, "id")
                savedItemService.saveItem(id, hand)
                context.locale.send(player, "utility.saveditem-saved", "id" to id)
                Command.SINGLE_SUCCESS
            }))
            .then(Commands.literal("get").then(Commands.argument("id", StringArgumentType.word()).suggests { _, b ->
                savedItemService.listIds().filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val id = StringArgumentType.getString(ctx, "id")
                val item = savedItemService.getItem(id) ?: run {
                    context.locale.send(player, "utility.saveditem-not-found", "id" to id)
                    return@executes 0
                }
                player.inventory.addItem(item)
                context.locale.send(player, "utility.saveditem-got", "id" to id)
                Command.SINGLE_SUCCESS
            }))
            .then(
                Commands.literal("give").then(
                    Commands.argument("target", ArgumentTypes.player()).then(
                        Commands.argument("id", StringArgumentType.word()).suggests { _, b ->
                            savedItemService.listIds().filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                            b.buildFuture()
                        }.executes { ctx ->
                            val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                .resolve(ctx.source).firstOrNull() ?: return@executes 0
                            val id = StringArgumentType.getString(ctx, "id")
                            if (savedItemService.giveItem(target, id, 1)) {
                                context.locale.send(
                                    ctx.source.sender,
                                    "utility.saveditem-given",
                                    "id" to id,
                                    "target" to target.name,
                                    "amount" to 1
                                )
                                Command.SINGLE_SUCCESS
                            } else {
                                context.locale.send(ctx.source.sender, "utility.saveditem-not-found", "id" to id)
                                0
                            }
                        }.then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400)).executes { ctx ->
                            val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                .resolve(ctx.source).firstOrNull() ?: return@executes 0
                            val id = StringArgumentType.getString(ctx, "id")
                            val amount = IntegerArgumentType.getInteger(ctx, "amount")
                            if (savedItemService.giveItem(target, id, amount)) {
                                context.locale.send(
                                    ctx.source.sender,
                                    "utility.saveditem-given",
                                    "id" to id,
                                    "target" to target.name,
                                    "amount" to amount
                                )
                                Command.SINGLE_SUCCESS
                            } else {
                                context.locale.send(ctx.source.sender, "utility.saveditem-not-found", "id" to id)
                                0
                            }
                        })
                    )
                )
            )
            .then(Commands.literal("list").executes { ctx ->
                val ids = savedItemService.listIds()
                if (ids.isEmpty()) {
                    context.locale.send(ctx.source.sender, "utility.saveditem-empty")
                } else {
                    context.locale.send(ctx.source.sender, "utility.saveditem-list", "items" to ids.joinToString(", "))
                }
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).suggests { _, b ->
                savedItemService.listIds().filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val id = StringArgumentType.getString(ctx, "id")
                if (savedItemService.removeItem(id)) {
                    context.locale.send(ctx.source.sender, "utility.saveditem-removed", "id" to id)
                    Command.SINGLE_SUCCESS
                } else {
                    context.locale.send(ctx.source.sender, "utility.saveditem-not-found", "id" to id)
                    0
                }
            }))
            .build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Manage custom saved items",
            if (context.config.registerStandaloneAliases) listOf("saveditem", "si") else emptyList(),
        )
    }

    private fun registerCustomRecipe(registrar: Commands) {
        val node = Commands.literal("customrecipe")
            .requires { it.sender.hasPermission("obsidian.command.customrecipe") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.literal("list").executes { ctx ->
                val list = customRecipeService.listRecipes()
                if (list.isEmpty()) {
                    context.locale.send(ctx.source.sender, "utility.customrecipe-empty")
                } else {
                    context.locale.send(ctx.source.sender, "utility.customrecipe-header", "count" to list.size)
                    for (item in list) {
                        ctx.source.sender.sendMessage(
                            TextFormatter.format("<gray>-</gray> <aqua>${item.id}</aqua> <dark_gray>(</dark_gray><yellow>${item.type}</yellow><dark_gray>)</dark_gray><gray>: <white>${item.amount}x ${item.result.name}</white>")
                        )
                    }
                }
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("delete").then(Commands.argument("id", StringArgumentType.word()).suggests { _, b ->
                customRecipeService.listRecipes().map { it.id }.filter { it.startsWith(b.remaining, true) }
                    .forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val id = StringArgumentType.getString(ctx, "id")
                if (customRecipeService.deleteRecipe(id)) {
                    context.locale.send(ctx.source.sender, "utility.customrecipe-deleted", "id" to id)
                    Command.SINGLE_SUCCESS
                } else {
                    context.locale.send(ctx.source.sender, "utility.customrecipe-not-found", "id" to id)
                    0
                }
            }))
            .build()

        registrar.register(context.plugin.pluginMeta, node, "List or manage custom crafting recipes", emptyList())
    }

    private fun registerScavenger(registrar: Commands) {
        val node = Commands.literal("scavenge")
            .requires { it.sender.hasPermission("obsidian.command.scavenge") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val hand = requireHeldItem(player) ?: return@executes 0
                val outcome = scavengerService.scavenge(player, hand)
                if (outcome == null) {
                    context.locale.send(player, "utility.scavenge-invalid")
                    return@executes 0
                }
                context.locale.send(
                    player,
                    "utility.scavenge-done",
                    "books" to outcome.bookCount,
                    "items" to outcome.returnItems.size,
                )
                Command.SINGLE_SUCCESS
            }.build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Recycle enchanted gear into books and components",
            if (context.config.registerStandaloneAliases) listOf("salvage", "recycle") else emptyList(),
        )
    }

    private fun registerInfoCommands(registrar: Commands) {
        // /iteminfo
        val itemInfoNode = Commands.literal("iteminfo")
            .requires { it.sender.hasPermission("obsidian.command.iteminfo") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val hand = requireHeldItem(player) ?: return@executes 0
                val meta = hand.itemMeta
                val damageMeta = meta as? org.bukkit.inventory.meta.Damageable
                val customModel = meta?.customModelData ?: 0
                val enchants = hand.enchantments.map { "${it.key.key.key}:${it.value}" }.ifEmpty { listOf("none") }
                    .joinToString(", ")
                context.locale.send(
                    player,
                    "utility.iteminfo-header",
                    "type" to hand.type.name,
                    "amount" to hand.amount,
                    "durability" to (damageMeta?.damage ?: 0),
                    "maxdurability" to hand.type.maxDurability,
                    "custommodel" to customModel,
                    "enchants" to enchants,
                )
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, itemInfoNode, "Show held item detailed metadata", emptyList())

        // /blockinfo
        val blockInfoNode = Commands.literal("blockinfo")
            .requires { it.sender.hasPermission("obsidian.command.blockinfo") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val block = player.getTargetBlockExact(8) ?: run {
                    context.locale.send(player, "utility.blockinfo-none")
                    return@executes 0
                }
                context.locale.send(
                    player,
                    "utility.blockinfo-header",
                    "type" to block.type.name,
                    "x" to block.x,
                    "y" to block.y,
                    "z" to block.z,
                    "light" to block.lightLevel,
                    "biome" to block.biome.key.key,
                )
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, blockInfoNode, "Show targeted block information", emptyList())

        // /entityinfo
        val entityInfoNode = Commands.literal("entityinfo")
            .requires { it.sender.hasPermission("obsidian.command.entityinfo") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val target = player.getTargetEntity(10) ?: run {
                    context.locale.send(player, "utility.entityinfo-none")
                    return@executes 0
                }
                val living = target as? org.bukkit.entity.LivingEntity
                val hp = living?.let { "%.1f".format(it.health) } ?: "N/A"
                val maxHp = living?.getAttribute(Attribute.MAX_HEALTH)?.value?.let { "%.1f".format(it) } ?: "N/A"
                context.locale.send(
                    player,
                    "utility.entityinfo-header",
                    "type" to target.type.name,
                    "name" to target.name,
                    "uuid" to target.uniqueId.toString(),
                    "hp" to "$hp/$maxHp",
                )
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, entityInfoNode, "Show targeted entity information", emptyList())
    }

    private fun registerAdminUtils(registrar: Commands) {
        // /smite [player]
        val smiteNode = Commands.literal("smite")
            .requires { it.sender.hasPermission("obsidian.command.smite") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val loc = player.getTargetBlockExact(30)?.location ?: player.location
                context.scheduler.runAtLocation(loc) {
                    loc.world?.strikeLightning(loc)
                }
                context.locale.send(player, "utility.smite-done", "target" to "target location")
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                    .firstOrNull() ?: return@executes 0
                context.scheduler.runForEntity(target) {
                    target.world.strikeLightning(target.location)
                }
                context.locale.send(ctx.source.sender, "utility.smite-done", "target" to target.name)
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            smiteNode,
            "Strike lightning at target player or block",
            emptyList()
        )

        // /burn <target> [seconds]
        val burnNode = Commands.literal("burn")
            .requires { it.sender.hasPermission("obsidian.command.burn") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                    .firstOrNull() ?: return@executes 0
                context.scheduler.runForEntity(target) { target.fireTicks = 100 }
                context.locale.send(ctx.source.sender, "utility.burn-done", "target" to target.name, "seconds" to 5)
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300)).executes { ctx ->
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                    .firstOrNull() ?: return@executes 0
                val sec = IntegerArgumentType.getInteger(ctx, "seconds")
                context.scheduler.runForEntity(target) { target.fireTicks = sec * 20 }
                context.locale.send(ctx.source.sender, "utility.burn-done", "target" to target.name, "seconds" to sec)
                Command.SINGLE_SUCCESS
            })).build()
        registrar.register(context.plugin.pluginMeta, burnNode, "Set target player on fire", emptyList())

        // /ext [player]
        val extNode = Commands.literal("ext")
            .requires { it.sender.hasPermission("obsidian.command.ext") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                context.scheduler.runForEntity(player) { player.fireTicks = 0 }
                context.locale.send(player, "utility.ext-done", "target" to player.name)
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                    .firstOrNull() ?: return@executes 0
                context.scheduler.runForEntity(target) { target.fireTicks = 0 }
                context.locale.send(ctx.source.sender, "utility.ext-done", "target" to target.name)
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, extNode, "Extinguish fire on target player", emptyList())

        // /launch <target> [power]
        val launchNode = Commands.literal("launch")
            .requires { it.sender.hasPermission("obsidian.command.launch") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                    .firstOrNull() ?: return@executes 0
                context.scheduler.runForEntity(target) {
                    target.velocity = org.bukkit.util.Vector(0.0, 2.0, 0.0)
                }
                context.locale.send(ctx.source.sender, "utility.launch-done", "target" to target.name)
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("power", DoubleArgumentType.doubleArg(0.5, 10.0)).executes { ctx ->
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                    .firstOrNull() ?: return@executes 0
                val power = DoubleArgumentType.getDouble(ctx, "power")
                context.scheduler.runForEntity(target) {
                    target.velocity = org.bukkit.util.Vector(0.0, power, 0.0)
                }
                context.locale.send(ctx.source.sender, "utility.launch-done", "target" to target.name)
                Command.SINGLE_SUCCESS
            })).build()
        registrar.register(context.plugin.pluginMeta, launchNode, "Launch player upwards", emptyList())

        // /saturation <target> <amount>
        val satNode = Commands.literal("saturation")
            .requires { it.sender.hasPermission("obsidian.command.saturation") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("amount", DoubleArgumentType.doubleArg(0.0, 20.0)).executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val amount = DoubleArgumentType.getDouble(ctx, "amount").toFloat()
                        context.scheduler.runForEntity(target) { target.saturation = amount }
                        context.locale.send(
                            ctx.source.sender,
                            "utility.saturation-set",
                            "target" to target.name,
                            "amount" to amount
                        )
                        Command.SINGLE_SUCCESS
                    }
                )).build()
        registrar.register(context.plugin.pluginMeta, satNode, "Set player food saturation", emptyList())

        // /hunger <target> <amount>
        val hungerNode = Commands.literal("hunger")
            .requires { it.sender.hasPermission("obsidian.command.hunger") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("amount", IntegerArgumentType.integer(0, 20)).executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                        context.scheduler.runForEntity(target) { target.foodLevel = amount }
                        context.locale.send(
                            ctx.source.sender,
                            "utility.hunger-set",
                            "target" to target.name,
                            "amount" to amount
                        )
                        Command.SINGLE_SUCCESS
                    }
                )).build()
        registrar.register(context.plugin.pluginMeta, hungerNode, "Set player hunger level", emptyList())

        // /exp <add|set|check> ...
        val expNode = Commands.literal("exp")
            .requires { it.sender.hasPermission("obsidian.command.exp") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.literal("check").then(Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                    .firstOrNull() ?: return@executes 0
                context.locale.send(
                    ctx.source.sender,
                    "utility.exp-check",
                    "target" to target.name,
                    "level" to target.level,
                    "total" to target.totalExperience
                )
                Command.SINGLE_SUCCESS
            }))
            .then(
                Commands.literal("add").then(
                    Commands.argument("target", ArgumentTypes.player()).then(
                        Commands.argument("amount", IntegerArgumentType.integer(1, 100000)).executes { ctx ->
                            val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                .resolve(ctx.source).firstOrNull() ?: return@executes 0
                            val amount = IntegerArgumentType.getInteger(ctx, "amount")
                            context.scheduler.runForEntity(target) { target.giveExp(amount) }
                            context.locale.send(
                                ctx.source.sender,
                                "utility.exp-added",
                                "target" to target.name,
                                "amount" to amount
                            )
                            Command.SINGLE_SUCCESS
                        }
                    )))
            .then(
                Commands.literal("set").then(
                    Commands.argument("target", ArgumentTypes.player()).then(
                        Commands.argument("level", IntegerArgumentType.integer(0, 10000)).executes { ctx ->
                            val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                .resolve(ctx.source).firstOrNull() ?: return@executes 0
                            val level = IntegerArgumentType.getInteger(ctx, "level")
                            context.scheduler.runForEntity(target) { target.level = level }
                            context.locale.send(
                                ctx.source.sender,
                                "utility.exp-set",
                                "target" to target.name,
                                "level" to level
                            )
                            Command.SINGLE_SUCCESS
                        }
                    )))
            .build()
        registrar.register(context.plugin.pluginMeta, expNode, "Check or modify player experience", emptyList())

        // /maxhp <target> <amount>
        val maxHpNode = Commands.literal("maxhp")
            .requires { it.sender.hasPermission("obsidian.command.maxhp") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("amount", DoubleArgumentType.doubleArg(1.0, 2048.0)).executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val amount = DoubleArgumentType.getDouble(ctx, "amount")
                        context.scheduler.runForEntity(target) {
                            target.getAttribute(Attribute.MAX_HEALTH)?.baseValue = amount
                            if (target.health > amount) target.health = amount
                        }
                        context.locale.send(
                            ctx.source.sender,
                            "utility.maxhp-set",
                            "target" to target.name,
                            "amount" to "%.1f".format(amount)
                        )
                        Command.SINGLE_SUCCESS
                    }
                )).build()
        registrar.register(context.plugin.pluginMeta, maxHpNode, "Set player maximum health attribute", emptyList())
    }

    private fun sendUsage(sender: CommandSender, usage: String): Int {
        context.locale.send(sender, "general.invalid-usage", "usage" to usage)
        return 0
    }
}
