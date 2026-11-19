package obsidian.modules.automation

import obsidian.core.ObsidianContext
import obsidian.core.command.SpecializedCommandExecutor
import obsidian.modules.economy.EconomyService
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles command economy costs (`commands.costs`) and customizable command aliases (`aliases.yml`).
 */
class AliasAndCostService(private val context: ObsidianContext) : Listener {

    private val aliasFile = File(context.plugin.dataFolder, "aliases.yml")
    private val aliases = ConcurrentHashMap<String, List<String>>()
    private val commandCosts = ConcurrentHashMap<String, Double>()

    init {
        reload()
    }

    fun reload() {
        reloadCosts()
        reloadAliases()
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

    private fun reloadAliases() {
        aliases.clear()
        if (!aliasFile.exists()) {
            createDefaultAliases()
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(aliasFile)
        val sec = yaml.getConfigurationSection("aliases") ?: return
        for (key in sec.getKeys(false)) {
            val lines = when {
                sec.isList(key) -> sec.getStringList(key)
                sec.isString(key) -> listOfNotNull(sec.getString(key))
                else -> emptyList()
            }
            if (lines.isNotEmpty()) {
                aliases[key.lowercase().removePrefix("/")] = lines
            }
        }
    }

    private fun createDefaultAliases() {
        val yaml = YamlConfiguration()
        yaml.set(
            "aliases.discord",
            listOf("msg: <blue><bold>Discord:</bold></blue> <aqua>https://discord.gg/example</aqua>")
        )
        yaml.set("aliases.rules", listOf("player: ctext rules"))
        runCatching {
            aliasFile.parentFile?.mkdirs()
            yaml.save(aliasFile)
        }
        aliases["discord"] = listOf("msg: <blue><bold>Discord:</bold></blue> <aqua>https://discord.gg/example</aqua>")
        aliases["rules"] = listOf("player: ctext rules")
    }

    fun saveAliases() {
        val yaml = YamlConfiguration()
        for ((key, lines) in aliases) {
            yaml.set("aliases.$key", lines)
        }
        runCatching {
            aliasFile.parentFile?.mkdirs()
            yaml.save(aliasFile)
        }
    }

    fun setAlias(name: String, commands: List<String>) {
        aliases[name.lowercase().removePrefix("/")] = commands
        context.scheduler.runAsync { saveAliases() }
    }

    fun removeAlias(name: String): Boolean {
        val removed = aliases.remove(name.lowercase().removePrefix("/")) != null
        if (removed) context.scheduler.runAsync { saveAliases() }
        return removed
    }

    fun getAliases(): Map<String, List<String>> = aliases.toMap()

    fun getCosts(): Map<String, Double> = commandCosts.toMap()

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        val rawMessage = event.message.trim()
        if (!rawMessage.startsWith("/")) return
        val rawCommand = rawMessage.substring(1).trim()
        if (rawCommand.isEmpty()) return

        val parts = rawCommand.split(" ", limit = 2)
        val label = parts[0].lowercase()
        val args = parts.getOrNull(1) ?: ""

        // 1. Check custom aliases first
        val aliasActions = aliases[label]
        if (aliasActions != null) {
            event.isCancelled = true
            val placeholders = mapOf(
                "args" to args,
                "player" to player.name,
            )
            val processedLines = aliasActions.map { line ->
                line.replace("{args}", args).replace("{player}", player.name)
            }
            SpecializedCommandExecutor.executeAll(context, processedLines, player, placeholders)
            return
        }

        // 2. Check command cost
        if (player.hasPermission("obsidian.commandcost.bypass") || player.hasPermission("obsidian.admin")) return
        val cost = commandCosts[label] ?: commandCosts[rawCommand.lowercase()] ?: return
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
                "command" to label,
            )
            return
        }

        // Deduct cost
        eco.withdraw(player.uniqueId, cost)
        context.locale.send(
            player,
            "economy.cost-deducted",
            "cost" to eco.formatMoney(cost),
            "command" to label,
        )
    }
}
