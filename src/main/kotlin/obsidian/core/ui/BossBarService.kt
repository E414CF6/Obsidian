package obsidian.core.ui

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized Adventure [BossBar] lifecycle manager for per-player timers, progress bars, and global countdowns.
 */
class BossBarService(private val context: ObsidianContext? = null) : Listener {

    data class ManagedPlayerBar(
        val id: String,
        val bar: BossBar,
        val createdAtMillis: Long,
        var expiresAtMillis: Long?,
        var countDown: Boolean,
    )

    data class GlobalCountdown(
        val id: String,
        val bar: BossBar,
        val totalSeconds: Int,
        var remainingSeconds: Int,
        val titleProvider: (Int) -> Component,
        val onComplete: (() -> Unit)?,
    )

    private val playerBars = ConcurrentHashMap<UUID, ConcurrentHashMap<String, ManagedPlayerBar>>()
    private val globalBars = ConcurrentHashMap<String, GlobalCountdown>()
    private var tickTask: ScheduledTask? = null

    fun start() {
        val ctx = context ?: return
        stop()
        tickTask = ctx.scheduler.runAsyncTimer(1L, 1L) {
            tick()
        }
    }

    private fun onlinePlayers(): Collection<Player> =
        if ((Bukkit.getServer() as Any?) != null) Bukkit.getOnlinePlayers() else emptyList()

    private fun playerByUuid(uuid: UUID): Player? =
        if ((Bukkit.getServer() as Any?) != null) Bukkit.getPlayer(uuid) else null

    fun stop() {
        tickTask?.cancel()
        tickTask = null
        for ((uuid, bars) in playerBars) {
            val player = playerByUuid(uuid)
            if (player != null && player.isOnline) {
                bars.values.forEach { player.hideBossBar(it.bar) }
            }
        }
        playerBars.clear()

        for (global in globalBars.values) {
            for (player in onlinePlayers()) {
                player.hideBossBar(global.bar)
            }
        }
        globalBars.clear()
    }

    fun showStatic(
        player: Player,
        id: String,
        title: Component,
        progress: Float = 1.0f,
        color: BossBar.Color = BossBar.Color.PURPLE,
        overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS,
    ) {
        if (!canShowTo(player)) return
        upsertPlayerBar(player, id, title, progress.coerceIn(0f, 1f), color, overlay, null, countDown = false)
    }

    fun showTimed(
        player: Player,
        id: String,
        title: Component,
        durationMillis: Long,
        color: BossBar.Color = BossBar.Color.BLUE,
        overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS,
        countDown: Boolean = true,
    ) {
        if (!canShowTo(player)) return
        val safeDur = durationMillis.coerceAtLeast(1000L)
        val now = System.currentTimeMillis()
        upsertPlayerBar(
            player = player,
            id = id,
            title = title,
            progress = if (countDown) 1.0f else 0.0f,
            color = color,
            overlay = overlay,
            expiresAtMillis = now + safeDur,
            countDown = countDown,
        )
    }

    fun showGlobalCountdown(
        id: String,
        durationSeconds: Int,
        color: BossBar.Color = BossBar.Color.RED,
        overlay: BossBar.Overlay = BossBar.Overlay.NOTCHED_10,
        titleProvider: (remainingSeconds: Int) -> Component,
        onComplete: (() -> Unit)? = null,
    ) {
        val ctx = context
        if (ctx != null && !ctx.config.uiBossBarTimers) {
            onComplete?.let { ctx.scheduler.runGlobal(it) }
            return
        }
        hideGlobal(id)
        val total = durationSeconds.coerceAtLeast(1)
        val bar = BossBar.bossBar(titleProvider(total), 1.0f, color, overlay)
        val entry = GlobalCountdown(
            id = id,
            bar = bar,
            totalSeconds = total,
            remainingSeconds = total,
            titleProvider = titleProvider,
            onComplete = onComplete,
        )
        globalBars[id] = entry
        for (player in onlinePlayers()) {
            if (canShowTo(player)) {
                player.showBossBar(bar)
            }
        }
    }

