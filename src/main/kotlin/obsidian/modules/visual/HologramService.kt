package obsidian.modules.visual

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.StoredLocation
import obsidian.core.text.TextFormatter
import obsidian.integration.PlaceholderService
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class HologramDefinition(
    val id: String,
    var location: StoredLocation,
    val lines: MutableList<String>,
    var billboard: Display.Billboard = Display.Billboard.CENTER,
    var lineWidth: Int = 240,
    var seeThrough: Boolean = false,
    var shadowed: Boolean = true,
    var backgroundArgb: Int? = 0x55000000,
)

/**
 * Manages Paper native [TextDisplay]-backed holograms (`holograms.yml`) with live placeholder refresh
 * and duplicate-safe chunk cleanup.
 */
class HologramService(private val context: ObsidianContext) : Listener {

    private val hologramsFile = YamlConfigFile(context.plugin, "holograms.yml")
    private val hologramKey = NamespacedKey(context.plugin, "hologram_id")
    private val definitions = LinkedHashMap<String, HologramDefinition>()
    private val spawnedEntityIds = ConcurrentHashMap<String, UUID>()
    private var refreshTask: ScheduledTask? = null

    init {
        reload()
    }

    fun reload() {
        despawnAll()
        hologramsFile.reload()
        definitions.clear()

        val sec = hologramsFile.yaml.getConfigurationSection("holograms")
        sec?.getKeys(false)?.forEach { key ->
            val node = sec.getConfigurationSection(key) ?: return@forEach
            val loc = StoredLocation.deserialize(node.getString("location")) ?: return@forEach
            val lines = node.getStringList("lines").toMutableList()
            val billboard = runCatching {
                Display.Billboard.valueOf((node.getString("billboard") ?: "CENTER").uppercase())
            }.getOrDefault(Display.Billboard.CENTER)
            val bg = if (node.contains("background-argb")) node.getInt("background-argb") else 0x55000000
            definitions[key.lowercase()] = HologramDefinition(
                id = key,
                location = loc,
                lines = lines,
                billboard = billboard,
                lineWidth = node.getInt("line-width", 240).coerceIn(40, 1024),
                seeThrough = node.getBoolean("see-through", false),
                shadowed = node.getBoolean("shadowed", true),
                backgroundArgb = bg,
            )
        }

        spawnAll()
        startRefreshTimer()
    }

    fun startRefreshTimer() {
        refreshTask?.cancel()
        val interval = context.config.raw.getLong("visual.hologram-refresh-seconds", 5L).coerceAtLeast(1L)
        refreshTask = context.scheduler.runGlobalTimer(interval, interval) {
            refreshAllDisplays()
        }
    }

    fun shutdown() {
        refreshTask?.cancel()
        refreshTask = null
        despawnAll()
    }

    fun getAllHolograms(): List<HologramDefinition> = definitions.values.toList()

    fun getHologram(id: String): HologramDefinition? = definitions[id.lowercase()]

    fun createHologram(id: String, location: StoredLocation, initialLines: List<String>): HologramDefinition {
        despawnSingle(id.lowercase())
        val def = HologramDefinition(
            id = id,
            location = location,
            lines = initialLines.toMutableList(),
        )
        definitions[id.lowercase()] = def
        saveDefinition(def)
        spawnSingle(def)
        return def
    }

    fun deleteHologram(id: String): Boolean {
        val key = id.lowercase()
        val removed = definitions.remove(key) ?: return false
        despawnSingle(key)
        hologramsFile.yaml.set("holograms.${removed.id}", null)
        hologramsFile.save()
        return true
    }

    fun moveHologram(def: HologramDefinition, newLoc: StoredLocation) {
        def.location = newLoc
        saveDefinition(def)
        despawnSingle(def.id.lowercase())
        spawnSingle(def)
    }

    fun saveAndRefresh(def: HologramDefinition) {
        saveDefinition(def)
        updateDisplayText(def)
    }

    private fun saveDefinition(def: HologramDefinition) {
        val base = "holograms.${def.id}"
        hologramsFile.yaml.set("$base.location", def.location.serialize())
        hologramsFile.yaml.set("$base.lines", def.lines)
        hologramsFile.yaml.set("$base.billboard", def.billboard.name)
        hologramsFile.yaml.set("$base.line-width", def.lineWidth)
        hologramsFile.yaml.set("$base.see-through", def.seeThrough)
        hologramsFile.yaml.set("$base.shadowed", def.shadowed)
        hologramsFile.yaml.set("$base.background-argb", def.backgroundArgb)
        hologramsFile.save()
    }

