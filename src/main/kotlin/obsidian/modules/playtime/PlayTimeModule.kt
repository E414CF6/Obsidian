package obsidian.modules.playtime

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import obsidian.modules.economy.EconomyService
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
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

    override fun onEnable() {
        context.registerService(rankService)
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
    }

    override fun listeners(): List<Listener> = listOf(rankService)

    override fun registerCommands(registrar: Commands) {
        registerPlayTime(registrar)
        registerPlayTimeTop(registrar)
        registerRankUp(registrar)
        registerRankInfo(registrar)
        registerRewards(registrar)
    }

    private fun registerPlayTime(registrar: Commands) {
        val node = Commands.literal("playtime")
            .requires { it.sender.hasPermission("obsidian.command.playtime") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val rec = rankService.getOrLoadSnapshot(player.uniqueId, player.name)
                context.locale.send(
                    player,
                    "playtime.self",
                    "total" to TextFormatter.formatDuration(rec.totalMillis),
                    "daily" to TextFormatter.formatDuration(rec.dailyMillis),
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
            if (context.config.registerStandaloneAliases) listOf("pt") else emptyList(),
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
            if (context.config.registerStandaloneAliases) listOf("pttop") else emptyList(),
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
                val rec = rankService.getOrLoadSnapshot(player.uniqueId, player.name)
                val current = rankService.getRank(rec.currentRank)
                val next = current?.nextRank?.let { rankService.getRank(it) }
                if (next == null) {
                    context.locale.send(
                        player,
                        "playtime.rank-max",
                        "current" to (current?.displayName ?: rec.currentRank),
                    )
                    return@executes Command.SINGLE_SUCCESS
                }
                val eco = context.getService<EconomyService>()
                context.locale.send(
                    player,
                    "playtime.rank-info",
                    "current" to (current.displayName),
                    "next" to next.displayName,
                    "req_time" to TextFormatter.formatDuration(next.requiredPlaytimeSeconds * 1000L),
                    "req_cost" to (eco?.formatMoney(next.cost) ?: next.cost.toString()),
                )
                Command.SINGLE_SUCCESS
            }.build()

        registrar.register(context.plugin.pluginMeta, node, "View current and next rank requirements", emptyList())
    }

    private fun registerRewards(registrar: Commands) {
        val node = Commands.literal("prewards")
            .requires { it.sender.hasPermission("obsidian.command.prewards") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val claimed = rankService.claimAvailableRewards(player)
                if (claimed.isEmpty()) {
                    context.locale.send(player, "playtime.reward-none")
                } else {
                    claimed.forEach { reward ->
                        context.locale.send(player, "playtime.reward-claimed", "reward" to reward.displayName)
                    }
                }
                Command.SINGLE_SUCCESS
            }.build()

        registrar.register(
            context.plugin.pluginMeta,
            node,
            "Claim milestone playtime rewards",
            if (context.config.registerStandaloneAliases) listOf("playtimerewards") else emptyList(),
        )
    }
}
