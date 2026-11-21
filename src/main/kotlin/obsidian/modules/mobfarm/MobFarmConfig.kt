package obsidian.modules.mobfarm

import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason

/**
 * Chunk coordinate pair within a world.
 */
data class ChunkCoord(val x: Int, val z: Int)

/**
 * Per-entity-type farm limit and cleaning rules.
 */
data class EntityTypeRule(
    val name: String,
    val use: Boolean,
    val limit: Int,
    val worldLimits: Map<String, Int>,
    val removeExisting: Boolean?,
    val manualClean: Boolean,
    val autoClean: Boolean,
    val naturalSpawn: Boolean,
    val disableAi: Boolean,
) {
    fun limitForWorld(worldName: String?): Int {
        if (worldName == null) return limit
        return worldLimits[worldName.lowercase()] ?: limit
    }
}

/**
 * Group-level entity limit spanning multiple entity types.
 */
data class EntityGroupRule(
    val groupName: String,
    val use: Boolean,
    val limit: Int,
    val types: Set<String>,
    val worlds: Set<String>,
    val removeExisting: Boolean?,
) {
    fun appliesToWorld(worldName: String?): Boolean {
        if (worlds.isEmpty() || worldName == null) return true
        return worldName.lowercase() in worlds
    }
}

/**
 * Immutable configuration snapshot for the MobFarm module.
 */
data class MobFarmSettings(
    val checkOnEntitySpawn: Boolean,
    val preventMobBuild: Boolean,
    val preventIronGolemSpawn: Boolean,
    val groupChunks: Boolean,
    val groupChunksRadius: Int,
    val removeExisting: Boolean,
    val excludedChunks: Map<String, Set<ChunkCoord>>,
    val autoCleanUse: Boolean,
    val autoCleanInform: Boolean,
    val autoCleanIntervalSeconds: Int,
    val autoCleanMaxRemoved: Int,
    val autoCleanGroupChunks: Boolean,
    val autoCleanChunkRadius: Int,
    val autoCleanExpandRange: Int,
    val commandsCleanMaxRemoved: Int,
    val commandsCleanGroupChunks: Boolean,
    val commandsCleanGroupChunksRadius: Int,
    val excludeDisabledFromList: Boolean,
    val ignoreFullArmorStands: Boolean,
    val ignoreEntityWithPickedItem: Boolean,
    val ignoreFullItemFrames: Boolean,
    val ignoreTamedAnimals: Boolean,
    val ignoreNamedAnimals: Boolean,
    val ignoreWithSaddle: Boolean,
    val ignoreBabyAnimals: Boolean,
    val ignoredWorlds: Set<String>,
    val wolfControlEnabled: Boolean,
    val wolfControlMax: Int,
    val hopperControlEnabled: Boolean,
    val hopperControlDefault: Int,
    val hopperControlSlowestRate: Int,
    val hopperControlAdjustEvery: Int,
    val redstoneTrack: Boolean,
    val redstoneWindowMillis: Long,
    val blockTypesLimit: Boolean,
    val blockTypesCheckPistonMove: Boolean,
    val blockLimits: Map<Material, Int>,
    val logEnabled: Boolean,
    val logFrom: Int,
    val logFolder: String,
    val spawnReasons: Map<String, Boolean>,
    val entityGroups: Map<String, EntityGroupRule>,
    val entityTypes: Map<String, EntityTypeRule>,
) {
    val trackableEntities: Set<String> = buildSet {
        for (rule in entityTypes.values) {
            if (rule.use && rule.limit > -1) {
                add(rule.name.lowercase())
            }
        }
        for (group in entityGroups.values) {
            if (group.use && group.limit > -1) {
                addAll(group.types)
            }
        }
    }

    private val groupByEntityGlobal: Map<String, EntityGroupRule> = buildMap {
        for (group in entityGroups.values) {
            if (!group.use) continue
            for (type in group.types) {
                put(type, group)
            }
        }
    }

    private val groupByEntityWorld: Map<String, Map<String, EntityGroupRule>> = buildMap {
        val grouped = mutableMapOf<String, MutableMap<String, EntityGroupRule>>()
        for (group in entityGroups.values) {
            if (!group.use || group.worlds.isEmpty()) continue
            for (world in group.worlds) {
                val map = grouped.getOrPut(world) { mutableMapOf() }
                for (type in group.types) {
                    map[type] = group
                }
            }
        }
        putAll(grouped)
    }

    fun isIgnoredWorld(worldName: String?): Boolean =
        worldName != null && worldName.lowercase() in ignoredWorlds

    fun isExcludedChunk(worldName: String?, chunkX: Int, chunkZ: Int): Boolean {
        if (worldName == null || excludedChunks.isEmpty()) return false
        val set = excludedChunks[worldName.lowercase()] ?: return false
        return ChunkCoord(chunkX, chunkZ) in set
    }

    fun isSpawnReasonChecked(reason: SpawnReason): Boolean =
        spawnReasons[reason.name] ?: true

    fun isTrackable(typeName: String): Boolean =
        typeName.lowercase() in trackableEntities

    fun getEntityTypeRule(typeName: String): EntityTypeRule? =
        entityTypes[typeName.uppercase()]

    fun getGroupForEntity(typeName: String, worldName: String?): EntityGroupRule? {
        val key = typeName.lowercase()
        if (worldName != null) {
            val worldMatch = groupByEntityWorld[worldName.lowercase()]?.get(key)
            if (worldMatch != null) return worldMatch
        }
        val global = groupByEntityGlobal[key] ?: return null
        return if (global.appliesToWorld(worldName)) global else null
    }
}

