package obsidian.modules.chat

import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import obsidian.integration.PlaceholderService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Renders animated/placeholder-driven TabList header, footer, and player list names.
 */
class TabListService(private val context: ObsidianContext) : Listener {

    var enabled: Boolean = true
        private set
    var updateIntervalSeconds: Long = 5L
        private set
    private var headerLines: List<String> = emptyList()
    private var footerLines: List<String> = emptyList()
    private var playerFormat: String = "<white>{displayname}</white>"

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        enabled = raw.getBoolean("tablist.enabled", true)
        updateIntervalSeconds = raw.getLong("tablist.update-interval-seconds", 5L).coerceAtLeast(1L)
        headerLines = raw.getStringList("tablist.header")
        footerLines = raw.getStringList("tablist.footer")
        playerFormat =
            raw.getString("tablist.player-format", "<white>{displayname}</white>") ?: "<white>{displayname}</white>"
    }

    fun updateAll() {
        if (!enabled) return
        for (player in Bukkit.getOnlinePlayers()) {
            updatePlayer(player)
        }
    }

    fun updatePlayer(player: Player) {
        if (!enabled) return
        val placeholders = context.getService<PlaceholderService>()
        val header = renderLines(player, headerLines, placeholders)
        val footer = renderLines(player, footerLines, placeholders)
        player.sendPlayerListHeaderAndFooter(header, footer)

        val nameResolved =
            placeholders?.applyPlaceholders(player, playerFormat) ?: playerFormat.replace("{displayname}", player.name)
        player.playerListName(TextFormatter.format(nameResolved))
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onJoin(event: PlayerJoinEvent) {
        if (!enabled) return
        val player = event.player
        context.scheduler.runForEntityDelayed(player, delayTicks = 10L) {
            updatePlayer(player)
        }
    }

    private fun renderLines(player: Player, lines: List<String>, placeholders: PlaceholderService?): Component {
        if (lines.isEmpty()) return Component.empty()
        val joined = lines.joinToString("\n") { line ->
            placeholders?.applyPlaceholders(player, line) ?: line
        }
        return TextFormatter.format(joined)
    }
}
