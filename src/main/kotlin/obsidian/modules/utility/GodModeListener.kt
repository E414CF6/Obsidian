package obsidian.modules.utility

import obsidian.core.ObsidianContext
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent

/**
 * Protects players with `godMode` enabled from incoming damage and hunger loss.
 */
class GodModeListener(private val context: ObsidianContext) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (context.users.getCached(player.uniqueId)?.godMode == true) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFoodChange(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (context.users.getCached(player.uniqueId)?.godMode == true && event.foodLevel < player.foodLevel) {
            event.isCancelled = true
        }
    }
}
