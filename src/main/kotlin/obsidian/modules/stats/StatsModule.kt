package obsidian.modules.stats

import com.mojang.brigadier.Command
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.launch
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.integration.LuckPermsHook
import obsidian.modules.playtime.RankService
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.nio.file.Path

/**
 * Web statistics & RSA-SHA256 authenticated REST API module for Obsidian (`web/` dashboard compatible).
 * Aggregates native Obsidian database records and vanilla world JSON files, and exposes
 * `/serve` (aliases: `/statsapi`, `/stats`) with `status`, `reload`, `genkey`, and `refresh`.
 */
class StatsModule(private val context: ObsidianContext) : ObsidianModule, Listener {

    override val id: String = "stats"
    override val description: String = "Web telemetry & RSA-authenticated REST API (/serve, /stats)"

    lateinit var rsaAuth: RsaAuthService
        private set
    lateinit var aggregator: StatsAggregatorService
        private set
    lateinit var httpServer: StatsHttpServer
        private set

    private var sampleTask: ScheduledTask? = null
    private var httpEnabled: Boolean = true

    override fun onEnable() {
        val dataPath = context.plugin.dataFolder.toPath()
        val defaultKeysDir = dataPath.resolve(context.config.raw.getString("stats.auth.keys-dir", "keys") ?: "keys")
        val skew = context.config.raw.getLong("stats.auth.clock-skew-seconds", 300L)

        rsaAuth = RsaAuthService(defaultKeysDir, skew)
        val generated = rsaAuth.ensureKeys()
        if (generated) {
            context.plugin.logger.info("Generated Stats RSA-2048 keys at ${rsaAuth.keysDirLabel()}.")
        }

        val rankService = context.getService<RankService>()
        val luckPerms = context.getService<LuckPermsHook>() ?: LuckPermsHook()
        aggregator = StatsAggregatorService(
            db = context.database,
            rankService = rankService,
            luckPermsHook = luckPerms,
            logger = context.plugin.logger,
        )
        aggregator.ensureSchema()

        httpServer = StatsHttpServer(aggregator, rsaAuth, context.plugin.logger)
        applyConfig()

        if (httpEnabled) {
            httpServer.start()
        }

        // Periodically sample online players into per-day 24h buckets every 30s.
        sampleTask = context.scheduler.runGlobalTimer(30L, 30L) {
            val now = System.currentTimeMillis()
            val onlineUuids = Bukkit.getOnlinePlayers().map { it.uniqueId }
            if (onlineUuids.isNotEmpty()) {
                context.scheduler.asyncScope.launch {
                    for (uuid in onlineUuids) {
                        aggregator.recordPlaytimeTick(uuid, now)
                    }
                }
            }
        }

        context.registerService(this)
        context.registerService(aggregator)
        context.registerService(rsaAuth)
        context.plugin.server.pluginManager.registerEvents(this, context.plugin)
    }

