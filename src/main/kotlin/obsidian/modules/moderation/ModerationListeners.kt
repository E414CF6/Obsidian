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
        val isAllowed = runBlocking { context.punishmentRepository.isAllowed(event.uniqueId) }
        if (isAllowed) return

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

        if (service.notifyBannedJoin && !service.isThrottled("banned_join_${event.uniqueId}")) {
            val alert = TextFormatter.format(
                "<dark_gray>[<red>Notice</red>]</dark_gray> <gray>Banned player <white>${event.name}</white> attempted to connect.</gray>"
            )
            for (p in Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("obsidian.notify.banned_join") || p.hasPermission("obsidian.admin")) {
                    p.sendMessage(alert)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        service.loadOnlineModerationState(player)
        service.applyVanishVisibilityForViewer(player)
        if (service.isVanished(player.uniqueId)) {
            event.joinMessage(null)
        }

        if (service.dupeipOnJoin) {
            val ip = player.address?.address?.hostAddress
            context.scheduler.runAsync {
                val alts = runBlocking { service.scanAlts(player.uniqueId, ip) }
                if (alts.size > 1) {
                    val hasBannedAlt = if (service.dupeipOnBannedAccount) runBlocking {
                        service.hasActiveBannedAlt(
                            alts,
                            player.uniqueId
                        )
                    } else false
                    val hasMutedAlt = if (service.dupeipOnMutedAccount) runBlocking {
                        service.hasActiveMutedAlt(
                            alts,
                            player.uniqueId
                        )
                    } else false
                    val exceedsThreshold = alts.size >= service.dupeipOnJoinThreshold

                    if (hasBannedAlt || hasMutedAlt || exceedsThreshold) {
                        if (!service.isThrottled("dupeip_${player.uniqueId}")) {
                            val otherAlts =
                                alts.filter { it.uuid != player.uniqueId }.joinToString(", ") { it.username }
                            val tag =
                                if (hasBannedAlt) " <red>(Banned Alt Detected)</red>" else if (hasMutedAlt) " <gold>(Muted Alt Detected)</gold>" else ""
                            val alertMsg = TextFormatter.format(
                                "<dark_gray>[<yellow>Alt Notice</yellow>]</dark_gray> <white>${player.name}</white> has <aqua>${alts.size}</aqua> accounts on this IP: <gray>$otherAlts</gray>$tag"
                            )
                            for (staff in Bukkit.getOnlinePlayers()) {
                                if (staff.hasPermission("obsidian.notify.dupeip_join") || staff.hasPermission("obsidian.admin")) {
                                    staff.sendMessage(alertMsg)
                                }
                            }
                        }
                    }
                }
            }
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
        val label = event.message.removePrefix("/").substringBefore(' ').substringAfter(':').lowercase()

        val jail = service.getActiveJail(player.uniqueId)
        if (jail != null && !player.hasPermission("obsidian.admin")) {
            if (label in service.blockedJailCommands) {
                event.isCancelled = true
                context.locale.send(
                    player,
                    "moderation.jailed-action-blocked",
                    "expires" to DurationParser.formatExpiry(jail.expiresAt),
                )
                return
            }
        }

        val mute = service.getActiveMute(player.uniqueId)
        if (mute != null && mute.type != PunishmentType.SHADOW_MUTE && !player.hasPermission("obsidian.admin")) {
            if (label in service.muteBlockedCommands) {
                event.isCancelled = true
                context.locale.send(
                    player,
                    "moderation.muted-chat-blocked",
                    "expires" to DurationParser.formatExpiry(mute.expiresAt),
                    "reason" to mute.reason,
                )
                if (service.notifyMutedChat && !service.isThrottled("muted_cmd_${player.uniqueId}")) {
                    val alert = TextFormatter.format(
                        "<dark_gray>[<red>Mute Notice</red>]</dark_gray> <gray>Muted player <white>${player.name}</white> tried to use command: <white>/$label</white></gray>"
                    )
                    for (staff in Bukkit.getOnlinePlayers()) {
                        if (staff.hasPermission("obsidian.notify.mute") || staff.hasPermission("obsidian.admin")) {
                            staff.sendMessage(alert)
                        }
                    }
                }
            }
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
