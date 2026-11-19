package obsidian.modules.moderation

import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Cycles through online players to patrol for rule-breakers or bot checks.
 */
class PatrolService(private val context: ObsidianContext) {

    private val lastIndex = ConcurrentHashMap<UUID, Int>()

    fun patrolNext(staff: Player): Player? {
        val candidates = Bukkit.getOnlinePlayers().filter { other ->
            other.uniqueId != staff.uniqueId &&
                    !other.hasPermission("obsidian.patrol.bypass") &&
                    !other.hasPermission("obsidian.admin")
        }

        if (candidates.isEmpty()) return null

        val currentIdx = lastIndex[staff.uniqueId] ?: -1
        val nextIdx = (currentIdx + 1) % candidates.size
        lastIndex[staff.uniqueId] = nextIdx
        val target = candidates[nextIdx]

        staff.teleportAsync(target.location, TeleportCause.COMMAND)
        return target
    }
}
