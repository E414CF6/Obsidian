package obsidian.modules.chat

import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.PlayerDeathEvent

/**
 * Customizes player death broadcasts using MiniMessage templates from `config.yml`.
 */
class DeathMessageService(private val context: ObsidianContext) : Listener {

    var enabled: Boolean = true
        private set
    private var pvpTemplate: String =
        "<red>☠ <white>{victim}</white> was slain by <white>{killer}</white> using <aqua>{weapon}</aqua>.</red>"
    private var fallTemplate: String = "<yellow>☠ <white>{victim}</white> fell from a high place.</yellow>"
    private var lavaTemplate: String = "<gold>☠ <white>{victim}</white> tried to swim in lava.</gold>"
    private var drownTemplate: String = "<blue>☠ <white>{victim}</white> forgot to breathe underwater.</blue>"
    private var genericTemplate: String = "<gray>☠ <white>{victim}</white> died.</gray>"

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        enabled = raw.getBoolean("death-messages.enabled", true)
        pvpTemplate = raw.getString("death-messages.pvp", pvpTemplate) ?: pvpTemplate
        fallTemplate = raw.getString("death-messages.fall", fallTemplate) ?: fallTemplate
        lavaTemplate = raw.getString("death-messages.lava", lavaTemplate) ?: lavaTemplate
        drownTemplate = raw.getString("death-messages.drown", drownTemplate) ?: drownTemplate
        genericTemplate = raw.getString("death-messages.generic", genericTemplate) ?: genericTemplate
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        if (!enabled) return
        val victim = event.player
        val killer = victim.killer
        val cause = victim.lastDamageCause?.cause

        val component = if (killer != null) {
            val weapon = killer.inventory.itemInMainHand.let { item ->
                if (item.type.isAir) "Fist" else item.type.name.lowercase().replace('_', ' ')
            }
            TextFormatter.format(
                pvpTemplate,
                mapOf("victim" to victim.name, "killer" to killer.name, "weapon" to weapon),
            )
        } else {
            val tpl = when (cause) {
                DamageCause.FALL -> fallTemplate
                DamageCause.LAVA, DamageCause.FIRE, DamageCause.FIRE_TICK -> lavaTemplate
                DamageCause.DROWNING -> drownTemplate
                else -> genericTemplate
            }
            TextFormatter.format(tpl, mapOf("victim" to victim.name))
        }
        event.deathMessage(component)
    }
}