/**
 * Manages loading, migrating, and reloading `mobfarm.yml`.
 */
class MobFarmConfig(private val context: ObsidianContext) {

    private var configFile: YamlConfigFile? = null

    @Volatile
    var settings: MobFarmSettings = defaultSettings()
        private set

    @Volatile
    var hopperControlOverride: Boolean? = null

    val isHopperControlActive: Boolean
        get() = hopperControlOverride ?: settings.hopperControlEnabled

    fun reload() {
        val rawFile = context.plugin.dataFolder.resolve("mobfarm.yml")
        val firstCreation = !rawFile.exists()
        val cfg = YamlConfigFile(context.plugin, "mobfarm.yml")
        configFile = cfg

        val yaml = cfg.yaml
        val legacy = if (firstCreation) context.config.raw else null
        var modified = migrateLegacyIfNeeded(yaml, legacy)
        modified = populateMissingEntries(yaml) || modified
        if (modified) {
            cfg.save()
        }

        hopperControlOverride = null
        settings = parseFromYaml(yaml)
    }

    fun setHopperControlEnabledRuntime(enabled: Boolean) {
        hopperControlOverride = enabled
    }

    private fun migrateLegacyIfNeeded(yaml: YamlConfiguration, legacy: YamlConfiguration?): Boolean {
        if (legacy == null || !legacy.isConfigurationSection("automation.mobfarm")) return false
        val sec = legacy.getConfigurationSection("automation.mobfarm") ?: return false

        if (sec.contains("enabled")) {
            val enabled = sec.getBoolean("enabled", true)
            yaml.set("entity-spawn.check", enabled)
            yaml.set("auto-clean.use", enabled)
        }
        if (sec.contains("exempt-named")) {
            yaml.set("ignore.named-animals", sec.getBoolean("exempt-named", true))
        }
        if (sec.contains("exempt-tamed")) {
            yaml.set("ignore.tamed-animals", sec.getBoolean("exempt-tamed", true))
        }
        if (sec.contains("chunk-monster-limit")) {
            yaml.set("entity-groups.Monsters.limit", sec.getInt("chunk-monster-limit", 30))
        }
        if (sec.contains("chunk-animal-limit")) {
            yaml.set("entity-groups.Animals.limit", sec.getInt("chunk-animal-limit", 30))
        }
        return true
    }

    private fun populateMissingEntries(yaml: YamlConfiguration): Boolean {
        var changed = false
        for (reason in SpawnReason.entries) {
            val path = "spawn-reasons.${reason.name}"
            if (!yaml.contains(path)) {
                yaml.set(path, true)
                changed = true
            }
        }
        for (type in EntityType.entries) {
            if (type.name in NON_FARM_EXCLUDED_TYPE_NAMES) continue
            val path = "entity-types.${type.name}"
            if (!yaml.contains(path)) {
                yaml.set("$path.use", true)
                yaml.set("$path.limit", defaultLimitForEntityType(type.name))
                changed = true
            }
        }
        return changed
    }

