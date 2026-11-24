package obsidian.modules.playtime

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import obsidian.core.ui.DialogBuilder
import obsidian.modules.economy.EconomyService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import kotlin.math.ceil

/**
 * PlayTime tracking, leaderboard, rankup progression, and milestone rewards module
 * (`/playtime`, `/playtimetop`, `/rankup`, `/rankinfo`, `/prewards`).
 */
class PlayTimeModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "playtime"
    override val description: String = "PlayTime tracker, ranks, and milestone rewards"

    val rankService = RankService(context)
    val voteService = VoteService(context)

    override fun onEnable() {
        context.registerService(rankService)
        context.registerService(voteService)
        voteService.loadAllAsync()
        voteService.registerVotifierHookIfPresent()
        val interval = context.config.raw.getLong("playtime.flush-interval-seconds", 30L).coerceAtLeast(5L)
        context.scheduler.runGlobalTimer(interval, interval) {
            rankService.tickAllOnline()
        }
    }

    override fun onDisable() {
        rankService.flushAllBlocking()
    }

    override fun onReload() {
        rankService.reload()
        voteService.reload()
    }

    override fun listeners(): List<Listener> = listOf(rankService, voteService)

    override fun registerCommands(registrar: Commands) {
        registerPlayTime(registrar)
        registerPlayTimeTop(registrar)
        registerRankUp(registrar)
        registerRankInfo(registrar)
        registerRewards(registrar)
        registerVoteCommands(registrar)
    }

    private fun registerPlayTime(registrar: Commands) {
        val node = Commands.literal("playtime")
            .requires { it.sender.hasPermission("obsidian.command.playtime") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val rec = rankService.getOrLoadSnapshot(player.uniqueId, player.name)
                val totalStr = TextFormatter.formatDuration(rec.totalMillis)
                val dailyStr = TextFormatter.formatDuration(rec.dailyMillis)
                context.locale.send(
                    player,
                    "playtime.self",
                    "total" to totalStr,
                    "daily" to dailyStr,
                )
                context.actionBar.pushTransientLocale(
                    player,
                    "playtime.self-actionbar",
                    "total" to totalStr,
                    "daily" to dailyStr,
                )
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("target", StringArgumentType.word())
                    .requires { it.sender.hasPermission("obsidian.command.playtime.others") || it.sender.hasPermission("obsidian.admin") }
                    .suggests { ctx, builder ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, builder.remaining)
                            .forEach(builder::suggest)
                        builder.buildFuture()
                    }.executes { ctx ->
                        val sender = ctx.source.sender
                        val targetName = StringArgumentType.getString(ctx, "target")
                        showOtherPlaytime(sender, targetName)
                        Command.SINGLE_SUCCESS
                    }).build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Check online playtime",
            listOf("pt"),
        )
    }

    private fun showOtherPlaytime(sender: CommandSender, targetName: String) {
        val online = Bukkit.getPlayerExact(targetName) ?: Bukkit.getPlayer(targetName)
        if (online != null) {
            val rec = rankService.getOrLoadSnapshot(online.uniqueId, online.name)
            context.locale.send(
                sender,
                "playtime.other",
                "target" to online.name,
                "total" to TextFormatter.formatDuration(rec.totalMillis),
                "daily" to TextFormatter.formatDuration(rec.dailyMillis),
            )
            return
        }
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val user = context.users.findByName(targetName)
                user?.let { context.playTimeRepository.findByUuid(it.uuid) }
            },
            onResult = { rec ->
                if (rec == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    context.locale.send(
                        sender,
                        "playtime.other",
                        "target" to rec.username,
                        "total" to TextFormatter.formatDuration(rec.totalMillis),
                        "daily" to TextFormatter.formatDuration(rec.dailyMillis),
                    )
                }
            },
        )
    }

    private fun registerPlayTimeTop(registrar: Commands) {
        val node = Commands.literal("playtimetop")
            .requires { it.sender.hasPermission("obsidian.command.playtimetop") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                sendPlayTimeTop(ctx.source.sender, 1)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                    sendPlayTimeTop(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "page"))
                    Command.SINGLE_SUCCESS
                }).build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Show top playtime leaderboard",
            listOf("pttop"),
        )
    }

    private fun sendPlayTimeTop(sender: CommandSender, requestedPage: Int) {
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                rankService.flushAll()
                context.playTimeRepository.topTotal(100)
            },
            onResult = { top ->
                if (top.isEmpty()) {
                    context.locale.send(sender, "playtime.top-empty")
                    return@dispatchAsync
                }
                if (sender is Player && context.config.uiGuiLeaderboards) {
                    openPlayTimeTopGui(sender, top, requestedPage)
                    return@dispatchAsync
                }
                val perPage = 10
                val totalPages = ceil(top.size.toDouble() / perPage).toInt().coerceAtLeast(1)
                val page = requestedPage.coerceIn(1, totalPages)
                val from = (page - 1) * perPage
                val to = minOf(from + perPage, top.size)

                context.locale.send(
                    sender, "playtime.top-header", "page" to page.toString(), "pages" to totalPages.toString()
                )
                for (i in from until to) {
                    val rec = top[i]
                    context.locale.sendRaw(
                        sender,
                        "playtime.top-entry",
                        "rank" to (i + 1).toString(),
                        "player" to rec.username,
                        "time" to TextFormatter.formatDuration(rec.totalMillis),
                    )
                }
            },
        )
    }

    private fun registerRankUp(registrar: Commands) {
        val node = Commands.literal("rankup")
            .requires { it.sender.hasPermission("obsidian.command.rankup") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val eco = context.getService<EconomyService>()
                when (val res = rankService.tryRankUp(player, chargeCost = true, silentIfNotReady = false)) {
                    is RankService.RankUpResult.Promoted -> Unit
                    is RankService.RankUpResult.AlreadyMax -> context.locale.send(
                        player,
                        "playtime.rank-max",
                        "current" to res.currentRank.displayName,
                    )

                    is RankService.RankUpResult.NotReady -> {
                        val next = res.nextRank
                        val reqTime =
                            next?.let { TextFormatter.formatDuration(it.requiredPlaytimeSeconds * 1000L) } ?: "-"
                        val reqCost = next?.let { eco?.formatMoney(it.cost) ?: it.cost.toString() } ?: "-"
                        context.locale.send(
                            player,
                            "playtime.rank-not-ready",
                            "req_time" to reqTime,
                            "req_cost" to reqCost,
                        )
                    }
                }
                Command.SINGLE_SUCCESS
            }.build()

        registrar.register(context.plugin.pluginMeta, node, "Promote to next rank", emptyList())
    }

    private fun registerRankInfo(registrar: Commands) {
        val node = Commands.literal("rankinfo")
            .requires { it.sender.hasPermission("obsidian.command.rankinfo") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                openRankInfoGui(player)
                Command.SINGLE_SUCCESS
            }.build()

        registrar.register(context.plugin.pluginMeta, node, "View current and next rank requirements", emptyList())
    }

    private fun registerRewards(registrar: Commands) {
        val node = Commands.literal("prewards")
            .requires { it.sender.hasPermission("obsidian.command.prewards") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                openRewardsGui(player)
                Command.SINGLE_SUCCESS
            }.build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Claim milestone playtime rewards",
            listOf("playtimerewards"),
        )
    }

    private fun openPlayTimeTopGui(
        player: Player,
        top: List<obsidian.core.database.PlayTimeRecord>,
        requestedPage: Int,
    ) {
        val perPage = 28
        val totalPages = ceil(top.size.toDouble() / perPage).toInt().coerceAtLeast(1)
        val page = requestedPage.coerceIn(1, totalPages)
        val window = MenuWindow(
            context.locale.component(
                "playtime.top-gui-title",
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
                    displayName = context.locale.raw("playtime.top-gui-item", "rank" to rank, "player" to rec.username),
                    lore = listOf(
                        context.locale.raw(
                            "playtime.top-gui-total",
                            "time" to TextFormatter.formatDuration(rec.totalMillis)
                        ),
                        context.locale.raw("playtime.top-gui-rank", "rank" to rec.currentRank),
                    ),
                )
                window.set(row * 9 + col, head)
            }
        }
        window.setFooterNav(
            context = context,
            page = page,
            totalPages = totalPages,
            onPrev = { openPlayTimeTopGui(player, top, page - 1) },
            onNext = { openPlayTimeTopGui(player, top, page + 1) },
        )
        window.open(player)
    }

    private fun openRankInfoGui(player: Player) {
        val rec = rankService.getOrLoadSnapshot(player.uniqueId, player.name)
        val allRanks = rankService.getAllRanks()
        val eco = context.getService<EconomyService>()
        val window = MenuWindow(context.locale.component("playtime.rank-gui-title"), 4)
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        var slot = 10
        for (r in allRanks) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++
            if (slot >= 26) break
            val isCurrent = r.id.equals(rec.currentRank, ignoreCase = true)
            val unlocked = rec.totalMillis >= r.requiredPlaytimeSeconds * 1000L
            val mat = when {
                isCurrent -> Material.NETHER_STAR
                unlocked -> Material.LIME_DYE
                else -> Material.GRAY_DYE
            }
            val lore = buildList {
                add(
                    context.locale.raw(
                        "playtime.rank-gui-req-time",
                        "time" to TextFormatter.formatDuration(r.requiredPlaytimeSeconds * 1000L)
                    )
                )
                add(
                    context.locale.raw(
                        "playtime.rank-gui-req-cost",
                        "cost" to (eco?.formatMoney(r.cost) ?: r.cost.toString())
                    )
                )
                if (isCurrent) {
                    add(context.locale.raw("playtime.rank-gui-current"))
                } else if (unlocked) {
                    add(context.locale.raw("playtime.rank-gui-unlocked"))
                } else {
                    add(context.locale.raw("playtime.rank-gui-locked"))
                }
            }
            window.set(slot++, MenuItems.item(mat, r.displayName, lore))
        }

        val current = rankService.getRank(rec.currentRank)
        val next = current?.nextRank?.let { rankService.getRank(it) }
        if (next != null) {
            val reqTime = next.requiredPlaytimeSeconds * 1000L
            val readyTime = rec.totalMillis >= reqTime
            val readyMoney = eco == null || next.cost <= 0.0 || eco.getBalance(player.uniqueId) >= next.cost
            val canPromote = readyTime && readyMoney
            window.set(
                31,
                MenuItems.item(
                    if (canPromote) Material.EMERALD_BLOCK else Material.CLOCK,
                    context.locale.raw(if (canPromote) "playtime.rank-gui-promote-ready" else "playtime.rank-gui-promote-wait"),
                    listOf(
                        context.locale.raw("playtime.rank-gui-next", "next" to next.displayName),
                        context.locale.raw(
                            "playtime.rank-gui-req-time",
                            "time" to TextFormatter.formatDuration(reqTime)
                        ),
                        context.locale.raw(
                            "playtime.rank-gui-req-cost",
                            "cost" to (eco?.formatMoney(next.cost) ?: next.cost.toString())
                        ),
                    ),
                ),
            ) { click ->
                if (canPromote) {
                    click.player.closeInventory()
                    DialogBuilder.confirm(
                        context = context,
                        player = click.player,
                        title = context.locale.component("playtime.rankup-dialog-title"),
                        bodyLines = listOf(
                            context.locale.component(
                                "playtime.rankup-dialog-body",
                                mapOf(
                                    "next" to next.displayName,
                                    "cost" to (eco?.formatMoney(next.cost) ?: next.cost.toString()),
                                ),
                            ),
                        ),
                        confirmLabel = context.locale.component("general.dialog-confirm"),
                        cancelLabel = context.locale.component("general.dialog-cancel"),
                        onConfirm = { p -> rankService.tryRankUp(p, chargeCost = true, silentIfNotReady = false) },
                    )
                } else {
                    context.actionBar.pushTransientLocale(
                        click.player,
                        "playtime.rank-not-ready",
                        "req_time" to TextFormatter.formatDuration(reqTime),
                        "req_cost" to (eco?.formatMoney(next.cost) ?: next.cost.toString()),
                    )
                }
            }
        }
        window.open(player)
    }

    private fun openRewardsGui(player: Player) {
        val rec = rankService.getOrLoadSnapshot(player.uniqueId, player.name)
        val rewards = rankService.getAllRewards()
        if (rewards.isEmpty()) {
            context.locale.send(player, "playtime.reward-none")
            return
        }
        val window = MenuWindow(context.locale.component("playtime.rewards-gui-title"), 5)
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        var slot = 10
        var availableCount = 0
        for (reward in rewards) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++
            if (slot >= 35) break
            val isClaimed = reward.id.lowercase() in rec.claimedRewards
            val isReady = !isClaimed && rec.totalMillis >= reward.requiredPlaytimeSeconds * 1000L
            if (isReady) availableCount++
            val mat = when {
                isClaimed -> Material.LIME_DYE
                isReady -> Material.CHEST
                else -> Material.IRON_BARS
            }
            val statusKey = when {
                isClaimed -> "playtime.rewards-gui-claimed"
                isReady -> "playtime.rewards-gui-ready"
                else -> "playtime.rewards-gui-locked"
            }
            val lore = listOf(
                context.locale.raw(
                    "playtime.rank-gui-req-time",
                    "time" to TextFormatter.formatDuration(reward.requiredPlaytimeSeconds * 1000L)
                ),
                context.locale.raw(statusKey),
            )
            window.set(slot++, MenuItems.item(mat, reward.displayName, lore)) { click ->
                if (isReady) {
                    val claimed = rankService.claimSingleReward(click.player, reward.id)
                    if (claimed != null) {
                        context.locale.send(click.player, "playtime.reward-claimed", "reward" to claimed.displayName)
                        context.actionBar.pushTransientLocale(
                            click.player,
                            "playtime.reward-claimed",
                            "reward" to claimed.displayName
                        )
                        openRewardsGui(click.player)
                    }
                }
            }
        }

        if (availableCount > 0) {
            window.set(
                40,
                MenuItems.item(
                    Material.EMERALD_BLOCK,
                    context.locale.raw("playtime.rewards-gui-claim-all", "count" to availableCount),
                ),
            ) { click ->
                val list = rankService.claimAvailableRewards(click.player)
                list.forEach { r ->
                    context.locale.send(click.player, "playtime.reward-claimed", "reward" to r.displayName)
                }
                openRewardsGui(click.player)
            }
        }
        window.open(player)
    }

    private fun registerVoteCommands(registrar: Commands) {
        val votesNode = Commands.literal("votes")
            .requires { it.sender.hasPermission("obsidian.command.votes") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val count = voteService.getVoteCount(player.uniqueId)
                context.locale.send(player, "playtime.votes-self", "votes" to count)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("target", StringArgumentType.word())
                    .requires { it.sender.hasPermission("obsidian.command.votes.others") || it.sender.hasPermission("obsidian.admin") }
                    .suggests { ctx, b ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val sender = ctx.source.sender
                        val targetName = StringArgumentType.getString(ctx, "target")
                        val online = Bukkit.getPlayerExact(targetName) ?: Bukkit.getPlayer(targetName)
                        if (online != null) {
                            context.locale.send(
                                sender,
                                "playtime.votes-other",
                                "target" to online.name,
                                "votes" to voteService.getVoteCount(online.uniqueId),
                            )
                        } else {
                            CommandHelper.dispatchAsync(
                                context,
                                sender,
                                asyncWork = { context.userRepository.findByName(targetName) },
                                onResult = { user ->
                                    if (user == null) {
                                        context.locale.send(sender, "general.player-not-found", "player" to targetName)
                                    } else {
                                        context.locale.send(
                                            sender,
                                            "playtime.votes-other",
                                            "target" to user.username,
                                            "votes" to voteService.getVoteCount(user.uuid),
                                        )
                                    }
                                },
                            )
                        }
                        Command.SINGLE_SUCCESS
                    }
            ).build()
        registrar.register(context.plugin.pluginMeta, votesNode, "Check vote counts", emptyList())

        val voteTopNode = Commands.literal("votetop")
            .requires { it.sender.hasPermission("obsidian.command.votetop") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                sendVoteTop(ctx.source.sender, 1)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                    sendVoteTop(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "page"))
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(context.plugin.pluginMeta, voteTopNode, "View vote leaderboard", emptyList())

        val voteEditNode = Commands.literal("voteedit")
            .requires { it.sender.hasPermission("obsidian.command.voteedit") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("add").then(
                    Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                        b.buildFuture()
                    }.then(
                        Commands.argument("amount", IntegerArgumentType.integer(1)).executes { ctx ->
                            handleVoteEdit(
                                ctx.source.sender,
                                StringArgumentType.getString(ctx, "target"),
                                "add",
                                IntegerArgumentType.getInteger(ctx, "amount").toLong()
                            )
                            Command.SINGLE_SUCCESS
                        }
                    ).executes { ctx ->
                        handleVoteEdit(ctx.source.sender, StringArgumentType.getString(ctx, "target"), "add", 1L)
                        Command.SINGLE_SUCCESS
                    }
                )
            )
            .then(
                Commands.literal("set").then(
                    Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                        b.buildFuture()
                    }.then(
                        Commands.argument("amount", IntegerArgumentType.integer(0)).executes { ctx ->
                            handleVoteEdit(
                                ctx.source.sender,
                                StringArgumentType.getString(ctx, "target"),
                                "set",
                                IntegerArgumentType.getInteger(ctx, "amount").toLong()
                            )
                            Command.SINGLE_SUCCESS
                        }
                    )
                )
            )
            .then(
                Commands.literal("clear").then(
                    Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        handleVoteEdit(ctx.source.sender, StringArgumentType.getString(ctx, "target"), "clear", 0L)
                        Command.SINGLE_SUCCESS
                    }
                )
            )
            .build()
        registrar.register(context.plugin.pluginMeta, voteEditNode, "Manage player vote counts", emptyList())
    }

    private fun sendVoteTop(sender: CommandSender, requestedPage: Int) {
        val all = voteService.getTopVotes(100, 0)
        if (all.isEmpty()) {
            context.locale.send(sender, "playtime.votetop-empty")
            return
        }
        if (sender is Player && context.config.uiGuiLeaderboards) {
            openVoteTopGui(sender, all, requestedPage)
            return
        }
        val perPage = 10
        val totalPages = ceil(all.size.toDouble() / perPage).toInt().coerceAtLeast(1)
        val page = requestedPage.coerceIn(1, totalPages)
        val from = (page - 1) * perPage
        val to = minOf(from + perPage, all.size)

        context.locale.send(sender, "playtime.votetop-header", "page" to page, "pages" to totalPages)
        for (i in from until to) {
            val rec = all[i]
            context.locale.sendRaw(
                sender,
                "playtime.votetop-entry",
                "rank" to (i + 1),
                "player" to rec.username,
                "votes" to rec.voteCount,
            )
        }
    }

    private fun openVoteTopGui(
        player: Player,
        all: List<obsidian.core.database.VoteRecord>,
        requestedPage: Int,
    ) {
        val perPage = 28
        val totalPages = ceil(all.size.toDouble() / perPage).toInt().coerceAtLeast(1)
        val page = requestedPage.coerceIn(1, totalPages)
        val window = MenuWindow(
            context.locale.component(
                "playtime.votetop-gui-title",
                mapOf("page" to page.toString(), "pages" to totalPages.toString())
            ),
            6,
        )
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val from = (page - 1) * perPage
        val slice = all.drop(from).take(perPage)
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
                        "playtime.votetop-gui-item",
                        "rank" to rank,
                        "player" to rec.username
                    ),
                    lore = listOf(context.locale.raw("playtime.votetop-gui-votes", "votes" to rec.voteCount)),
                )
                window.set(row * 9 + col, head)
            }
        }
        window.setFooterNav(
            context = context,
            page = page,
            totalPages = totalPages,
            onPrev = { openVoteTopGui(player, all, page - 1) },
            onNext = { openVoteTopGui(player, all, page + 1) },
        )
        window.open(player)
    }

    private fun handleVoteEdit(sender: CommandSender, targetName: String, action: String, amount: Long) {
        val online = Bukkit.getPlayerExact(targetName) ?: Bukkit.getPlayer(targetName)
        if (online != null) {
            applyVoteEdit(sender, online.uniqueId, online.name, action, amount)
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
                    applyVoteEdit(sender, user.uuid, user.username, action, amount)
                }
            },
        )
    }

    private fun applyVoteEdit(sender: CommandSender, uuid: java.util.UUID, name: String, action: String, amount: Long) {
        val updated = when (action) {
            "add" -> voteService.addVotes(uuid, name, amount)
            "set" -> voteService.setVotes(uuid, name, amount)
            else -> voteService.setVotes(uuid, name, 0L)
        }
        context.locale.send(
            sender,
            "playtime.voteedit-updated",
            "target" to name,
            "votes" to updated.voteCount,
        )
    }
}
