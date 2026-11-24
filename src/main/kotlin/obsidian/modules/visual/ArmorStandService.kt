package obsidian.modules.visual

import obsidian.core.ObsidianContext
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.util.EulerAngle

/**
 * Provides direct CLI flag adjustments and a Chest GUI editor for [ArmorStand] entities.
 */
class ArmorStandService(private val context: ObsidianContext) {

    fun findTargetArmorStand(player: Player, maxDistance: Double = 6.0): ArmorStand? {
        val eye = player.eyeLocation
        val hit = player.world.rayTraceEntities(eye, eye.direction, maxDistance, 0.25) {
            it is ArmorStand && it.uniqueId != player.uniqueId
        }?.hitEntity as? ArmorStand
        if (hit != null) return hit
        return player.getNearbyEntities(maxDistance, maxDistance, maxDistance).asSequence()
            .filterIsInstance<ArmorStand>().minByOrNull { it.location.distanceSquared(player.location) }
    }

    fun applyFlag(stand: ArmorStand, flag: String, explicitState: Boolean? = null): Pair<String, Boolean>? {
        val normalized = flag.lowercase()
        val next = when (normalized) {
            "small" -> (explicitState ?: !stand.isSmall).also { stand.isSmall = it }
            "arms" -> (explicitState ?: !stand.hasArms()).also { stand.setArms(it) }
            "baseplate", "plate" -> (explicitState ?: !stand.hasBasePlate()).also { stand.setBasePlate(it) }
            "visible", "show" -> (explicitState ?: !stand.isVisible).also { stand.isVisible = it }
            "gravity" -> (explicitState ?: !stand.hasGravity()).also { stand.setGravity(it) }
            "marker" -> (explicitState ?: !stand.isMarker).also { stand.isMarker = it }
            "glow", "glowing" -> (explicitState ?: !stand.isGlowing).also { stand.isGlowing = it }
            "customname", "namevisible" -> (explicitState
                ?: !stand.isCustomNameVisible).also { stand.isCustomNameVisible = it }

            else -> return null
        }
        return normalized to next
    }

    fun resetPose(stand: ArmorStand) {
        stand.headPose = EulerAngle.ZERO
        stand.bodyPose = EulerAngle.ZERO
        stand.leftArmPose = EulerAngle.ZERO
        stand.rightArmPose = EulerAngle.ZERO
        stand.leftLegPose = EulerAngle.ZERO
        stand.rightLegPose = EulerAngle.ZERO
    }

    fun openEditor(player: Player, stand: ArmorStand) {
        val menu =
            MenuWindow(context.locale.component("visual.armorstand-editor-title"), 3)
        menu.fillBorder(MenuItems.item(Material.BLACK_STAINED_GLASS_PANE, "<gray> </gray>"))

        fun stateTag(enabled: Boolean): String =
            context.locale.raw(if (enabled) "general.state-on" else "general.state-off")

        fun addToggle(slot: Int, mat: Material, labelKey: String, flag: String, current: () -> Boolean) {
            val label = context.locale.raw(labelKey)
            menu.set(
                slot,
                MenuItems.item(
                    mat,
                    "<aqua>$label</aqua>",
                    listOf(
                        context.locale.raw("visual.armorstand-current", "state" to stateTag(current())),
                        context.locale.raw("visual.armorstand-click-toggle"),
                    ),
                ),
            ) { click ->
                if (!stand.isValid) {
                    click.player.closeInventory()
                    return@set
                }
                applyFlag(stand, flag)
                openEditor(click.player, stand)
            }
        }

        addToggle(10, Material.FEATHER, "visual.armorstand-opt-small", "small") { stand.isSmall }
        addToggle(11, Material.STICK, "visual.armorstand-opt-arms", "arms") { stand.hasArms() }
        addToggle(
            12,
            Material.SMOOTH_STONE_SLAB,
            "visual.armorstand-opt-baseplate",
            "baseplate"
        ) { stand.hasBasePlate() }
        addToggle(13, Material.GLASS, "visual.armorstand-opt-visible", "visible") { stand.isVisible }
        addToggle(14, Material.ANVIL, "visual.armorstand-opt-gravity", "gravity") { stand.hasGravity() }
        addToggle(15, Material.GLOWSTONE_DUST, "visual.armorstand-opt-glowing", "glowing") { stand.isGlowing }
        addToggle(16, Material.BARRIER, "visual.armorstand-opt-marker", "marker") { stand.isMarker }

        menu.set(
            21,
            MenuItems.item(
                Material.COMPASS,
                context.locale.raw("visual.armorstand-rotate-name"),
                listOf(context.locale.raw("visual.armorstand-rotate-lore")),
            ),
        ) { click ->
            if (!stand.isValid) {
                click.player.closeInventory()
                return@set
            }
            val loc = stand.location
            loc.yaw = (loc.yaw + 45f) % 360f
            stand.teleport(loc)
            openEditor(click.player, stand)
        }

        menu.set(
            23,
            MenuItems.item(
                Material.MILK_BUCKET,
                context.locale.raw("visual.armorstand-reset-name"),
                listOf(context.locale.raw("visual.armorstand-reset-lore")),
            ),
        ) { click ->
            if (!stand.isValid) {
                click.player.closeInventory()
                return@set
            }
            resetPose(stand)
            openEditor(click.player, stand)
        }

        menu.open(player)
    }
}
