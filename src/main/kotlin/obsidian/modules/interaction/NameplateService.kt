package obsidian.modules.interaction

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scoreboard.Team
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages CMI-style overhead nameplates (Scoreboard Team prefix/suffix/color/collision)
 * and `/notarget` mob targeting immunity.
 */
class NameplateService(private val context: ObsidianContext) : Listener {

    data class NameplateState(
        val prefix: String? = null,
        val suffix: String? = null,
        val color: NamedTextColor? = null,
        val collisionEnabled: Boolean = true,
    )

    private val states = ConcurrentHashMap<UUID, NameplateState>()
    private val noTargetPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun isNoTarget(uuid: UUID): Boolean = noTargetPlayers.contains(uuid)

    fun toggleNoTarget(uuid: UUID): Boolean {
        return if (noTargetPlayers.remove(uuid)) {
            false
        } else {
            noTargetPlayers.add(uuid)
            true
        }
    }

    fun setPrefix(player: Player, prefix: String?) {
        val cleaned = prefix?.takeIf { it.isNotBlank() && !it.equals("reset", ignoreCase = true) }
        val next = states.compute(player.uniqueId) { _, cur ->
            (cur ?: NameplateState()).copy(prefix = cleaned)
        }!!
        applyTeam(player, next)
    }

    fun setSuffix(player: Player, suffix: String?) {
        val cleaned = suffix?.takeIf { it.isNotBlank() && !it.equals("reset", ignoreCase = true) }
        val next = states.compute(player.uniqueId) { _, cur ->
            (cur ?: NameplateState()).copy(suffix = cleaned)
        }!!
        applyTeam(player, next)
    }

    fun setColor(player: Player, color: NamedTextColor?) {
        val next = states.compute(player.uniqueId) { _, cur ->
            (cur ?: NameplateState()).copy(color = color)
        }!!
        applyTeam(player, next)
    }

    fun setCollision(player: Player, enabled: Boolean) {
        val next = states.compute(player.uniqueId) { _, cur ->
            (cur ?: NameplateState()).copy(collisionEnabled = enabled)
        }!!
        applyTeam(player, next)
    }

    fun isCollisionEnabled(uuid: UUID): Boolean = states[uuid]?.collisionEnabled ?: true

    fun resetNameplate(player: Player) {
        states.remove(player.uniqueId)
        removeTeam(player)
    }

    private fun teamNameFor(uuid: UUID): String =
        "obs_${uuid.toString().replace("-", "").take(12)}"

    private fun applyTeam(player: Player, state: NameplateState) {
        context.scheduler.runGlobal {
            val board = Bukkit.getScoreboardManager().mainScoreboard
            val teamName = teamNameFor(player.uniqueId)
            val team = board.getTeam(teamName) ?: board.registerNewTeam(teamName)

            team.prefix(state.prefix?.let { TextFormatter.format("$it ") } ?: Component.empty())
            team.suffix(state.suffix?.let { TextFormatter.format(" $it") } ?: Component.empty())
            team.color(state.color)
            team.setOption(
                Team.Option.COLLISION_RULE,
                if (state.collisionEnabled) Team.OptionStatus.ALWAYS else Team.OptionStatus.NEVER,
            )
            if (!team.hasEntry(player.name)) {
                team.addEntry(player.name)
            }
        }
    }

    private fun removeTeam(player: Player) {
        context.scheduler.runGlobal {
            val board = Bukkit.getScoreboardManager().mainScoreboard
            board.getTeam(teamNameFor(player.uniqueId))?.unregister()
        }
    }

    fun shutdown() {
        for (uuid in states.keys.toList()) {
            Bukkit.getPlayer(uuid)?.let(::removeTeam)
        }
        states.clear()
        noTargetPlayers.clear()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityTarget(event: EntityTargetLivingEntityEvent) {
        val target = event.target as? Player ?: return
        if (isNoTarget(target.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val state = states[event.player.uniqueId] ?: return
        applyTeam(event.player, state)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        removeTeam(event.player)
    }

    companion object {
        fun parseNamedColor(raw: String): NamedTextColor? {
            val clean = raw.trim().lowercase(Locale.ROOT)
            if (clean == "reset" || clean == "clear" || clean == "off") return null
            return NamedTextColor.NAMES.value(clean)
        }
    }
}
