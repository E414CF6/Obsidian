package obsidian.modules.utility

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.Repairable
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimMaterial
import org.bukkit.inventory.meta.trim.TrimPattern
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles CMI-style anvil color renaming, repair-cost caps, disabled enchantments,
 * armor trims, and custom model data utilities (`AnvilManager` & `EnchantManager` equivalent).
 */
class AnvilAndEnchantService(private val context: ObsidianContext) : Listener {

    private val disabledEnchants = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var anvilColorsEnabled: Boolean = true
        private set

    @Volatile
    var maxRepairCost: Int = 0
        private set

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        anvilColorsEnabled = raw.getBoolean("anvil.colors-enabled", true)
        maxRepairCost = raw.getInt("anvil.max-repair-cost", 0).coerceAtLeast(0)
        disabledEnchants.clear()
        for (key in raw.getStringList("enchants.disabled")) {
            val clean = normalizeKey(key)
            if (clean.isNotEmpty()) disabledEnchants += clean
        }
    }

    fun isEnchantDisabled(enchant: Enchantment): Boolean =
        disabledEnchants.contains(enchant.key.key.lowercase(Locale.ROOT)) ||
                disabledEnchants.contains(enchant.key.toString().lowercase(Locale.ROOT))

    fun toggleDisabledEnchant(enchant: Enchantment): Boolean {
        val key = enchant.key.key.lowercase(Locale.ROOT)
        val nowDisabled = if (disabledEnchants.contains(key)) {
            disabledEnchants.remove(key)
            false
        } else {
            disabledEnchants.add(key)
            true
        }
        context.config.raw.set("enchants.disabled", disabledEnchants.sorted())
        context.config.save()
        return nowDisabled
    }

    fun getDisabledEnchantKeys(): Set<String> = disabledEnchants.toSet()

    fun resolveEnchantment(query: String): Enchantment? {
        val clean = query.trim().lowercase(Locale.ROOT)
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
        val namespaced = if (clean.contains(':')) {
            NamespacedKey.fromString(clean)
        } else {
            NamespacedKey.minecraft(clean)
        }
        if (namespaced != null) {
            registry.get(namespaced)?.let { return it }
        }
        return registry.firstOrNull { it.key.key.equals(clean, ignoreCase = true) }
    }

    fun resolveTrimMaterial(query: String): TrimMaterial? {
        val clean = query.trim().lowercase(Locale.ROOT)
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL)
        val key = if (clean.contains(':')) NamespacedKey.fromString(clean) else NamespacedKey.minecraft(clean)
        return (key?.let { registry.get(it) })
            ?: registry.firstOrNull { it.key.key.equals(clean, ignoreCase = true) }
    }

    fun resolveTrimPattern(query: String): TrimPattern? {
        val clean = query.trim().lowercase(Locale.ROOT)
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN)
        val key = if (clean.contains(':')) NamespacedKey.fromString(clean) else NamespacedKey.minecraft(clean)
        return (key?.let { registry.get(it) })
            ?: registry.firstOrNull { it.key.key.equals(clean, ignoreCase = true) }
    }

    fun applyTrim(item: ItemStack, material: TrimMaterial, pattern: TrimPattern): Boolean {
        val meta = item.itemMeta as? ArmorMeta ?: return false
        meta.trim = ArmorTrim(material, pattern)
        item.itemMeta = meta
        return true
    }

    fun getRepairCost(item: ItemStack): Int {
        val meta = item.itemMeta as? Repairable ?: return 0
        return if (meta.hasRepairCost()) meta.repairCost else 0
    }

    fun setRepairCost(item: ItemStack, cost: Int): Boolean {
        val meta = item.itemMeta as? Repairable ?: return false
        meta.repairCost = cost.coerceAtLeast(0)
        item.itemMeta = meta
        return true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val view = event.view
        val player = view.player as? Player ?: return
        val result = event.result ?: return

        var updated = result
        val renameText = view.renameText
        if (anvilColorsEnabled && !renameText.isNullOrBlank() &&
            (player.hasPermission("obsidian.anvil.color") || player.hasPermission("obsidian.admin"))
        ) {
            val meta = updated.itemMeta
            if (meta != null) {
                meta.displayName(TextFormatter.format(renameText))
                updated.itemMeta = meta
            }
        }

        if (disabledEnchants.isNotEmpty() && !player.hasPermission("obsidian.admin")) {
            val toRemove = updated.enchantments.keys.filter { isEnchantDisabled(it) }
            if (toRemove.isNotEmpty()) {
                updated = updated.clone()
                for (ench in toRemove) {
                    updated.removeEnchantment(ench)
                }
            }
        }

        if (maxRepairCost > 0) {
            view.maximumRepairCost = maxRepairCost
            if (view.repairCost > maxRepairCost) {
                view.repairCost = maxRepairCost
            }
        }

        val firstSlot = view.topInventory.getItem(0)
        val secondSlot = view.topInventory.getItem(1)
        if ((secondSlot == null || secondSlot.type.isAir) && firstSlot != null &&
            (player.hasPermission("obsidian.anvil.freerename") || player.hasPermission("obsidian.admin"))
        ) {
            view.repairCost = 0
        }

        event.result = updated
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEnchantItem(event: EnchantItemEvent) {
        if (disabledEnchants.isEmpty()) return
        val player = event.enchanter
        if (player.hasPermission("obsidian.admin")) return

        val blocked = event.enchantsToAdd.keys.filter { isEnchantDisabled(it) }
        for (ench in blocked) {
            event.enchantsToAdd.remove(ench)
        }
        if (event.enchantsToAdd.isEmpty()) {
            event.isCancelled = true
            context.locale.send(player, "utility.enchant-disabled-all")
        }
    }

    private fun normalizeKey(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).removePrefix("minecraft:")
}