    fun hide(player: Player, id: String) {
        val map = playerBars[player.uniqueId] ?: return
        val removed = map.remove(id) ?: return
        if (map.isEmpty()) playerBars.remove(player.uniqueId)
        if (player.isOnline) {
            player.hideBossBar(removed.bar)
        }
    }

    fun hideGlobal(id: String) {
        val removed = globalBars.remove(id) ?: return
        for (player in onlinePlayers()) {
            player.hideBossBar(removed.bar)
        }
    }

    fun hideAll(player: Player) {
        val map = playerBars.remove(player.uniqueId)
        if (player.isOnline) {
            map?.values?.forEach { player.hideBossBar(it.bar) }
            globalBars.values.forEach { player.hideBossBar(it.bar) }
        }
    }

    fun hasPlayerBar(uuid: UUID, id: String): Boolean = playerBars[uuid]?.containsKey(id) == true

    fun hasGlobalBar(id: String): Boolean = globalBars.containsKey(id)

    private fun canShowTo(player: Player): Boolean {
        val ctx = context ?: return true
        return ctx.config.uiBossBarTimers && UiPreferences.isBossBarTimersEnabled(ctx, player)
    }

    private fun upsertPlayerBar(
        player: Player,
        id: String,
        title: Component,
        progress: Float,
        color: BossBar.Color,
        overlay: BossBar.Overlay,
        expiresAtMillis: Long?,
        countDown: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val map = playerBars.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val existing = map[id]
        if (existing != null) {
            existing.bar.name(title)
            existing.bar.progress(progress)
            existing.bar.color(color)
            existing.bar.overlay(overlay)
            existing.expiresAtMillis = expiresAtMillis
            existing.countDown = countDown
            if (player.isOnline) player.showBossBar(existing.bar)
            return
        }
        val bar = BossBar.bossBar(title, progress, color, overlay)
        map[id] = ManagedPlayerBar(id, bar, now, expiresAtMillis, countDown)
        if (player.isOnline) {
            player.showBossBar(bar)
        }
    }

    /**
     * Advances timers and expires completed bars; exposed for unit testing.
     */
    fun tick(nowMillis: Long = System.currentTimeMillis()) {
        for (uuid in playerBars.keys.toList()) {
            val map = playerBars[uuid] ?: continue
            val player = playerByUuid(uuid)
            if (player == null || !player.isOnline) {
                playerBars.remove(uuid)
                continue
            }
            for ((barId, entry) in map.entries.toList()) {
                val exp = entry.expiresAtMillis ?: continue
                val total = (exp - entry.createdAtMillis).coerceAtLeast(1L).toFloat()
                val rem = exp - nowMillis
                if (rem <= 0L) {
                    map.remove(barId)
                    player.hideBossBar(entry.bar)
                } else {
                    val ratio = (rem / total).coerceIn(0f, 1f)
                    entry.bar.progress(if (entry.countDown) ratio else (1.0f - ratio).coerceIn(0f, 1f))
                }
            }
            if (map.isEmpty()) {
                playerBars.remove(uuid)
            }
        }

        for ((id, global) in globalBars.entries.toList()) {
            global.remainingSeconds -= 1
            if (global.remainingSeconds <= 0) {
                globalBars.remove(id)
                for (p in onlinePlayers()) {
                    p.hideBossBar(global.bar)
                }
                global.onComplete?.let { callback ->
                    val ctx = context
                    if (ctx != null) ctx.scheduler.runGlobal(callback) else callback()
                }
            } else {
                global.bar.name(global.titleProvider(global.remainingSeconds))
                global.bar.progress(
                    (global.remainingSeconds.toFloat() / global.totalSeconds.toFloat()).coerceIn(
                        0f,
                        1f
                    )
                )
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!canShowTo(player)) return
        for (global in globalBars.values) {
            player.showBossBar(global.bar)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        hideAll(event.player)
    }
}