    private fun spawnAll() {
        definitions.values.forEach { spawnSingle(it) }
    }

    private fun spawnSingle(def: HologramDefinition) {
        val loc = def.location.toBukkitLocation() ?: return
        val world = loc.world ?: return
        if (!world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) return

        context.scheduler.runAtLocation(loc) {
            cleanupChunkDuplicates(loc.chunk.entities.asList(), def.id)
            val display = world.spawn(loc, TextDisplay::class.java) { td ->
                td.isPersistent = false
                td.billboard = def.billboard
                td.lineWidth = def.lineWidth
                td.isSeeThrough = def.seeThrough
                td.isShadowed = def.shadowed
                def.backgroundArgb?.let { td.backgroundColor = Color.fromARGB(it) }
                td.persistentDataContainer.set(hologramKey, PersistentDataType.STRING, def.id.lowercase())
                td.text(renderComponent(def))
            }
            spawnedEntityIds[def.id.lowercase()] = display.uniqueId
        }
    }

    private fun despawnSingle(key: String) {
        val entityId = spawnedEntityIds.remove(key) ?: return
        val entity = Bukkit.getEntity(entityId) ?: return
        context.scheduler.runForEntity(entity) { entity.remove() }
    }

    private fun despawnAll() {
        for (entityId in spawnedEntityIds.values) {
            val entity = Bukkit.getEntity(entityId) ?: continue
            context.scheduler.runForEntity(entity) { entity.remove() }
        }
        spawnedEntityIds.clear()
    }

    private fun refreshAllDisplays() {
        for ((key, def) in definitions) {
            val entityId = spawnedEntityIds[key]
            val entity = entityId?.let { Bukkit.getEntity(it) as? TextDisplay }
            if (entity == null || !entity.isValid) {
                spawnSingle(def)
                continue
            }
            val component = renderComponent(def)
            context.scheduler.runForEntity(entity) {
                entity.text(component)
            }
        }
    }

    private fun updateDisplayText(def: HologramDefinition) {
        val entityId = spawnedEntityIds[def.id.lowercase()] ?: run {
            spawnSingle(def)
            return
        }
        val entity = Bukkit.getEntity(entityId) as? TextDisplay ?: run {
            spawnSingle(def)
            return
        }
        val component = renderComponent(def)
        context.scheduler.runForEntity(entity) {
            entity.text(component)
        }
    }

    internal fun renderComponent(def: HologramDefinition): Component {
        val ph = context.getService<PlaceholderService>()
        val renderedLines = def.lines.map { line ->
            val withPlaceholders = ph?.applyPlaceholders(null, line) ?: line
            TextFormatter.format(withPlaceholders)
        }
        var combined: Component = Component.empty()
        renderedLines.forEachIndexed { idx, lineComp ->
            if (idx > 0) combined = combined.append(Component.newline())
            combined = combined.append(lineComp)
        }
        return combined
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (definitions.isEmpty()) return
        val chunk = event.chunk
        val entities = chunk.entities
        for (entity in entities) {
            val id = entity.persistentDataContainer.get(hologramKey, PersistentDataType.STRING) ?: continue
            val tracked = spawnedEntityIds[id]
            if (tracked == null || tracked != entity.uniqueId || !definitions.containsKey(id)) {
                entity.remove()
            }
        }
        for (def in definitions.values) {
            val loc = def.location
            if (!loc.world.equals(chunk.world.name, ignoreCase = true)) continue
            val cx = kotlin.math.floor(loc.x).toInt() shr 4
            val cz = kotlin.math.floor(loc.z).toInt() shr 4
            if (cx == chunk.x && cz == chunk.z) {
                val existing = spawnedEntityIds[def.id.lowercase()]?.let { Bukkit.getEntity(it) }
                if (existing == null || !existing.isValid) {
                    spawnSingle(def)
                }
            }
        }
    }

    private fun cleanupChunkDuplicates(entities: List<org.bukkit.entity.Entity>, id: String) {
        val key = id.lowercase()
        for (entity in entities) {
            val tag = entity.persistentDataContainer.get(hologramKey, PersistentDataType.STRING) ?: continue
            if (tag == key) entity.remove()
        }
    }
}
