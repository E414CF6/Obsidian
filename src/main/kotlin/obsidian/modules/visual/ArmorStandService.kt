package obsidian.modules.visual

import io.papermc.paper.registry.data.dialog.input.DialogInput
import obsidian.core.ObsidianContext
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import obsidian.core.text.TextFormatter
import obsidian.core.ui.DialogBuilder
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
            19,
            MenuItems.item(
                Material.NAME_TAG,
                context.locale.raw("visual.armorstand-name-btn"),
                listOf(context.locale.raw("visual.armorstand-name-lore")),
            ),
        ) { click ->
            if (!stand.isValid) {
                click.player.closeInventory()
                return@set
            }
            click.player.closeInventory()
            val curName =
                stand.customName()?.let { net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(it) }
                    ?: ""
            DialogBuilder.form(
                context = context,
                player = click.player,
                title = context.locale.component("visual.armorstand-name-dialog-title"),
                inputs = listOf(
                    DialogInput.text("name", context.locale.component("visual.armorstand-name-dialog-label"))
                        .initial(curName).width(220).build(),
                ),
                submitLabel = context.locale.component("general.dialog-save"),
                cancelLabel = context.locale.component("general.dialog-cancel"),
            ) { p, resp ->
                if (!stand.isValid) return@form
                val raw = resp.getText("name")?.trim().orEmpty()
                if (raw.isEmpty()) {
                    stand.customName(null)
                    stand.isCustomNameVisible = false
                } else {
                    stand.customName(TextFormatter.format(raw))
                    stand.isCustomNameVisible = true
                }
                openEditor(p, stand)
            }
        }

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

        menu.set(
            25,
            MenuItems.item(
                Material.ARMOR_STAND,
                context.locale.raw("visual.armorstand-pose-btn"),
                listOf(context.locale.raw("visual.armorstand-pose-lore")),
            ),
        ) { click ->
            if (!stand.isValid) {
                click.player.closeInventory()
                return@set
            }
            click.player.closeInventory()
            openHeadPoseDialog(click.player, stand)
        }

        menu.open(player)
    }

    private fun openHeadPoseDialog(player: Player, stand: ArmorStand) {
        val degX = Math.toDegrees(stand.headPose.x).toFloat().coerceIn(-180f, 180f)
        val degY = Math.toDegrees(stand.headPose.y).toFloat().coerceIn(-180f, 180f)
        val degZ = Math.toDegrees(stand.headPose.z).toFloat().coerceIn(-180f, 180f)
        DialogBuilder.form(
            context = context,
            player = player,
            title = context.locale.component("visual.armorstand-pose-dialog-title"),
            inputs = listOf(
                DialogInput.numberRange("hx", context.locale.component("visual.armorstand-pose-x"), -180f, 180f)
                    .initial(degX).step(5f).width(220).build(),
                DialogInput.numberRange("hy", context.locale.component("visual.armorstand-pose-y"), -180f, 180f)
                    .initial(degY).step(5f).width(220).build(),
                DialogInput.numberRange("hz", context.locale.component("visual.armorstand-pose-z"), -180f, 180f)
                    .initial(degZ).step(5f).width(220).build(),
            ),
            submitLabel = context.locale.component("general.dialog-save"),
            cancelLabel = context.locale.component("general.dialog-cancel"),
        ) { p, resp ->
            if (!stand.isValid) return@form
            val rx = Math.toRadians((resp.getFloat("hx") ?: degX).toDouble())
            val ry = Math.toRadians((resp.getFloat("hy") ?: degY).toDouble())
            val rz = Math.toRadians((resp.getFloat("hz") ?: degZ).toDouble())
            stand.headPose = EulerAngle(rx, ry, rz)
            openEditor(p, stand)
        }
    }
}
