package obsidian.modules.teleport

import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
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
            msg = msg.append(acceptBtn).append(TextFormatter.format(" ")).append(denyBtn)
        }
        target.sendMessage(msg)
    }

    fun accept(target: Player, senderName: String?) {
        val request = popRequest(target, senderName) ?: run {
            context.locale.send(target, "teleport.tpa-none")
            return
        }
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
