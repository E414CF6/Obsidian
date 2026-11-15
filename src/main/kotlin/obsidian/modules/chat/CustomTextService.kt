package obsidian.modules.chat

import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.text.TextFormatter
import obsidian.integration.PlaceholderService
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.math.ceil

/**
 * Serves paginated MiniMessage custom text sections from `customtext.yml` (`/ctext <section> [page]`).
 */
class CustomTextService(private val context: ObsidianContext) {

    private val file = YamlConfigFile(context.plugin, "customtext.yml")

    data class TextSection(
        val name: String,
        val title: String,
        val linesPerPage: Int,
        val lines: List<String>,
    )

    private val sections = LinkedHashMap<String, TextSection>()

    init {
        reload()
    }

    fun reload() {
        file.reload()
        sections.clear()
        val root = file.yaml.getConfigurationSection("sections") ?: return
        for (key in root.getKeys(false)) {
            val sec = root.getConfigurationSection(key) ?: continue
            sections[key.lowercase()] = TextSection(
                name = key,
                title = sec.getString("title", "<aqua>$key</aqua>") ?: "<aqua>$key</aqua>",
                linesPerPage = sec.getInt("lines-per-page", 8).coerceAtLeast(1),
                lines = sec.getStringList("lines"),
            )
        }
    }

    fun getSectionNames(): List<String> = sections.values.map { it.name }

    fun sendSection(sender: CommandSender, name: String, requestedPage: Int): Boolean {
        val sec = sections[name.lowercase()] ?: return false
        val placeholders = context.getService<PlaceholderService>()
        val player = sender as? Player
        val totalPages = ceil(sec.lines.size.toDouble() / sec.linesPerPage).toInt().coerceAtLeast(1)
        val page = requestedPage.coerceIn(1, totalPages)
        val from = (page - 1) * sec.linesPerPage
        val to = minOf(from + sec.linesPerPage, sec.lines.size)

        sender.sendMessage(TextFormatter.format("${sec.title} <gray>($page/$totalPages)</gray>"))
        for (i in from until to) {
            val line = placeholders?.applyPlaceholders(player, sec.lines[i]) ?: sec.lines[i]
            sender.sendMessage(TextFormatter.format(line))
        }
        return true
    }
}
