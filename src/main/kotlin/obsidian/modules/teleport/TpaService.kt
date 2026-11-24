package obsidian.modules.teleport

import net.kyori.adventure.bossbar.BossBar
import obsidian.core.ObsidianContext
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import obsidian.core.text.TextFormatter
import obsidian.core.ui.DialogBuilder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.*
import java.util.concurrent.ConcurrentHashMap

enum class TpaType {
    TO_TARGET, HERE,
}

data class TpaRequest(
    val senderUuid: UUID,
    val targetUuid: UUID,
    val type: TpaType,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Manages `/tpa`, `/tpahere`, `/tpaccept`, and `/tpdeny` requests with expiration and deny cooldowns.
 */
class TpaService(
    private val context: ObsidianContext,
    private val teleportService: TeleportService,
) : Listener {

    private var expireSeconds: Int = 60
    private var blockAfterDenySeconds: Int = 120
    private var maxDistance: Double = 0.0
    private var showActionButtons: Boolean = true

    private val incoming = ConcurrentHashMap<UUID, MutableList<TpaRequest>>()
    private val blockedUntil = ConcurrentHashMap<Pair<UUID, UUID>, Long>()

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        expireSeconds = raw.getInt("teleport.tpa.expire-seconds", 60).coerceAtLeast(5)
        blockAfterDenySeconds = raw.getInt("teleport.tpa.block-after-deny-seconds", 120).coerceAtLeast(0)
        maxDistance = raw.getDouble("teleport.tpa.max-distance", 0.0).coerceAtLeast(0.0)
        showActionButtons = raw.getBoolean("teleport.tpa.show-action-buttons", true)
    }

    fun sendRequest(sender: Player, target: Player, type: TpaType) {
        if (sender.uniqueId == target.uniqueId) {
            context.locale.send(sender, "teleport.tpa-self")
            return
        }

        val blockKey = sender.uniqueId to target.uniqueId
        val blockedExp = blockedUntil[blockKey]
        if (blockedExp != null) {
            val remaining = blockedExp - System.currentTimeMillis()
            if (remaining > 0L) {
                context.locale.send(
                    sender,
                    "teleport.tpa-blocked",
                    "target" to target.name,
                    "time" to TextFormatter.formatDuration(remaining),
                )
                return
            } else {
                blockedUntil.remove(blockKey)
            }
        }

        if (maxDistance > 0.0) {
            val sameWorld = sender.world == target.world
            if (!sameWorld || sender.location.distance(target.location) > maxDistance) {
                context.locale.send(sender, "teleport.tpa-too-far")
                return
            }
        }

        val list = incoming.computeIfAbsent(target.uniqueId) { mutableListOf() }
        pruneExpired(list)
        if (list.any { it.senderUuid == sender.uniqueId }) {
            context.locale.send(sender, "teleport.tpa-already-sent", "target" to target.name)
            return
        }

        list += TpaRequest(sender.uniqueId, target.uniqueId, type)

        val timeText = TextFormatter.formatDuration(expireSeconds * 1000L)
        context.locale.send(sender, "teleport.tpa-sent", "target" to target.name, "time" to timeText)

        val receiveKey = if (type == TpaType.TO_TARGET) "teleport.tpa-received" else "teleport.tpahere-received"
        var msg = context.locale.prefixedComponent(receiveKey, mapOf("sender" to sender.name))
        if (showActionButtons) {
            val acceptBtn = context.locale.component("teleport.tpa-accept-btn", mapOf("sender" to sender.name))
            val denyBtn = context.locale.component("teleport.tpa-deny-btn", mapOf("sender" to sender.name))
            val dialogBtn = context.locale.component("teleport.tpa-dialog-btn", mapOf("sender" to sender.name))
            msg = msg.append(acceptBtn)
                .append(TextFormatter.format(" "))
                .append(denyBtn)
                .append(TextFormatter.format(" "))
                .append(dialogBtn)
        }
        target.sendMessage(msg)

        val abKey = if (type == TpaType.TO_TARGET) "teleport.tpa-actionbar" else "teleport.tpahere-actionbar"
        context.actionBar.pushTransientLocale(target, abKey, "sender" to sender.name, durationMillis = 3500L)
        context.bossBar.showTimed(
            player = target,
            id = tpaBarId(sender.uniqueId),
            title = context.locale.component(
                if (type == TpaType.TO_TARGET) "teleport.tpa-bossbar" else "teleport.tpahere-bossbar",
                mapOf("sender" to sender.name),
            ),
            durationMillis = expireSeconds * 1000L,
            color = BossBar.Color.BLUE,
        )
    }

    fun accept(target: Player, senderName: String?) {
        val request = popRequest(target, senderName) ?: run {
            context.locale.send(target, "teleport.tpa-none")
            return
        }
        context.bossBar.hide(target, tpaBarId(request.senderUuid))
        val sender = Bukkit.getPlayer(request.senderUuid)
        if (sender == null || !sender.isOnline) {
            context.locale.send(target, "general.player-not-found", "player" to (senderName ?: "?"))
            return
        }

        context.locale.send(sender, "teleport.tpa-accepted-sender", "target" to target.name)
        context.locale.send(target, "teleport.tpa-accepted-target", "sender" to sender.name)

        when (request.type) {
            TpaType.TO_TARGET -> {
                context.warmups.runWithWarmup(sender, "tpa") {
                    if (sender.isOnline && target.isOnline) {
                        teleportService.teleport(sender, target.location, TeleportCause.COMMAND)
                    }
                }
            }

            TpaType.HERE -> {
                context.warmups.runWithWarmup(target, "tpahere") {
                    if (sender.isOnline && target.isOnline) {
                        teleportService.teleport(target, sender.location, TeleportCause.COMMAND)
                    }
                }
            }
        }
    }

    fun deny(target: Player, senderName: String?) {
        val request = popRequest(target, senderName) ?: run {
            context.locale.send(target, "teleport.tpa-none")
            return
        }
        context.bossBar.hide(target, tpaBarId(request.senderUuid))
        if (blockAfterDenySeconds > 0) {
            blockedUntil[request.senderUuid to target.uniqueId] =
                System.currentTimeMillis() + blockAfterDenySeconds * 1000L
        }
        val sender = Bukkit.getPlayer(request.senderUuid)
        context.locale.send(target, "teleport.tpa-denied-target", "sender" to (sender?.name ?: senderName ?: "?"))
        if (sender != null && sender.isOnline) {
            context.locale.send(sender, "teleport.tpa-denied-sender", "target" to target.name)
        }
    }

    fun openPromptDialog(target: Player, senderName: String? = null) {
        val list = incoming[target.uniqueId] ?: run {
            context.locale.send(target, "teleport.tpa-none")
            return
        }
        val req = synchronized(list) {
            pruneExpired(list)
            if (senderName == null) list.lastOrNull()
            else list.lastOrNull { Bukkit.getPlayer(it.senderUuid)?.name?.equals(senderName, true) == true }
        }
        if (req == null) {
            context.locale.send(target, "teleport.tpa-none")
            return
        }
        val sender = Bukkit.getPlayer(req.senderUuid)
        val sName = sender?.name ?: senderName ?: "?"
        val bodyKey = if (req.type == TpaType.TO_TARGET) "teleport.tpa-dialog-body" else "teleport.tpahere-dialog-body"
        DialogBuilder.confirm(
            context = context,
            player = target,
            title = context.locale.component("teleport.tpa-dialog-title"),
            bodyLines = listOf(context.locale.component(bodyKey, mapOf("sender" to sName))),
            confirmLabel = context.locale.component("teleport.tpa-dialog-accept"),
            cancelLabel = context.locale.component("teleport.tpa-dialog-deny"),
            onConfirm = { p -> accept(p, sName) },
            onCancel = { p -> deny(p, sName) },
        )
    }

    fun openPlayerSelectorGui(sender: Player, type: TpaType, page: Int = 1) {
        val candidates = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != sender.uniqueId && sender.canSee(it) }
            .sortedBy { it.name.lowercase() }
        if (candidates.isEmpty()) {
            context.locale.send(sender, "teleport.tpa-no-players")
            return
        }
        val perPage = 28
        val totalPages = ((candidates.size - 1) / perPage) + 1
        val currentPage = page.coerceIn(1, totalPages)
        val titleKey = if (type == TpaType.TO_TARGET) "teleport.tpa-gui-title" else "teleport.tpahere-gui-title"
        val window = MenuWindow(context.locale.component(titleKey, mapOf("page" to currentPage.toString())), 6)
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val slice = candidates.drop((currentPage - 1) * perPage).take(perPage)
        var idx = 0
        for (row in 1..4) {
            for (col in 1..7) {
                if (idx >= slice.size) break
                val target = slice[idx++]
                val loreKey = if (type == TpaType.TO_TARGET) "teleport.tpa-gui-lore" else "teleport.tpahere-gui-lore"
                val head = MenuItems.playerHead(
                    uuid = target.uniqueId,
                    nameHint = target.name,
                    displayName = "<aqua>${target.name}</aqua>",
                    lore = listOf(
                        context.locale.raw("general.gui-world", "world" to target.world.name),
                        context.locale.raw(loreKey),
                    ),
                )
                window.set(row * 9 + col, head) { click ->
                    click.player.closeInventory()
                    sendRequest(click.player, target, type)
                }
            }
        }
        window.setFooterNav(
            context = context,
            page = currentPage,
            totalPages = totalPages,
            onPrev = { openPlayerSelectorGui(sender, type, currentPage - 1) },
            onNext = { openPlayerSelectorGui(sender, type, currentPage + 1) },
        )
        window.open(sender)
    }

    private fun tpaBarId(senderUuid: UUID): String = "tpa_$senderUuid"

    fun suggestIncomingSenders(target: Player, prefix: String): List<String> {
        val list = incoming[target.uniqueId] ?: return emptyList()
        pruneExpired(list)
        return list.mapNotNull { Bukkit.getPlayer(it.senderUuid)?.name }
            .filter { it.startsWith(prefix, ignoreCase = true) }
    }

    private fun popRequest(target: Player, senderName: String?): TpaRequest? {
        val list = incoming[target.uniqueId] ?: return null
        synchronized(list) {
            pruneExpired(list)
            if (list.isEmpty()) return null
            val idx = if (senderName == null) {
                list.lastIndex
            } else {
                list.indexOfLast { req ->
                    Bukkit.getPlayer(req.senderUuid)?.name?.equals(senderName, ignoreCase = true) == true
                }
            }
            if (idx < 0) return null
            return list.removeAt(idx)
        }
    }

    private fun pruneExpired(list: MutableList<TpaRequest>) {
        val cutoff = System.currentTimeMillis() - expireSeconds * 1000L
        synchronized(list) {
            list.removeIf { it.createdAt < cutoff }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        incoming.remove(uuid)
        incoming.values.forEach { list ->
            synchronized(list) { list.removeIf { it.senderUuid == uuid } }
        }
    }
}
