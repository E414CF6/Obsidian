package obsidian.modules.utility

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.DurationParser
import obsidian.core.text.TextFormatter
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.potion.PotionEffect
import java.net.URI

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
    val combatTagService = CombatTagService(context)
    val anvilAndEnchantService = AnvilAndEnchantService(context)
    val savedItemService = SavedItemService(context)
    val customRecipeService = CustomRecipeService(context)
    val scavengerService = ScavengerService(context)
    val serverOpsService = ServerOpsService(context)

    override fun listeners(): List<Listener> = listOf(
        godListener,
        shulkerBoxService,
        flightAndCombatService,
        combatTagService,
        anvilAndEnchantService,
        serverOpsService,
    )

    override fun onEnable() {
        context.registerService(savedItemService)
        context.registerService(customRecipeService)
        context.registerService(scavengerService)
        context.registerService(combatTagService)
        context.registerService(anvilAndEnchantService)
        context.registerService(serverOpsService)
        flightAndCombatService.start()
        combatTagService.start()
    }

    override fun onDisable() {
        combatTagService.stop()
        flightAndCombatService.stop()
        shulkerBoxService.closeAll()
        customRecipeService.unregisterAll()
    }

    override fun onReload() {
        savedItemService.load()
        customRecipeService.reload()
        combatTagService.reload()
        anvilAndEnchantService.reload()
        serverOpsService.reload()
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
        registerTier2Commands(registrar)
        registerTier3Commands(registrar)
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
            listOf("gamemode"),
        )

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
            listOf("enderchest", "ec"),
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
            listOf("i"),
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
            listOf("trash"),
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
            listOf("compact"),
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
            listOf("rename"),
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
            listOf("skull"),
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
            aliases,
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
            listOf("saveditem", "si"),
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
                        context.locale.sendRaw(
                            ctx.source.sender,
                            "utility.customrecipe-entry",
                            "id" to item.id,
                            "type" to item.type,
                            "amount" to item.amount,
                            "result" to item.result.name,
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
            listOf("salvage", "recycle"),
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

    private fun registerTier2Commands(registrar: Commands) {
        // /mobhead <entity|player> [amount]
        val mobHeadNode = Commands.literal("mobhead")
            .requires { it.sender.hasPermission("obsidian.command.mobhead") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { _, b ->
                    listOf("SKELETON", "WITHER_SKELETON", "ZOMBIE", "CREEPER", "ENDER_DRAGON", "PIGLIN")
                        .filter { it.startsWith(b.remaining, true) }
                        .forEach(b::suggest)
                    Bukkit.getOnlinePlayers().map { it.name }
                        .filter { it.startsWith(b.remaining, true) }
                        .forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    giveMobOrPlayerHead(player, StringArgumentType.getString(ctx, "target"), 1)
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("amount", IntegerArgumentType.integer(1, 64)).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        giveMobOrPlayerHead(
                            player,
                            StringArgumentType.getString(ctx, "target"),
                            IntegerArgumentType.getInteger(ctx, "amount"),
                        )
                        Command.SINGLE_SUCCESS
                    }
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, mobHeadNode, "Obtain a mob or player head", emptyList())

        // /enchant <enchantment> <level> [target]
        val enchantNode = Commands.literal("enchant")
            .requires { it.sender.hasPermission("obsidian.command.enchant") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("enchantment", StringArgumentType.word()).suggests { _, b ->
                    RegistryAccess.registryAccess()
                        .getRegistry(RegistryKey.ENCHANTMENT)
                        .map { it.key.key }
                        .filter { it.startsWith(b.remaining, true) }
                        .sorted()
                        .forEach(b::suggest)
                    b.buildFuture()
                }.then(
                    Commands.argument("level", IntegerArgumentType.integer(0, 255)).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        applyEnchant(
                            ctx.source.sender,
                            player,
                            StringArgumentType.getString(ctx, "enchantment"),
                            IntegerArgumentType.getInteger(ctx, "level"),
                        )
                        Command.SINGLE_SUCCESS
                    }.then(
                        Commands.argument("target", ArgumentTypes.player())
                            .requires {
                                it.sender.hasPermission("obsidian.command.enchant.others") || it.sender.hasPermission(
                                    "obsidian.admin"
                                )
                            }
                            .executes { ctx ->
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                applyEnchant(
                                    ctx.source.sender,
                                    target,
                                    StringArgumentType.getString(ctx, "enchantment"),
                                    IntegerArgumentType.getInteger(ctx, "level"),
                                )
                                Command.SINGLE_SUCCESS
                            }
                    )
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, enchantNode, "Enchant held item", emptyList())

        // /disableenchant <enchantment>
        val disableEnchantNode = Commands.literal("disableenchant")
            .requires { it.sender.hasPermission("obsidian.command.disableenchant") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("enchantment", StringArgumentType.word()).suggests { _, b ->
                    RegistryAccess.registryAccess()
                        .getRegistry(RegistryKey.ENCHANTMENT)
                        .map { it.key.key }
                        .filter { it.startsWith(b.remaining, true) }
                        .sorted()
                        .forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val name = StringArgumentType.getString(ctx, "enchantment")
                    val ench = anvilAndEnchantService.resolveEnchantment(name)
                    if (ench == null) {
                        context.locale.send(ctx.source.sender, "utility.enchant-unknown", "enchant" to name)
                        return@executes 0
                    }
                    val disabled = anvilAndEnchantService.toggleDisabledEnchant(ench)
                    val state = context.locale.raw(if (disabled) "general.state-on" else "general.state-off")
                    context.locale.send(
                        ctx.source.sender,
                        "utility.enchant-disable-toggled",
                        "enchant" to ench.key.key,
                        "state" to state,
                    )
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            disableEnchantNode,
            "Enable or disable an enchantment globally",
            emptyList()
        )

        // /repaircost
        val repairCostNode = Commands.literal("repaircost")
            .requires { it.sender.hasPermission("obsidian.command.repaircost") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val held = player.inventory.itemInMainHand
                if (held.type.isAir) {
                    context.locale.send(player, "utility.hold-item")
                    return@executes 0
                }
                val cost = anvilAndEnchantService.getRepairCost(held)
                context.locale.send(player, "utility.repaircost-show", "cost" to cost)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            repairCostNode,
            "Check held item anvil repair penalty",
            emptyList()
        )

        // /anvilrepaircost <cost>
        val setRepairCostNode = Commands.literal("anvilrepaircost")
            .requires { it.sender.hasPermission("obsidian.command.anvilrepaircost") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("cost", IntegerArgumentType.integer(0, 1000)).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val held = player.inventory.itemInMainHand
                    if (held.type.isAir) {
                        context.locale.send(player, "utility.hold-item")
                        return@executes 0
                    }
                    val cost = IntegerArgumentType.getInteger(ctx, "cost")
                    anvilAndEnchantService.setRepairCost(held, cost)
                    context.locale.send(player, "utility.repaircost-set", "cost" to cost)
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            setRepairCostNode,
            "Set held item anvil repair penalty",
            emptyList()
        )

        // /trim <material> <pattern>
        val trimNode = Commands.literal("trim")
            .requires { it.sender.hasPermission("obsidian.command.trim") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("material", StringArgumentType.word()).suggests { _, b ->
                    RegistryAccess.registryAccess()
                        .getRegistry(RegistryKey.TRIM_MATERIAL)
                        .map { it.key.key }
                        .filter { it.startsWith(b.remaining, true) }
                        .sorted()
                        .forEach(b::suggest)
                    b.buildFuture()
                }.then(
                    Commands.argument("pattern", StringArgumentType.word()).suggests { _, b ->
                        RegistryAccess.registryAccess()
                            .getRegistry(RegistryKey.TRIM_PATTERN)
                            .map { it.key.key }
                            .filter { it.startsWith(b.remaining, true) }
                            .sorted()
                            .forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val held = player.inventory.itemInMainHand
                        if (held.type.isAir) {
                            context.locale.send(player, "utility.hold-item")
                            return@executes 0
                        }
                        val matName = StringArgumentType.getString(ctx, "material")
                        val patName = StringArgumentType.getString(ctx, "pattern")
                        val mat = anvilAndEnchantService.resolveTrimMaterial(matName)
                        val pat = anvilAndEnchantService.resolveTrimPattern(patName)
                        if (mat == null || pat == null) {
                            context.locale.send(player, "utility.trim-invalid")
                            return@executes 0
                        }
                        if (!anvilAndEnchantService.applyTrim(held, mat, pat)) {
                            context.locale.send(player, "utility.trim-armor-only")
                            return@executes 0
                        }
                        context.locale.send(
                            player,
                            "utility.trim-applied",
                            "material" to mat.key.key,
                            "pattern" to pat.key.key
                        )
                        Command.SINGLE_SUCCESS
                    }
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, trimNode, "Apply an armor trim to held armor", emptyList())

        // /itemcmdata <value|clear>
        val cmDataNode = Commands.literal("itemcmdata")
            .requires { it.sender.hasPermission("obsidian.command.itemcmdata") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.literal("clear").executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val held = player.inventory.itemInMainHand
                if (held.type.isAir) {
                    context.locale.send(player, "utility.hold-item")
                    return@executes 0
                }
                val meta = held.itemMeta
                val comp = meta.customModelDataComponent
                comp.floats = emptyList()
                meta.setCustomModelDataComponent(comp)
                held.itemMeta = meta
                context.locale.send(player, "utility.itemcmdata-cleared")
                Command.SINGLE_SUCCESS
            })
            .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val held = player.inventory.itemInMainHand
                if (held.type.isAir) {
                    context.locale.send(player, "utility.hold-item")
                    return@executes 0
                }
                val value = IntegerArgumentType.getInteger(ctx, "value")
                val meta = held.itemMeta
                val comp = meta.customModelDataComponent
                comp.floats = listOf(value.toFloat())
                meta.setCustomModelDataComponent(comp)
                held.itemMeta = meta
                context.locale.send(player, "utility.itemcmdata-set", "value" to value)
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            cmDataNode,
            "Set or clear custom model data on held item",
            emptyList()
        )

        // /book <title|author|sign|unsign> [value]
        val bookNode = Commands.literal("book")
            .requires { it.sender.hasPermission("obsidian.command.book") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("title").then(
                    Commands.argument("value", StringArgumentType.greedyString()).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        editBook(player, "title", StringArgumentType.getString(ctx, "value"))
                        Command.SINGLE_SUCCESS
                    }
                )
            )
            .then(
                Commands.literal("author").then(
                    Commands.argument("value", StringArgumentType.greedyString()).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        editBook(player, "author", StringArgumentType.getString(ctx, "value"))
                        Command.SINGLE_SUCCESS
                    }
                )
            )
            .then(Commands.literal("sign").executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                editBook(player, "sign", "")
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("unsign").executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                editBook(player, "unsign", "")
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, bookNode, "Edit book title, author, or signature", emptyList())

        // /openbook
        val openBookNode = Commands.literal("openbook")
            .requires { it.sender.hasPermission("obsidian.command.openbook") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val held = player.inventory.itemInMainHand
                if (held.type != Material.WRITTEN_BOOK) {
                    context.locale.send(player, "utility.book-written-only")
                    return@executes 0
                }
                player.openBook(held)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, openBookNode, "Open held written book GUI", emptyList())
    }

    private fun giveMobOrPlayerHead(player: Player, target: String, amount: Int) {
        val entityType = runCatching { org.bukkit.entity.EntityType.valueOf(target.uppercase()) }.getOrNull()
        val item = if (entityType != null) {
            CombatTagService.createMobHead(entityType, amount)
        } else {
            null
        } ?: CombatTagService.createPlayerSkull(target, Bukkit.getPlayerExact(target)).apply {
            this.amount = amount.coerceIn(1, 64)
        }
        val leftover = player.inventory.addItem(item)
        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        context.locale.send(player, "utility.mobhead-given", "target" to target, "amount" to item.amount)
    }

    private fun applyEnchant(sender: CommandSender, target: Player, enchantName: String, level: Int) {
        val held = target.inventory.itemInMainHand
        if (held.type.isAir) {
            context.locale.send(sender, "utility.hold-item")
            return
        }
        val ench = anvilAndEnchantService.resolveEnchantment(enchantName)
        if (ench == null) {
            context.locale.send(sender, "utility.enchant-unknown", "enchant" to enchantName)
            return
        }
        if (anvilAndEnchantService.isEnchantDisabled(ench) && !sender.hasPermission("obsidian.admin")) {
            context.locale.send(sender, "utility.enchant-disabled-all")
            return
        }
        if (level == 0) {
            held.removeEnchantment(ench)
            context.locale.send(sender, "utility.enchant-removed", "enchant" to ench.key.key, "target" to target.name)
            return
        }
        val allowUnsafe =
            sender.hasPermission("obsidian.command.enchant.unsafe") || sender.hasPermission("obsidian.admin")
        if (allowUnsafe) {
            held.addUnsafeEnchantment(ench, level)
        } else {
            if (!ench.canEnchantItem(held) || level > ench.maxLevel) {
                context.locale.send(
                    sender,
                    "utility.enchant-unsafe-denied",
                    "enchant" to ench.key.key,
                    "max" to ench.maxLevel
                )
                return
            }
            held.addEnchantment(ench, level)
        }
        context.locale.send(
            sender,
            "utility.enchant-applied",
            "enchant" to ench.key.key,
            "level" to level,
            "target" to target.name,
        )
    }

    private fun editBook(player: Player, action: String, value: String) {
        val held = player.inventory.itemInMainHand
        if (held.type != Material.WRITABLE_BOOK && held.type != Material.WRITTEN_BOOK) {
            context.locale.send(player, "utility.book-required")
            return
        }
        val meta = held.itemMeta as? org.bukkit.inventory.meta.BookMeta ?: return
        when (action) {
            "title" -> {
                meta.title(TextFormatter.format(value))
                held.itemMeta = meta
                context.locale.send(player, "utility.book-updated", "field" to "title")
            }

            "author" -> {
                meta.author(TextFormatter.format(value))
                held.itemMeta = meta
                context.locale.send(player, "utility.book-updated", "field" to "author")
            }

            "sign" -> {
                if (held.type == Material.WRITTEN_BOOK) return
                val signed = ItemStack(Material.WRITTEN_BOOK, held.amount)
                val signedMeta = signed.itemMeta as org.bukkit.inventory.meta.BookMeta
                signedMeta.pages(meta.pages())
                signedMeta.title(meta.title() ?: Component.text("Book"))
                signedMeta.author(meta.author() ?: Component.text(player.name))
                signed.itemMeta = signedMeta
                player.inventory.setItemInMainHand(signed)
                context.locale.send(player, "utility.book-signed")
            }

            "unsign" -> {
                if (held.type == Material.WRITABLE_BOOK) return
                val writable = ItemStack(Material.WRITABLE_BOOK, held.amount)
                val writableMeta = writable.itemMeta as org.bukkit.inventory.meta.BookMeta
                writableMeta.pages(meta.pages())
                writable.itemMeta = writableMeta
                player.inventory.setItemInMainHand(writable)
                context.locale.send(player, "utility.book-unsigned")
            }
        }
    }

    private fun registerTier3Commands(registrar: Commands) {
        // /world <world> [target]
        val worldNode = Commands.literal("world")
            .requires { it.sender.hasPermission("obsidian.command.world") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("world", ArgumentTypes.world()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val world = ctx.getArgument("world", World::class.java)
                    player.teleportAsync(world.spawnLocation, TeleportCause.COMMAND).thenAccept { ok ->
                        if (ok) context.locale.send(player, "utility.world-teleported", "world" to world.name)
                    }
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("target", ArgumentTypes.player())
                        .requires {
                            it.sender.hasPermission("obsidian.command.world.others") || it.sender.hasPermission(
                                "obsidian.admin"
                            )
                        }
                        .executes { ctx ->
                            val world = ctx.getArgument("world", World::class.java)
                            val target =
                                ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                            target.teleportAsync(world.spawnLocation, TeleportCause.COMMAND).thenAccept { ok ->
                                if (ok) {
                                    context.locale.send(
                                        ctx.source.sender,
                                        "utility.world-teleported-other",
                                        "target" to target.name,
                                        "world" to world.name,
                                    )
                                }
                            }
                            Command.SINGLE_SUCCESS
                        }
                )
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            worldNode,
            "Teleport yourself or another player to a world's spawn",
            emptyList()
        )

        // /gamerule [rule] [value]
        val gameRuleNode = Commands.literal("gamerule")
            .requires { it.sender.hasPermission("obsidian.command.gamerule") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = ctx.source.sender
                val world = (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: return@executes 0
                context.locale.sendRaw(
                    sender,
                    "utility.gamerule-list",
                    "world" to world.name,
                    "rules" to GameRule.values().take(12)
                        .joinToString(", ") { "${it.name}=${world.getGameRuleValue(it)}" },
                )
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("rule", StringArgumentType.word()).suggests { _, b ->
                    GameRule.values().map { it.name }.filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val sender = ctx.source.sender
                    val world = (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: return@executes 0
                    val ruleName = StringArgumentType.getString(ctx, "rule")
                    val rule: GameRule<Any>? = GameRule.getByName<Any>(ruleName)
                    if (rule == null) {
                        context.locale.send(sender, "utility.gamerule-invalid", "rule" to ruleName)
                        return@executes 0
                    }
                    context.locale.send(
                        sender,
                        "utility.gamerule-value",
                        "world" to world.name,
                        "rule" to rule.name,
                        "value" to (world.getGameRuleValue(rule).toString() ?: "null"),
                    )
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("value", StringArgumentType.word()).executes { ctx ->
                        val sender = ctx.source.sender
                        val world = (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: return@executes 0
                        val ruleName = StringArgumentType.getString(ctx, "rule")
                        val rawVal = StringArgumentType.getString(ctx, "value")
                        val rule: GameRule<Any>? = GameRule.getByName<Any>(ruleName)
                        if (rule == null) {
                            context.locale.send(sender, "utility.gamerule-invalid", "rule" to ruleName)
                            return@executes 0
                        }
                        context.scheduler.runGlobal {
                            @Suppress("UNCHECKED_CAST")
                            val ok = when (rule.type) {
                                java.lang.Boolean::class.java, Boolean::class.java -> {
                                    val b = rawVal.toBooleanStrictOrNull()
                                    if (b != null) world.setGameRule(rule as GameRule<Boolean>, b) else false
                                }

                                Integer::class.java, Int::class.java -> {
                                    val i = rawVal.toIntOrNull()
                                    if (i != null) world.setGameRule(rule as GameRule<Int>, i) else false
                                }

                                else -> false
                            }
                            if (ok) {
                                context.locale.send(
                                    sender,
                                    "utility.gamerule-set",
                                    "world" to world.name,
                                    "rule" to rule.name,
                                    "value" to rawVal,
                                )
                            } else {
                                context.locale.send(sender, "utility.gamerule-invalid", "rule" to "$ruleName=$rawVal")
                            }
                        }
                        Command.SINGLE_SUCCESS
                    }
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, gameRuleNode, "View or modify world gamerules", emptyList())

        // /weather <sun|rain|thunder> [seconds]
        val weatherNode = Commands.literal("weather")
            .requires { it.sender.hasPermission("obsidian.command.weather") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("type", StringArgumentType.word()).suggests { _, b ->
                    listOf("sun", "clear", "rain", "thunder", "storm").filter { it.startsWith(b.remaining, true) }
                        .forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    applyWorldWeather(ctx.source.sender, StringArgumentType.getString(ctx, "type"), 300)
                }.then(
                    Commands.argument("seconds", IntegerArgumentType.integer(1, 86400)).executes { ctx ->
                        applyWorldWeather(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "type"),
                            IntegerArgumentType.getInteger(ctx, "seconds"),
                        )
                    }
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, weatherNode, "Set world weather", emptyList())

        // /time <day|noon|night|midnight|set <ticks>|add <ticks>>
        val timeNode = Commands.literal("time")
            .requires { it.sender.hasPermission("obsidian.command.time") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.literal("day").executes { applyWorldTime(it.source.sender, 1000L, false) })
            .then(Commands.literal("noon").executes { applyWorldTime(it.source.sender, 6000L, false) })
            .then(Commands.literal("night").executes { applyWorldTime(it.source.sender, 13000L, false) })
            .then(Commands.literal("midnight").executes { applyWorldTime(it.source.sender, 18000L, false) })
            .then(
                Commands.literal("set").then(
                    Commands.argument("ticks", IntegerArgumentType.integer(0)).executes {
                        applyWorldTime(it.source.sender, IntegerArgumentType.getInteger(it, "ticks").toLong(), false)
                    }
                )
            ).then(
                Commands.literal("add").then(
                    Commands.argument("ticks", IntegerArgumentType.integer(1)).executes {
                        applyWorldTime(it.source.sender, IntegerArgumentType.getInteger(it, "ticks").toLong(), true)
                    }
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, timeNode, "Adjust world time", emptyList())

        // /viewdistance <distance> [world]
        val viewDistNode = Commands.literal("viewdistance")
            .requires { it.sender.hasPermission("obsidian.command.viewdistance") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("distance", IntegerArgumentType.integer(2, 32)).executes { ctx ->
                    val sender = ctx.source.sender
                    val dist = IntegerArgumentType.getInteger(ctx, "distance")
                    val world = (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: return@executes 0
                    context.scheduler.runGlobal {
                        world.viewDistance = dist
                        world.sendViewDistance = dist
                        context.locale.send(
                            sender,
                            "utility.viewdistance-set",
                            "world" to world.name,
                            "distance" to dist
                        )
                    }
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("world", ArgumentTypes.world()).executes { ctx ->
                        val sender = ctx.source.sender
                        val dist = IntegerArgumentType.getInteger(ctx, "distance")
                        val world = ctx.getArgument("world", World::class.java)
                        context.scheduler.runGlobal {
                            world.viewDistance = dist
                            world.sendViewDistance = dist
                            context.locale.send(
                                sender,
                                "utility.viewdistance-set",
                                "world" to world.name,
                                "distance" to dist
                            )
                        }
                        Command.SINGLE_SUCCESS
                    }
                )
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            viewDistNode,
            "Dynamically update world view distance",
            emptyList()
        )

        // /setmotd <text|reset>
        val setMotdNode = Commands.literal("setmotd")
            .requires { it.sender.hasPermission("obsidian.command.setmotd") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("motd", StringArgumentType.greedyString()).executes { ctx ->
                    val sender = ctx.source.sender
                    val text = StringArgumentType.getString(ctx, "motd")
                    if (text.equals("reset", ignoreCase = true) || text.equals("clear", ignoreCase = true)) {
                        serverOpsService.setMotd(null)
                        context.locale.send(sender, "utility.motd-reset")
                    } else {
                        serverOpsService.setMotd(text)
                        context.locale.send(sender, "utility.motd-set", "motd" to text)
                    }
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(context.plugin.pluginMeta, setMotdNode, "Override server ping MOTD dynamically", emptyList())

        // /maxplayers <count|reset>
        val maxPlayersNode = Commands.literal("maxplayers")
            .requires { it.sender.hasPermission("obsidian.command.maxplayers") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("reset").executes { ctx ->
                    serverOpsService.setMaxPlayers(null)
                    context.locale.send(ctx.source.sender, "utility.maxplayers-reset")
                    Command.SINGLE_SUCCESS
                }
            ).then(
                Commands.argument("count", IntegerArgumentType.integer(1, 100000)).executes { ctx ->
                    val count = IntegerArgumentType.getInteger(ctx, "count")
                    serverOpsService.setMaxPlayers(count)
                    context.locale.send(ctx.source.sender, "utility.maxplayers-set", "count" to count)
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            maxPlayersNode,
            "Override displayed max player capacity",
            emptyList()
        )

        // /serverlinks <list|add <label> <url>|remove <index>>
        val serverLinksNode = Commands.literal("serverlinks")
            .requires { it.sender.hasPermission("obsidian.command.serverlinks") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("list").executes { ctx ->
                    val sender = ctx.source.sender
                    val links = Bukkit.getServer().serverLinks.links
                    if (links.isEmpty()) {
                        context.locale.send(sender, "utility.serverlinks-empty")
                        return@executes Command.SINGLE_SUCCESS
                    }
                    context.locale.sendRaw(sender, "utility.serverlinks-header", "count" to links.size)
                    links.forEachIndexed { idx, link ->
                        context.locale.sendRaw(
                            sender,
                            "utility.serverlinks-entry",
                            "index" to (idx + 1),
                            "url" to link.url.toString(),
                        )
                    }
                    Command.SINGLE_SUCCESS
                }
            ).then(
                Commands.literal("add").then(
                    Commands.argument("label", StringArgumentType.word()).then(
                        Commands.argument("url", StringArgumentType.greedyString()).executes { ctx ->
                            val sender = ctx.source.sender
                            val label = StringArgumentType.getString(ctx, "label")
                            val rawUrl = StringArgumentType.getString(ctx, "url")
                            val uri = runCatching { URI.create(rawUrl) }.getOrNull()
                            if (uri == null || !uri.isAbsolute) {
                                context.locale.send(sender, "utility.serverlinks-invalid-url", "url" to rawUrl)
                                return@executes 0
                            }
                            context.scheduler.runGlobal {
                                Bukkit.getServer().serverLinks.addLink(TextFormatter.format(label), uri)
                                context.locale.send(
                                    sender,
                                    "utility.serverlinks-added",
                                    "label" to label,
                                    "url" to rawUrl
                                )
                            }
                            Command.SINGLE_SUCCESS
                        }
                    )
                )
            ).then(
                Commands.literal("remove").then(
                    Commands.argument("index", IntegerArgumentType.integer(1)).executes { ctx ->
                        val sender = ctx.source.sender
                        val index = IntegerArgumentType.getInteger(ctx, "index") - 1
                        context.scheduler.runGlobal {
                            val links = Bukkit.getServer().serverLinks
                            val current = links.links
                            if (index !in current.indices) {
                                context.locale.send(sender, "general.invalid-number", "input" to (index + 1).toString())
                            } else {
                                links.removeLink(current[index])
                                context.locale.send(sender, "utility.serverlinks-removed", "index" to (index + 1))
                            }
                        }
                        Command.SINGLE_SUCCESS
                    }
                )
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            serverLinksNode,
            "Manage Paper ServerLinks pause-menu links",
            emptyList()
        )

        // /fixchunk [radius]
        val fixChunkNode = Commands.literal("fixchunk")
            .requires { it.sender.hasPermission("obsidian.command.fixchunk") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                refreshNearbyChunks(player, 0)
            }.then(
                Commands.argument("radius", IntegerArgumentType.integer(0, 4)).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    refreshNearbyChunks(player, IntegerArgumentType.getInteger(ctx, "radius"))
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            fixChunkNode,
            "Refresh chunk packets around your location",
            emptyList()
        )

        // /unloadchunks [world]
        val unloadChunksNode = Commands.literal("unloadchunks")
            .requires { it.sender.hasPermission("obsidian.command.unloadchunks") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val world =
                    (ctx.source.sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: return@executes 0
                queueChunkUnloads(ctx.source.sender, world)
            }.then(
                Commands.argument("world", ArgumentTypes.world()).executes { ctx ->
                    queueChunkUnloads(ctx.source.sender, ctx.getArgument("world", World::class.java))
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            unloadChunksNode,
            "Request safe chunk unloading for idle chunks",
            emptyList()
        )

        // /effect <target> <clear|give <effect> [seconds] [amplifier]>
        val effectRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
        val effectNode = Commands.literal("effect")
            .requires { it.sender.hasPermission("obsidian.command.effect") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.literal("clear").executes { ctx ->
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                .resolve(ctx.source).firstOrNull() ?: return@executes 0
                        context.scheduler.runForEntity(target) {
                            target.activePotionEffects.forEach { target.removePotionEffect(it.type) }
                            context.locale.send(ctx.source.sender, "utility.effect-cleared", "target" to target.name)
                        }
                        Command.SINGLE_SUCCESS
                    }
                ).then(
                    Commands.literal("give").then(
                        Commands.argument("effect", StringArgumentType.word()).suggests { _, b ->
                            effectRegistry.keyStream().map { it.key().value() }
                                .filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                            b.buildFuture()
                        }.executes { ctx ->
                            applyPotionEffect(ctx.source.sender, ctx, 30, 0)
                        }.then(
                            Commands.argument("seconds", IntegerArgumentType.integer(1, 100000)).executes { ctx ->
                                applyPotionEffect(
                                    ctx.source.sender,
                                    ctx,
                                    IntegerArgumentType.getInteger(ctx, "seconds"),
                                    0
                                )
                            }.then(
                                Commands.argument("amplifier", IntegerArgumentType.integer(0, 255)).executes { ctx ->
                                    applyPotionEffect(
                                        ctx.source.sender,
                                        ctx,
                                        IntegerArgumentType.getInteger(ctx, "seconds"),
                                        IntegerArgumentType.getInteger(ctx, "amplifier"),
                                    )
                                }
                            )
                        )
                    )
                )
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            effectNode,
            "Give or clear potion effects on a player",
            emptyList()
        )

        // /me <action...>
        val meNode = Commands.literal("me")
            .requires { it.sender.hasPermission("obsidian.command.me") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("action", StringArgumentType.greedyString()).executes { ctx ->
                    val sender = ctx.source.sender
                    val action = StringArgumentType.getString(ctx, "action")
                    val component = context.locale.component(
                        "utility.me-format",
                        mapOf("player" to sender.name, "action" to action),
                    )
                    Bukkit.broadcast(component)
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(context.plugin.pluginMeta, meNode, "Broadcast an emote action message", emptyList())

        // /suicide
        val suicideNode = Commands.literal("suicide")
            .requires { it.sender.hasPermission("obsidian.command.suicide") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                context.scheduler.runForEntity(player) {
                    player.health = 0.0
                    context.locale.send(player, "utility.suicide-done")
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, suicideNode, "Kill yourself immediately", emptyList())

        // /giveall <material> [amount]
        val giveAllNode = Commands.literal("giveall")
            .requires { it.sender.hasPermission("obsidian.command.giveall") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("material", StringArgumentType.word()).suggests { _, b ->
                    Material.entries.filter { it.isItem && it.name.startsWith(b.remaining, true) }
                        .take(25).forEach { b.suggest(it.name.lowercase()) }
                    b.buildFuture()
                }.executes { ctx ->
                    executeGiveAll(ctx.source.sender, StringArgumentType.getString(ctx, "material"), 1)
                }.then(
                    Commands.argument("amount", IntegerArgumentType.integer(1, 2304)).executes { ctx ->
                        executeGiveAll(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "material"),
                            IntegerArgumentType.getInteger(ctx, "amount"),
                        )
                    }
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, giveAllNode, "Give an item to all online players", emptyList())

        // /donate
        val donateNode = Commands.literal("donate")
            .requires { it.sender.hasPermission("obsidian.command.donate") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = ctx.source.sender
                val lines = serverOpsService.donateLines
                if (lines.isEmpty()) {
                    context.locale.send(sender, "utility.donate-empty")
                } else {
                    lines.forEach { sender.sendMessage(TextFormatter.format(it)) }
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            donateNode,
            "Display server donation and store links",
            emptyList()
        )

        // /recipe [material]
        val recipeNode = Commands.literal("recipe")
            .requires { it.sender.hasPermission("obsidian.command.recipe") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val held = player.inventory.itemInMainHand
                if (held.type.isAir) {
                    context.locale.send(player, "utility.item-required")
                    return@executes 0
                }
                showRecipes(player, held)
            }.then(
                Commands.argument("material", StringArgumentType.word()).suggests { _, b ->
                    Material.entries.filter { it.isItem && it.name.startsWith(b.remaining, true) }
                        .take(25).forEach { b.suggest(it.name.lowercase()) }
                    b.buildFuture()
                }.executes { ctx ->
                    val matName = StringArgumentType.getString(ctx, "material")
                    val mat = Material.matchMaterial(matName)
                    if (mat == null || !mat.isItem) {
                        context.locale.send(ctx.source.sender, "utility.invalid-material", "input" to matName)
                        return@executes 0
                    }
                    showRecipes(ctx.source.sender, ItemStack(mat))
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            recipeNode,
            "Look up crafting and smelting recipes for an item",
            emptyList()
        )

        // /colors
        val colorsNode = Commands.literal("colors")
            .requires { it.sender.hasPermission("obsidian.command.colors") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = ctx.source.sender
                context.locale.sendRaw(sender, "utility.colors-header")
                sender.sendMessage(
                    TextFormatter.format(
                        "&0&0 &1&1 &2&2 &3&3 &4&4 &5&5 &6&6 &7&7 &8&8 &9&9 &a&a &b&b &c&c &d&d &e&e &f&f"
                    )
                )
                sender.sendMessage(
                    TextFormatter.format(
                        "&r&l&l Bold&r &o&o Italic&r &n&n Underline&r &m&m Strike&r &k&k &7(&kObfuscated&7)"
                    )
                )
                sender.sendMessage(Component.text("MiniMessage: <gradient:#55FFFF:#FF55FF>Example</gradient> | <#AABBCC>Hex"))
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            colorsNode,
            "Display Minecraft chat color and formatting chart",
            emptyList()
        )

        // /solve <expression...>
        val solveNode = Commands.literal("solve")
            .requires { it.sender.hasPermission("obsidian.command.solve") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("expression", StringArgumentType.greedyString()).executes { ctx ->
                    val sender = ctx.source.sender
                    val expr = StringArgumentType.getString(ctx, "expression")
                    val result = runCatching { ServerOpsService.solveMathExpression(expr) }
                    result.onSuccess { v ->
                        val formatted = if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e12) {
                            v.toLong().toString()
                        } else {
                            String.format("%.6f", v).trimEnd('0').trimEnd('.')
                        }
                        context.locale.send(sender, "utility.solve-result", "expression" to expr, "result" to formatted)
                    }.onFailure { err ->
                        context.locale.send(
                            sender,
                            "utility.solve-error",
                            "error" to (err.message ?: "Invalid expression")
                        )
                    }
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(context.plugin.pluginMeta, solveNode, "Evaluate a mathematical expression", listOf("calc"))
    }

    private fun applyWorldWeather(sender: CommandSender, type: String, seconds: Int): Int {
        val world = (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: return 0
        val ticks = seconds * 20
        val normalized = type.lowercase()
        context.scheduler.runGlobal {
            when (normalized) {
                "sun", "clear" -> {
                    world.setStorm(false)
                    world.isThundering = false
                    world.weatherDuration = ticks
                }

                "rain", "storm" -> {
                    world.setStorm(true)
                    world.isThundering = false
                    world.weatherDuration = ticks
                }

                "thunder" -> {
                    world.setStorm(true)
                    world.isThundering = true
                    world.weatherDuration = ticks
                    world.thunderDuration = ticks
                }

                else -> {
                    context.locale.send(sender, "utility.weather-invalid", "type" to type)
                    return@runGlobal
                }
            }
            context.locale.send(
                sender,
                "utility.weather-set",
                "world" to world.name,
                "type" to normalized,
                "seconds" to seconds
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun applyWorldTime(sender: CommandSender, ticks: Long, relative: Boolean): Int {
        val world = (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: return 0
        context.scheduler.runGlobal {
            val newTime = if (relative) world.time + ticks else ticks
            world.time = newTime
            context.locale.send(sender, "utility.time-set", "world" to world.name, "time" to world.time)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun refreshNearbyChunks(player: Player, radius: Int): Int {
        val center = player.location
        val world = center.world ?: return 0
        val cx = center.chunk.x
        val cz = center.chunk.z
        var refreshed = 0
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (world.refreshChunk(cx + dx, cz + dz)) refreshed++
            }
        }
        context.locale.send(player, "utility.fixchunk-done", "count" to refreshed)
        return Command.SINGLE_SUCCESS
    }

    private fun queueChunkUnloads(sender: CommandSender, world: World): Int {
        context.scheduler.runGlobal {
            var requested = 0
            for (chunk in world.loadedChunks) {
                if (!chunk.isForceLoaded && chunk.entities.none { it is Player }) {
                    if (world.unloadChunkRequest(chunk.x, chunk.z)) requested++
                }
            }
            context.locale.send(sender, "utility.unloadchunks-done", "world" to world.name, "count" to requested)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun applyPotionEffect(
        sender: CommandSender,
        ctx: com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack>,
        seconds: Int,
        amplifier: Int,
    ): Int {
        val target =
            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source).firstOrNull()
                ?: return 0
        val rawEffect = StringArgumentType.getString(ctx, "effect").lowercase()
        val key = if (rawEffect.contains(':')) Key.key(rawEffect) else Key.key("minecraft", rawEffect)
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
        val type = registry.get(key)
        if (type == null) {
            context.locale.send(sender, "utility.effect-invalid", "effect" to rawEffect)
            return 0
        }
        context.scheduler.runForEntity(target) {
            target.addPotionEffect(PotionEffect(type, seconds * 20, amplifier, true, true, true))
            context.locale.send(
                sender,
                "utility.effect-given",
                "target" to target.name,
                "effect" to key.value(),
                "seconds" to seconds,
                "level" to (amplifier + 1),
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeGiveAll(sender: CommandSender, matInput: String, amount: Int): Int {
        val mat = Material.matchMaterial(matInput)
        if (mat == null || !mat.isItem) {
            context.locale.send(sender, "utility.invalid-material", "input" to matInput)
            return 0
        }
        val players = Bukkit.getOnlinePlayers()
        for (target in players) {
            context.scheduler.runForEntity(target) {
                val stack = ItemStack(mat, amount.coerceIn(1, mat.maxStackSize * 36))
                val overflow = target.inventory.addItem(stack)
                overflow.values.forEach { target.world.dropItemNaturally(target.location, it) }
            }
        }
        context.locale.send(
            sender,
            "utility.giveall-done",
            "amount" to amount,
            "material" to mat.name.lowercase(),
            "count" to players.size
        )
        return Command.SINGLE_SUCCESS
    }

    private fun showRecipes(sender: CommandSender, stack: ItemStack): Int {
        val lines = serverOpsService.describeRecipes(stack)
        if (lines.isEmpty()) {
            context.locale.send(sender, "utility.recipe-none", "item" to stack.type.name.lowercase())
            return Command.SINGLE_SUCCESS
        }
        context.locale.sendRaw(sender, "utility.recipe-header", "item" to stack.type.name.lowercase())
        lines.forEach { sender.sendMessage(Component.text(it)) }
        return Command.SINGLE_SUCCESS
    }
}
