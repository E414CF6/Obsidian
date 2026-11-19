package obsidian.modules.visual

import obsidian.core.ObsidianContext
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SpawnEggMeta

/**
 * Handles spawner right-click with spawn eggs and enforces permission checks.
 * Permissions: `obsidian.egginteract.<mob>`, `obsidian.egginteract.all`, or `obsidian.admin`.
 */
class SpawnerEggService(private val context: ObsidianContext) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val mat = clicked.type
        val isSpawner = mat == Material.SPAWNER
        val isTrialSpawner = mat.name == "TRIAL_SPAWNER"
        if (!isSpawner && !isTrialSpawner) return

        val item = event.item ?: return
        if (item.type.isAir) return
        val itemType = item.type.name
        if (!itemType.endsWith("_SPAWN_EGG")) return

        val requirePerm = context.plugin.config.getBoolean("spawners.egg-interact-require-permission", true)
        if (!requirePerm) return

        val player = event.player
        val entityType = resolveEntityType(item) ?: return
        val entityName = entityType.name.lowercase()

        val basePerm = if (isTrialSpawner) "obsidian.trialegginteract" else "obsidian.egginteract"
        val hasPerm = player.hasPermission("obsidian.admin") ||
                player.hasPermission("$basePerm.all") ||
                player.hasPermission("$basePerm.$entityName")

        if (!hasPerm) {
            event.isCancelled = true
            context.locale.send(player, "visual.egginteract-denied", "mob" to entityType.name)
        }
    }

    private fun resolveEntityType(item: ItemStack): EntityType? {
        val meta = item.itemMeta
        if (meta is SpawnEggMeta) {
            meta.customSpawnedType?.let { return it }
        }
        val name = item.type.name.removeSuffix("_SPAWN_EGG")
        runCatching { EntityType.valueOf(name) }.getOrNull()?.let { return it }
        return when (name) {
            "MOOSHROOM" -> runCatching { EntityType.valueOf("MUSHROOM_COW") }.getOrNull()
            else -> null
        }
    }
}
