package obsidian.modules.economy

import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import obsidian.core.database.DeliveryRecord
import obsidian.core.gui.MenuWindow
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Offline/online item and package mailbox delivery service (modeled after CMI's `CMIDeliveryManager`).
 */
class DeliveryService(private val context: ObsidianContext) : Listener {

    private val claimingGuard = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var defaultExpireDays: Int = 30
        private set

    init {
        reload()
    }

    fun reload() {
        defaultExpireDays = context.config.raw.getInt("delivery.expire-days", 30).coerceAtLeast(0)
        context.scheduler.asyncScope.launch {
            runCatching { context.deliveryRepository.purgeExpired() }
        }
    }

    fun encodeItem(item: ItemStack): String =
        Base64.getEncoder().encodeToString(item.serializeAsBytes())

    fun decodeItem(base64: String?): ItemStack? {
        if (base64.isNullOrBlank()) return null
        return runCatching {
            ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))
        }.getOrNull()?.takeUnless { it.type.isAir }
    }

    fun sendDelivery(
        senderName: String,
        targetUuid: UUID,
        item: ItemStack?,
        message: String = "",
        expireDays: Int = defaultExpireDays,
    ): DeliveryRecord {
        val now = System.currentTimeMillis()
        val expiresAt = if (expireDays > 0) now + expireDays * 86_400_000L else null
        val record = DeliveryRecord(
            id = UUID.randomUUID().toString(),
            targetUuid = targetUuid,
            senderName = senderName,
            itemBase64 = item?.takeUnless { it.type.isAir }?.let(::encodeItem),
            message = message,
            createdAt = now,
            expiresAt = expiresAt,
        )
        context.scheduler.asyncScope.launch {
            context.deliveryRepository.insert(record)
            val online = Bukkit.getPlayer(targetUuid)
            if (online != null && online.isOnline) {
                context.scheduler.runForEntity(online) {
                    if (online.isOnline) {
                        context.locale.send(online, "economy.delivery-received", "sender" to senderName)
                    }
                }
            }
        }
        return record
    }

    fun openGui(player: Player, requestedPage: Int = 1) {
        context.scheduler.asyncScope.launch {
            val pending = context.deliveryRepository.findPending(player.uniqueId)
            context.scheduler.runForEntity(player) {
                if (!player.isOnline) return@runForEntity
                if (pending.isEmpty()) {
                    context.locale.send(player, "economy.delivery-empty")
                    return@runForEntity
                }
                renderGui(player, pending, requestedPage)
            }
        }
    }

    private fun renderGui(player: Player, pending: List<DeliveryRecord>, requestedPage: Int) {
        val perPage = 28
        val totalPages = ceil(pending.size.toDouble() / perPage).toInt().coerceAtLeast(1)
        val page = requestedPage.coerceIn(1, totalPages)
        val window = MenuWindow(
            context.locale.component("economy.delivery-gui-title", mapOf("page" to page.toString(), "pages" to totalPages.toString())),
            6,
        )
        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            itemMeta = itemMeta.apply { displayName(Component.text(" ")) }
        }
        window.fillBorder(filler)

        val slots = buildList {
            for (row in 1..4) {
                for (col in 1..7) add(row * 9 + col)
            }
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")
        val slice = pending.drop((page - 1) * perPage).take(perPage)
        slice.forEachIndexed { idx, rec ->
            val slot = slots.getOrNull(idx) ?: return@forEachIndexed
            val baseItem = decodeItem(rec.itemBase64) ?: ItemStack(Material.CHEST)
            val display = baseItem.clone().apply {
                val meta = itemMeta
                val lore = mutableListOf<Component>()
                meta.lore()?.let { lore.addAll(it) }
                lore += TextFormatter.format("<dark_gray>────────────────</dark_gray>")
                lore += context.locale.component("economy.delivery-lore-sender", mapOf("sender" to rec.senderName))
                if (rec.message.isNotBlank()) {
                    lore += context.locale.component("economy.delivery-lore-message", mapOf("message" to rec.message))
                }
                lore += context.locale.component(
                    "economy.delivery-lore-date",
                    mapOf("date" to dateFormat.format(Date(rec.createdAt))),
                )
                lore += context.locale.component("economy.delivery-lore-claim")
                meta.lore(lore)
                itemMeta = meta
            }
            window.set(slot, display) { ctx ->
                claimDelivery(ctx.player, rec, page)
            }
        }

        if (page > 1) {
            val prev = ItemStack(Material.ARROW).apply {
                itemMeta = itemMeta.apply { displayName(context.locale.component("general.gui-prev-page")) }
            }
            window.set(45, prev) { openGui(player, page - 1) }
        }
        if (page < totalPages) {
            val next = ItemStack(Material.ARROW).apply {
                itemMeta = itemMeta.apply { displayName(context.locale.component("general.gui-next-page")) }
            }
            window.set(53, next) { openGui(player, page + 1) }
        }
        window.open(player)
    }

    private fun claimDelivery(player: Player, rec: DeliveryRecord, currentPage: Int) {
        if (!claimingGuard.add(rec.id)) return
        context.scheduler.asyncScope.launch {
            val claimed = runCatching { context.deliveryRepository.markClaimed(rec.id) }.getOrDefault(false)
            context.scheduler.runForEntity(player) {
                claimingGuard.remove(rec.id)
                if (!player.isOnline || !claimed) return@runForEntity
                val item = decodeItem(rec.itemBase64)
                if (item != null) {
                    val leftover = player.inventory.addItem(item)
                    if (leftover.isNotEmpty()) {
                        leftover.values.forEach { drop ->
                            player.world.dropItemNaturally(player.location, drop)
                        }
                    }
                }
                if (rec.message.isNotBlank()) {
                    context.locale.send(
                        player,
                        "economy.delivery-claimed-msg",
                        "sender" to rec.senderName,
                        "message" to rec.message,
                    )
                } else {
                    context.locale.send(player, "economy.delivery-claimed", "sender" to rec.senderName)
                }
                openGui(player, currentPage)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        context.scheduler.asyncScope.launch {
            val pending = context.deliveryRepository.findPending(player.uniqueId)
            if (pending.isNotEmpty()) {
                context.scheduler.runForEntityDelayed(player, 40L) {
                    if (player.isOnline) {
                        context.locale.send(player, "economy.delivery-pending-notify", "count" to pending.size)
                    }
                }
            }
        }
    }
}
