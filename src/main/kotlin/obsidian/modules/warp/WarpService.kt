package obsidian.modules.warp

import io.papermc.paper.registry.data.dialog.input.DialogInput
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import obsidian.core.ObsidianContext
import obsidian.core.database.StoredLocation
import obsidian.core.database.WarpRecord
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import obsidian.core.ui.DialogBuilder
import obsidian.modules.teleport.TeleportService
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Manages server warps, permission gating, GUI browsing, and persistence.
 */
class WarpService(
    private val context: ObsidianContext,
    private val teleportService: TeleportService,
) {
    var useGui: Boolean = true
        private set
    var requirePermissionByDefault: Boolean = false
        private set

    private val warps = ConcurrentHashMap<String, WarpRecord>()

    init {
        reload()
    }

    fun loadAllBlocking() {
        val loaded = runBlocking { context.warpRepository.findAll() }
        warps.clear()
        loaded.forEach { warps[it.name.lowercase()] = it }
    }

    fun reload() {
        val raw = context.config.raw
        useGui = raw.getBoolean("warps.use-gui", true)
        requirePermissionByDefault = raw.getBoolean("warps.require-permission-by-default", false)
    }

    fun getWarp(name: String): WarpRecord? = warps[name.lowercase()]

    fun getAccessibleWarps(sender: CommandSender, group: String? = null): List<WarpRecord> =
        warps.values.asSequence()
            .filter { (!it.hidden || sender.hasPermission("obsidian.admin")) && canAccess(sender, it) }
            .filter { group == null || it.groupName.equals(group, ignoreCase = true) }
            .sortedWith(compareBy<WarpRecord> { it.page }.thenBy { it.name.lowercase() })
            .toList()

    fun getWarpGroups(sender: CommandSender): List<String> =
        warps.values.asSequence()
            .filter { (!it.hidden || sender.hasPermission("obsidian.admin")) && canAccess(sender, it) }
            .mapNotNull { it.groupName }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

    fun canAccess(sender: CommandSender, warp: WarpRecord): Boolean {
        if (!warp.requiresPermission) return true
        return sender.hasPermission("obsidian.admin") || sender.hasPermission("obsidian.warp.*") || sender.hasPermission(
            "obsidian.warp.${warp.name.lowercase()}"
        )
    }

    fun setWarp(
        creator: Player, name: String, group: String? = null, requiresPermission: Boolean? = null
    ): WarpRecord? {
        val stored = StoredLocation.fromBukkit(creator.location) ?: return null
        val key = name.lowercase()
        val existing = warps[key]
        val icon = creator.inventory.itemInMainHand.takeIf { !it.type.isAir }?.type?.name ?: existing?.iconMaterial
        ?: "ENDER_PEARL"

        val record = WarpRecord(
            name = name,
            creatorUuid = creator.uniqueId,
            groupName = group ?: existing?.groupName,
            location = stored,
            requiresPermission = requiresPermission ?: existing?.requiresPermission ?: requirePermissionByDefault,
            hidden = existing?.hidden ?: false,
            iconMaterial = icon,
            slot = existing?.slot,
            page = existing?.page ?: 1,
        )
        warps[key] = record
        context.scheduler.asyncScope.launch {
            context.warpRepository.upsert(record)
        }
        return record
    }

    fun setWarpGroup(name: String, group: String?): Boolean {
        val key = name.lowercase()
        val existing = warps[key] ?: return false
        val updated =
            existing.copy(groupName = group?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) })
        warps[key] = updated
        context.scheduler.asyncScope.launch {
            context.warpRepository.upsert(updated)
        }
        return true
    }

    fun updateWarpMetadata(
        name: String,
        group: String?,
        requiresPermission: Boolean,
        hidden: Boolean,
    ): Boolean {
        val key = name.lowercase()
        val existing = warps[key] ?: return false
        val updated = existing.copy(
            groupName = group?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) },
            requiresPermission = requiresPermission,
            hidden = hidden,
        )
        warps[key] = updated
        context.scheduler.asyncScope.launch {
            context.warpRepository.upsert(updated)
        }
        return true
    }

    fun deleteWarp(name: String): Boolean {
        val removed = warps.remove(name.lowercase()) ?: return false
        context.scheduler.asyncScope.launch {
            context.warpRepository.delete(removed.name)
        }
        return true
    }

    fun teleportToWarp(player: Player, warp: WarpRecord) {
        if (!canAccess(player, warp)) {
            context.locale.send(player, "warp.no-access", "warp" to warp.name)
            return
        }
        val loc = warp.location.toBukkitLocation()
        if (loc == null) {
            context.locale.send(player, "general.world-not-found", "world" to warp.location.world)
            return
        }
        context.warmups.runWithWarmup(player, "warp") {
            teleportService.teleport(player, loc, TeleportCause.COMMAND).thenAccept { ok ->
                if (ok) {
                    context.locale.send(player, "warp.teleported", "warp" to warp.name)
                    context.actionBar.pushTransientLocale(player, "warp.actionbar-teleported", "warp" to warp.name)
                } else {
                    context.locale.send(player, "teleport.unsafe-location")
                }
            }
        }
    }

    fun openWarpsGui(player: Player, page: Int = 1, group: String? = null) {
        val list = getAccessibleWarps(player, group)
        val perPage = 28
        val totalPages = ((list.size - 1).coerceAtLeast(0) / perPage) + 1
        val currentPage = page.coerceIn(1, totalPages)

        val title = if (group != null) {
            context.locale.component("warp.gui-title-group", mapOf("page" to currentPage.toString(), "group" to group))
        } else {
            context.locale.component("warp.gui-title", mapOf("page" to currentPage.toString()))
        }
        val window = MenuWindow(title, 6)
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val slice = list.drop((currentPage - 1) * perPage).take(perPage)
        val isAdmin = player.hasPermission("obsidian.command.setwarp") || player.hasPermission("obsidian.admin")
        var idx = 0
        for (row in 1..4) {
            for (col in 1..7) {
                if (idx >= slice.size) break
                val warp = slice[idx++]
                val mat = Material.matchMaterial(warp.iconMaterial) ?: Material.ENDER_PEARL
                val item = MenuItems.item(
                    material = mat,
                    name = context.locale.raw("warp.gui-item-name", "name" to warp.name),
                    lore = buildList {
                        if (!warp.groupName.isNullOrBlank()) {
                            add(context.locale.raw("warp.gui-group", "group" to warp.groupName))
                        }
                        add(context.locale.raw("general.gui-world", "world" to warp.location.world))
                        add(
                            context.locale.raw(
                                "general.gui-coords",
                                "x" to warp.location.x.roundToInt(),
                                "y" to warp.location.y.roundToInt(),
                                "z" to warp.location.z.roundToInt(),
                            )
                        )
                        add("")
                        add(context.locale.raw("warp.gui-click"))
                        if (isAdmin) {
                            add(context.locale.raw("warp.gui-admin-edit"))
                        }
                    },
                )
                window.set(row * 9 + col, item) { click ->
                    if (isAdmin && click.clickType.isShiftClick && click.clickType.isRightClick) {
                        click.player.closeInventory()
                        openWarpEditorDialog(click.player, warp, currentPage, group)
                    } else {
                        player.closeInventory()
                        teleportToWarp(player, warp)
                    }
                }
            }
        }

        val groups = getWarpGroups(player)
        if (groups.isNotEmpty() || group != null) {
            window.set(
                49,
                MenuItems.item(
                    Material.COMPASS,
                    context.locale.raw(if (group == null) "warp.gui-groups-btn" else "warp.gui-groups-clear"),
                    listOf(context.locale.raw("warp.gui-groups-lore")),
                ),
            ) { click ->
                if (group != null) {
                    openWarpsGui(click.player, 1, null)
                } else {
                    openWarpGroupsGui(click.player)
                }
            }
        }

        window.setFooterNav(
            context = context,
            page = currentPage,
            totalPages = totalPages,
            onPrev = { openWarpsGui(player, currentPage - 1, group) },
            onNext = { openWarpsGui(player, currentPage + 1, group) },
        )
        window.open(player)
    }

    fun openWarpGroupsGui(player: Player) {
        val groups = getWarpGroups(player)
        val window = MenuWindow(context.locale.component("warp.groups-gui-title"), 4)
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        window.set(
            10,
            MenuItems.item(
                Material.NETHER_STAR,
                context.locale.raw("warp.groups-all-name"),
                listOf(context.locale.raw("warp.groups-entry-lore", "count" to getAccessibleWarps(player, null).size)),
            ),
        ) {
            openWarpsGui(player, 1, null)
        }

        var slot = 11
        for (g in groups) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++
            if (slot >= 26) break
            val count = getAccessibleWarps(player, g).size
            window.set(
                slot++,
                MenuItems.item(
                    Material.BOOK,
                    "<aqua>$g</aqua>",
                    listOf(context.locale.raw("warp.groups-entry-lore", "count" to count)),
                ),
            ) {
                openWarpsGui(player, 1, g)
            }
        }
        window.open(player)
    }

    fun openWarpEditorDialog(player: Player, warp: WarpRecord, returnPage: Int = 1, returnGroup: String? = null) {
        val inputs = listOf(
            DialogInput.text("group", context.locale.component("warp.edit-group-label"))
                .initial(warp.groupName ?: "")
                .width(180)
                .build(),
            DialogInput.bool("perm", context.locale.component("warp.edit-perm-label"))
                .initial(warp.requiresPermission)
                .build(),
            DialogInput.bool("hidden", context.locale.component("warp.edit-hidden-label"))
                .initial(warp.hidden)
                .build(),
        )
        val saveBtn = DialogBuilder.actionButton(
            context,
            player,
            context.locale.component("general.dialog-save"),
        ) { p, resp ->
            val newGroup = resp.getText("group")?.trim()
            val reqPerm = resp.getBoolean("perm") ?: warp.requiresPermission
            val isHidden = resp.getBoolean("hidden") ?: warp.hidden
            updateWarpMetadata(warp.name, newGroup, reqPerm, isHidden)
            context.locale.send(p, "warp.updated", "warp" to warp.name)
            openWarpsGui(p, returnPage, returnGroup)
        }
        val deleteBtn = DialogBuilder.actionButton(
            context,
            player,
            context.locale.component("general.dialog-delete"),
        ) { p, _ ->
            DialogBuilder.confirm(
                context = context,
                player = p,
                title = context.locale.component("warp.delete-dialog-title"),
                bodyLines = listOf(context.locale.component("warp.delete-dialog-body", mapOf("warp" to warp.name))),
                confirmLabel = context.locale.component("general.dialog-confirm"),
                cancelLabel = context.locale.component("general.dialog-cancel"),
                onConfirm = { confirmed ->
                    deleteWarp(warp.name)
                    context.locale.send(confirmed, "warp.removed", "warp" to warp.name)
                    openWarpsGui(confirmed, returnPage, returnGroup)
                },
                onCancel = { cancelled -> openWarpsGui(cancelled, returnPage, returnGroup) },
            )
        }
        DialogBuilder.multiAction(
            context = context,
            player = player,
            title = context.locale.component("warp.edit-dialog-title", mapOf("warp" to warp.name)),
            bodyLines = listOf(context.locale.component("warp.edit-dialog-body", mapOf("warp" to warp.name))),
            actions = listOf(saveBtn, deleteBtn),
            exitLabel = context.locale.component("general.dialog-cancel"),
            columns = 2,
            inputs = inputs,
        )
    }
}
