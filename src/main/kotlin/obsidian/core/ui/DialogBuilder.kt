package obsidian.core.ui

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import obsidian.core.ObsidianContext
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Duration

/**
 * Fluent wrapper over Paper 26.3's native [Dialog] API supporting confirmations, notices,
 * multi-action dashboards, and typed [DialogInput] forms with Folia-safe custom click callbacks.
 */
object DialogBuilder {

    private fun oneShotCallback(
        context: ObsidianContext,
        player: Player,
        handler: (Player, DialogResponseView) -> Unit,
    ): DialogAction = DialogAction.customClick(
        { response, _ ->
            context.scheduler.runForEntity(player) {
                if (player.isOnline) {
                    handler(player, response)
                }
            }
        },
        ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(5))
            .build(),
    )

    fun show(context: ObsidianContext, player: Player, dialog: Dialog) {
        context.scheduler.runForEntity(player) {
            if (player.isOnline) {
                player.showDialog(dialog)
            }
        }
    }

    fun shouldAutoPopup(context: ObsidianContext, player: Player): Boolean =
        context.config.uiDialogsEnabled && UiPreferences.isDialogPopupsEnabled(context, player)

    fun confirm(
        context: ObsidianContext,
        player: Player,
        title: Component,
        bodyLines: List<Component>,
        confirmLabel: Component,
        cancelLabel: Component,
        itemPreview: ItemStack? = null,
        canCloseWithEscape: Boolean = true,
        onConfirm: (Player) -> Unit,
        onCancel: ((Player) -> Unit)? = null,
    ) {
        val bodies = buildList {
            if (itemPreview != null && !itemPreview.type.isAir) {
                add(DialogBody.item(itemPreview).showDecorations(true).showTooltip(true).build())
            }
            bodyLines.forEach { add(DialogBody.plainMessage(it)) }
        }
        val yesBtn = ActionButton.builder(confirmLabel)
            .width(140)
            .action(oneShotCallback(context, player) { p, _ -> onConfirm(p) })
            .build()
        val noBtn = ActionButton.builder(cancelLabel)
            .width(140)
            .apply {
                if (onCancel != null) {
                    action(oneShotCallback(context, player) { p, _ -> onCancel(p) })
                }
            }
            .build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(title)
                        .body(bodies)
                        .canCloseWithEscape(canCloseWithEscape)
                        .build()
                )
                .type(DialogType.confirmation(yesBtn, noBtn))
        }
        show(context, player, dialog)
    }

    fun notice(
        context: ObsidianContext,
        player: Player,
        title: Component,
        bodyLines: List<Component>,
        closeLabel: Component,
        itemPreview: ItemStack? = null,
    ) {
        val bodies = buildList {
            if (itemPreview != null && !itemPreview.type.isAir) {
                add(DialogBody.item(itemPreview).showDecorations(true).showTooltip(true).build())
            }
            bodyLines.forEach { add(DialogBody.plainMessage(it)) }
        }
        val closeBtn = ActionButton.builder(closeLabel).width(140).build()
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(title)
                        .body(bodies)
                        .canCloseWithEscape(true)
                        .build()
                )
                .type(DialogType.notice(closeBtn))
        }
        show(context, player, dialog)
    }

    fun form(
        context: ObsidianContext,
        player: Player,
        title: Component,
        bodyLines: List<Component> = emptyList(),
        inputs: List<DialogInput>,
        submitLabel: Component,
        cancelLabel: Component,
        itemPreview: ItemStack? = null,
        canCloseWithEscape: Boolean = true,
        onSubmit: (Player, DialogResponseView) -> Unit,
    ) {
        val bodies = buildList {
            if (itemPreview != null && !itemPreview.type.isAir) {
                add(DialogBody.item(itemPreview).showDecorations(true).showTooltip(true).build())
            }
            bodyLines.forEach { add(DialogBody.plainMessage(it)) }
        }
        val submitBtn = ActionButton.builder(submitLabel)
            .width(140)
            .action(oneShotCallback(context, player, onSubmit))
            .build()
        val cancelBtn = ActionButton.builder(cancelLabel).width(140).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(title)
                        .body(bodies)
                        .inputs(inputs)
                        .canCloseWithEscape(canCloseWithEscape)
                        .build()
                )
                .type(DialogType.confirmation(submitBtn, cancelBtn))
        }
        show(context, player, dialog)
    }

    fun multiAction(
        context: ObsidianContext,
        player: Player,
        title: Component,
        bodyLines: List<Component>,
        actions: List<ActionButton>,
        exitLabel: Component,
        columns: Int = 2,
        inputs: List<DialogInput> = emptyList(),
        itemPreview: ItemStack? = null,
    ) {
        val bodies = buildList {
            if (itemPreview != null && !itemPreview.type.isAir) {
                add(DialogBody.item(itemPreview).showDecorations(true).showTooltip(true).build())
            }
            bodyLines.forEach { add(DialogBody.plainMessage(it)) }
        }
        val exitBtn = ActionButton.builder(exitLabel).width(140).build()
        val dialog = Dialog.create { factory ->
            val type = if (actions.isEmpty()) {
                DialogType.notice(exitBtn)
            } else {
                DialogType.multiAction(actions, exitBtn, columns.coerceIn(1, 4))
            }
            factory.empty()
                .base(
                    DialogBase.builder(title)
                        .body(bodies)
                        .inputs(inputs)
                        .canCloseWithEscape(true)
                        .build()
                )
                .type(type)
        }
        show(context, player, dialog)
    }

    fun actionButton(
        context: ObsidianContext,
        player: Player,
        label: Component,
        tooltip: Component? = null,
        width: Int = 150,
        onClick: (Player, DialogResponseView) -> Unit,
    ): ActionButton {
        val builder = ActionButton.builder(label)
            .width(width)
            .action(oneShotCallback(context, player, onClick))
        if (tooltip != null) {
            builder.tooltip(tooltip)
        }
        return builder.build()
    }
}