    override fun onDisable() {
        sampleTask?.cancel()
        sampleTask = null
        if (::httpServer.isInitialized) {
            httpServer.stop()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        if (!::aggregator.isInitialized) return
        val uuid = event.player.uniqueId
        val now = System.currentTimeMillis()
        context.scheduler.asyncScope.launch {
            aggregator.recordPlaytimeTick(uuid, now)
            aggregator.clearSessionTrack(uuid)
            aggregator.invalidateCache()
        }
    }

    fun reloadSettings(): Boolean {
        context.config.reload()
        val wasRunning = ::httpServer.isInitialized && httpServer.isRunning
        val prevHost = httpServer.host
        val prevPort = httpServer.port
        applyConfig()
        aggregator.invalidateCache()

        if (!httpEnabled) {
            if (wasRunning) httpServer.stop()
            return false
        }
        if (!wasRunning || prevHost != httpServer.host || prevPort != httpServer.port) {
            httpServer.stop()
            return httpServer.start()
        }
        return true
    }

    private fun applyConfig() {
        val raw = context.config.raw
        val dataPath = context.plugin.dataFolder.toPath()
        val configuredKeys = raw.getString("stats.auth.keys-dir", "keys") ?: "keys"
        val resolvedKeys = Path.of(configuredKeys).let { if (it.isAbsolute) it else dataPath.resolve(it) }
        val skew = raw.getLong("stats.auth.clock-skew-seconds", 300L)
        rsaAuth.configure(resolvedKeys, skew)

        aggregator.cacheTtlSeconds = raw.getLong("stats.cache-ttl-seconds", 15L)

        httpEnabled = raw.getBoolean("stats.http.enabled", true)
        httpServer.host = raw.getString("stats.http.host", "0.0.0.0") ?: "0.0.0.0"
        httpServer.port = raw.getInt("stats.http.port", 4000)
        httpServer.workerThreads = raw.getInt("stats.http.worker-threads", 4)
        httpServer.corsOrigin = raw.getString("stats.http.cors-origin", "*") ?: "*"
        httpServer.requireAuth = raw.getBoolean("stats.auth.require-signature", true)
        httpServer.apiKey = raw.getString("stats.auth.api-key", "") ?: ""
    }

    override fun registerCommands(registrar: Commands) {
        CommandHelper.registerWithOptionalAliases(
            registrar = registrar,
            context = context,
            primaryName = "serve",
            description = "Manage Obsidian Stats REST API and RSA keys",
            aliases = listOf("statsapi", "stats"),
            nodeBuilder = { rootName ->
                Commands.literal(rootName)
                    .requires { it.sender.hasPermission("obsidian.stats.admin") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        sendStatus(ctx.source.sender)
                        Command.SINGLE_SUCCESS
                    }.then(
                        Commands.literal("status").executes { ctx ->
                                sendStatus(ctx.source.sender)
                                Command.SINGLE_SUCCESS
                            }).then(
                        Commands.literal("reload").executes { ctx ->
                                val running = reloadSettings()
                                val state =
                                    if (running) "${httpServer.host}:${httpServer.boundPort}" else "disabled/stopped"
                                context.locale.send(ctx.source.sender, "stats.reloaded", "state" to state)
                                Command.SINGLE_SUCCESS
                            }).then(
                        Commands.literal("genkey").executes { ctx ->
                                rsaAuth.generateKeys()
                                context.locale.send(
                                    ctx.source.sender,
                                    "stats.genkey-done",
                                    "path" to rsaAuth.publicKeyFile.toString(),
                                )
                                Command.SINGLE_SUCCESS
                            }).then(
                        Commands.literal("refresh").executes { ctx ->
                                val sender = ctx.source.sender
                                context.scheduler.asyncScope.launch {
                                    val snapshot = aggregator.getSnapshot(forceRefresh = true)
                                    context.scheduler.runGlobal {
                                        context.locale.send(
                                            sender,
                                            "stats.refreshed",
                                            "players" to snapshot.overview.totalPlayers,
                                            "online" to snapshot.overview.onlinePlayers,
                                        )
                                    }
                                }
                                Command.SINGLE_SUCCESS
                            })
            },
        )
    }

    private fun sendStatus(sender: CommandSender) {
        val bindState = if (httpServer.isRunning) {
            "RUNNING (${httpServer.host}:${httpServer.boundPort})"
        } else {
            "STOPPED"
        }
        val cached = aggregator.getLastCachedSnapshot()
        val playersCount = cached?.overview?.totalPlayers ?: Bukkit.getOfflinePlayers().size
        val authMode = buildString {
            append(if (httpServer.requireAuth) "RSA-SHA256" else "open")
            if (httpServer.apiKey.isNotBlank()) append("+ApiKey")
        }
        context.locale.send(
            sender,
            "stats.status",
            "bind" to bindState,
            "auth" to authMode,
            "players" to playersCount,
            "ttl" to aggregator.cacheTtlSeconds,
        )
    }

    private fun RsaAuthService.keysDirLabel(): String = publicKeyFile.parent.toString()
}
