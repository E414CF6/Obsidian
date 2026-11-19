package obsidian.modules.kit

import kotlinx.coroutines.launch
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.KitClaimRecord
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import obsidian.core.text.TextFormatter
import obsidian.modules.economy.EconomyService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

data class KitDefinition(
    val name: String,
    var displayName: String,
    var cooldownSeconds: Long = 0L,
    var cost: Double = 0.0,
    var maxUses: Int = 0,
    var permissionRequired: Boolean = false,
    var iconMaterial: Material = Material.CHEST,
    var slot: Int? = null,
    var page: Int = 1,
    var hidden: Boolean = false,
    var itemsBase64: String = "",
    var fallbackItems: List<String> = emptyList(),
    var commands: List<String> = emptyList(),
) {
    fun resolveItems(): List<ItemStack> {
        if (itemsBase64.isNotBlank()) {
            val bytes = Base64.getDecoder().decode(itemsBase64)
            return ItemStack.deserializeItemsFromBytes(bytes).toList()
        }
        return fallbackItems.mapNotNull { entry ->
            val parts = entry.split(':')
            val mat = Material.matchMaterial(parts[0].trim()) ?: return@mapNotNull null
            val amount = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, mat.maxStackSize) ?: 1
            ItemStack(mat, amount)
        }
    }
}

/**
 * Manages kit definitions (`kits.yml`), per-player cooldown/claim persistence, economy cost, and preview/claim GUI.
 */
class KitService(private val context: ObsidianContext) : Listener {

    private val kitsFile = YamlConfigFile(context.plugin, "kits.yml")
    private val kits = LinkedHashMap<String, KitDefinition>()
    private val claims = ConcurrentHashMap<UUID, MutableMap<String, KitClaimRecord>>()

    var dropOverflow: Boolean = true
        private set
    var newbieKit: String = ""
        private set

    init {
        reload()
    }

    fun reload() {
        kitsFile.reload()
        dropOverflow = context.config.raw.getBoolean("kits.drop-overflow", true)
        newbieKit = context.config.raw.getString("kits.newbie-kit", "") ?: ""

        kits.clear()
        val sec = kitsFile.yaml.getConfigurationSection("kits") ?: return
        for (key in sec.getKeys(false)) {
            val node = sec.getConfigurationSection(key) ?: continue
            val icon = Material.matchMaterial(node.getString("icon", "CHEST") ?: "CHEST") ?: Material.CHEST
            val kit = KitDefinition(
                name = key,
                displayName = node.getString("display-name", "<aqua>$key</aqua>") ?: "<aqua>$key</aqua>",
                cooldownSeconds = node.getLong("cooldown-seconds", 0L).coerceAtLeast(0L),
                cost = node.getDouble("cost", 0.0).coerceAtLeast(0.0),
                maxUses = node.getInt("max-uses", 0).coerceAtLeast(0),
                permissionRequired = node.getBoolean("permission-required", false),
                iconMaterial = icon,
                slot = if (node.contains("slot")) node.getInt("slot") else null,
                page = node.getInt("page", 1).coerceAtLeast(1),
                hidden = node.getBoolean("hidden", false),
                itemsBase64 = node.getString("items-base64", "") ?: "",
                fallbackItems = node.getStringList("fallback-items"),
                commands = node.getStringList("commands"),
            )
            kits[key.lowercase()] = kit
        }
    }

    fun getAllKits(): List<KitDefinition> = kits.values.toList()

    fun getKit(name: String): KitDefinition? = kits[name.lowercase()]

    fun canAccess(player: Player, kit: KitDefinition): Boolean {
        if (!kit.permissionRequired) return true
        return player.hasPermission("obsidian.kit.${kit.name.lowercase()}") || player.hasPermission("obsidian.kit.*") || player.hasPermission(
            "obsidian.admin"
        )
    }