    companion object {
        private val NON_FARM_EXCLUDED_TYPE_NAMES = setOf(
            "AREA_EFFECT_CLOUD",
            "LEASH_KNOT",
            "ENDER_SIGNAL",
            "EYE_OF_ENDER",
            "EVOKER_FANGS",
            "LLAMA_SPIT",
            "BLOCK_DISPLAY",
            "TEXT_DISPLAY",
            "ITEM_DISPLAY",
            "INTERACTION",
            "PLAYER",
            "UNKNOWN",
        )

        private val DEFAULT_ENTITY_LIMITS: Map<String, Int> = mapOf(
            "ALLAY" to 20,
            "ARMADILLO" to 30,
            "ARMOR_STAND" to 10,
            "ARROW" to 10,
            "AXOLOTL" to 20,
            "BAT" to 20,
            "BEE" to 20,
            "BLAZE" to 30,
            "BOAT" to 10,
            "OAK_BOAT" to 10,
            "BOGGED" to 20,
            "CAMEL" to 10,
            "CAMEL_HUSK" to 20,
            "CAVE_SPIDER" to 30,
            "CHICKEN" to 20,
            "COD" to 20,
            "COPPER_GOLEM" to 30,
            "COW" to 20,
            "CREAKING" to 20,
            "CREEPER" to 30,
            "DOLPHIN" to 10,
            "DONKEY" to 30,
            "DROWNED" to 10,
            "ELDER_GUARDIAN" to 10,
            "ENDERMAN" to 30,
            "ENDERMITE" to 30,
            "ENDER_CRYSTAL" to 10,
            "END_CRYSTAL" to 10,
            "ENDER_DRAGON" to 5,
            "ENDER_PEARL" to 10,
            "EVOKER" to 30,
            "FIREBALL" to 10,
            "FIREWORK" to 10,
            "FIREWORK_ROCKET" to 10,
            "FOX" to 10,
            "FROG" to 15,
            "GHAST" to 30,
            "GIANT" to 20,
            "GLOW_SQUID" to 20,
            "GOAT" to 20,
            "GUARDIAN" to 30,
            "HOGLIN" to 20,
            "HORSE" to 20,
            "HUSK" to 20,
            "ILLUSIONER" to 20,
            "IRON_GOLEM" to 20,
            "ITEM_FRAME" to 30,
            "GLOW_ITEM_FRAME" to 30,
            "LLAMA" to 30,
            "MAGMA_CUBE" to 20,
            "MANNEQUIN" to 30,
            "MOOSHROOM" to 20,
            "MULE" to 30,
            "NAUTILUS" to 30,
            "OCELOT" to 20,
            "PANDA" to 20,
            "PARCHED" to 20,
            "PARROT" to 10,
            "PHANTOM" to 20,
            "PIG" to 20,
            "PIGLIN" to 20,
            "PIGLIN_BRUTE" to 20,
            "PILLAGER" to 20,
            "POLAR_BEAR" to 10,
            "PUFFERFISH" to 20,
            "RABBIT" to 30,
            "RAVAGER" to 20,
            "SALMON" to 20,
            "SHEEP" to 20,
            "SHULKER" to 30,
            "SILVERFISH" to 30,
            "SKELETON" to 30,
            "SKELETON_HORSE" to 10,
            "SLIME" to 30,
            "SNIFFER" to 20,
            "SNOWMAN" to 20,
            "SNOW_GOLEM" to 30,
            "SPIDER" to 30,
            "SQUID" to 20,
            "STRAY" to 20,
            "STRIDER" to 20,
            "TADPOLE" to 20,
            "TROPICAL_FISH" to 20,
            "TURTLE" to 20,
            "VEX" to 20,
            "VILLAGER" to 20,
            "VINDICATOR" to 20,
            "WARDEN" to 10,
            "WITCH" to 20,
            "WITHER" to 10,
            "WITHER_SKELETON" to 10,
            "WOLF" to 20,
            "ZOGLIN" to 20,
            "ZOMBIE" to 30,
            "ZOMBIE_HORSE" to 10,
            "ZOMBIE_NAUTILUS" to 20,
            "ZOMBIE_VILLAGER" to 20,
            "ZOMBIFIED_PIGLIN" to 20,
        )

        fun defaultLimitForEntityType(typeName: String): Int =
            DEFAULT_ENTITY_LIMITS[typeName.uppercase()] ?: -1

        fun parseExcludedChunk(raw: String): Pair<String, ChunkCoord>? {
            val parts = raw.trim().split(":")
            if (parts.size != 3) return null
            val world = parts[0].trim().lowercase()
            val x = parts[1].trim().toIntOrNull() ?: return null
            val z = parts[2].trim().toIntOrNull() ?: return null
            if (world.isEmpty()) return null
            return world to ChunkCoord(x, z)
        }

        /**
         * Resolves chunk coordinates in a square region around ([centerX], [centerZ]).
         * Radius 1 -> 1x1 (1 chunk), 2 -> 3x3 (9 chunks), 3 -> 5x5 (25 chunks).
         */
        fun resolveGroupChunks(centerX: Int, centerZ: Int, radius: Int): List<ChunkCoord> {
            val half = radius.coerceIn(1, 3) - 1
            val out = ArrayList<ChunkCoord>((2 * half + 1) * (2 * half + 1))
            for (dx in -half..half) {
                for (dz in -half..half) {
                    out += ChunkCoord(centerX + dx, centerZ + dz)
                }
            }
            return out
        }

        fun defaultSettings(): MobFarmSettings = parseFromYaml(YamlConfiguration())

        fun parseFromYaml(yaml: YamlConfiguration): MobFarmSettings {
            val excludedChunks = mutableMapOf<String, MutableSet<ChunkCoord>>()
            for (entry in yaml.getStringList("excluded-chunks")) {
                val (world, coord) = parseExcludedChunk(entry) ?: continue
                excludedChunks.getOrPut(world) { mutableSetOf() }.add(coord)
            }

            val autoMaxRemoved = yaml.getInt("auto-clean.max-removed", 500).let { if (it < 1) 500 else it }
            val cmdMaxRemoved = yaml.getInt("commands.clean.max-removed", 500).let { if (it < 1) 500 else it }

            val ignoredWorlds = yaml.getStringList("ignore.worlds").map { it.lowercase() }.toSet()

            val blockLimits = buildMap {
                val sec = yaml.getConfigurationSection("block-types.list")
                if (sec != null) {
                    for (key in sec.getKeys(false)) {
                        val mat = Material.matchMaterial(key) ?: continue
                        val limit = sec.getInt(key, 10)
                        if (limit > 0) {
                            put(mat, limit)
                        }
                    }
                } else {
                    put(Material.HOPPER, 10)
                    put(Material.PISTON, 10)
                    put(Material.STICKY_PISTON, 10)
                    put(Material.OBSERVER, 10)
                }
            }

            val spawnReasons = buildMap {
                val sec = yaml.getConfigurationSection("spawn-reasons")
                if (sec != null) {
                    for (key in sec.getKeys(false)) {
                        put(key.uppercase(), sec.getBoolean(key, true))
                    }
                }
            }

            val entityGroups = buildMap {
                val sec = yaml.getConfigurationSection("entity-groups")
                if (sec != null) {
                    for (groupName in sec.getKeys(false)) {
                        val sub = sec.getConfigurationSection(groupName) ?: continue
                        val use = sub.getBoolean("use", true)
                        val limit = sub.getInt("limit", -1)
                        val types = sub.getStringList("list").map { it.lowercase() }.toSet()
                        val worlds = sub.getStringList("worlds").map { it.lowercase() }.toSet()
                        val removeExisting = if (sub.contains("remove-existing")) sub.getBoolean("remove-existing") else null
                        put(groupName, EntityGroupRule(groupName, use, limit, types, worlds, removeExisting))
                    }
                }
            }

            val entityTypes = buildMap {
                val sec = yaml.getConfigurationSection("entity-types")
                if (sec != null) {
                    for (key in sec.getKeys(false)) {
                        val sub = sec.getConfigurationSection(key) ?: continue
                        val upper = key.uppercase()
                        val rule = parseEntityTypeRule(upper, sub)
                        put(upper, rule)
                    }
                }
            }

            val slowestRate = yaml.getInt("hopper-control.slowest-rate", 40).coerceAtLeast(1)
            val defaultCooldown = yaml.getInt("hopper-control.default-cooldown", 8).coerceIn(1, slowestRate)

            return MobFarmSettings(
                checkOnEntitySpawn = yaml.getBoolean("entity-spawn.check", true),
                preventMobBuild = yaml.getBoolean("entity-spawn.prevent-mob-build", false),
                preventIronGolemSpawn = yaml.getBoolean("entity-spawn.prevent-iron-golem-spawn", false),
                groupChunks = yaml.getBoolean("entity-spawn.group-chunks", true),
                groupChunksRadius = yaml.getInt("entity-spawn.group-chunks-radius", 2).coerceIn(1, 3),
                removeExisting = yaml.getBoolean("remove-existing", true),
                excludedChunks = excludedChunks,
                autoCleanUse = yaml.getBoolean("auto-clean.use", true),
                autoCleanInform = yaml.getBoolean("auto-clean.inform", true),
                autoCleanIntervalSeconds = yaml.getInt("auto-clean.interval-seconds", 30).coerceIn(15, 3600),
                autoCleanMaxRemoved = autoMaxRemoved,
                autoCleanGroupChunks = yaml.getBoolean("auto-clean.group-chunks", false),
                autoCleanChunkRadius = yaml.getInt("auto-clean.chunk-radius", 2).coerceIn(1, 3),
                autoCleanExpandRange = yaml.getInt("auto-clean.expand-range", 2).coerceIn(1, 2),
                commandsCleanMaxRemoved = cmdMaxRemoved,
                commandsCleanGroupChunks = yaml.getBoolean("commands.clean.group-chunks", false),
                commandsCleanGroupChunksRadius = yaml.getInt("commands.clean.group-chunks-radius", 1).coerceIn(1, 3),
                excludeDisabledFromList = yaml.getBoolean("commands.exclude-disabled-from-list", false),
                ignoreFullArmorStands = yaml.getBoolean("ignore.full-armor-stands", true),
                ignoreEntityWithPickedItem = yaml.getBoolean("ignore.entity-with-picked-item", true),
                ignoreFullItemFrames = yaml.getBoolean("ignore.full-item-frames", true),
                ignoreTamedAnimals = yaml.getBoolean("ignore.tamed-animals", true),
                ignoreNamedAnimals = yaml.getBoolean("ignore.named-animals", true),
                ignoreWithSaddle = yaml.getBoolean("ignore.with-saddle", true),
                ignoreBabyAnimals = yaml.getBoolean("ignore.baby-animals", true),
                ignoredWorlds = ignoredWorlds,
                wolfControlEnabled = yaml.getBoolean("wolf-control.enabled", false),
                wolfControlMax = yaml.getInt("wolf-control.max", 3).coerceAtLeast(1),
                hopperControlEnabled = yaml.getBoolean("hopper-control.enabled", false),
                hopperControlDefault = defaultCooldown,
                hopperControlSlowestRate = slowestRate,
                hopperControlAdjustEvery = yaml.getInt("hopper-control.adjust-every", 30).coerceAtLeast(1),
                redstoneTrack = yaml.getBoolean("redstone.track", false),
                redstoneWindowMillis = yaml.getLong("redstone.window-seconds", 60L).coerceIn(5L, 600L) * 1000L,
                blockTypesLimit = yaml.getBoolean("block-types.limit", false),
                blockTypesCheckPistonMove = yaml.getBoolean("block-types.check-piston-move", true),
                blockLimits = blockLimits,
                logEnabled = yaml.getBoolean("log.enabled", false),
                logFrom = yaml.getInt("log.from", 5).coerceAtLeast(1),
                logFolder = yaml.getString("log.folder", "logs/mobfarm") ?: "logs/mobfarm",
                spawnReasons = spawnReasons,
                entityGroups = entityGroups,
                entityTypes = entityTypes,
            )
        }

        private fun parseEntityTypeRule(name: String, sub: ConfigurationSection): EntityTypeRule {
            val worldLimits = buildMap {
                val wl = sub.getConfigurationSection("world-limit")
                if (wl != null) {
                    for (w in wl.getKeys(false)) {
                        put(w.lowercase(), wl.getInt(w, -1))
                    }
                }
            }
            return EntityTypeRule(
                name = name,
                use = sub.getBoolean("use", true),
                limit = sub.getInt("limit", defaultLimitForEntityType(name)),
                worldLimits = worldLimits,
                removeExisting = if (sub.contains("remove-existing")) sub.getBoolean("remove-existing") else null,
                manualClean = sub.getBoolean("manual-clean", true),
                autoClean = sub.getBoolean("auto-clean", true),
                naturalSpawn = sub.getBoolean("natural-spawn", true),
                disableAi = sub.getBoolean("disable-ai", false),
            )
        }
    }
}
