package obsidian.modules.interaction

import obsidian.core.ObsidianContext
import obsidian.core.command.SpecializedCommandExecutor
import obsidian.core.config.YamlConfigFile
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class InteractiveBinding(
    val id: String,
    val type: String, // "block" or "entity"
    val targetKey: String,
    val commands: MutableList<String>,
    var cooldownMs: Long = 500L,
)

/**
 * Binds specialized commands to blocks or entities (`ic.yml`) and executes them on player right-click.
 */
class InteractiveCommandService(private val context: ObsidianContext) : Listener {

    private val icFile = YamlConfigFile(context.plugin, "ic.yml")
    private val bindingsByTarget = ConcurrentHashMap<String, InteractiveBinding>()
    private val lastClickByPlayer = ConcurrentHashMap<UUID, Long>()

    init {
        reload()
    }

    fun reload() {
        icFile.reload()
        bindingsByTarget.clear()
        val sec = icFile.yaml.getConfigurationSection("bindings") ?: return
        for (id in sec.getKeys(false)) {
            val node = sec.getConfigurationSection(id) ?: continue
            val type = node.getString("type", "block") ?: "block"
            val targetKey = node.getString("target") ?: continue
            val commands = node.getStringList("commands").toMutableList()
            val cd = node.getLong("cooldown-ms", 500L).coerceAtLeast(0L)
            bindingsByTarget[targetKey] = InteractiveBinding(id, type, targetKey, commands, cd)
        }
    }

    fun getAll(): List<InteractiveBinding> = bindingsByTarget.values.sortedBy { it.id }

    fun getByBlock(block: Block): InteractiveBinding? = bindingsByTarget[blockKey(block.location)]

    fun getByEntity(entity: Entity): InteractiveBinding? = bindingsByTarget[entity.uniqueId.toString()]

    fun addBlockCommand(block: Block, command: String): InteractiveBinding {
        val key = blockKey(block.location)
        val existing = bindingsByTarget[key]
        val binding = existing?.apply { commands += command } ?: InteractiveBinding(
            id = "blk_${block.world.name}_${block.x}_${block.y}_${block.z}",
            type = "block",
            targetKey = key,
            commands = mutableListOf(command),
        )
        bindingsByTarget[key] = binding
        saveBinding(binding)
        return binding
    }

    fun addEntityCommand(entity: Entity, command: String): InteractiveBinding {
        val key = entity.uniqueId.toString()
        val existing = bindingsByTarget[key]
        val binding = existing?.apply { commands += command } ?: InteractiveBinding(
            id = "ent_${entity.uniqueId.toString().take(8)}",
            type = "entity",
            targetKey = key,
            commands = mutableListOf(command),
        )
        bindingsByTarget[key] = binding
        saveBinding(binding)
        return binding
    }

    fun removeByTargetKey(key: String): Boolean {
        val removed = bindingsByTarget.remove(key) ?: return false
        icFile.yaml.set("bindings.${removed.id}", null)
        icFile.save()
        return true
    }

    fun removeById(id: String): Boolean {
        val match = bindingsByTarget.values.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: return false
        return removeByTargetKey(match.targetKey)
    }

    private fun saveBinding(binding: InteractiveBinding) {
        val base = "bindings.${binding.id}"
        icFile.yaml.set("$base.type", binding.type)
        icFile.yaml.set("$base.target", binding.targetKey)
        icFile.yaml.set("$base.cooldown-ms", binding.cooldownMs)
        icFile.yaml.set("$base.commands", binding.commands)
        icFile.save()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val binding = getByBlock(block) ?: return
        event.isCancelled = true
        trigger(event.player, binding)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityClick(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val binding = getByEntity(event.rightClicked) ?: return
        event.isCancelled = true
        trigger(event.player, binding)
    }

    private fun trigger(player: Player, binding: InteractiveBinding) {
        val now = System.currentTimeMillis()
        val last = lastClickByPlayer[player.uniqueId] ?: 0L
        if (now - last < binding.cooldownMs) return
        lastClickByPlayer[player.uniqueId] = now
        SpecializedCommandExecutor.executeAll(context, binding.commands, player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastClickByPlayer.remove(event.player.uniqueId)
    }

    companion object {
        fun blockKey(loc: Location): String = "${loc.world.name}:${loc.blockX}:${loc.blockY}:${loc.blockZ}"
    }
}
