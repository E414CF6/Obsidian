package obsidian.modules.interaction

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ArmorEffectRule(
    val materialName: String,
    val effects: List<PotionEffect>,
)

/**
 * Periodically applies potion effects to players wearing designated armor pieces.
 * Configured in `plugins/Obsidian/armoreffects.yml`.
 */
class ArmorEffectService(private val context: ObsidianContext) : Listener {

    private val file = File(context.plugin.dataFolder, "armoreffects.yml")
    private val rules = ConcurrentHashMap<String, List<PotionEffect>>()
    private var tickTask: ScheduledTask? = null

    init {
        reload()
    }

    fun reload() {
        rules.clear()
        if (!file.exists()) {
            createDefaultConfig()
        }
        loadRules()
    }

    fun start() {
        if (tickTask != null) return
        tickTask = context.scheduler.runGlobalTimer(40L, 40L) {
            checkAllPlayers()
        }
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
    }

    private fun createDefaultConfig() {
        val yaml = YamlConfiguration()
        yaml.set("armoreffects.NETHERITE_BOOTS", listOf("SPEED:1", "FIRE_RESISTANCE:0"))
        yaml.set("armoreffects.DIAMOND_HELMET", listOf("NIGHT_VISION:0", "WATER_BREATHING:0"))
        yaml.set("armoreffects.TURTLE_HELMET", listOf("WATER_BREATHING:0"))
        runCatching {
            file.parentFile?.mkdirs()
            yaml.save(file)
        }
    }

    private fun loadRules() {
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val sec = yaml.getConfigurationSection("armoreffects") ?: return
        for (matKey in sec.getKeys(false)) {
            val rawList = sec.getStringList(matKey)
            val list = mutableListOf<PotionEffect>()
            for (raw in rawList) {
                val parts = raw.split(":")
                val typeName = parts[0].trim().lowercase().replace(" ", "_")
                val amp = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val effectType = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(typeName))
                    ?: @Suppress("DEPRECATION") PotionEffectType.getByName(typeName.uppercase())
                    ?: continue
                list += PotionEffect(effectType, 100, amp, true, false, true)
            }
            if (list.isNotEmpty()) {
                rules[matKey.uppercase()] = list
            }
        }
    }

    private fun checkAllPlayers() {
        if (rules.isEmpty()) return
        for (player in Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("obsidian.armoreffects") && !player.hasPermission("obsidian.admin")) continue
            val armor = player.inventory.armorContents
            val toApply = mutableListOf<PotionEffect>()
            for (item in armor) {
                if (item == null || item.type.isAir) continue
                val effects = rules[item.type.name] ?: continue
                toApply.addAll(effects)
            }
            if (toApply.isNotEmpty()) {
                context.scheduler.runForEntity(player) {
                    for (eff in toApply) {
                        player.addPotionEffect(eff)
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        // Player left, any transient tasks clear automatically
    }
}
