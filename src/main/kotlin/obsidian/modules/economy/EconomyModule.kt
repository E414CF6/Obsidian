package obsidian.modules.economy

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.registry.data.dialog.input.DialogInput
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import obsidian.core.module.ObsidianModule
import obsidian.core.ui.DialogBuilder
import obsidian.integration.VaultEconomyHook
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
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
    val deliveryService = DeliveryService(context)
    private var vaultHook: VaultEconomyHook? = null

    override fun onEnable() {
        context.registerService(economyService)
        context.registerService(worthService)
        context.registerService(deliveryService)
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
        deliveryService.reload()
    }

    override fun listeners(): List<Listener> = listOf(economyService, deliveryService)

    override fun registerCommands(registrar: Commands) {
        registerBalance(registrar)
        registerPay(registrar)
        registerMoneyAdmin(registrar)
        registerBaltop(registrar)
        registerCheque(registrar)
        registerWorth(registrar)
        registerSetWorth(registrar)
        registerSell(registrar)
        registerDelivery(registrar)
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
            listOf("bal"),
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
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                openPaySelectorGui(player, 1)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("amount", StringArgumentType.word()).executes { ctx ->
                        val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                        val target = resolver.resolve(ctx.source).firstOrNull() ?: return@executes 0
                        executePay(sender, target, StringArgumentType.getString(ctx, "amount"))
                    })
            ).build()

        registrar.register(context.plugin.pluginMeta, node, "Pay money to another player", emptyList())
    }

    private fun executePay(sender: Player, target: Player, rawAmount: String): Int {
        if (target.uniqueId == sender.uniqueId) {
            context.locale.send(sender, "economy.pay-self")
            return 0
        }
        val amount = EconomyService.parseAmount(rawAmount)
        if (amount == null) {
            context.locale.send(sender, "economy.invalid-amount", "input" to rawAmount)
            return 0
        }
        if (amount < economyService.minPayAmount) {
            context.locale.send(
                sender,
                "economy.below-min-pay",
                "min" to economyService.formatMoney(economyService.minPayAmount),
            )
            return 0
        }
        if (!economyService.transfer(sender.uniqueId, sender.name, target.uniqueId, target.name, amount)) {
            context.locale.send(
                sender,
                "economy.insufficient-funds",
                "amount" to economyService.formatMoney(amount),
            )
            return 0
        }
        val formatted = economyService.formatMoney(amount)
        context.locale.send(sender, "economy.paid", "target" to target.name, "amount" to formatted)
        context.locale.send(target, "economy.received", "sender" to sender.name, "amount" to formatted)
        context.actionBar.pushTransientLocale(sender, "economy.paid", "target" to target.name, "amount" to formatted)
        context.actionBar.pushTransientLocale(
            target,
            "economy.received",
            "sender" to sender.name,
            "amount" to formatted
        )
        return Command.SINGLE_SUCCESS
    }

    private fun openPaySelectorGui(sender: Player, page: Int) {
        val candidates = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != sender.uniqueId && sender.canSee(it) }
            .sortedBy { it.name.lowercase() }
        if (candidates.isEmpty()) {
            context.locale.send(sender, "economy.pay-no-players")
            return
        }
        val perPage = 28
        val totalPages = ceil(candidates.size.toDouble() / perPage).toInt().coerceAtLeast(1)
        val currentPage = page.coerceIn(1, totalPages)
        val window =
            MenuWindow(context.locale.component("economy.pay-gui-title", mapOf("page" to currentPage.toString())), 6)
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val slice = candidates.drop((currentPage - 1) * perPage).take(perPage)
        var idx = 0
        for (row in 1..4) {
            for (col in 1..7) {
                if (idx >= slice.size) break
                val target = slice[idx++]
                val head = MenuItems.playerHead(
                    uuid = target.uniqueId,
                    nameHint = target.name,
                    displayName = "<green>${target.name}</green>",
                    lore = listOf(context.locale.raw("economy.pay-gui-lore")),
                )
                window.set(row * 9 + col, head) { click ->
                    click.player.closeInventory()
                    openPayAmountDialog(click.player, target)
                }
            }
        }
        window.setFooterNav(
            context = context,
            page = currentPage,
            totalPages = totalPages,
            onPrev = { openPaySelectorGui(sender, currentPage - 1) },
            onNext = { openPaySelectorGui(sender, currentPage + 1) },
        )
        window.open(sender)
    }

    private fun openPayAmountDialog(sender: Player, target: Player) {
        val balanceText = economyService.formatMoney(economyService.getBalance(sender.uniqueId))
        DialogBuilder.form(
            context = context,
            player = sender,
            title = context.locale.component("economy.pay-dialog-title", mapOf("target" to target.name)),
            bodyLines = listOf(
                context.locale.component("economy.pay-dialog-balance", mapOf("balance" to balanceText)),
            ),
            inputs = listOf(
                DialogInput.text("amount", context.locale.component("economy.pay-dialog-label"))
                    .initial("100")
                    .width(180)
                    .build(),
            ),
            submitLabel = context.locale.component("general.dialog-confirm"),
            cancelLabel = context.locale.component("general.dialog-cancel"),
        ) { p, resp ->
            val rawAmt = resp.getText("amount")?.trim() ?: return@form
            if (target.isOnline) {
                executePay(p, target, rawAmt)
            } else {
                context.locale.send(p, "general.player-not-found", "player" to target.name)
            }
        }
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
                    })
            ).build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Administrative economy commands",
            listOf("eco"),
        )
    }

    private fun moneySubcommand(
        literal: String,
        action: (java.util.UUID, String, Double) -> Double,
    ) = Commands.literal(literal).then(
        Commands.argument("target", StringArgumentType.word()).suggests { ctx, builder ->
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
            })
    )

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
            listOf("balancetop"),
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
                if (sender is Player && context.config.uiGuiLeaderboards) {
                    openBaltopGui(sender, top, requestedPage)
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

    private fun openBaltopGui(
        player: Player,
        top: List<obsidian.core.database.EconomyRecord>,
        requestedPage: Int,
    ) {
        val perPage = 28
        val totalPages = ceil(top.size.toDouble() / perPage).toInt().coerceAtLeast(1)
        val page = requestedPage.coerceIn(1, totalPages)
        val window = MenuWindow(
            context.locale.component(
                "economy.baltop-gui-title",
                mapOf("page" to page.toString(), "pages" to totalPages.toString())
            ),
            6,
        )
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val from = (page - 1) * perPage
        val slice = top.drop(from).take(perPage)
        var idx = 0
        for (row in 1..4) {
            for (col in 1..7) {
                if (idx >= slice.size) break
                val rec = slice[idx]
                val rank = from + idx + 1
                idx++
                val head = MenuItems.playerHead(
                    uuid = rec.uuid,
                    nameHint = rec.username,
                    displayName = context.locale.raw(
                        "economy.baltop-gui-item",
                        "rank" to rank,
                        "player" to rec.username,
                    ),
                    lore = listOf(
                        context.locale.raw(
                            "economy.baltop-gui-balance",
                            "amount" to economyService.formatMoney(rec.balance)
                        ),
                    ),
                )
                window.set(row * 9 + col, head)
            }
        }

        val myIdx = top.indexOfFirst { it.uuid == player.uniqueId }
        val myRankText = if (myIdx >= 0) "#${myIdx + 1}" else "-"
        val myBal = economyService.formatMoney(economyService.getBalance(player.uniqueId))
        window.set(
            49,
            MenuItems.playerHead(
                uuid = player.uniqueId,
                nameHint = player.name,
                displayName = context.locale.raw("economy.baltop-gui-self", "player" to player.name),
                lore = listOf(
                    context.locale.raw("economy.baltop-gui-self-rank", "rank" to myRankText),
                    context.locale.raw("economy.baltop-gui-balance", "amount" to myBal),
                ),
            ),
        )
        window.setFooterNav(
            context = context,
            page = page,
            totalPages = totalPages,
            onPrev = { openBaltopGui(player, top, page - 1) },
            onNext = { openBaltopGui(player, top, page + 1) },
        )
        window.open(player)
    }

    private fun registerCheque(registrar: Commands) {
        val node = Commands.literal("cheque")
            .requires { it.sender.hasPermission("obsidian.command.cheque") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                openChequeDialog(player)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("amount", StringArgumentType.word()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    issueCheque(player, StringArgumentType.getString(ctx, "amount"))
                }).build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Withdraw money as a physical bank cheque",
            listOf("moneycheque"),
        )
    }

    private fun issueCheque(player: Player, rawAmount: String): Int {
        val amount = EconomyService.parseAmount(rawAmount)
        if (amount == null) {
            context.locale.send(player, "economy.invalid-amount", "input" to rawAmount)
            return 0
        }
        if (!economyService.withdraw(player.uniqueId, amount, player.name)) {
            context.locale.send(
                player,
                "economy.insufficient-funds",
                "amount" to economyService.formatMoney(amount),
            )
            return 0
        }
        val item = economyService.createChequeItem(player.name, amount)
        val leftover = player.inventory.addItem(item)
        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        val formatted = economyService.formatMoney(amount)
        context.locale.send(player, "economy.cheque-created", "amount" to formatted)
        context.actionBar.pushTransientLocale(player, "economy.cheque-created", "amount" to formatted)
        return Command.SINGLE_SUCCESS
    }

    private fun openChequeDialog(player: Player) {
        val bal = economyService.formatMoney(economyService.getBalance(player.uniqueId))
        DialogBuilder.form(
            context = context,
            player = player,
            title = context.locale.component("economy.cheque-dialog-title"),
            bodyLines = listOf(context.locale.component("economy.pay-dialog-balance", mapOf("balance" to bal))),
            inputs = listOf(
                DialogInput.text("amount", context.locale.component("economy.cheque-dialog-label"))
                    .initial("100")
                    .width(180)
                    .build(),
            ),
            submitLabel = context.locale.component("general.dialog-confirm"),
            cancelLabel = context.locale.component("general.dialog-cancel"),
        ) { p, resp ->
            val raw = resp.getText("amount")?.trim() ?: return@form
            issueCheque(p, raw)
        }
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
                if (player.inventory.itemInMainHand.type.isAir) {
                    openSellGui(player)
                } else {
                    handleSell(player, sellAll = false)
                }
                Command.SINGLE_SUCCESS
            }.then(
                Commands.literal("gui").executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    openSellGui(player)
                    Command.SINGLE_SUCCESS
                }
            ).then(
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
        val formatted = economyService.formatMoney(res.totalEarned)
        context.locale.send(
            player,
            "economy.sold",
            "count" to res.itemCount.toString(),
            "amount" to formatted,
        )
        context.actionBar.pushTransientLocale(
            player,
            "economy.sold",
            "count" to res.itemCount.toString(),
            "amount" to formatted,
        )
    }

    private fun openSellGui(player: Player) {
        val window = MenuWindow(context.locale.component("economy.sell-gui-title"), 6)
        for (slot in 0 until 45) {
            window.editableSlots += slot
        }
        window.allowBottomInventoryClick = true
        window.fillRow(5, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        var soldCommitted = false

        fun calculateTotal(): Pair<Int, Double> {
            var count = 0
            var total = 0.0
            for (slot in 0 until 45) {
                val stack = window.inventory.getItem(slot) ?: continue
                if (stack.type.isAir) continue
                val unit = worthService.getSellPrice(stack.type) ?: continue
                count += stack.amount
                total += unit * stack.amount
            }
            return count to EconomyService.roundMoney(total)
        }

        fun refreshConfirmButton() {
            val (count, total) = calculateTotal()
            window.set(
                49,
                MenuItems.item(
                    Material.EMERALD_BLOCK,
                    context.locale.raw("economy.sell-gui-confirm"),
                    listOf(
                        context.locale.raw("economy.sell-gui-preview-count", "count" to count),
                        context.locale.raw(
                            "economy.sell-gui-preview-total",
                            "amount" to economyService.formatMoney(total)
                        ),
                    ),
                ),
            ) { click ->
                var soldCount = 0
                var earned = 0.0
                val unsellable = mutableListOf<ItemStack>()
                for (slot in 0 until 45) {
                    val stack = window.inventory.getItem(slot) ?: continue
                    if (stack.type.isAir) continue
                    val unit = worthService.getSellPrice(stack.type)
                    if (unit != null) {
                        soldCount += stack.amount
                        earned += unit * stack.amount
                        window.inventory.setItem(slot, null)
                    } else {
                        unsellable += stack
                        window.inventory.setItem(slot, null)
                    }
                }
                soldCommitted = true
                unsellable.forEach { item ->
                    val leftover = click.player.inventory.addItem(item)
                    leftover.values.forEach { click.player.world.dropItemNaturally(click.player.location, it) }
                }
                click.player.closeInventory()
                if (soldCount > 0) {
                    val rounded = EconomyService.roundMoney(earned)
                    economyService.deposit(click.player.uniqueId, rounded, click.player.name)
                    val formatted = economyService.formatMoney(rounded)
                    context.locale.send(click.player, "economy.sold", "count" to soldCount, "amount" to formatted)
                    context.actionBar.pushTransientLocale(
                        click.player,
                        "economy.sold",
                        "count" to soldCount,
                        "amount" to formatted
                    )
                } else {
                    context.locale.send(click.player, "economy.nothing-to-sell")
                }
            }
        }

        window.onEditableChanged = { refreshConfirmButton() }
        window.onClose = { event ->
            if (!soldCommitted) {
                val p = event.player as? Player
                if (p != null) {
                    for (slot in 0 until 45) {
                        val stack = window.inventory.getItem(slot) ?: continue
                        if (stack.type.isAir) continue
                        window.inventory.setItem(slot, null)
                        val leftover = p.inventory.addItem(stack)
                        leftover.values.forEach { p.world.dropItemNaturally(p.location, it) }
                    }
                }
            }
        }

        refreshConfirmButton()
        window.open(player)
    }

    private fun registerDelivery(registrar: Commands) {
        val node = Commands.literal("delivery")
            .requires { it.sender.hasPermission("obsidian.command.delivery") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                deliveryService.openGui(player, 1)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.literal("gui").executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    deliveryService.openGui(player, 1)
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.literal("send")
                    .requires { it.sender.hasPermission("obsidian.command.delivery.send") || it.sender.hasPermission("obsidian.admin") }
                    .then(
                        Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                            CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                            b.buildFuture()
                        }.executes { ctx ->
                            val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                            handleSendDelivery(player, StringArgumentType.getString(ctx, "target"), "")
                            Command.SINGLE_SUCCESS
                        }.then(
                            Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                                val player =
                                    CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                                handleSendDelivery(
                                    player,
                                    StringArgumentType.getString(ctx, "target"),
                                    StringArgumentType.getString(ctx, "message"),
                                )
                                Command.SINGLE_SUCCESS
                            }
                        )
                    )
            )
            .then(
                Commands.literal("sendall")
                    .requires {
                        it.sender.hasPermission("obsidian.command.delivery.sendall") || it.sender.hasPermission(
                            "obsidian.admin"
                        )
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        handleSendAllDelivery(player, "")
                        Command.SINGLE_SUCCESS
                    }.then(
                        Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                            val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                            handleSendAllDelivery(player, StringArgumentType.getString(ctx, "message"))
                            Command.SINGLE_SUCCESS
                        }
                    )
            )
            .build()

        registrar.register(context.plugin.pluginMeta, node, "Open or send item deliveries", emptyList())
    }

    private fun handleSendDelivery(sender: Player, targetName: String, message: String) {
        val held = sender.inventory.itemInMainHand
        if (held.type.isAir) {
            context.locale.send(sender, "utility.hold-item")
            return
        }
        val itemCopy = held.clone()
        val online = Bukkit.getPlayerExact(targetName) ?: Bukkit.getPlayer(targetName)
        if (online != null) {
            sender.inventory.setItemInMainHand(null)
            deliveryService.sendDelivery(sender.name, online.uniqueId, itemCopy, message)
            context.locale.send(sender, "economy.delivery-sent", "target" to online.name)
            return
        }
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = { context.userRepository.findByName(targetName) },
            onResult = { user ->
                if (user == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    val currentHand = sender.inventory.itemInMainHand
                    if (currentHand.type.isAir) {
                        context.locale.send(sender, "utility.hold-item")
                        return@dispatchAsync
                    }
                    val toSend = currentHand.clone()
                    sender.inventory.setItemInMainHand(null)
                    deliveryService.sendDelivery(sender.name, user.uuid, toSend, message)
                    context.locale.send(sender, "economy.delivery-sent", "target" to user.username)
                }
            },
        )
    }

    private fun handleSendAllDelivery(sender: Player, message: String) {
        val held = sender.inventory.itemInMainHand
        if (held.type.isAir) {
            context.locale.send(sender, "utility.hold-item")
            return
        }
        val itemCopy = held.clone()
        val targets = Bukkit.getOnlinePlayers().toList()
        for (target in targets) {
            deliveryService.sendDelivery(sender.name, target.uniqueId, itemCopy.clone(), message)
        }
        context.locale.send(sender, "economy.delivery-sent-all", "count" to targets.size)
    }
}
