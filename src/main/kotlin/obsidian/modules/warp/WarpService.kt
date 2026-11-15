package obsidian.modules.warp

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import obsidian.core.ObsidianContext
import obsidian.core.database.StoredLocation
import obsidian.core.database.WarpRecord
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
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

    fun getAccessibleWarps(sender: CommandSender): List<WarpRecord> =
        warps.values.filter { (!it.hidden || sender.hasPermission("obsidian.admin")) && canAccess(sender, it) }
            .sortedWith(compareBy<WarpRecord> { it.page }.thenBy { it.name.lowercase() })

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
                } else {
                    context.locale.send(player, "teleport.unsafe-location")
                }
            }
        }
    }

    fun openWarpsGui(player: Player, page: Int = 1) {
        val list = getAccessibleWarps(player)
        val perPage = 28
        val totalPages = ((list.size - 1).coerceAtLeast(0) / perPage) + 1
        val currentPage = page.coerceIn(1, totalPages)

        val title = context.locale.component("warp.gui-title", mapOf("page" to currentPage.toString()))
        val window = MenuWindow(title, 6)
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val slice = list.drop((currentPage - 1) * perPage).take(perPage)
        var idx = 0
        for (row in 1..4) {
            for (col in 1..7) {
                if (idx >= slice.size) break
                val warp = slice[idx++]
                val mat = Material.matchMaterial(warp.iconMaterial) ?: Material.ENDER_PEARL
                val item = MenuItems.item(
                    material = mat,
                    name = "<aqua>${warp.name}</aqua>",
                    lore = buildList {
                        if (!warp.groupName.isNullOrBlank()) add("<gray>Group: <white>${warp.groupName}</white>")
                        add("<gray>World: <white>${warp.location.world}</white>")
                        add("<gray>Coords: <white>${warp.location.x.roundToInt()}, ${warp.location.y.roundToInt()}, ${warp.location.z.roundToInt()}</white>")
                        add("")
                        add("<green>Click to teleport</green>")
                    },
                )
                window.set(row * 9 + col, item) {
                    player.closeInventory()
                    teleportToWarp(player, warp)
                }
            }
        }

        if (currentPage > 1) {
            window.set(48, MenuItems.item(Material.ARROW, "<yellow>Previous Page</yellow>")) {
                openWarpsGui(player, currentPage - 1)
            }
        }
        if (currentPage < totalPages) {
            window.set(50, MenuItems.item(Material.ARROW, "<yellow>Next Page</yellow>")) {
                openWarpsGui(player, currentPage + 1)
            }
        }
        window.open(player)
    }
}
