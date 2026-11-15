package obsidian.integration

import me.clip.placeholderapi.PlaceholderAPI
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import obsidian.modules.automation.AfkService
import obsidian.modules.chat.ChatService
import obsidian.modules.economy.EconomyService
import obsidian.modules.home.HomeService
import obsidian.modules.moderation.ModerationService
import obsidian.modules.playtime.RankService
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/**
 * Resolves Obsidian built-in placeholders (`{...}` / `%obsidian_*%`) and delegates external `%...%`
 * placeholders to PlaceholderAPI when installed.
 */
class PlaceholderService(private val context: ObsidianContext) {

    fun isPapiEnabled(): Boolean = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")

    fun resolveKey(player: OfflinePlayer?, key: String): String? {
        val online = player as? Player ?: player?.player
        val uuid = player?.uniqueId
        val eco = context.getService<EconomyService>()
        val ranks = context.getService<RankService>()
        val homes = context.getService<HomeService>()
        val mod = context.getService<ModerationService>()
        val chat = context.getService<ChatService>()
        val afk = context.getService<AfkService>()
        val lp = context.getService<LuckPermsHook>()

        return when (key.lowercase()) {
            "online" -> {
                val visibleCount = Bukkit.getOnlinePlayers().count { p -> mod == null || !mod.isVanished(p.uniqueId) }
                visibleCount.toString()
            }

            "max_players" -> Bukkit.getMaxPlayers().toString()
            "player" -> player?.name ?: ""
            "displayname" -> if (online != null) (chat?.getEffectiveDisplayNameRaw(online)
                ?: online.name) else (player?.name ?: "")

            "nick" -> if (uuid != null) (chat?.getNickname(uuid) ?: player.name ?: "") else ""
            "ping" -> online?.ping?.toString() ?: "0"
            "world" -> online?.world?.name ?: ""
            "balance" -> if (uuid != null && eco != null) "%.2f".format(eco.getBalance(uuid)) else "0.00"
            "balance_formatted" -> if (uuid != null && eco != null) eco.formatMoney(eco.getBalance(uuid)) else "0"
            "playtime" -> if (uuid != null && ranks != null) TextFormatter.formatDuration(
                ranks.getOrLoadSnapshot(
                    uuid,
                    player.name
                ).totalMillis
            ) else "0s"

            "playtime_daily" -> if (uuid != null && ranks != null) TextFormatter.formatDuration(
                ranks.getOrLoadSnapshot(
                    uuid,
                    player.name
                ).dailyMillis
            ) else "0s"

            "rank" -> if (uuid != null && ranks != null) ranks.getCurrentRankDisplay(uuid) else "default"
            "homes" -> if (uuid != null && homes != null) homes.getHomes(uuid).size.toString() else "0"
            "max_homes" -> if (online != null && homes != null) {
                val m = homes.getMaxHomes(online)
                if (m == Int.MAX_VALUE) "Unlimited" else m.toString()
            } else "0"

            "vanished" -> if (uuid != null && mod != null) mod.isVanished(uuid).toString() else "false"
            "muted" -> if (uuid != null && mod != null) (mod.getActiveMute(uuid) != null).toString() else "false"
            "jailed" -> if (uuid != null && mod != null) (mod.getActiveJail(uuid) != null).toString() else "false"
            "god" -> if (uuid != null) (context.users.getCached(uuid)?.godMode ?: false).toString() else "false"
            "afk" -> if (uuid != null && afk != null) afk.isAfk(uuid).toString() else "false"
            "prefix" -> if (online != null && lp != null) lp.getPrefix(online) else ""
            "suffix" -> if (online != null && lp != null) lp.getSuffix(online) else ""
            "luckperms_group" -> (if (online != null && lp != null) lp.getPrimaryGroup(online) else null) ?: ""
            else -> null
        }
    }

    fun applyPlaceholders(player: Player?, template: String): String {
        val lp = context.getService<LuckPermsHook>()
        val prefix = if (player != null && lp != null) lp.getPrefix(player) else ""
        val suffix = if (player != null && lp != null) lp.getSuffix(player) else ""
        var resolved = template
            .replace("{online}", resolveKey(player, "online") ?: "0")
            .replace("{max_players}", resolveKey(player, "max_players") ?: "0")
            .replace("{player}", resolveKey(player, "player") ?: "")
            .replace("{displayname}", resolveKey(player, "displayname") ?: "")
            .replace("{nick}", resolveKey(player, "nick") ?: "")
            .replace("{ping}", resolveKey(player, "ping") ?: "0")
            .replace("{world}", resolveKey(player, "world") ?: "")
            .replace("{balance}", resolveKey(player, "balance_formatted") ?: "0")
            .replace("{playtime}", resolveKey(player, "playtime") ?: "0s")
            .replace("{rank}", resolveKey(player, "rank") ?: "default")
            .replace("{homes}", resolveKey(player, "homes") ?: "0")
            .replace("{afk}", resolveKey(player, "afk") ?: "false")
            .replace("{prefix}", prefix)
            .replace("{suffix}", suffix)

        if (player != null && isPapiEnabled()) {
            resolved = runCatching { PlaceholderAPI.setPlaceholders(player, resolved) }.getOrDefault(resolved)
        }
        return resolved
    }
}
