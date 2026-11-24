package obsidian.modules.automation

import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import obsidian.core.ui.ActionBarPriority
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks player activity to manage automatic/manual AFK status and optional idle kicks.
 */
class AfkService(private val context: ObsidianContext) : Listener {

    data class AfkState(
        @Volatile var lastActivityAt: Long,
        @Volatile var isAfk: Boolean = false,
        @Volatile var reason: String? = null,
    )

    private val states = ConcurrentHashMap<UUID, AfkState>()
    private var checkTask: ScheduledTask? = null

    @Volatile
    var idleSeconds: Long = 300L
        private set

    @Volatile
    var kickSeconds: Long = 0L
        private set

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        idleSeconds = raw.getLong("afk.idle-seconds", 300L).coerceAtLeast(0L)
        kickSeconds = raw.getLong("afk.kick-seconds", 0L).coerceAtLeast(0L)
        startTimer()
    }

    fun startTimer() {
        checkTask?.cancel()
        checkTask = context.scheduler.runGlobalTimer(5L, 5L) {
            checkIdlePlayers()
        }
    }

    fun shutdown() {
        checkTask?.cancel()
        checkTask = null
        states.clear()
    }

    fun isAfk(uuid: UUID): Boolean = states[uuid]?.isAfk == true

    fun getReason(uuid: UUID): String? = states[uuid]?.reason

    fun setAfk(player: Player, afk: Boolean, reason: String? = null, silent: Boolean = false) {
        val now = System.currentTimeMillis()
        val state = states.computeIfAbsent(player.uniqueId) { AfkState(now) }
        val changed = state.isAfk != afk
        state.isAfk = afk
        state.reason = if (afk) reason?.takeIf { it.isNotBlank() } else null
        if (!afk) {
            state.lastActivityAt = now
            context.actionBar.clearState(player.uniqueId, "afk")
            context.bossBar.hide(player, "afk_kick_${player.uniqueId}")
        } else {
            context.actionBar.setLocaleState(player, "afk", ActionBarPriority.AFK, "automation.afk-actionbar")
        }

        if (changed && !silent) {
            val key = if (afk) "automation.afk-enter" else "automation.afk-leave"
            Bukkit.broadcast(
                context.locale.component(
                    key,
                    mapOf("player" to player.name, "reason" to (state.reason ?: "")),
                )
            )
        }
    }

    fun recordActivity(player: Player) {
        val now = System.currentTimeMillis()
        val state = states.computeIfAbsent(player.uniqueId) { AfkState(now) }
        state.lastActivityAt = now
        if (state.isAfk) {
            setAfk(player, afk = false)
        }
    }

    private fun checkIdlePlayers() {
        if (idleSeconds <= 0L && kickSeconds <= 0L) return
        val now = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            val state = states.computeIfAbsent(player.uniqueId) { AfkState(now) }
            val elapsedSec = (now - state.lastActivityAt) / 1000L
            if (idleSeconds > 0L && !state.isAfk && elapsedSec >= idleSeconds && !player.hasPermission("obsidian.afk.bypass")) {
                setAfk(player, afk = true)
            }
            if (kickSeconds > 0L && !player.hasPermission("obsidian.afk.kickbypass")) {
                val warnThreshold = context.config.uiAfkKickWarningSeconds.toLong()
                val remSec = (kickSeconds - elapsedSec).coerceAtLeast(0L)
                if (warnThreshold > 0L && remSec in 1L..warnThreshold) {
                    context.bossBar.showStatic(
                        player = player,
                        id = "afk_kick_${player.uniqueId}",
                        title = context.locale.component(
                            "automation.afk-kick-bossbar",
                            mapOf("seconds" to remSec.toString())
                        ),
                        progress = (remSec.toFloat() / warnThreshold.toFloat()).coerceIn(0f, 1f),
                        color = BossBar.Color.RED,
                    )
                }
                if (elapsedSec >= kickSeconds) {
                    context.scheduler.runForEntity(player) {
                        if (player.isOnline) {
                            player.kick(TextFormatter.format(context.locale.raw("automation.afk-kicked")))
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        states[event.player.uniqueId] = AfkState(System.currentTimeMillis())
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        states.remove(event.player.uniqueId)
        context.actionBar.clearState(event.player.uniqueId, "afk")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ && from.yaw == to.yaw) {
            return
        }
        recordActivity(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        recordActivity(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        context.scheduler.runForEntity(event.player) {
            recordActivity(event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val cmd = event.message.lowercase()
        if (!cmd.startsWith("/afk")) {
            recordActivity(event.player)
        }
    }
}
