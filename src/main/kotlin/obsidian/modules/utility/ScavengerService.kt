package obsidian.modules.utility

import obsidian.core.ObsidianContext
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta

data class ScavengeOutcome(
    val bookCount: Int,
    val returnItems: List<ItemStack>,
)

/**
 * Recycles enchanted armor, tools, and weapons into enchanted books and raw crafting components.
 */
class ScavengerService(private val context: ObsidianContext) {

    fun canScavenge(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        val mat = item.type
        val isEquipment = mat.name.endsWith("_HELMET") ||
                mat.name.endsWith("_CHESTPLATE") ||
                mat.name.endsWith("_LEGGINGS") ||
                mat.name.endsWith("_BOOTS") ||
                mat.name.endsWith("_SWORD") ||
                mat.name.endsWith("_PICKAXE") ||
                mat.name.endsWith("_AXE") ||
                mat.name.endsWith("_SHOVEL") ||
                mat.name.endsWith("_HOE") ||
                mat == Material.BOW || mat == Material.CROSSBOW || mat == Material.TRIDENT || mat == Material.FISHING_ROD
        return isEquipment && item.enchantments.isNotEmpty()
    }

    fun scavenge(player: Player, item: ItemStack): ScavengeOutcome? {
        if (!canScavenge(item)) return null

        val returnItems = mutableListOf<ItemStack>()
        var bookCount = 0

        // Extract enchantments into books
        for ((enchant, level) in item.enchantments) {
            val book = ItemStack(Material.ENCHANTED_BOOK)
            val meta = book.itemMeta as? EnchantmentStorageMeta ?: continue
            meta.addStoredEnchant(enchant, level, true)
            book.itemMeta = meta
            returnItems += book
            bookCount++
        }

        // Return raw primary material
        val matName = item.type.name
        val rawMaterial = when {
            matName.startsWith("NETHERITE_") -> Material.NETHERITE_INGOT
            matName.startsWith("DIAMOND_") -> Material.DIAMOND
            matName.startsWith("GOLDEN_") -> Material.GOLD_INGOT
            matName.startsWith("IRON_") -> Material.IRON_INGOT
            matName.startsWith("LEATHER_") -> Material.LEATHER
            else -> null
        }
        if (rawMaterial != null) {
            returnItems += ItemStack(rawMaterial, 1)
        }

        // Consume one held item
        if (item.amount > 1) {
            item.amount -= 1
        } else {
            player.inventory.setItemInMainHand(null)
        }

        // Give extracted items
        for (extracted in returnItems) {
            val leftovers = player.inventory.addItem(extracted)
            for (leftover in leftovers.values) {
                player.world.dropItemNaturally(player.location, leftover)
            }
        }

        return ScavengeOutcome(bookCount, returnItems)
    }
}