    fun createFromPlayerInventory(name: String, player: Player): KitDefinition {
        val nonEmpty = player.inventory.storageContents.filterNotNull().filter { !it.type.isAir }
        val encoded = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(nonEmpty.toTypedArray()))
        val existing = getKit(name)
        val kit = existing?.apply {
            itemsBase64 = encoded
            fallbackItems = emptyList()
        } ?: KitDefinition(
            name = name,
            displayName = "<aqua>$name</aqua>",
            itemsBase64 = encoded,
        )
        kits[name.lowercase()] = kit
        saveKit(kit)
        return kit
    }

    fun deleteKit(name: String): Boolean {
        val removed = kits.remove(name.lowercase()) ?: return false
        kitsFile.yaml.set("kits.${removed.name}", null)
        kitsFile.save()
        return true
    }

    fun saveKit(kit: KitDefinition) {
        val base = "kits.${kit.name}"
        kitsFile.yaml.set("$base.display-name", kit.displayName)
        kitsFile.yaml.set("$base.cooldown-seconds", kit.cooldownSeconds)
        kitsFile.yaml.set("$base.cost", kit.cost)
        kitsFile.yaml.set("$base.max-uses", kit.maxUses)
        kitsFile.yaml.set("$base.permission-required", kit.permissionRequired)
        kitsFile.yaml.set("$base.icon", kit.iconMaterial.name)
        kitsFile.yaml.set("$base.slot", kit.slot)
        kitsFile.yaml.set("$base.page", kit.page)
        kitsFile.yaml.set("$base.hidden", kit.hidden)
        kitsFile.yaml.set("$base.items-base64", kit.itemsBase64)
        kitsFile.yaml.set("$base.fallback-items", kit.fallbackItems)
        kitsFile.yaml.set("$base.commands", kit.commands)
        kitsFile.save()
    }

    fun claimKit(player: Player, kit: KitDefinition, bypassChecks: Boolean = false): ClaimResult {
        if (!bypassChecks && !canAccess(player, kit)) {
            return ClaimResult.NoPermission
        }
        val playerClaims = claims.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val existing = playerClaims[kit.name.lowercase()]
        val now = System.currentTimeMillis()

        if (!bypassChecks && !player.hasPermission("obsidian.kit.bypasscooldown") && !player.hasPermission("obsidian.admin")) {
            if (kit.maxUses > 0 && (existing?.claimCount ?: 0) >= kit.maxUses) {
                return ClaimResult.MaxUsesReached
            }
            if (kit.cooldownSeconds > 0L && existing != null) {
                val unlockAt = existing.lastClaimedAt + kit.cooldownSeconds * 1000L
                if (unlockAt > now) {
                    return ClaimResult.OnCooldown(unlockAt - now)
                }
            }
        }

        if (!bypassChecks && kit.cost > 0.0) {
            val eco = context.getService<EconomyService>()
            if (eco != null && !eco.withdraw(player.uniqueId, kit.cost, player.name)) {
                return ClaimResult.InsufficientFunds(kit.cost)
            }
        }

        val items = kit.resolveItems()
        for (item in items) {
            val leftover = player.inventory.addItem(item.clone())
            if (leftover.isNotEmpty() && dropOverflow) {
                leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
        }
        for (cmd in kit.commands) {
            val resolved = cmd.replace("{player}", player.name)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)
        }

        val updated = KitClaimRecord(
            uuid = player.uniqueId,
            kitName = kit.name,
            claimCount = (existing?.claimCount ?: 0) + 1,
            lastClaimedAt = now,
        )
        playerClaims[kit.name.lowercase()] = updated
        context.scheduler.asyncScope.launch {
            context.kitClaimRepository.upsert(updated)
        }
        return ClaimResult.Claimed
    }

    fun openKitsGui(player: Player, page: Int = 1) {
        val visible = getAllKits().filter { !it.hidden && canAccess(player, it) }
        if (visible.isEmpty()) {
            context.locale.send(player, "kit.none")
            return
        }
        val perPage = 45
        val totalPages = ceil(visible.size.toDouble() / perPage).toInt().coerceAtLeast(1)
        val currentPage = page.coerceIn(1, totalPages)
        val start = (currentPage - 1) * perPage
        val slice = visible.subList(start, minOf(start + perPage, visible.size))

        val title = TextFormatter.format(context.locale.raw("kit.gui-title"), mapOf("page" to currentPage.toString()))
        val window = MenuWindow(title, rows = 6)

        var fallbackSlot = 0
        val playerClaims = claims[player.uniqueId].orEmpty()
        val now = System.currentTimeMillis()
        val eco = context.getService<EconomyService>()

        for (kit in slice) {
            val slot = kit.slot?.takeIf { it in 0 until 45 && kit.page == currentPage } ?: run {
                while (window.hasButton(fallbackSlot) && fallbackSlot < 45) fallbackSlot++
                fallbackSlot++
            }
            if (slot >= 45) continue

            val claim = playerClaims[kit.name.lowercase()]
            val lore = mutableListOf<String>()
            if (kit.cooldownSeconds > 0L) {
                val rem =
                    if (claim != null) (claim.lastClaimedAt + kit.cooldownSeconds * 1000L - now).coerceAtLeast(0L) else 0L
                if (rem > 0L) {
                    lore += "<red>Cooldown: ${TextFormatter.formatDuration(rem)}</red>"
                } else {
                    lore += "<green>Ready to claim</green>"
                }
            }
            if (kit.cost > 0.0 && eco != null) {
                lore += "<gold>Cost: ${eco.formatMoney(kit.cost)}</gold>"
            }
            lore += "<gray>Click to claim</gray>"

            window.set(slot, MenuItems.item(kit.iconMaterial, kit.displayName, lore)) {
                player.closeInventory()
                when (val res = claimKit(player, kit)) {
                    ClaimResult.Claimed -> context.locale.send(player, "kit.given", "kit" to kit.name)
                    ClaimResult.NoPermission -> context.locale.send(player, "kit.no-access", "kit" to kit.name)
                    ClaimResult.MaxUsesReached -> context.locale.send(player, "kit.max-uses-reached", "kit" to kit.name)
                    is ClaimResult.OnCooldown -> context.locale.send(
                        player,
                        "kit.cooldown",
                        "kit" to kit.name,
                        "time" to TextFormatter.formatDuration(res.remainingMillis),
                    )

                    is ClaimResult.InsufficientFunds -> context.locale.send(
                        player,
                        "kit.insufficient-funds",
                        "kit" to kit.name,
                        "cost" to (eco?.formatMoney(res.cost) ?: res.cost.toString()),
                    )
                }
            }
        }
        window.open(player)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        context.scheduler.asyncScope.launch {
            val map = context.kitClaimRepository.findByUuid(player.uniqueId)
            claims[player.uniqueId] = ConcurrentHashMap(map)
        }
        if (!player.hasPlayedBefore() && newbieKit.isNotBlank()) {
            val kit = getKit(newbieKit) ?: return
            context.scheduler.runForEntity(player) {
                claimKit(player, kit, bypassChecks = true)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        claims.remove(event.player.uniqueId)
    }

    fun resetCooldown(uuid: UUID, kitName: String) {
        val map = claims[uuid]
        val existing = map?.get(kitName.lowercase())
        if (existing != null) {
            val updated = existing.copy(lastClaimedAt = 0L)
            map[kitName.lowercase()] = updated
            context.scheduler.asyncScope.launch { context.kitClaimRepository.upsert(updated) }
        } else {
            context.scheduler.asyncScope.launch {
                val loaded = context.kitClaimRepository.findByUuid(uuid)[kitName.lowercase()] ?: return@launch
                context.kitClaimRepository.upsert(loaded.copy(lastClaimedAt = 0L))
            }
        }
    }

    fun resetUsage(uuid: UUID, kitName: String) {
        val map = claims[uuid]
        val existing = map?.get(kitName.lowercase())
        if (existing != null) {
            val updated = existing.copy(claimCount = 0)
            map[kitName.lowercase()] = updated
            context.scheduler.asyncScope.launch { context.kitClaimRepository.upsert(updated) }
        } else {
            context.scheduler.asyncScope.launch {
                val loaded = context.kitClaimRepository.findByUuid(uuid)[kitName.lowercase()] ?: return@launch
                context.kitClaimRepository.upsert(loaded.copy(claimCount = 0))
            }
        }
    }

    sealed interface ClaimResult {
        data object Claimed : ClaimResult
        data object NoPermission : ClaimResult
        data object MaxUsesReached : ClaimResult
        data class OnCooldown(val remainingMillis: Long) : ClaimResult
        data class InsufficientFunds(val cost: Double) : ClaimResult
    }
}
