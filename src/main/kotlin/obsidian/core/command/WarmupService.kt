package obsidian.core.command

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import obsidian.core.config.ObsidianConfig
import obsidian.core.locale.LocaleService
import obsidian.core.scheduler.ObsidianScheduler
import obsidian.core.text.TextFormatter
import obsidian.core.ui.ActionBarPriority
import obsidian.core.ui.ActionBarService
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles movement-sensitive command/teleport warmup countdowns using Folia/Paper entity schedulers.
 */
class WarmupService(
    private val config: ObsidianConfig,
    private val locale: LocaleService,
    private val scheduler: ObsidianScheduler,
    private val actionBar: ActionBarService? = null,
) : Listener {

    private data class ActiveWarmup(
        val startLocation: Location,
        val task: ScheduledTask,
        var remainingSeconds: Int,
    )

    private var enabled: Boolean = true
    private var cancelOnMove: Boolean = true
    private var showActionBar: Boolean = true
    private val configuredSeconds = ConcurrentHashMap<String, Int>()
    private val active = ConcurrentHashMap<UUID, ActiveWarmup>()

    init {
        reload()
    }

    fun reload() {
        val raw = config.raw
        enabled = raw.getBoolean("commands.warmups.enabled", true)
        cancelOnMove = raw.getBoolean("commands.warmups.cancel-on-move", true)
        showActionBar = raw.getBoolean("commands.warmups.show-actionbar", true)
        configuredSeconds.clear()
        val section = raw.getConfigurationSection("commands.warmups.list") ?: return
        for (key in section.getKeys(false)) {
            val sec = section.getInt(key, 0)
            if (sec > 0) {
                configuredSeconds[key.lowercase()] = sec
            }
        }
    }

    fun getWarmupSeconds(player: Player, commandKey: String): Int {
        if (!enabled) return 0
        val key = commandKey.lowercase()
        if (player.hasPermission("obsidian.warmup.bypass.$key") || player.hasPermission("obsidian.warmup.bypass.*")) {
            return 0
        }
        return configuredSeconds[key] ?: 0
    }

    /**
     * Runs [action] after the configured warmup for [commandKey], or immediately if no warmup applies.
     */
    fun runWithWarmup(player: Player, commandKey: String, action: () -> Unit) {
        val seconds = getWarmupSeconds(player, commandKey)
        if (seconds <= 0) {
            action()
            return
        }

        cancel(player.uniqueId, notify = false)
        locale.send(player, "general.warmup-started", "time" to TextFormatter.formatDuration(seconds * 1000L))

        val startLoc = player.location.clone()
        var remaining = seconds
        val task = scheduler.runForEntityTimer(
            entity = player,
            initialDelayTicks = 1L,
            periodTicks = 20L,
            retired = { active.remove(player.uniqueId) },
        ) { scheduled ->
            val current = active[player.uniqueId]
            if (current == null || !player.isOnline) {
                scheduled.cancel()
                active.remove(player.uniqueId)
                return@runForEntityTimer
            }
            if (remaining <= 0) {
                scheduled.cancel()
                active.remove(player.uniqueId)
                actionBar?.clearState(player.uniqueId, WARMUP_STATE_KEY)
                action()
                return@runForEntityTimer
            }
            if (showActionBar) {
                if (actionBar != null) {
                    actionBar.setLocaleState(
                        player,
                        WARMUP_STATE_KEY,
                        ActionBarPriority.WARMUP,
                        "general.warmup-actionbar",
                        "seconds" to remaining,
                    )
                } else {
                    locale.sendActionBar(player, "general.warmup-actionbar", "seconds" to remaining)
                }
            }
            remaining -= 1
            current.remainingSeconds = remaining
        }

        if (task != null) {
            active[player.uniqueId] = ActiveWarmup(startLoc, task, seconds)
        } else {
            action()
        }
    }

    fun cancel(uuid: UUID, notify: Boolean = true) {
        val removed = active.remove(uuid) ?: return
        removed.task.cancel()
        actionBar?.clearState(uuid, WARMUP_STATE_KEY)
        if (notify) {
            org.bukkit.Bukkit.getPlayer(uuid)?.let { player ->
                locale.send(player, "general.warmup-cancelled")
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!cancelOnMove) return
        val warmup = active[event.player.uniqueId] ?: return
        val from = warmup.startLocation
        val to = event.to
        if (from.world != to.world || from.distanceSquared(to) > 0.25) {
            cancel(event.player.uniqueId, notify = true)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        cancel(event.player.uniqueId, notify = false)
    }

    companion object {
        private const val WARMUP_STATE_KEY = "warmup"
    }
}
