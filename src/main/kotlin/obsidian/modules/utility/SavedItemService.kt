package obsidian.modules.utility

import obsidian.core.ObsidianContext
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages custom persistent item stacks stored in `plugins/Obsidian/saveditems.yml`.
 * Serializes item meta, PDC, enchantments, and custom model data cleanly via Base64.
 */
class SavedItemService(private val context: ObsidianContext) {

    private val file = File(context.plugin.dataFolder, "saveditems.yml")
    private val items = ConcurrentHashMap<String, ItemStack>()

    init {
        load()
    }

    fun load() {
        items.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val sec = yaml.getConfigurationSection("items") ?: return
        for (key in sec.getKeys(false)) {
            val base64 = sec.getString(key) ?: continue
            val decoded = runCatching {
                val bytes = Base64.getDecoder().decode(base64)
                ItemStack.deserializeBytes(bytes)
            }.getOrNull()
            if (decoded != null && !decoded.type.isAir) {
                items[key.lowercase()] = decoded
            }
        }
    }

    fun save() {
        val yaml = YamlConfiguration()
        for ((key, item) in items) {
            val base64 = Base64.getEncoder().encodeToString(item.serializeAsBytes())
            yaml.set("items.$key", base64)
        }
        runCatching {
            file.parentFile?.mkdirs()
            yaml.save(file)
        }
    }

    fun saveItem(id: String, item: ItemStack): Boolean {
        if (item.type.isAir) return false
        items[id.lowercase()] = item.clone()
        context.scheduler.runAsync { save() }
        return true
    }

    fun getItem(id: String): ItemStack? = items[id.lowercase()]?.clone()

    fun removeItem(id: String): Boolean {
        val removed = items.remove(id.lowercase()) != null
        if (removed) {
            context.scheduler.runAsync { save() }
        }
        return removed
    }

    fun listIds(): Set<String> = items.keys.toSet()

    fun giveItem(player: Player, id: String, amount: Int = 1): Boolean {
        val base = getItem(id) ?: return false
        var remaining = amount.coerceAtLeast(1)
        val maxStack = base.maxStackSize
        while (remaining > 0) {
            val chunk = minOf(remaining, maxStack)
            val stack = base.clone().apply { this.amount = chunk }
            val leftovers = player.inventory.addItem(stack)
            for (leftover in leftovers.values) {
                player.world.dropItemNaturally(player.location, leftover)
            }
            remaining -= chunk
        }
        return true
    }
}
