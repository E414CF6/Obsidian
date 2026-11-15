package obsidian.modules.chat

import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import obsidian.core.ObsidianContext
import obsidian.core.database.ChatProfileRecord
import obsidian.core.database.PunishmentType
import obsidian.core.text.DurationParser
import obsidian.core.text.TextFormatter
import obsidian.integration.PlaceholderService
import obsidian.modules.moderation.ModerationService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Paper [AsyncChatEvent] formatting, nicknames, `/msg` & `/reply`, `/socialspy`,
 * `/staffmsg`, `/helpop`, and global chat mute.
 */
class ChatService(
    private val context: ObsidianContext,
    private val filterService: ChatFilterService,
) : Listener {

    private val profiles = ConcurrentHashMap<UUID, ChatProfileRecord>()
    private val lastMessagedBy = ConcurrentHashMap<UUID, UUID>()

    @Volatile
    var globalChatMuted: Boolean = false

    var chatFormat: String =
        "<gray>[<aqua>{rank}</aqua>]</gray> {prefix}<white>{displayname}</white>{suffix} <dark_gray>»</dark_gray> <gray>{message}</gray>"
        private set
    var hoverLines: List<String> = emptyList()
        private set
    var msgSendFormat: String =
        "<gray>[<light_purple>Me</light_purple> <dark_gray>→</dark_gray> <white>{target}</white>]</gray> <white>{message}</white>"
        private set
    var msgReceiveFormat: String =
        "<gray>[<white>{sender}</white> <dark_gray>→</dark_gray> <light_purple>Me</light_purple>]</gray> <white>{message}</white>"
        private set
    var socialSpyFormat: String =
        "<dark_gray>[SPY]</dark_gray> <gray>{sender} → {target}:</gray> <white>{message}</white>"
        private set
    var staffChatFormat: String = "<dark_purple>[Staff]</dark_purple> <white>{sender}</white><gray>: {message}</gray>"
        private set
    var helpopFormat: String = "<red>[HelpOp]</red> <white>{sender}</white><gray>: {message}</gray>"
        private set
    var broadcastFormat: String =
        "<dark_gray>[<gradient:#f59e0b:#ef4444><bold>NOTICE</bold></gradient>]</dark_gray> <white>{message}</white>"
        private set

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        chatFormat = raw.getString("chat.format", chatFormat) ?: chatFormat
        hoverLines = raw.getStringList("chat.hover-info")
        msgSendFormat = raw.getString("chat.msg-send-format", msgSendFormat) ?: msgSendFormat
        msgReceiveFormat = raw.getString("chat.msg-receive-format", msgReceiveFormat) ?: msgReceiveFormat
        socialSpyFormat = raw.getString("chat.socialspy-format", socialSpyFormat) ?: socialSpyFormat
        staffChatFormat = raw.getString("chat.staffchat-format", staffChatFormat) ?: staffChatFormat
        helpopFormat = raw.getString("chat.helpop-format", helpopFormat) ?: helpopFormat
        broadcastFormat = raw.getString("chat.broadcast-format", broadcastFormat) ?: broadcastFormat
    }

    fun getProfile(uuid: UUID): ChatProfileRecord =
        profiles.computeIfAbsent(uuid) { ChatProfileRecord(uuid = uuid) }

    fun getNickname(uuid: UUID): String? = profiles[uuid]?.nickname?.takeIf { it.isNotBlank() }

    fun getEffectiveDisplayNameRaw(player: Player): String =
        getNickname(player.uniqueId) ?: player.name

    fun setNickname(player: Player, nickname: String?) {
        val cleaned = nickname?.takeIf { it.isNotBlank() }
        val updated = getProfile(player.uniqueId).copy(
            nickname = cleaned,
            updatedAt = System.currentTimeMillis(),
        )
        profiles[player.uniqueId] = updated
        applyDisplayName(player, cleaned)
        persistAsync(updated)
    }

    fun applyDisplayName(player: Player, nickname: String? = getNickname(player.uniqueId)) {
        val comp = if (nickname.isNullOrBlank()) Component.text(player.name) else TextFormatter.format(nickname)
        player.displayName(comp)
    }

    fun toggleMsgReceive(player: Player): Boolean {
        val cur = getProfile(player.uniqueId)
        val next = !cur.msgToggle
        val updated = cur.copy(msgToggle = next, updatedAt = System.currentTimeMillis())
        profiles[player.uniqueId] = updated
        persistAsync(updated)
        return next
    }

    fun toggleSocialSpy(player: Player): Boolean {
        val cur = getProfile(player.uniqueId)
        val next = !cur.socialSpy
        val updated = cur.copy(socialSpy = next, updatedAt = System.currentTimeMillis())
        profiles[player.uniqueId] = updated
        persistAsync(updated)
        return next
    }

    fun getReplyTarget(senderUuid: UUID): Player? {
        val targetUuid = lastMessagedBy[senderUuid] ?: return null
        return Bukkit.getPlayer(targetUuid)
    }

    fun sendPrivateMessage(sender: Player, target: Player, message: String) {
        val mod = context.getService<ModerationService>()
        val mute = mod?.getActiveMute(sender.uniqueId)
        if (mute != null && mute.type == PunishmentType.MUTE) {
            context.locale.send(
                sender,
                "moderation.muted-chat-blocked",
                "expires" to DurationParser.formatExpiry(mute.expiresAt),
                "reason" to mute.reason,
            )
            return
        }
        val targetProfile = getProfile(target.uniqueId)
        if (!targetProfile.msgToggle && !sender.hasPermission("obsidian.admin")) {
            context.locale.send(sender, "chat.msg-disabled-target", "target" to target.name)
            return
        }

        val sendComp = TextFormatter.format(
            msgSendFormat,
            mapOf("target" to target.name, "sender" to sender.name, "message" to message),
        )
        sender.sendMessage(sendComp)

        if (mute == null || mute.type != PunishmentType.SHADOW_MUTE) {
            val recvComp = TextFormatter.format(
                msgReceiveFormat,
                mapOf("sender" to sender.name, "target" to target.name, "message" to message),
            )
            target.sendMessage(recvComp)
            lastMessagedBy[target.uniqueId] = sender.uniqueId
        }
        lastMessagedBy[sender.uniqueId] = target.uniqueId

        val spyComp = TextFormatter.format(
            socialSpyFormat,
            mapOf("sender" to sender.name, "target" to target.name, "message" to message),
        )
        for (online in Bukkit.getOnlinePlayers()) {
            if (online.uniqueId == sender.uniqueId || online.uniqueId == target.uniqueId) continue
            if (getProfile(online.uniqueId).socialSpy && (online.hasPermission("obsidian.socialspy") || online.hasPermission(
                    "obsidian.admin"
                ))
            ) {
                online.sendMessage(spyComp)
            }
        }
    }

    fun findByNickname(query: String): Player? {
        val strippedQuery = query.lowercase()
        for (online in Bukkit.getOnlinePlayers()) {
            val nick = getNickname(online.uniqueId) ?: continue
            val plainNick = PlainTextComponentSerializer.plainText().serialize(TextFormatter.format(nick))
            if (plainNick.equals(strippedQuery, ignoreCase = true) || plainNick.contains(
                    strippedQuery,
                    ignoreCase = true
                )
            ) {
                return online
            }
        }
        return null
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val mod = context.getService<ModerationService>()

        if (globalChatMuted && !player.hasPermission("obsidian.chat.bypassmute") && !player.hasPermission("obsidian.admin")) {
            event.isCancelled = true
            context.locale.send(player, "chat.chat-is-muted")
            return
        }

        val mute = mod?.getActiveMute(player.uniqueId)
        if (mute != null) {
            if (mute.type == PunishmentType.SHADOW_MUTE) {
                event.viewers().removeIf { it !== player }
            } else {
                event.isCancelled = true
                context.locale.send(
                    player,
                    "moderation.muted-chat-blocked",
                    "expires" to DurationParser.formatExpiry(mute.expiresAt),
                    "reason" to mute.reason,
                )
                return
            }
        }

        val plainRaw = PlainTextComponentSerializer.plainText().serialize(event.message())
        val bypassFilter = player.hasPermission("obsidian.chat.bypassfilter") || player.hasPermission("obsidian.admin")
        val filteredText = when (val outcome = filterService.checkAndProcess(player.uniqueId, plainRaw, bypassFilter)) {
            is ChatFilterService.FilterOutcome.Blocked -> {
                event.isCancelled = true
                context.locale.send(player, outcome.localeKey)
                return
            }

            is ChatFilterService.FilterOutcome.Allowed -> outcome.processedMessage
        }

        val canColor = player.hasPermission("obsidian.chat.color") || player.hasPermission("obsidian.admin")
        val messageComp = if (canColor) TextFormatter.format(filteredText) else Component.text(filteredText)
        val placeholders = context.getService<PlaceholderService>()

        val hoverComp = if (hoverLines.isNotEmpty()) {
            val joined = hoverLines.joinToString("\n") { line ->
                placeholders?.applyPlaceholders(player, line) ?: line
            }
            TextFormatter.format(joined)
        } else null

        val templateWithPlaceholders = placeholders?.applyPlaceholders(player, chatFormat) ?: chatFormat
        val prefixPart = templateWithPlaceholders.substringBefore("{message}", "")
        val suffixPart = templateWithPlaceholders.substringAfter("{message}", "")

        var renderedPrefix = TextFormatter.format(prefixPart)
            .clickEvent(ClickEvent.suggestCommand("/msg ${player.name} "))
        if (hoverComp != null) {
            renderedPrefix = renderedPrefix.hoverEvent(HoverEvent.showText(hoverComp))
        }
        val renderedSuffix = if (suffixPart.isEmpty()) Component.empty() else TextFormatter.format(suffixPart)

        event.renderer(ChatRenderer.viewerUnaware { _, _, _ ->
            Component.empty()
                .append(renderedPrefix)
                .append(messageComp)
                .append(renderedSuffix)
        })
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        context.scheduler.asyncScope.launch {
            val loaded = context.chatProfileRepository.findByUuid(player.uniqueId)
                ?: ChatProfileRecord(uuid = player.uniqueId)
            profiles[player.uniqueId] = loaded
            context.scheduler.runForEntity(player) {
                applyDisplayName(player, loaded.nickname)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        profiles.remove(event.player.uniqueId)
        lastMessagedBy.remove(event.player.uniqueId)
    }

    private fun persistAsync(record: ChatProfileRecord) {
        context.scheduler.asyncScope.launch {
            context.chatProfileRepository.upsert(record)
        }
    }
}
