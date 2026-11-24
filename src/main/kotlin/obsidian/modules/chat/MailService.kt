package obsidian.modules.chat

import io.papermc.paper.registry.data.dialog.input.DialogInput
import net.kyori.adventure.text.event.ClickEvent
import obsidian.core.ObsidianContext
import obsidian.core.gui.MenuItems
import obsidian.core.gui.MenuWindow
import obsidian.core.text.TextFormatter
import obsidian.core.ui.DialogBuilder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class MailEntry(
    val sender: String,
    val message: String,
    val timestamp: Long,
    var read: Boolean = false,
)

/**
 * Persists offline/online player mail in `plugins/Obsidian/mail.yml` and notifies players on join.
 */
class MailService(private val context: ObsidianContext) : Listener {

    private val file = File(context.plugin.dataFolder, "mail.yml")
    private val mailboxes = ConcurrentHashMap<UUID, MutableList<MailEntry>>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    fun load() {
        mailboxes.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("mail") ?: return
        for (uuidStr in section.getKeys(false)) {
            val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: continue
            val list = mutableListOf<MailEntry>()
            val entries = section.getMapList(uuidStr)
            for (raw in entries) {
                val sender = raw["sender"]?.toString() ?: "Server"
                val message = raw["message"]?.toString() ?: continue
                val timestamp = (raw["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                val read = (raw["read"] as? Boolean) ?: false
                list += MailEntry(sender, message, timestamp, read)
            }
            if (list.isNotEmpty()) {
                mailboxes[uuid] = list
            }
        }
    }

    fun save() {
        val yaml = YamlConfiguration()
        for ((uuid, entries) in mailboxes) {
            val serialized = entries.map {
                mapOf(
                    "sender" to it.sender,
                    "message" to it.message,
                    "timestamp" to it.timestamp,
                    "read" to it.read,
                )
            }
            yaml.set("mail.$uuid", serialized)
        }
        runCatching {
            file.parentFile?.mkdirs()
            yaml.save(file)
        }
    }

    fun sendMail(targetUuid: UUID, senderName: String, message: String) {
        val list = mailboxes.computeIfAbsent(targetUuid) { mutableListOf() }
        synchronized(list) {
            list += MailEntry(senderName, message, System.currentTimeMillis(), read = false)
        }
        context.scheduler.runAsync { save() }

        val online = Bukkit.getPlayer(targetUuid)
        if (online != null && online.isOnline) {
            context.scheduler.runForEntity(online) {
                context.locale.send(online, "chat.mail-received", "sender" to senderName)
                context.actionBar.pushTransientLocale(online, "chat.mail-received", "sender" to senderName)
            }
        }
    }

    fun sendMailAll(senderName: String, message: String): Int {
        val targets = linkedSetOf<UUID>()
        Bukkit.getOnlinePlayers().forEach { targets += it.uniqueId }
        Bukkit.getOfflinePlayers().forEach { targets += it.uniqueId }
        for (uuid in targets) {
            val list = mailboxes.computeIfAbsent(uuid) { mutableListOf() }
            synchronized(list) {
                list += MailEntry(senderName, message, System.currentTimeMillis(), read = false)
            }
        }
        context.scheduler.runAsync { save() }
        return targets.size
    }

    fun peekMail(uuid: UUID): List<MailEntry> {
        val list = mailboxes[uuid] ?: return emptyList()
        synchronized(list) {
            return list.map { it.copy() }
        }
    }

    fun markReadAt(uuid: UUID, index: Int) {
        val list = mailboxes[uuid] ?: return
        var changed = false
        synchronized(list) {
            if (index in list.indices && !list[index].read) {
                list[index].read = true
                changed = true
            }
        }
        if (changed) context.scheduler.runAsync { save() }
    }

    fun deleteMailAt(uuid: UUID, index: Int): Boolean {
        val list = mailboxes[uuid] ?: return false
        val removed = synchronized(list) {
            if (index in list.indices) {
                list.removeAt(index)
                true
            } else false
        }
        if (removed) context.scheduler.runAsync { save() }
        return removed
    }

    fun readMail(uuid: UUID): List<MailEntry> {
        val list = mailboxes[uuid] ?: return emptyList()
        val copy: List<MailEntry>
        synchronized(list) {
            copy = list.map { it.copy() }
            list.forEach { it.read = true }
        }
        if (copy.isNotEmpty()) {
            context.scheduler.runAsync { save() }
        }
        return copy
    }

    fun clearMail(uuid: UUID): Int {
        val removed = mailboxes.remove(uuid)?.size ?: 0
        if (removed > 0) {
            context.scheduler.runAsync { save() }
        }
        return removed
    }

    fun formatTimestamp(timestamp: Long): String = synchronized(dateFormat) { dateFormat.format(Date(timestamp)) }

    fun openMailGui(player: Player, page: Int = 1) {
        val entries = peekMail(player.uniqueId)
        val perPage = 28
        val totalPages = ((entries.size - 1).coerceAtLeast(0) / perPage) + 1
        val currentPage = page.coerceIn(1, totalPages)

        val window = MenuWindow(
            context.locale.component("chat.mail-gui-title", mapOf("count" to entries.size.toString())),
            6,
        )
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val from = (currentPage - 1) * perPage
        val slice = entries.drop(from).take(perPage)
        var slotIdx = 0
        for (row in 1..4) {
            for (col in 1..7) {
                if (slotIdx >= slice.size) break
                val entryIdx = from + slotIdx
                val entry = slice[slotIdx++]
                val mat = if (entry.read) Material.BOOK else Material.WRITABLE_BOOK
                val badge = context.locale.raw(if (entry.read) "chat.mail-badge-read" else "chat.mail-badge-new")
                val preview = if (entry.message.length > 36) entry.message.take(36) + "..." else entry.message
                val item = MenuItems.item(
                    material = mat,
                    name = "$badge <white>${entry.sender}</white>",
                    lore = listOf(
                        "<gray>${formatTimestamp(entry.timestamp)}</gray>",
                        "<white>$preview</white>",
                        "",
                        context.locale.raw("chat.mail-gui-click"),
                    ),
                )
                window.set(row * 9 + col, item) { click ->
                    click.player.closeInventory()
                    markReadAt(click.player.uniqueId, entryIdx)
                    openMailDetailDialog(click.player, entry, entryIdx, currentPage)
                }
            }
        }

        window.set(
            48,
            MenuItems.item(
                Material.FEATHER,
                context.locale.raw("chat.mail-gui-compose"),
                listOf(context.locale.raw("chat.mail-gui-compose-lore"))
            ),
        ) { click ->
            openComposeTargetGui(click.player, 1)
        }

        if (entries.isNotEmpty()) {
            window.set(
                50,
                MenuItems.item(
                    Material.LAVA_BUCKET,
                    context.locale.raw("chat.mail-gui-clear"),
                    listOf(context.locale.raw("chat.mail-gui-clear-lore"))
                ),
            ) { click ->
                click.player.closeInventory()
                DialogBuilder.confirm(
                    context = context,
                    player = click.player,
                    title = context.locale.component("chat.mail-clear-dialog-title"),
                    bodyLines = listOf(
                        context.locale.component(
                            "chat.mail-clear-dialog-body",
                            mapOf("count" to entries.size.toString())
                        )
                    ),
                    confirmLabel = context.locale.component("general.dialog-confirm"),
                    cancelLabel = context.locale.component("general.dialog-cancel"),
                    onConfirm = { p ->
                        val removed = clearMail(p.uniqueId)
                        context.locale.send(p, "chat.mail-cleared", "count" to removed)
                        openMailGui(p, 1)
                    },
                    onCancel = { p -> openMailGui(p, currentPage) },
                )
            }
        }

        window.setFooterNav(
            context = context,
            page = currentPage,
            totalPages = totalPages,
            prevSlot = 45,
            nextSlot = 53,
            onPrev = { openMailGui(player, currentPage - 1) },
            onNext = { openMailGui(player, currentPage + 1) },
        )
        window.open(player)
    }

    private fun openMailDetailDialog(player: Player, entry: MailEntry, entryIndex: Int, returnPage: Int) {
        val replyBtn =
            DialogBuilder.actionButton(context, player, context.locale.component("chat.mail-dialog-reply")) { p, _ ->
                openComposeDialog(p, entry.sender)
            }
        val deleteBtn =
            DialogBuilder.actionButton(context, player, context.locale.component("general.dialog-delete")) { p, _ ->
                deleteMailAt(p.uniqueId, entryIndex)
                openMailGui(p, returnPage)
            }
        val backBtn =
            DialogBuilder.actionButton(context, player, context.locale.component("general.gui-back")) { p, _ ->
                openMailGui(p, returnPage)
            }
        DialogBuilder.multiAction(
            context = context,
            player = player,
            title = context.locale.component("chat.mail-dialog-title", mapOf("sender" to entry.sender)),
            bodyLines = listOf(
                TextFormatter.format("<gray>${formatTimestamp(entry.timestamp)}</gray>"),
                TextFormatter.format("<white>${TextFormatter.escape(entry.message)}</white>"),
            ),
            actions = listOf(replyBtn, deleteBtn, backBtn),
            exitLabel = context.locale.component("general.dialog-cancel"),
            columns = 3,
        )
    }

    private fun openComposeTargetGui(player: Player, page: Int) {
        val candidates = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != player.uniqueId && player.canSee(it) }
            .sortedBy { it.name.lowercase() }
        if (candidates.isEmpty()) {
            openComposeDialog(player, "")
            return
        }
        val perPage = 28
        val totalPages = ((candidates.size - 1) / perPage) + 1
        val currentPage = page.coerceIn(1, totalPages)
        val window = MenuWindow(context.locale.component("chat.mail-compose-gui-title"), 6)
        window.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val slice = candidates.drop((currentPage - 1) * perPage).take(perPage)
        var idx = 0
        for (row in 1..4) {
            for (col in 1..7) {
                if (idx >= slice.size) break
                val target = slice[idx++]
                window.set(
                    row * 9 + col,
                    MenuItems.playerHead(
                        target.uniqueId,
                        target.name,
                        "<aqua>${target.name}</aqua>",
                        listOf(context.locale.raw("chat.mail-compose-click"))
                    ),
                ) { click ->
                    click.player.closeInventory()
                    openComposeDialog(click.player, target.name)
                }
            }
        }
        window.set(49, MenuItems.item(Material.NAME_TAG, context.locale.raw("chat.mail-compose-manual"))) { click ->
            click.player.closeInventory()
            openComposeDialog(click.player, "")
        }
        window.set(45, MenuItems.backButton(context)) { openMailGui(player, 1) }
        window.open(player)
    }

    private fun openComposeDialog(player: Player, prefillTarget: String) {
        val inputs = listOf(
            DialogInput.text("target", context.locale.component("chat.mail-compose-target-label"))
                .initial(prefillTarget).width(180).build(),
            DialogInput.text("message", context.locale.component("chat.mail-compose-msg-label"))
                .width(240).maxLength(300).build(),
        )
        DialogBuilder.form(
            context = context,
            player = player,
            title = context.locale.component("chat.mail-compose-dialog-title"),
            inputs = inputs,
            submitLabel = context.locale.component("chat.mail-compose-send-btn"),
            cancelLabel = context.locale.component("general.dialog-cancel"),
        ) { p, resp ->
            val targetName = resp.getText("target")?.trim().orEmpty()
            val message = resp.getText("message")?.trim().orEmpty()
            if (targetName.isEmpty() || message.isEmpty()) return@form
            val target = Bukkit.getPlayerExact(targetName) ?: Bukkit.getOfflinePlayerIfCached(targetName)
            if (target == null) {
                context.locale.send(p, "general.player-not-found", "player" to targetName)
                return@form
            }
            sendMail(target.uniqueId, p.name, message)
            context.locale.send(p, "chat.mail-sent", "target" to (target.name ?: targetName))
            context.actionBar.pushTransientLocale(p, "chat.mail-sent", "target" to (target.name ?: targetName))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val list = mailboxes[player.uniqueId] ?: return
        val unread = synchronized(list) { list.count { !it.read } }
        if (unread <= 0) return

        context.scheduler.runForEntityDelayed(player, 20L) {
            if (!player.isOnline) return@runForEntityDelayed
            val comp = context.locale.prefixedComponent("chat.mail-unread-join", mapOf("count" to unread.toString()))
                .append(
                    context.locale.component("chat.mail-read-btn")
                        .clickEvent(ClickEvent.runCommand("/mail read"))
                )
            player.sendMessage(comp)
        }
    }
}
