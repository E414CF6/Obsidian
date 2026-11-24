package obsidian.modules.home

import kotlinx.coroutines.launch
import obsidian.core.ObsidianContext
import obsidian.core.database.HomeRecord
import obsidian.core.database.StoredLocation
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import obsidian.modules.teleport.TeleportService
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Manages player home persistence, numeric permission limits, and teleportation.
 */
class HomeService(
    private val context: ObsidianContext,
    private val teleportService: TeleportService,
) : Listener {

    var defaultHomeName: String = "home"
        private set
    var defaultMaxHomes: Int = 3
        private set
    var confirmOverride: Boolean = true
        private set
    var useGui: Boolean = true
        private set

    private val cache = ConcurrentHashMap<UUID, LinkedHashMap<String, HomeRecord>>()

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        defaultHomeName = raw.getString("homes.default-name", "home") ?: "home"
        defaultMaxHomes = raw.getInt("homes.default-max", 3).coerceAtLeast(1)
        confirmOverride = raw.getBoolean("homes.confirm-override", true)
        useGui = raw.getBoolean("homes.use-gui", true)
    }

    fun getHomes(uuid: UUID): List<HomeRecord> {
        val map = cache[uuid] ?: return emptyList()
        synchronized(map) { return map.values.toList() }
    }

    fun getHome(uuid: UUID, name: String): HomeRecord? {
        val map = cache[uuid] ?: return null
        synchronized(map) {
            return map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }
    }

    fun getMaxHomes(player: Player): Int {
        if (player.hasPermission("obsidian.sethome.unlimited") || player.hasPermission("obsidian.admin")) {
            return Int.MAX_VALUE
        }
        val activePerms =
            player.effectivePermissions.asSequence().filter { it.value }.map { it.permission }.asIterable()
        return resolveMaxNumericPermission(activePerms, "obsidian.sethome.", defaultMaxHomes)
    }

    fun setHome(player: Player, rawName: String?, overrideExisting: Boolean): SetHomeResult {
        val name = (rawName?.takeIf { it.isNotBlank() } ?: defaultHomeName).trim()
        val existing = getHome(player.uniqueId, name)
        if (existing != null && confirmOverride && !overrideExisting) {
            return SetHomeResult.ConfirmOverride(existing.name)
        }
        val currentHomes = getHomes(player.uniqueId)
        val max = getMaxHomes(player)
        if (existing == null && currentHomes.size >= max) {
            return SetHomeResult.LimitReached(currentHomes.size, max)
        }

        val stored = StoredLocation.fromBukkit(player.location) ?: return SetHomeResult.InvalidLocation
        val key = existing?.name ?: name
        val record = HomeRecord(player.uniqueId, key, stored)

        val map = cache.computeIfAbsent(player.uniqueId) { LinkedHashMap() }
        synchronized(map) { map[key] = record }

        context.scheduler.asyncScope.launch {
            context.homeRepository.upsert(record)
        }
        return SetHomeResult.Saved(key)
    }

    fun deleteHome(player: Player, name: String): Boolean {
        val map = cache[player.uniqueId] ?: return false
        val actualKey = synchronized(map) {
            val found = map.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return false
            map.remove(found)
            found
        }
        context.scheduler.asyncScope.launch {
            context.homeRepository.delete(player.uniqueId, actualKey)
        }
        return true
    }

    fun teleportToHome(player: Player, record: HomeRecord) {
        val loc = record.location.toBukkitLocation()
        if (loc == null) {
            context.locale.send(player, "general.world-not-found", "world" to record.location.world)
            return
        }
        context.warmups.runWithWarmup(player, "home") {
            teleportService.teleport(player, loc, TeleportCause.COMMAND).thenAccept { ok ->
                if (ok) {
                    context.locale.send(player, "home.teleported", "home" to record.name)
                } else {
                    context.locale.send(player, "teleport.unsafe-location")
                }
            }
        }
    }

    fun openHomesGui(player: Player, page: Int = 1) {
        val homes = getHomes(player.uniqueId)
        val max = getMaxHomes(player)
        val maxText = if (max == Int.MAX_VALUE) "∞" else max.toString()
        val perPage = 28
        val totalPages = ((homes.size - 1).coerceAtLeast(0) / perPage) + 1
        val currentPage = page.coerceIn(1, totalPages)

        val title = context.locale.component(
            "home.gui-title",
            mapOf("current" to homes.size.toString(), "max" to maxText),
        )
        val window = MenuWindow(title, 6)
        val border = MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " ")
        window.fillBorder(border)

        val start = (currentPage - 1) * perPage
        val slice = homes.drop(start).take(perPage)
        var slotIdx = 0
        for (row in 1..4) {
            for (col in 1..7) {
                if (slotIdx >= slice.size) break
                val home = slice[slotIdx++]
                val slot = row * 9 + col
                val item = MenuItems.item(
                    material = Material.RED_BED,
                    name = context.locale.raw("home.gui-item-name", "name" to home.name),
                    lore = listOf(
                        context.locale.raw("general.gui-world", "world" to home.location.world),
                        context.locale.raw(
                            "general.gui-coords",
                            "x" to home.location.x.roundToInt(),
                            "y" to home.location.y.roundToInt(),
                            "z" to home.location.z.roundToInt(),
                        ),
                        "",
                        context.locale.raw("home.gui-left-click"),
                        context.locale.raw("home.gui-shift-right-click"),
                    ),
                )
                window.set(slot, item) { click ->
                    if (click.clickType.isShiftClick && click.clickType.isRightClick) {
                        if (deleteHome(player, home.name)) {
                            context.locale.send(player, "home.removed", "home" to home.name)
                            openHomesGui(player, currentPage)
                        }
                    } else {
                        player.closeInventory()
                        teleportToHome(player, home)
                    }
                }
            }
        }

        if (currentPage > 1) {
            window.set(48, MenuItems.item(Material.ARROW, context.locale.raw("general.gui-prev-page"))) {
                openHomesGui(player, currentPage - 1)
            }
        }
        if (currentPage < totalPages) {
            window.set(50, MenuItems.item(Material.ARROW, context.locale.raw("general.gui-next-page"))) {
                openHomesGui(player, currentPage + 1)
            }
        }
        window.open(player)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        context.scheduler.asyncScope.launch {
            val records = context.homeRepository.findByOwner(uuid)
            val map = LinkedHashMap<String, HomeRecord>()
            records.forEach { map[it.name] = it }
            cache[uuid] = map
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        cache.remove(event.player.uniqueId)
    }

    sealed interface SetHomeResult {
        data class Saved(val name: String) : SetHomeResult
        data class ConfirmOverride(val name: String) : SetHomeResult
        data class LimitReached(val current: Int, val max: Int) : SetHomeResult
        data object InvalidLocation : SetHomeResult
    }

    companion object {
        /**
         * Resolves the highest numeric suffix from [permissions] matching [prefix].
         */
        fun resolveMaxNumericPermission(
            permissions: Iterable<String>,
            prefix: String,
            defaultLimit: Int,
        ): Int {
            var max = defaultLimit
            for (perm in permissions) {
                if (perm.startsWith(prefix, ignoreCase = true)) {
                    val suffix = perm.substring(prefix.length)
                    val parsed = suffix.toIntOrNull()
                    if (parsed != null && parsed > max) {
                        max = parsed
                    }
                }
            }
            return max
        }
    }
}
