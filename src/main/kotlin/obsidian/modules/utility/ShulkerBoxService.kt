package obsidian.modules.utility

import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Allows players with `obsidian.openshulker` to right-click a shulker box in hand to open and edit
 * its contents safely, using PDC session IDs to prevent duplication or nested shulker exploits.
 */
class ShulkerBoxService(private val context: ObsidianContext) : Listener {

    private val shulkerKey = NamespacedKey(context.plugin, "obsidian_shulker_session")
    private val nextId = AtomicInteger(1)
    private val activeSessions = ConcurrentHashMap<UUID, ShulkerSession>()

    private class ShulkerHolder(val sessionId: Int) : InventoryHolder {
        lateinit var backingInventory: Inventory
        override fun getInventory(): Inventory = backingInventory
    }

    private data class ShulkerSession(
        val sessionId: Int,
        val holder: ShulkerHolder,
        val readOnly: Boolean,
    )

    fun closeAll() {
        for ((uuid, _) in activeSessions.toMap()) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            saveAndCleanup(player, player.openInventory.topInventory)
            player.closeInventory()
        }
        activeSessions.clear()
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR) return

        val player = event.player
        if (!player.hasPermission("obsidian.openshulker") && !player.hasPermission("obsidian.admin")) return

        val item = player.inventory.itemInMainHand
        if (!isShulkerBox(item.type)) return
        if (activeSessions.containsKey(player.uniqueId)) return

        val meta = item.itemMeta as? BlockStateMeta ?: return
        val boxState = meta.blockState as? ShulkerBox ?: return

        event.isCancelled = true

        val sessionId = nextId.getAndIncrement()
        meta.persistentDataContainer.set(shulkerKey, PersistentDataType.INTEGER, sessionId)
        item.itemMeta = meta

        val title = meta.displayName() ?: Component.translatable(item.type.translationKey())
        val holder = ShulkerHolder(sessionId)
        val inv = Bukkit.createInventory(holder, InventoryType.SHULKER_BOX, title)
        holder.backingInventory = inv
        inv.contents = boxState.inventory.contents.map { it?.clone() }.toTypedArray()

        val readOnly = !player.hasPermission("obsidian.openshulker.edit") && !player.hasPermission("obsidian.admin")
        activeSessions[player.uniqueId] = ShulkerSession(sessionId, holder, readOnly)
        player.openInventory(inv)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = activeSessions[player.uniqueId] ?: return
        if (event.view.topInventory.holder !== session.holder) return

        if (session.readOnly) {
            event.isCancelled = true
            return
        }

        if (event.click == ClickType.NUMBER_KEY || event.click == ClickType.SWAP_OFFHAND) {
            event.isCancelled = true
            return
        }

        val current = event.currentItem
        if (current != null && isShulkerBox(current.type)) {
            event.isCancelled = true
            return
        }

        val cursor = event.cursor
        if (isShulkerBox(cursor.type)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = activeSessions[player.uniqueId] ?: return
        if (event.view.topInventory.holder !== session.holder) return

        if (session.readOnly || isShulkerBox(event.oldCursor.type)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) {
        val player = event.player
        val session = activeSessions[player.uniqueId] ?: return
        val dropped = event.itemDrop.itemStack
        val droppedId = dropped.itemMeta?.persistentDataContainer?.get(shulkerKey, PersistentDataType.INTEGER)
        if (droppedId == session.sessionId) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = activeSessions[player.uniqueId] ?: return
        if (event.inventory.holder !== session.holder) return
        saveAndCleanup(player, event.inventory)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val session = activeSessions[player.uniqueId] ?: return
        saveAndCleanup(player, session.holder.backingInventory)
    }

    private fun saveAndCleanup(player: Player, inv: Inventory) {
        val session = activeSessions.remove(player.uniqueId) ?: return
        val contents = player.inventory.contents
        for (i in contents.indices) {
            val item = contents[i] ?: continue
            if (!isShulkerBox(item.type)) continue
            val meta = item.itemMeta as? BlockStateMeta ?: continue
            val id = meta.persistentDataContainer.get(shulkerKey, PersistentDataType.INTEGER)
            if (id != session.sessionId) continue

            meta.persistentDataContainer.remove(shulkerKey)
            if (!session.readOnly) {
                val boxState = meta.blockState as? ShulkerBox
                if (boxState != null) {
                    boxState.inventory.contents = inv.contents.map { it?.clone() }.toTypedArray()
                    meta.blockState = boxState
                }
            }
            item.itemMeta = meta
            player.inventory.setItem(i, item)
            break
        }
    }

    private fun isShulkerBox(material: Material): Boolean {
        return material == Material.SHULKER_BOX || material.name.endsWith("_SHULKER_BOX")
    }
}
