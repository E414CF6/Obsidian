package obsidian.integration

import kotlinx.coroutines.runBlocking
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.StoredLocation
import obsidian.modules.teleport.SpawnService
import obsidian.modules.visual.PortalService
import org.bukkit.Bukkit
import java.util.Optional
import java.util.function.Consumer

/**
 * Reflection-based web map marker bridge for BlueMap and Dynmap.
 * Publishes Obsidian warps, spawn, and portals with zero compile-time dependencies.
 */
class MapMarkerService(private val context: ObsidianContext) {

    @Volatile
    var enabled: Boolean = true
        private set
    @Volatile
    var blueMapEnabled: Boolean = true
        private set
    @Volatile
    var dynmapEnabled: Boolean = true
        private set
    @Volatile
    var showWarps: Boolean = true
        private set
    @Volatile
    var showSpawn: Boolean = true
        private set
    @Volatile
    var showPortals: Boolean = true
        private set

    data class MarkerEntry(
        val id: String,
        val category: String,
        val label: String,
        val location: StoredLocation,
    )

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        enabled = raw.getBoolean("maps.enabled", true)
        blueMapEnabled = raw.getBoolean("maps.bluemap", true)
        dynmapEnabled = raw.getBoolean("maps.dynmap", true)
        showWarps = raw.getBoolean("maps.show-warps", true)
        showSpawn = raw.getBoolean("maps.show-spawn", true)
        showPortals = raw.getBoolean("maps.show-portals", true)
    }

    fun registerAndSync() {
        if (!enabled) return
        if (blueMapEnabled && Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            registerBlueMapHook()
        }
        syncMarkers()
    }

    fun syncMarkers() {
        if (!enabled) return
        context.scheduler.runAsync {
            val entries = collectMarkers()
            if (blueMapEnabled && Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
                publishToBlueMap(entries)
            }
            if (dynmapEnabled && Bukkit.getPluginManager().isPluginEnabled("Dynmap")) {
                publishToDynmap(entries)
            }
        }
    }

    private fun collectMarkers(): List<MarkerEntry> {
        val list = mutableListOf<MarkerEntry>()
        if (showWarps) {
            val warps = runBlocking { context.warpRepository.findAll() }
            for (warp in warps) {
                list.add(MarkerEntry("warp_${warp.name}", "Warps", "Warp: ${warp.name}", warp.location))
            }
        }
        if (showSpawn) {
            val spawnService = context.getService<SpawnService>()
            val spawnLoc = spawnService?.getFirstSpawn()
                ?: Bukkit.getWorlds().firstOrNull()?.let { StoredLocation.fromBukkit(it.spawnLocation) }
                    ?.toBukkitLocation()
            val stored = (spawnLoc?.let { StoredLocation.fromBukkit(it) })
                ?: StoredLocation.deserialize(YamlConfigFile(context.plugin, "spawns.yml").yaml.getString("main"))
            if (stored != null) {
                list.add(MarkerEntry("spawn_main", "Spawn", "Main Spawn", stored))
            }
        }
        if (showPortals) {
            val portalService = context.getService<PortalService>()
            if (portalService != null) {
                for (portal in portalService.getAllPortals()) {
                    val reg = portal.region
                    val center = StoredLocation(
                        world = reg.worldName,
                        x = (reg.minX + reg.maxX) / 2.0,
                        y = (reg.minY + reg.maxY) / 2.0,
                        z = (reg.minZ + reg.maxZ) / 2.0,
                    )
                    list.add(MarkerEntry("portal_${portal.name}", "Portals", "Portal: ${portal.name}", center))
                }
            }
        }
        return list
    }

    private fun registerBlueMapHook() {
        runCatching {
            val apiClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI")
            val onEnableMethod = apiClass.getMethod("onEnable", Consumer::class.java)
            onEnableMethod.invoke(null, Consumer<Any> { syncMarkers() })
        }
    }

    private fun publishToBlueMap(entries: List<MarkerEntry>) {
        runCatching {
            val apiClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI")
            val opt = apiClass.getMethod("getInstance").invoke(null) as? Optional<*> ?: return
            if (opt.isEmpty) return
            val api = opt.get()

            val markerSetClass = Class.forName("de.bluecolored.bluemap.api.markers.MarkerSet")
            val poiMarkerClass = Class.forName("de.bluecolored.bluemap.api.markers.POIMarker")
            val poiBuilderMethod = poiMarkerClass.getMethod("builder")

            val grouped = entries.groupBy { it.location.world }
            val getWorldMethod = api.javaClass.getMethod("getWorld", String::class.java)

            for ((worldName, worldEntries) in grouped) {
                val worldOpt = getWorldMethod.invoke(api, worldName) as? Optional<*> ?: continue
                if (worldOpt.isEmpty) continue
                val blueWorld = worldOpt.get()
                val maps = blueWorld.javaClass.getMethod("getMaps").invoke(blueWorld) as? Collection<*> ?: continue
                for (blueMap in maps) {
                    if (blueMap == null) continue
                    val markerSets = blueMap.javaClass.getMethod("getMarkerSets").invoke(blueMap)
                        ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val setMap = markerSets as? MutableMap<String, Any> ?: continue
                    val builder = markerSetClass.getMethod("builder").invoke(null)
                    builder.javaClass.getMethod("label", String::class.java).invoke(builder, "Obsidian Markers")
                    val markerSet = builder.javaClass.getMethod("build").invoke(builder)
                    val markersMethod = markerSet.javaClass.getMethod("getMarkers")
                    @Suppress("UNCHECKED_CAST")
                    val markerMap = markersMethod.invoke(markerSet) as? MutableMap<String, Any> ?: continue

                    for (entry in worldEntries) {
                        val poiBuilder = poiBuilderMethod.invoke(null)
                        poiBuilder.javaClass.getMethod("label", String::class.java).invoke(poiBuilder, entry.label)
                        poiBuilder.javaClass.getMethod(
                            "position",
                            Double::class.javaPrimitiveType,
                            Double::class.javaPrimitiveType,
                            Double::class.javaPrimitiveType,
                        ).invoke(poiBuilder, entry.location.x, entry.location.y, entry.location.z)
                        val poi = poiBuilder.javaClass.getMethod("build").invoke(poiBuilder)
                        markerMap[entry.id] = poi
                    }
                    setMap["obsidian_markers"] = markerSet
                }
            }
        }.onFailure { err ->
            context.plugin.logger.fine("BlueMap marker sync skipped: ${err.message}")
        }
    }

    private fun publishToDynmap(entries: List<MarkerEntry>) {
        runCatching {
            val plugin = Bukkit.getPluginManager().getPlugin("Dynmap") ?: return
            val commonApiClass = Class.forName("org.dynmap.DynmapCommonAPI")
            if (!commonApiClass.isInstance(plugin)) return
            val markerApi = commonApiClass.getMethod("getMarkerAPI").invoke(plugin) ?: return
            val existingSet = markerApi.javaClass.getMethod("getMarkerSet", String::class.java)
                .invoke(markerApi, "obsidian_markers")
            val markerSet = existingSet ?: markerApi.javaClass.getMethod(
                "createMarkerSet",
                String::class.java,
                String::class.java,
                Set::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(markerApi, "obsidian_markers", "Obsidian", null, false) ?: return

            val defaultIcon = markerApi.javaClass.getMethod("getMarkerIcon", String::class.java)
                .invoke(markerApi, "default") ?: return
            val markerIconClass = Class.forName("org.dynmap.markers.MarkerIcon")
            val createMarker = markerSet.javaClass.getMethod(
                "createMarker",
                String::class.java,
                String::class.java,
                String::class.java,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                markerIconClass,
                Boolean::class.javaPrimitiveType,
            )

            // Recreate markers cleanly
            @Suppress("UNCHECKED_CAST")
            val currentMarkers = (markerSet.javaClass.getMethod("getMarkers").invoke(markerSet) as? Collection<Any>)?.toList()
            currentMarkers?.forEach { m ->
                runCatching { m.javaClass.getMethod("deleteMarker").invoke(m) }
            }

            for (entry in entries) {
                createMarker.invoke(
                    markerSet,
                    entry.id,
                    entry.label,
                    entry.location.world,
                    entry.location.x,
                    entry.location.y,
                    entry.location.z,
                    defaultIcon,
                    false,
                )
            }
        }.onFailure { err ->
            context.plugin.logger.fine("Dynmap marker sync skipped: ${err.message}")
        }
    }
}
