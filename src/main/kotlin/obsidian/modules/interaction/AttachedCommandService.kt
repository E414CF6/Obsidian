package obsidian.modules.interaction

import obsidian.core.ObsidianContext
import obsidian.core.command.SpecializedCommandExecutor
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores and executes item-bound commands inside Paper [PersistentDataContainer] (`/attachcommand`).
 */
class AttachedCommandService(private val context: ObsidianContext) : Listener {

    private val cmdsKey = NamespacedKey(context.plugin, "attached_commands")
    private val cooldownKey = NamespacedKey(context.plugin, "attached_cooldown_sec")
    private val usesKey = NamespacedKey(context.plugin, "attached_uses")
    private val lastUseByPlayer = ConcurrentHashMap<UUID, Long>()

    fun getCommands(item: ItemStack?): List<String> {
        if (item == null || item.type.isAir) return emptyList()
        val raw = item.itemMeta?.persistentDataContainer?.get(cmdsKey, PersistentDataType.STRING) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split("\u0000").filter { it.isNotBlank() }
    }

    fun getCooldownSeconds(item: ItemStack): Long =
        item.itemMeta?.persistentDataContainer?.get(cooldownKey, PersistentDataType.LONG) ?: 0L

    fun getRemainingUses(item: ItemStack): Int =
        item.itemMeta?.persistentDataContainer?.get(usesKey, PersistentDataType.INTEGER) ?: -1

    fun addCommand(item: ItemStack, command: String): List<String> {
        val current = getCommands(item).toMutableList()
        current += command
        writeCommands(item, current)
        return current
    }

    fun clearCommands(item: ItemStack) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.remove(cmdsKey)
        meta.persistentDataContainer.remove(cooldownKey)
        meta.persistentDataContainer.remove(usesKey)
        item.itemMeta = meta
    }

    fun setCooldown(item: ItemStack, seconds: Long) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(cooldownKey, PersistentDataType.LONG, seconds.coerceAtLeast(0L))
        item.itemMeta = meta
    }

    fun setUses(item: ItemStack, uses: Int) {
        val meta = item.itemMeta ?: return
        if (uses <= 0) {
            meta.persistentDataContainer.remove(usesKey)
        } else {
            meta.persistentDataContainer.set(usesKey, PersistentDataType.INTEGER, uses)
        }
        item.itemMeta = meta
    }

    private fun writeCommands(item: ItemStack, commands: List<String>) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(cmdsKey, PersistentDataType.STRING, commands.joinToString("\u0000"))
        item.itemMeta = meta
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onItemInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (!event.action.isRightClick) return
        val item = event.item ?: return
        val commands = getCommands(item)
        if (commands.isEmpty()) return

        event.isCancelled = true
        val player = event.player
        val cdMs = getCooldownSeconds(item) * 1000L
        val now = System.currentTimeMillis()
        if (cdMs > 0L) {
            val last = lastUseByPlayer[player.uniqueId] ?: 0L
            if (now - last < cdMs) {
                val remain = ((cdMs - (now - last)) / 1000L).coerceAtLeast(1L)
                context.locale.send(player, "interaction.attached-cooldown-active", "seconds" to remain)
                return
            }
            lastUseByPlayer[player.uniqueId] = now
        }

        val uses = getRemainingUses(item)
        if (uses > 0) {
            val next = uses - 1
            if (next <= 0) {
                if (item.amount > 1) {
                    item.amount -= 1
                } else {
                    player.inventory.setItemInMainHand(null)
                }
            } else {
                setUses(item, next)
            }
        }

        SpecializedCommandExecutor.executeAll(context, commands, player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastUseByPlayer.remove(event.player.uniqueId)
    }
}
