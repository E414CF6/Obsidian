package obsidian.core.ui

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import java.util.*
import java.util.concurrent.ConcurrentHashMap

enum class ActionBarPriority(val weight: Int) {
    AFK(10),
    SELECTION_WAND(20),
    PROTECT_INSPECTOR(30),
    VANISH(40),
    FLIGHT_CHARGE(50),
    JAIL(60),
    COMBAT(80),
    WARMUP(90),
    TRANSIENT(100),
}

/**
 * Per-player UI preference flags persisted in player PDC.
 */
object UiPreferences {
    private const val ACTIONBAR_HUD_KEY = "ui_actionbar_hud"
    private const val BOSSBAR_TIMERS_KEY = "ui_bossbar_timers"
    private const val DIALOG_POPUPS_KEY = "ui_dialog_popups"

    private fun readBool(context: ObsidianContext, player: Player, keyName: String, default: Boolean = true): Boolean {
        val key = NamespacedKey(context.plugin, keyName)
        val raw = player.persistentDataContainer.get(key, PersistentDataType.BYTE)
        return if (raw == null) default else raw == 1.toByte()
    }

    private fun writeBool(context: ObsidianContext, player: Player, keyName: String, enabled: Boolean) {
        val key = NamespacedKey(context.plugin, keyName)
        player.persistentDataContainer.set(key, PersistentDataType.BYTE, if (enabled) 1 else 0)
    }

    fun isActionBarHudEnabled(context: ObsidianContext, player: Player): Boolean =
        readBool(context, player, ACTIONBAR_HUD_KEY, true)

    fun setActionBarHudEnabled(context: ObsidianContext, player: Player, enabled: Boolean) =
        writeBool(context, player, ACTIONBAR_HUD_KEY, enabled)

    fun isBossBarTimersEnabled(context: ObsidianContext, player: Player): Boolean =
        readBool(context, player, BOSSBAR_TIMERS_KEY, true)

    fun setBossBarTimersEnabled(context: ObsidianContext, player: Player, enabled: Boolean) =
        writeBool(context, player, BOSSBAR_TIMERS_KEY, enabled)

    fun isDialogPopupsEnabled(context: ObsidianContext, player: Player): Boolean =
        readBool(context, player, DIALOG_POPUPS_KEY, true)

    fun setDialogPopupsEnabled(context: ObsidianContext, player: Player, enabled: Boolean) =
        writeBool(context, player, DIALOG_POPUPS_KEY, enabled)
}

/**
 * Coordinates persistent state HUDs and transient feedback on the ActionBar without flickering or clobbering.
 */
class ActionBarService(private val context: ObsidianContext? = null) : Listener {

    data class StateEntry(
        val key: String,
        val priority: ActionBarPriority,
        val component: Component,
        val expiresAtMillis: Long = Long.MAX_VALUE,
    )

    private val playerStates = ConcurrentHashMap<UUID, ConcurrentHashMap<String, StateEntry>>()
    private var tickTask: ScheduledTask? = null

    fun start() {
        val ctx = context ?: return
        stop()
        tickTask = ctx.scheduler.runAsyncTimer(1L, 1L) {
            tickAll()
        }
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
        playerStates.clear()
    }

    fun setState(
        player: Player,
        key: String,
        priority: ActionBarPriority,
        component: Component,
        ttlMillis: Long? = null,
    ) {
        setState(player.uniqueId, key, priority, component, ttlMillis)
        dispatchNow(player)
    }

    fun setState(
        uuid: UUID,
        key: String,
        priority: ActionBarPriority,
        component: Component,
        ttlMillis: Long? = null,
    ) {
        val expires = if (ttlMillis != null && ttlMillis > 0L) {
            System.currentTimeMillis() + ttlMillis
        } else {
            Long.MAX_VALUE
        }
        val map = playerStates.computeIfAbsent(uuid) { ConcurrentHashMap() }
        map[key] = StateEntry(key, priority, component, expires)
    }

    fun setLocaleState(
        player: Player,
        stateKey: String,
        priority: ActionBarPriority,
        localeKey: String,
        vararg placeholders: Pair<String, Any?>,
    ) {
        val ctx = context ?: return
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        setState(player, stateKey, priority, ctx.locale.component(localeKey, map))
    }

    fun clearState(uuid: UUID, key: String) {
        val map = playerStates[uuid] ?: return
        map.remove(key)
        if (map.isEmpty()) {
            playerStates.remove(uuid)
        }
    }

    fun pushTransient(player: Player, component: Component, durationMillis: Long = 2500L) {
        setState(player, TRANSIENT_KEY, ActionBarPriority.TRANSIENT, component, durationMillis)
    }

    fun pushTransientRaw(player: Player, miniMessage: String, durationMillis: Long = 2500L) {
        pushTransient(player, TextFormatter.format(miniMessage), durationMillis)
    }

    fun pushTransientLocale(
        player: Player,
        localeKey: String,
        vararg placeholders: Pair<String, Any?>,
        durationMillis: Long = 2500L,
    ) {
        val ctx = context ?: return
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        pushTransient(player, ctx.locale.component(localeKey, map), durationMillis)
    }

    /**
     * Resolves the highest-priority unexpired ActionBar component for [uuid] at [nowMillis].
     */
    fun resolveActiveEntry(
        uuid: UUID,
        nowMillis: Long = System.currentTimeMillis(),
        includeLowPriorityHud: Boolean = true,
    ): StateEntry? {
        val map = playerStates[uuid] ?: return null
        var best: StateEntry? = null
        val expired = mutableListOf<String>()
        for ((k, entry) in map) {
            if (entry.expiresAtMillis <= nowMillis) {
                expired += k
                continue
            }
            if (!includeLowPriorityHud && entry.priority.weight < ActionBarPriority.COMBAT.weight) {
                continue
            }
            if (best == null || entry.priority.weight > best.priority.weight) {
                best = entry
            }
        }
        expired.forEach { map.remove(it) }
        if (map.isEmpty()) {
            playerStates.remove(uuid)
        }
        return best
    }

    private fun dispatchNow(player: Player) {
        val ctx = context ?: return
        if (!player.isOnline) return
        val hudEnabled = ctx.config.uiActionBarHud && UiPreferences.isActionBarHudEnabled(ctx, player)
        val entry = resolveActiveEntry(player.uniqueId, includeLowPriorityHud = hudEnabled) ?: return
        ctx.scheduler.runForEntity(player) {
            if (player.isOnline) {
                player.sendActionBar(entry.component)
            }
        }
    }

    private fun tickAll() {
        val ctx = context ?: return
        if (playerStates.isEmpty()) return
        val now = System.currentTimeMillis()
        for (uuid in playerStates.keys.toList()) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                playerStates.remove(uuid)
                continue
            }
            val hudEnabled = ctx.config.uiActionBarHud && UiPreferences.isActionBarHudEnabled(ctx, player)
            val entry = resolveActiveEntry(uuid, now, includeLowPriorityHud = hudEnabled) ?: continue
            ctx.scheduler.runForEntity(player) {
                if (player.isOnline) {
                    player.sendActionBar(entry.component)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        playerStates.remove(event.player.uniqueId)
    }

    companion object {
        const val TRANSIENT_KEY = "__transient__"
    }
}
