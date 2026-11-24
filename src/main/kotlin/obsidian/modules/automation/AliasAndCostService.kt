package obsidian.modules.automation

import obsidian.core.ObsidianContext
import obsidian.modules.economy.EconomyService
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles command economy costs (`commands.costs`) and delegates custom command aliases
 * (`aliases.yml`) to [obsidian.core.command.CommandAliasService].
 */
class AliasAndCostService(private val context: ObsidianContext) : Listener {

    private val commandCosts = ConcurrentHashMap<String, Double>()

    init {
        reloadCosts()
    }

    fun reload() {
        reloadCosts()
    }

    private fun reloadCosts() {
        commandCosts.clear()
        val sec = context.config.raw.getConfigurationSection("commands.costs") ?: return
        for (key in sec.getKeys(false)) {
            val cost = sec.getDouble(key, 0.0)
            if (cost > 0.0) {
                commandCosts[key.lowercase().removePrefix("/")] = cost
            }
        }
    }

    fun setAlias(name: String, commands: List<String>) {
        context.commandAliases.setCustomAlias(name, commands)
    }

    fun removeAlias(name: String): Boolean =
        context.commandAliases.removeCustomAlias(name)

    fun getAliases(): Map<String, List<String>> =
        context.commandAliases.getCustomAliases()

    fun getCosts(): Map<String, Double> = commandCosts.toMap()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        if (commandCosts.isEmpty()) return
        val player = event.player
        if (player.hasPermission("obsidian.commandcost.bypass") || player.hasPermission("obsidian.admin")) return

        val rawMessage = event.message.trim()
        if (!rawMessage.startsWith("/")) return
        val rawCommand = rawMessage.substring(1).trim()
        if (rawCommand.isEmpty()) return

        val parts = rawCommand.split(Regex("\\s+"), limit = 3)
        val label = parts[0].substringAfter(':').lowercase()
        val subLabel = if ((label == "obsidian" || label == "obs") && parts.size >= 2) {
            parts[1].lowercase()
        } else {
            null
        }
        val cost = commandCosts[label]
            ?: subLabel?.let { commandCosts[it] }
            ?: return
        if (cost <= 0.0) return

        val eco = context.getService<EconomyService>() ?: return
        val balance = eco.getBalance(player.uniqueId)
        if (balance < cost) {
            event.isCancelled = true
            context.locale.send(
                player,
                "economy.cost-insufficient",
                "cost" to eco.formatMoney(cost),
                "balance" to eco.formatMoney(balance),
                "command" to (subLabel ?: label),
            )
            return
        }

        eco.withdraw(player.uniqueId, cost)
        context.locale.send(
            player,
            "economy.cost-deducted",
            "cost" to eco.formatMoney(cost),
            "command" to (subLabel ?: label),
        )
    }
}
