package obsidian.modules.economy

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
import obsidian.integration.VaultEconomyHook
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import kotlin.math.ceil

/**
 * Economy, Vault bridge, cheques, item worth, and selling module (`/bal`, `/pay`, `/money`, `/baltop`,
 * `/cheque`, `/worth`, `/setworth`, `/sell`).
 */
class EconomyModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "economy"
    override val description: String = "Player economy, cheques, worth, and Vault provider"

    val economyService = EconomyService(context)
    val worthService = WorthService(context, economyService)
    private var vaultHook: VaultEconomyHook? = null

    override fun onEnable() {
        context.registerService(economyService)
        context.registerService(worthService)
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            vaultHook = VaultEconomyHook(context.plugin, economyService).also {
                it.register()
                context.plugin.logger.info("Registered Vault Economy provider (ObsidianEconomy).")
            }
        }
    }

    override fun onDisable() {
        vaultHook?.unregister()
        vaultHook = null
        economyService.flushAllBlocking()
    }

    override fun onReload() {
        economyService.reload()
        worthService.reload()
    }

    override fun listeners(): List<Listener> = listOf(economyService)

    override fun registerCommands(registrar: Commands) {
        registerBalance(registrar)
        registerPay(registrar)
        registerMoneyAdmin(registrar)
        registerBaltop(registrar)
        registerCheque(registrar)
        registerWorth(registrar)
        registerSetWorth(registrar)
        registerSell(registrar)
    }

    private fun registerBalance(registrar: Commands) {
        val node = Commands.literal("balance")
            .requires { it.sender.hasPermission("obsidian.command.balance") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val bal = economyService.getBalance(player.uniqueId)
                context.locale.send(
                    player,
                    "economy.balance",
                    "target" to player.name,
                    "amount" to economyService.formatMoney(bal),
                )
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("target", StringArgumentType.word())
                    .requires { it.sender.hasPermission("obsidian.command.balance.others") || it.sender.hasPermission("obsidian.admin") }
                    .suggests { ctx, builder ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, builder.remaining)
                            .forEach(builder::suggest)
                        builder.buildFuture()
                    }.executes { ctx ->
                        val sender = ctx.source.sender
                        val targetName = StringArgumentType.getString(ctx, "target")
                        showOtherBalance(sender, targetName)
                        Command.SINGLE_SUCCESS
                    }).build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Check balance",
            if (context.config.registerStandaloneAliases) listOf("bal") else emptyList(),
        )
    }

    private fun showOtherBalance(sender: CommandSender, targetName: String) {
        val online = Bukkit.getPlayerExact(targetName) ?: Bukkit.getPlayer(targetName)
        if (online != null) {
            val bal = economyService.getBalance(online.uniqueId)
            context.locale.send(
                sender,
                "economy.balance",
                "target" to online.name,
                "amount" to economyService.formatMoney(bal),
            )
            return
        }
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = { context.economyRepository.findByName(targetName) },
            onResult = { rec ->
                if (rec == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    context.locale.send(
                        sender,
                        "economy.balance",
                        "target" to rec.username,
                        "amount" to economyService.formatMoney(rec.balance),
                    )
                }
            },
        )
    }

    private fun registerPay(registrar: Commands) {
        val node = Commands.literal("pay")
            .requires { it.sender.hasPermission("obsidian.command.pay") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("amount", StringArgumentType.word()).executes { ctx ->
                            val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                            val resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            val target = resolver.resolve(ctx.source).firstOrNull() ?: return@executes 0
                            if (target.uniqueId == sender.uniqueId) {
                                context.locale.send(sender, "economy.pay-self")
                                return@executes 0
                            }
                            val rawAmount = StringArgumentType.getString(ctx, "amount")
                            val amount = EconomyService.parseAmount(rawAmount)
                            if (amount == null) {
                                context.locale.send(sender, "economy.invalid-amount", "input" to rawAmount)
                                return@executes 0
                            }
                            if (amount < economyService.minPayAmount) {
                                context.locale.send(
                                    sender,
                                    "economy.below-min-pay",
                                    "min" to economyService.formatMoney(economyService.minPayAmount),
                                )
                                return@executes 0
                            }
                            if (!economyService.transfer(
                                    sender.uniqueId, sender.name, target.uniqueId, target.name, amount
                                )
                            ) {
                                context.locale.send(
                                    sender,
                                    "economy.insufficient-funds",
                                    "amount" to economyService.formatMoney(amount),
                                )
                                return@executes 0
                            }
                            val formatted = economyService.formatMoney(amount)
                            context.locale.send(
                                sender, "economy.paid", "target" to target.name, "amount" to formatted
                            )
                            context.locale.send(
                                target, "economy.received", "sender" to sender.name, "amount" to formatted
                            )
                            Command.SINGLE_SUCCESS
                        })).build()

        registrar.register(context.plugin.pluginMeta, node, "Pay money to another player", emptyList())
    }

    private fun registerMoneyAdmin(registrar: Commands) {
        val node = Commands.literal("money")
            .requires { it.sender.hasPermission("obsidian.command.money") || it.sender.hasPermission("obsidian.admin") }
            .then(moneySubcommand("give") { targetUuid, targetName, amount ->
                economyService.deposit(targetUuid, amount, targetName)
                economyService.getBalance(targetUuid)
            }).then(moneySubcommand("take") { targetUuid, targetName, amount ->
                economyService.withdraw(targetUuid, amount, targetName)
                economyService.getBalance(targetUuid)
            }).then(moneySubcommand("set") { targetUuid, targetName, amount ->
                economyService.setBalance(targetUuid, amount, targetName)
            })
            .then(
                Commands.literal("reset")
                    .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, builder ->
                            CommandHelper.suggestOnlinePlayers(ctx.source.sender, builder.remaining)
                                .forEach(builder::suggest)
                            builder.buildFuture()
                        }.executes { ctx ->
                            val sender = ctx.source.sender
                            val targetName = StringArgumentType.getString(ctx, "target")
                            updateMoneyTarget(
                                sender, targetName, economyService.startingBalance
                            ) { uuid, name, amt ->
                                economyService.setBalance(uuid, amt, name)
                            }
                            Command.SINGLE_SUCCESS
                        })).build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Administrative economy commands",
            if (context.config.registerStandaloneAliases) listOf("eco") else emptyList(),
        )
    }

    private fun moneySubcommand(
        literal: String,
        action: (java.util.UUID, String, Double) -> Double,
    ) = Commands.literal(literal).then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, builder ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, builder.remaining).forEach(builder::suggest)
                builder.buildFuture()
            }.then(
                Commands.argument("amount", StringArgumentType.word()).executes { ctx ->
                        val sender = ctx.source.sender
                        val targetName = StringArgumentType.getString(ctx, "target")
                        val rawAmount = StringArgumentType.getString(ctx, "amount")
                        val amount = EconomyService.parseAmount(rawAmount)
                        if (amount == null) {
                            context.locale.send(sender, "economy.invalid-amount", "input" to rawAmount)
                            return@executes 0
                        }
                        updateMoneyTarget(sender, targetName, amount, action)
                        Command.SINGLE_SUCCESS
                    }))

    private fun updateMoneyTarget(
        sender: CommandSender,
        targetName: String,
        amount: Double,
        action: (java.util.UUID, String, Double) -> Double,
    ) {
        val online = Bukkit.getPlayerExact(targetName) ?: Bukkit.getPlayer(targetName)
        if (online != null) {
            val newBal = action(online.uniqueId, online.name, amount)
            context.locale.send(
                sender,
                "economy.admin-updated",
                "target" to online.name,
                "amount" to economyService.formatMoney(newBal),
            )
            return
        }
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val user = context.users.findByName(targetName)
                val eco = context.economyRepository.findByName(targetName)
                val offline = if (user == null && eco == null) Bukkit.getOfflinePlayerIfCached(targetName) else null
                val resolvedUuid = user?.uuid ?: eco?.uuid ?: offline?.uniqueId
                val resolvedName = user?.username ?: eco?.username ?: offline?.name
                if (resolvedUuid == null || resolvedName == null) {
                    null
                } else {
                    resolvedName to action(resolvedUuid, resolvedName, amount)
                }
            },
            onResult = { result ->
                if (result == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    context.locale.send(
                        sender,
                        "economy.admin-updated",
                        "target" to result.first,
                        "amount" to economyService.formatMoney(result.second),
                    )
                }
            },
        )
    }

    private fun registerBaltop(registrar: Commands) {
        val node = Commands.literal("baltop")
            .requires { it.sender.hasPermission("obsidian.command.baltop") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                sendBaltop(ctx.source.sender, 1)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                        sendBaltop(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "page"))
                        Command.SINGLE_SUCCESS
                    }).build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Show richest players",
            if (context.config.registerStandaloneAliases) listOf("balancetop") else emptyList(),
        )
    }

    private fun sendBaltop(sender: CommandSender, requestedPage: Int) {
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = { economyService.getTopBalances(100) },
            onResult = { top ->
                if (top.isEmpty()) {
                    context.locale.send(sender, "economy.baltop-empty")
                    return@dispatchAsync
                }
                val perPage = 10
                val totalPages = ceil(top.size.toDouble() / perPage).toInt().coerceAtLeast(1)
                val page = requestedPage.coerceIn(1, totalPages)
                val from = (page - 1) * perPage
                val to = minOf(from + perPage, top.size)

                context.locale.send(
                    sender, "economy.baltop-header", "page" to page.toString(), "pages" to totalPages.toString()
                )
                for (idx in from until to) {
                    val rec = top[idx]
                    context.locale.sendRaw(
                        sender,
                        "economy.baltop-entry",
                        "rank" to (idx + 1).toString(),
                        "player" to rec.username,
                        "amount" to economyService.formatMoney(rec.balance),
                    )
                }
            },
        )
    }

    private fun registerCheque(registrar: Commands) {
        val node = Commands.literal("cheque")
            .requires { it.sender.hasPermission("obsidian.command.cheque") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("amount", StringArgumentType.word()).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val rawAmount = StringArgumentType.getString(ctx, "amount")
                        val amount = EconomyService.parseAmount(rawAmount)
                        if (amount == null) {
                            context.locale.send(player, "economy.invalid-amount", "input" to rawAmount)
                            return@executes 0
                        }
                        if (!economyService.withdraw(player.uniqueId, amount, player.name)) {
                            context.locale.send(
                                player,
                                "economy.insufficient-funds",
                                "amount" to economyService.formatMoney(amount),
                            )
                            return@executes 0
                        }
                        val item = economyService.createChequeItem(player.name, amount)
                        val leftover = player.inventory.addItem(item)
                        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
                        context.locale.send(
                            player, "economy.cheque-created", "amount" to economyService.formatMoney(amount)
                        )
                        Command.SINGLE_SUCCESS
                    }).build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Withdraw money as a physical bank cheque",
            if (context.config.registerStandaloneAliases) listOf("moneycheque") else emptyList(),
        )
    }

    private fun registerWorth(registrar: Commands) {
        val node = Commands.literal("worth")
            .requires { it.sender.hasPermission("obsidian.command.worth") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val item = player.inventory.itemInMainHand
                if (item.type.isAir) {
                    context.locale.send(player, "economy.nothing-to-sell")
                    return@executes 0
                }
                val unit = worthService.getSellPrice(item.type)
                if (unit == null) {
                    context.locale.send(player, "economy.worth-none", "item" to item.type.name)
                    return@executes 0
                }
                context.locale.send(
                    player,
                    "economy.worth-info",
                    "item" to item.type.name,
                    "amount" to item.amount.toString(),
                    "unit" to economyService.formatMoney(unit),
                    "total" to economyService.formatMoney(unit * item.amount),
                )
                Command.SINGLE_SUCCESS
            }.build()

        registrar.register(context.plugin.pluginMeta, node, "Check sell worth of held item", emptyList())
    }

    private fun registerSetWorth(registrar: Commands) {
        val node = Commands.literal("setworth")
            .requires { it.sender.hasPermission("obsidian.command.setworth") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("price", DoubleArgumentType.doubleArg(0.0)).executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val item = player.inventory.itemInMainHand
                        if (item.type.isAir) {
                            context.locale.send(player, "utility.hat-invalid")
                            return@executes 0
                        }
                        val price = DoubleArgumentType.getDouble(ctx, "price")
                        worthService.setSellPrice(item.type, price)
                        context.locale.send(
                            player,
                            "economy.worth-set",
                            "item" to item.type.name,
                            "amount" to economyService.formatMoney(price),
                        )
                        Command.SINGLE_SUCCESS
                    }).build()

        registrar.register(context.plugin.pluginMeta, node, "Set sell price of held item", emptyList())
    }

    private fun registerSell(registrar: Commands) {
        val node = Commands.literal("sell")
            .requires { it.sender.hasPermission("obsidian.command.sell") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                handleSell(player, sellAll = false)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.literal("hand").executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        handleSell(player, sellAll = false)
                        Command.SINGLE_SUCCESS
                    }).then(
                Commands.literal("all").executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        handleSell(player, sellAll = true)
                        Command.SINGLE_SUCCESS
                    }).build()

        registrar.register(context.plugin.pluginMeta, node, "Sell held item or entire inventory", emptyList())
    }

    private fun handleSell(player: Player, sellAll: Boolean) {
        val res = if (sellAll) worthService.sellAll(player) else worthService.sellHand(player)
        if (res.itemCount <= 0) {
            context.locale.send(player, "economy.nothing-to-sell")
            return
        }
        context.locale.send(
            player,
            "economy.sold",
            "count" to res.itemCount.toString(),
            "amount" to economyService.formatMoney(res.totalEarned),
        )
    }
}
