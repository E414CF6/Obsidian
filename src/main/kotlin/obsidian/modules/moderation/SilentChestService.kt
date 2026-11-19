package obsidian.modules.moderation

import obsidian.core.ObsidianContext
import org.bukkit.block.Container
import org.bukkit.block.EnderChest
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Allows staff members to open containers (chests, barrels, shulker boxes) silently
 * without opening animations or sounds, with optional view-only inspection.
 */
class SilentChestService(private val context: ObsidianContext) : Listener {

    private val silentModeActive = ConcurrentHashMap.newKeySet<UUID>()
    private val openInspecting = ConcurrentHashMap<UUID, Inventory>()

    fun isSilent(player: Player): Boolean {
        if (silentModeActive.contains(player.uniqueId)) return true
        val mod = context.getService<ModerationService>()
        return mod?.isVanished(player.uniqueId) == true
    }

    fun toggleSilent(player: Player): Boolean {
        return if (silentModeActive.remove(player.uniqueId)) {
            false
        } else {
            silentModeActive.add(player.uniqueId)
            true
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        if (!player.hasPermission("obsidian.command.silentchest") && !player.hasPermission("obsidian.admin")) return
        if (!isSilent(player)) return

        val block = event.clickedBlock ?: return
        val state = block.state

        if (state is Container) {
            event.isCancelled = true
            val inv = state.inventory
            val canEdit = player.hasPermission("obsidian.silentchest.edit") || player.hasPermission("obsidian.admin")
            if (!canEdit) {
                openInspecting[player.uniqueId] = inv
            }
            player.openInventory(inv)
            context.locale.send(player, "moderation.silentchest-opened", "type" to block.type.name.lowercase())
        } else if (state is EnderChest) {
            event.isCancelled = true
            player.openInventory(player.enderChest)
            context.locale.send(player, "moderation.silentchest-opened", "type" to "ender_chest")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val inspecting = openInspecting[player.uniqueId] ?: return
        if (event.inventory == inspecting || event.clickedInventory == inspecting) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        silentModeActive.remove(event.player.uniqueId)
        openInspecting.remove(event.player.uniqueId)
    }
}
