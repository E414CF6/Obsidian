package obsidian.modules.visual

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickEvent
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.text.TextFormatter
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and displays Paper 26.3 native Dialogs defined in `dialogs.yml` (`/dialogs`).
 */
class DialogService(private val context: ObsidianContext) {

    data class DialogButtonSpec(
        val label: String,
        val tooltip: String?,
        val command: String?,
        val url: String?,
        val width: Int,
    )

    data class DialogSpec(
        val id: String,
        val title: String,
        val bodyLines: List<String>,
        val buttons: List<DialogButtonSpec>,
        val columns: Int,
    )

    private val dialogsFile = YamlConfigFile(context.plugin, "dialogs.yml")
    private val dialogs = ConcurrentHashMap<String, DialogSpec>()

    init {
        reload()
    }

    fun reload() {
        dialogsFile.reload()
        dialogs.clear()

        if (dialogsFile.yaml.getConfigurationSection("dialogs") == null) {
            seedDefaultDialogs()
        }

        val root = dialogsFile.yaml.getConfigurationSection("dialogs") ?: return
        for (id in root.getKeys(false)) {
            val sec = root.getConfigurationSection(id) ?: continue
            val title = sec.getString("title", id) ?: id
            val body = sec.getStringList("body")
            val columns = sec.getInt("columns", 2).coerceIn(1, 4)
            val buttonList = mutableListOf<DialogButtonSpec>()
            val buttonsSec = sec.getMapList("buttons")
            for (rawBtn in buttonsSec) {
                val label = rawBtn["label"]?.toString() ?: continue
                val tooltip = rawBtn["tooltip"]?.toString()
                val command = rawBtn["command"]?.toString()
                val url = rawBtn["url"]?.toString()
                val width = (rawBtn["width"] as? Number)?.toInt()?.coerceIn(60, 300) ?: 150
                buttonList.add(DialogButtonSpec(label, tooltip, command, url, width))
            }
            dialogs[id.lowercase()] = DialogSpec(id.lowercase(), title, body, buttonList, columns)
        }
    }

    private fun seedDefaultDialogs() {
        dialogsFile.yaml.set("dialogs.welcome.title", "<gradient:#55FFFF:#FF55FF>Obsidian Server Guide</gradient>")
        dialogsFile.yaml.set(
            "dialogs.welcome.body",
            listOf(
                "&7Welcome to the server!",
                "&fUse the quick actions below to explore.",
            ),
        )
        dialogsFile.yaml.set("dialogs.welcome.columns", 2)
        dialogsFile.yaml.set(
            "dialogs.welcome.buttons",
            listOf(
                mapOf(
                    "label" to "&aSpawn",
                    "tooltip" to "&7Teleport to main spawn",
                    "command" to "/spawn",
                    "width" to 140,
                ),
                mapOf(
                    "label" to "&bWarp Menu",
                    "tooltip" to "&7Browse public warps",
                    "command" to "/warps",
                    "width" to 140,
                ),
                mapOf(
                    "label" to "&eDaily Kits",
                    "tooltip" to "&7Open kit selector",
                    "command" to "/kits",
                    "width" to 140,
                ),
                mapOf(
                    "label" to "&dDiscord",
                    "tooltip" to "&7Join our community",
                    "url" to "https://discord.com",
                    "width" to 140,
                ),
            ),
        )
        dialogsFile.save()
    }

    fun getDialogIds(): List<String> = dialogs.keys.sorted()

    fun openDialog(player: Player, id: String): Boolean {
        val spec = dialogs[id.lowercase()] ?: return false
        val body = spec.bodyLines.map { DialogBody.plainMessage(TextFormatter.format(it)) }
        val actionButtons = spec.buttons.map { btn ->
            val action = when {
                !btn.command.isNullOrBlank() -> DialogAction.commandTemplate(btn.command)
                !btn.url.isNullOrBlank() -> DialogAction.staticAction(ClickEvent.openUrl(btn.url))
                else -> null
            }
            val builder = ActionButton.builder(TextFormatter.format(btn.label)).width(btn.width)
            if (!btn.tooltip.isNullOrBlank()) {
                builder.tooltip(TextFormatter.format(btn.tooltip))
            }
            if (action != null) {
                builder.action(action)
            }
            builder.build()
        }

        val closeBtn = ActionButton.builder(context.locale.component("visual.dialog-close")).width(120).build()

        val dialog = Dialog.create { factory ->
            val dialogType = if (actionButtons.isEmpty()) {
                DialogType.notice(closeBtn)
            } else {
                DialogType.multiAction(actionButtons, closeBtn, spec.columns)
            }
            factory.empty()
                .base(
                    DialogBase.builder(TextFormatter.format(spec.title))
                        .body(body)
                        .canCloseWithEscape(true)
                        .build()
                )
                .type(dialogType)
        }

        context.scheduler.runForEntity(player) {
            player.showDialog(dialog)
        }
        return true
    }
}
