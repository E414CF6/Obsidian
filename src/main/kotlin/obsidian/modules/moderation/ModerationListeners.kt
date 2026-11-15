package obsidian.modules.moderation

import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.coroutines.runBlocking
import obsidian.core.ObsidianContext
import obsidian.core.database.PunishmentType
import obsidian.core.text.DurationParser
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.*

/**
 * Enforces bans, mutes, jails, cuffs, vanish visibility, and silent chest inspection.
 */
class ModerationListeners(
    private val context: ObsidianContext,
    private val service: ModerationService,
) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        val ip = event.address.hostAddress
        val ban = runBlocking { service.checkActiveBan(event.uniqueId, ip, event.name) } ?: return
        val expiresStr = DurationParser.formatExpiry(ban.expiresAt)
        val screen = TextFormatter.format(
            context.locale.raw("moderation.banned-screen"),
            mapOf(
                "reason" to ban.reason,
                "actor" to ban.actorName,
                "expires" to expiresStr,
            ),
        )
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        service.loadOnlineModerationState(player)
        service.applyVanishVisibilityForViewer(player)
        if (service.isVanished(player.uniqueId)) {
            event.joinMessage(null)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (service.isVanished(player.uniqueId)) {
            event.quitMessage(null)
        }
        service.clearOnlineTransientState(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFallbackMuteCheck(event: AsyncChatEvent) {
        // When ChatModule is disabled, ModerationListeners still enforces mutes & shadowmutes.
        if (context.modules.isActive("chat")) return
        val player = event.player
        val mute = service.getActiveMute(player.uniqueId) ?: return
        if (mute.type == PunishmentType.SHADOW_MUTE) {
            event.viewers().removeIf { it !== player }
        } else {
            event.isCancelled = true
            val expiresStr = DurationParser.formatExpiry(mute.expiresAt)
            context.locale.send(
                player,
                "moderation.muted-chat-blocked",
                "expires" to expiresStr,
                "reason" to mute.reason,
            )
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        val jail = service.getActiveJail(player.uniqueId) ?: return
        if (player.hasPermission("obsidian.admin")) return
        val label = event.message.removePrefix("/").substringBefore(' ').substringAfter(':').lowercase()
        if (label in service.blockedJailCommands) {
            event.isCancelled = true
            context.locale.send(
                player,
                "moderation.jailed-action-blocked",
                "expires" to DurationParser.formatExpiry(jail.expiresAt),
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        if (service.isCuffed(player.uniqueId)) {
            if (event.from.x != event.to.x || event.from.y != event.to.y || event.from.z != event.to.z) {
                event.isCancelled = true
            }
            return
        }
        val jail = service.getActiveJail(player.uniqueId) ?: return
        val jailLoc = jail.jailName?.let(service::getJailLocation) ?: return
        if (event.to.world?.name != jailLoc.world || event.to.distanceSquared(
                jailLoc.toBukkitLocation() ?: return
            ) > 64.0
        ) {
            jailLoc.toBukkitLocation()?.let { event.to = it }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (service.isCuffed(player.uniqueId)) {
            event.isCancelled = true
            context.locale.send(player, "moderation.cuffed-blocked")
            return
        }
        if (service.isVanished(player.uniqueId) && service.vanishSilentChest && event.action == Action.RIGHT_CLICK_BLOCK) {
            val block = event.clickedBlock ?: return
            val state = block.state
            if (state is Container) {
                event.isCancelled = true
                val copy = Bukkit.createInventory(
                    null, state.inventory.size, TextFormatter.format("<gray>Silent Inspect</gray>")
                )
                copy.contents = state.inventory.contents
                player.openInventory(copy)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        if (service.isCuffed(victim.uniqueId) || service.getActiveJail(victim.uniqueId) != null) {
            event.isCancelled = true
            return
        }
        if (event is EntityDamageByEntityEvent) {
            val damager = event.damager as? Player ?: return
            if (service.isCuffed(damager.uniqueId)) {
                event.isCancelled = true
            }
        }
    }
}
