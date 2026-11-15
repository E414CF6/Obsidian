package obsidian.modules.visual

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.StoredLocation
import obsidian.core.text.TextFormatter
import obsidian.integration.PlaceholderService
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player

data class DynamicSignDefinition(
    val key: String,
    val location: StoredLocation,
    val frontTemplates: MutableList<String> = MutableList(4) { "" },
    val backTemplates: MutableList<String> = MutableList(4) { "" },
)

/**
 * Supports direct `/signedit` updates on front/back sides and periodic placeholder-driven dynamic signs (`dsigns.yml`).
 */
class SignService(private val context: ObsidianContext) {

    private val signsFile = YamlConfigFile(context.plugin, "dsigns.yml")
    private val dynamicSigns = LinkedHashMap<String, DynamicSignDefinition>()
    private var updateTask: ScheduledTask? = null

    init {
        reload()
    }

    fun reload() {
        signsFile.reload()
        dynamicSigns.clear()
        val sec = signsFile.yaml.getConfigurationSection("signs")
        sec?.getKeys(false)?.forEach { key ->
            val node = sec.getConfigurationSection(key) ?: return@forEach
            val loc = StoredLocation.deserialize(node.getString("location")) ?: return@forEach
            val front = node.getStringList("front").toMutableList().apply { while (size < 4) add("") }
            val back = node.getStringList("back").toMutableList().apply { while (size < 4) add("") }
            dynamicSigns[key] = DynamicSignDefinition(
                key = key,
                location = loc,
                frontTemplates = front.take(4).toMutableList(),
                backTemplates = back.take(4).toMutableList(),
            )
        }
        startTimer()
    }

    fun startTimer() {
        updateTask?.cancel()
        val interval = context.config.raw.getLong("visual.dynamic-sign-interval-seconds", 5L).coerceAtLeast(1L)
        updateTask = context.scheduler.runGlobalTimer(interval, interval) {
            refreshAllDynamicSigns()
        }
    }

    fun shutdown() {
        updateTask?.cancel()
        updateTask = null
    }

    fun findTargetSign(player: Player): Sign? {
        val block = player.getTargetBlockExact(6) ?: return null
        return block.state as? Sign
    }

    fun editSignLine(sign: Sign, side: Side, oneBasedLine: Int, rawText: String): Boolean {
        if (oneBasedLine !in 1..4) return false
        sign.getSide(side).line(oneBasedLine - 1, TextFormatter.format(rawText))
        sign.update()
        return true
    }

    fun registerDynamicSign(
        sign: Sign, frontTemplates: List<String>, backTemplates: List<String>
    ): DynamicSignDefinition {
        val loc = StoredLocation.fromBukkit(sign.location)!!
        val key = blockKey(loc)
        val def = DynamicSignDefinition(
            key = key,
            location = loc,
            frontTemplates = frontTemplates.take(4).toMutableList().apply { while (size < 4) add("") },
            backTemplates = backTemplates.take(4).toMutableList().apply { while (size < 4) add("") },
        )
        dynamicSigns[key] = def
        saveDynamicSign(def)
        applyTemplatesToSign(def)
        return def
    }

    fun removeDynamicSign(sign: Sign): Boolean {
        val loc = StoredLocation.fromBukkit(sign.location) ?: return false
        val key = blockKey(loc)
        val removed = dynamicSigns.remove(key) ?: return false
        signsFile.yaml.set("signs.${removed.key}", null)
        signsFile.save()
        return true
    }

    fun getDynamicSign(sign: Sign): DynamicSignDefinition? {
        val loc = StoredLocation.fromBukkit(sign.location) ?: return null
        return dynamicSigns[blockKey(loc)]
    }

    fun getAllDynamicSigns(): List<DynamicSignDefinition> = dynamicSigns.values.toList()

    fun saveAndRefresh(def: DynamicSignDefinition) {
        saveDynamicSign(def)
        applyTemplatesToSign(def)
    }

    private fun saveDynamicSign(def: DynamicSignDefinition) {
        val base = "signs.${def.key}"
        signsFile.yaml.set("$base.location", def.location.serialize())
        signsFile.yaml.set("$base.front", def.frontTemplates)
        signsFile.yaml.set("$base.back", def.backTemplates)
        signsFile.save()
    }

    private fun refreshAllDynamicSigns() {
        for (def in dynamicSigns.values) {
            applyTemplatesToSign(def)
        }
    }

    private fun applyTemplatesToSign(def: DynamicSignDefinition) {
        val loc = def.location.toBukkitLocation() ?: return
        val world = loc.world ?: return
        if (!world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) return
        val ph = context.getService<PlaceholderService>()

        context.scheduler.runAtLocation(loc) {
            val sign = loc.block.state as? Sign ?: return@runAtLocation
            val frontSide = sign.getSide(Side.FRONT)
            val backSide = sign.getSide(Side.BACK)
            var dirty = false
            for (i in 0 until 4) {
                val fRaw = def.frontTemplates[i]
                if (fRaw.isNotEmpty()) {
                    frontSide.line(i, TextFormatter.format(ph?.applyPlaceholders(null, fRaw) ?: fRaw))
                    dirty = true
                }
                val bRaw = def.backTemplates[i]
                if (bRaw.isNotEmpty()) {
                    backSide.line(i, TextFormatter.format(ph?.applyPlaceholders(null, bRaw) ?: bRaw))
                    dirty = true
                }
            }
            if (dirty) sign.update()
        }
    }

    private fun blockKey(loc: StoredLocation): String =
        "${loc.world}:${loc.x.toInt()}:${loc.y.toInt()}:${loc.z.toInt()}"
}
