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
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType

/**
 * Essential server and player utility commands (`/gm`, `/fly`, `/god`, `/heal`, `/feed`, `/speed`,
 * `/repair`, `/workbench`, `/ender`, `/hat`, `/sudo`, `/ptime`, `/pweather`, `/clear`, `/give`, `/item`).
 */
class UtilityModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "utility"
    override val description: String = "Core player and administrative utility commands"

    private val godListener = GodModeListener(context)

    override fun listeners(): List<Listener> = listOf(godListener)

    override fun registerCommands(registrar: Commands) {
        registerGameMode(registrar)
        registerToggleCommands(registrar)
        registerHealAndFeed(registrar)
        registerSpeedCommands(registrar)
        registerRepair(registrar)
        registerInventoryAndMenuCommands(registrar)
        registerPersonalEnvironmentCommands(registrar)
        registerGiveAndItem(registrar)
        registerSudo(registrar)
    }

    private fun registerGameMode(registrar: Commands) {
        val gmNode = Commands.literal("gm")
            .requires { it.sender.hasPermission("obsidian.command.gm") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("mode", StringArgumentType.word())
                    .suggests { _, builder ->
                        listOf("survival", "creative", "adventure", "spectator")
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val mode = UtilityHelpers.parseGameMode(StringArgumentType.getString(ctx, "mode"))
                            ?: return@executes sendUsage(
                                ctx.source.sender,
                                "/gm <survival|creative|adventure|spectator> [player]"
                            )
                        applyGameMode(ctx.source.sender, player, mode)
                    }
                    .then(
                        Commands.argument("target", ArgumentTypes.player())
                            .requires {
                                it.sender.hasPermission("obsidian.command.gm.others") || it.sender.hasPermission(
                                    "obsidian.admin"
                                )
                            }
                            .executes { ctx ->
                                val mode = UtilityHelpers.parseGameMode(StringArgumentType.getString(ctx, "mode"))
                                    ?: return@executes sendUsage(ctx.source.sender, "/gm <mode> [player]")
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                applyGameMode(ctx.source.sender, target, mode)
                            }
                    )
            )
            .build()

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
                    }
                    .then(
                        Commands.argument("target", ArgumentTypes.player())
                            .requires {
                                it.sender.hasPermission("obsidian.command.gm.others") || it.sender.hasPermission(
                                    "obsidian.admin"
                                )
                            }
                            .executes { ctx ->
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                applyGameMode(ctx.source.sender, target, mode)
                            }
                    )
                    .build()
                registrar.register(
                    context.plugin.pluginMeta,
                    aliasNode,
                    "Shortcut for /gm ${mode.name.lowercase()}",
                    emptyList()
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
                sender,
                "utility.fly-toggled",
                "target" to target.name,
                "state" to if (newState) "ON" else "OFF"
            )
        }

        registerTargetPlayerCommand(registrar, "god", "Toggle god mode", "obsidian.command.god") { sender, target ->
            val profile = context.users.getCached(target)
            val newState = !profile.godMode
            profile.godMode = newState
            profile.markDirty()
            context.locale.send(
                sender,
                "utility.god-toggled",
                "target" to target.name,
                "state" to if (newState) "ON" else "OFF"
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
            registrar,
            "feed",
            "Restore hunger and saturation",
            "obsidian.command.feed",
            checkCooldown = true
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
            .then(
                Commands.argument("amount", DoubleArgumentType.doubleArg(0.0, 10.0))
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val amount = DoubleArgumentType.getDouble(ctx, "amount")
                        applySpeed(ctx.source.sender, player, amount, forcedFly ?: player.isFlying)
                    }
                    .then(
                        Commands.argument("target", ArgumentTypes.player())
                            .requires {
                                it.sender.hasPermission("obsidian.command.$name.others") || it.sender.hasPermission(
                                    "obsidian.admin"
                                )
                            }
                            .executes { ctx ->
                                val amount = DoubleArgumentType.getDouble(ctx, "amount")
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                applySpeed(ctx.source.sender, target, amount, forcedFly ?: target.isFlying)
                            }
                    )
            )
            .build()
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
            }
            .then(
                Commands.argument("scope", StringArgumentType.word())
                    .suggests { _, builder ->
                        listOf("hand", "offhand", "armor", "all")
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        executeRepair(ctx.source.sender, player, StringArgumentType.getString(ctx, "scope"))
                    }
                    .then(
                        Commands.argument("target", ArgumentTypes.player())
                            .requires {
                                it.sender.hasPermission("obsidian.command.repair.others") || it.sender.hasPermission(
                                    "obsidian.admin"
                                )
                            }
                            .executes { ctx ->
                                val scope = StringArgumentType.getString(ctx, "scope")
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                executeRepair(ctx.source.sender, target, scope)
                            }
                    )
            )
            .build()
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
            }
            .then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.ender.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val viewer = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
                        viewer.openInventory(target.enderChest)
                        context.locale.send(viewer, "utility.ender-opened", "target" to target.name)
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
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
                player.inventory.helmet = hand.clone()
                player.inventory.itemInMainHand = currentHelmet
                context.locale.send(player, "utility.hat-equipped")
                Command.SINGLE_SUCCESS
            }
            .build()
        registrar.register(context.plugin.pluginMeta, hatNode, "Wear held item as hat", emptyList())

        val clearNode = Commands.literal("clear")
            .requires { it.sender.hasPermission("obsidian.command.clear") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                player.inventory.clear()
                context.locale.send(player, "utility.cleared", "scope" to "inventory", "target" to player.name)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.clear.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
                        target.inventory.clear()
                        context.locale.send(
                            ctx.source.sender,
                            "utility.cleared",
                            "scope" to "inventory",
                            "target" to target.name
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(context.plugin.pluginMeta, clearNode, "Clear player inventory", emptyList())
    }

    private fun registerPersonalEnvironmentCommands(registrar: Commands) {
        val ptimeNode = Commands.literal("ptime")
            .requires { it.sender.hasPermission("obsidian.command.ptime") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("value", StringArgumentType.word())
                    .suggests { _, builder ->
                        listOf("day", "noon", "sunset", "night", "midnight", "sunrise", "reset")
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        applyPTime(ctx.source.sender, player, StringArgumentType.getString(ctx, "value"))
                    }
                    .then(
                        Commands.argument("target", ArgumentTypes.player())
                            .requires {
                                it.sender.hasPermission("obsidian.command.ptime.others") || it.sender.hasPermission(
                                    "obsidian.admin"
                                )
                            }
                            .executes { ctx ->
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                applyPTime(ctx.source.sender, target, StringArgumentType.getString(ctx, "value"))
                            }
                    )
            )
            .build()
        registrar.register(context.plugin.pluginMeta, ptimeNode, "Set personal player time", emptyList())

        val pweatherNode = Commands.literal("pweather")
            .requires { it.sender.hasPermission("obsidian.command.pweather") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("value", StringArgumentType.word())
                    .suggests { _, builder ->
                        listOf("sun", "rain", "reset")
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        applyPWeather(ctx.source.sender, player, StringArgumentType.getString(ctx, "value"))
                    }
                    .then(
                        Commands.argument("target", ArgumentTypes.player())
                            .requires {
                                it.sender.hasPermission("obsidian.command.pweather.others") || it.sender.hasPermission(
                                    "obsidian.admin"
                                )
                            }
                            .executes { ctx ->
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                applyPWeather(ctx.source.sender, target, StringArgumentType.getString(ctx, "value"))
                            }
                    )
            )
            .build()
        registrar.register(context.plugin.pluginMeta, pweatherNode, "Set personal player weather", emptyList())
    }

    private fun applyPTime(sender: CommandSender, target: Player, value: String): Int {
        if (value.equals("reset", ignoreCase = true)) {
            target.resetPlayerTime()
            context.locale.send(sender, "utility.ptime-set", "target" to target.name, "value" to "reset")
            return Command.SINGLE_SUCCESS
        }
        val ticks = UtilityHelpers.parsePersonalTime(value)
            ?: return sendUsage(sender, "/ptime <day|noon|night|midnight|ticks|reset> [player]")
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
        val weather = UtilityHelpers.parsePersonalWeather(value)
            ?: return sendUsage(sender, "/pweather <sun|rain|reset> [player]")
        target.playerWeather = weather
        context.locale.send(sender, "utility.pweather-set", "target" to target.name, "value" to value)
        return Command.SINGLE_SUCCESS
    }

    private fun registerGiveAndItem(registrar: Commands) {
        val giveNode = Commands.literal("give")
            .requires { it.sender.hasPermission("obsidian.command.give") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player())
                    .then(
                        Commands.argument("item", ArgumentTypes.itemStack())
                            .executes { ctx ->
                                val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                    .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                val stack = ctx.getArgument("item", ItemStack::class.java)
                                giveItem(ctx.source.sender, target, stack, 1)
                            }
                            .then(
                                Commands.argument("amount", IntegerArgumentType.integer(1, 2304))
                                    .executes { ctx ->
                                        val target =
                                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                                .resolve(ctx.source).firstOrNull() ?: return@executes 0
                                        val stack = ctx.getArgument("item", ItemStack::class.java)
                                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                        giveItem(ctx.source.sender, target, stack, amount)
                                    }
                            )
                    )
            )
            .build()
        registrar.register(context.plugin.pluginMeta, giveNode, "Give an item to a player", emptyList())

        val itemNode = Commands.literal("item")
            .requires { it.sender.hasPermission("obsidian.command.item") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("item", ArgumentTypes.itemStack())
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val stack = ctx.getArgument("item", ItemStack::class.java)
                        giveItem(ctx.source.sender, player, stack, 1)
                    }
                    .then(
                        Commands.argument("amount", IntegerArgumentType.integer(1, 2304))
                            .executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                val stack = ctx.getArgument("item", ItemStack::class.java)
                                val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                giveItem(ctx.source.sender, player, stack, amount)
                            }
                    )
            )
            .build()
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
                Commands.argument("target", ArgumentTypes.player())
                    .then(
                        Commands.argument("command", StringArgumentType.greedyString())
                            .executes { ctx ->
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
                                    sender,
                                    "utility.sudo-executed",
                                    "target" to target.name,
                                    "command" to rawCmd
                                )
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .build()
        registrar.register(
            context.plugin.pluginMeta,
            sudoNode,
            "Force a player to execute a command or chat",
            emptyList()
        )
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
            }
            .then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("$permission.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        if (checkCooldown && !CommandHelper.checkCooldown(
                                context,
                                ctx.source.sender,
                                name
                            )
                        ) return@executes 0
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
                        action(ctx.source.sender, target)
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            description,
            if (context.config.registerStandaloneAliases) aliases else emptyList(),
        )
    }

    private fun sendUsage(sender: CommandSender, usage: String): Int {
        context.locale.send(sender, "general.invalid-usage", "usage" to usage)
        return 0
    }
}
