package obsidian.integration

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import obsidian.core.ObsidianContext
import org.bukkit.OfflinePlayer

/**
 * Registers `%obsidian_<key>%` placeholders with PlaceholderAPI when installed.
 */
class PlaceholderApiHook(
    private val context: ObsidianContext,
    private val placeholderService: PlaceholderService,
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "obsidian"

    override fun getAuthor(): String = "Obsidian"

    override fun getVersion(): String = context.plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        placeholderService.resolveKey(player, params)
}
