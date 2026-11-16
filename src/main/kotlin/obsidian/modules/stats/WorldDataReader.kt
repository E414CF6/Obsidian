package obsidian.modules.stats

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads vanilla Minecraft `stats/{uuid}.json` and `advancements/{uuid}.json` files and normalizes
 * Minecraft formatting codes (legacy `&`/`§`, hex `{#RRGGBB}`, and MiniMessage `<...>`) into plain text.
 */
object WorldDataReader {

    private val LEGACY_CODE_PATTERN = Regex("[&§][0-9A-FK-ORXa-fk-orx]")
    private val BRACED_HEX_PATTERN = Regex("\\{#[0-9A-Fa-f]{6}}")
    private val AMPERSAND_HEX_PATTERN = Regex("&#[0-9A-Fa-f]{6}")
    private val MINIMESSAGE_TAG_PATTERN = Regex("</?[a-zA-Z0-9_:#,\\-]+>")
    private val EXTRA_SPACES_PATTERN = Regex("\\s{2,}")

    private val BIOME_ADVANCEMENTS = listOf(
        "minecraft:adventure/adventuring_time",
        "minecraft:nether/explore_nether",
    )

    private const val DEFAULT_BIOMES_TARGET = 54
    private const val DAMAGE_SCALE = 10.0

    fun stripMinecraftCodes(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.replace(BRACED_HEX_PATTERN, "").replace(AMPERSAND_HEX_PATTERN, "").replace(LEGACY_CODE_PATTERN, "")
            .replace(MINIMESSAGE_TAG_PATTERN, "").replace(EXTRA_SPACES_PATTERN, " ").trim()
    }

    fun stripNamespace(key: String?): String {
        if (key == null) return ""
        return key.removePrefix("minecraft:")
    }

    fun readVanillaStats(worldDir: Path?, uuid: String): PlayerVanillaStats {
        if (worldDir == null || uuid.isBlank()) return PlayerVanillaStats.EMPTY
        val file = resolveUuidFile(worldDir, "stats", uuid) ?: return PlayerVanillaStats.EMPTY
        return runCatching {
            val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).asJsonObject
            val stats = root.getAsJsonObject("stats") ?: return PlayerVanillaStats.EMPTY
            val custom = stats.getAsJsonObject("minecraft:custom")

            val walkCm = getLong(custom, "minecraft:walk_one_cm")
            val sprintCm = getLong(custom, "minecraft:sprint_one_cm")
            val swimCm = getLong(custom, "minecraft:swim_one_cm")
            val flyCm = getLong(custom, "minecraft:fly_one_cm")
            val vehicleCm =
                getLong(custom, "minecraft:boat_one_cm") + getLong(custom, "minecraft:horse_one_cm") + getLong(
                    custom,
                    "minecraft:minecart_one_cm"
                ) + getLong(custom, "minecraft:pig_one_cm") + getLong(custom, "minecraft:strider_one_cm")

            val mined = sumAndTop(stats.getAsJsonObject("minecraft:mined"), 10)
            val used = sumAndTop(stats.getAsJsonObject("minecraft:used"), 8)
            val crafted = sumAndTop(stats.getAsJsonObject("minecraft:crafted"), 8)
            val broken = sumCategory(stats.getAsJsonObject("minecraft:broken"))
            val pickedUp = sumCategory(stats.getAsJsonObject("minecraft:picked_up"))

            PlayerVanillaStats(
                deaths = getLong(custom, "minecraft:deaths"),
                timeSinceDeathMs = getLong(custom, "minecraft:time_since_death") * 50L,
                mobKills = getLong(custom, "minecraft:mob_kills"),
                playerKills = getLong(custom, "minecraft:player_kills"),
                damageDealt = getLong(custom, "minecraft:damage_dealt") / DAMAGE_SCALE,
                damageTaken = getLong(custom, "minecraft:damage_taken") / DAMAGE_SCALE,
                jumps = getLong(custom, "minecraft:jump"),
                leaveGameCount = getLong(custom, "minecraft:leave_game"),
                walkCm = walkCm,
                sprintCm = sprintCm,
                swimCm = swimCm,
                flyCm = flyCm,
                vehicleCm = vehicleCm,
                totalDistanceCm = walkCm + sprintCm + swimCm + flyCm + vehicleCm,
                totalMined = mined.total,
                topMined = mined.top,
                totalUsed = used.total,
                topUsed = used.top,
                totalCrafted = crafted.total,
                topCrafted = crafted.top,
                totalBroken = broken,
                totalPickedUp = pickedUp,
            )
        }.getOrDefault(PlayerVanillaStats.EMPTY)
    }

    fun readAdvancements(
        worldDir: Path?,
        uuid: String,
        totalBiomesTarget: Int = DEFAULT_BIOMES_TARGET,
    ): PlayerAdvancementStats {
        val resolvedBiomesTarget = totalBiomesTarget.coerceAtLeast(1)
        if (worldDir == null || uuid.isBlank()) {
            return PlayerAdvancementStats.EMPTY.copy(totalBiomesTarget = resolvedBiomesTarget)
        }
        val file = resolveUuidFile(worldDir, "advancements", uuid)
            ?: return PlayerAdvancementStats.EMPTY.copy(totalBiomesTarget = resolvedBiomesTarget)
        return runCatching {
            val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).asJsonObject

            val categories = linkedMapOf(
                "story" to 0,
                "adventure" to 0,
                "nether" to 0,
                "end" to 0,
                "husbandry" to 0,
            )
            var totalCompleted = 0L
            val recent = mutableListOf<RecentAdvancementItem>()
            val visitedBiomes = mutableListOf<VisitedBiomeItem>()

            for ((key, value) in root.entrySet()) {
                if (key == "DataVersion" || key.startsWith("minecraft:recipes/") || !value.isJsonObject) continue
                val obj = value.asJsonObject
                val done = obj.has("done") && obj.get("done").asBoolean
                val criteria = obj.getAsJsonObject("criteria")

                if (key in BIOME_ADVANCEMENTS && criteria != null) {
                    for ((biomeKey, biomeTime) in criteria.entrySet()) {
                        visitedBiomes += VisitedBiomeItem(
                            id = stripNamespace(biomeKey),
                            visitedAt = biomeTime.asString,
                        )
                    }
                }

                if (!done) continue
                totalCompleted++

                val category = extractAdvancementCategory(key)
                if (category in categories) {
                    categories[category] = (categories[category] ?: 0) + 1
                }

                var completedAt = ""
                if (criteria != null) {
                    for ((_, timestampElem) in criteria.entrySet()) {
                        if (!timestampElem.isJsonPrimitive) continue
                        val ts = timestampElem.asString
                        if (ts > completedAt) completedAt = ts
                    }
                }

                recent += RecentAdvancementItem(
                    key = stripNamespace(key),
                    category = category,
                    completedAt = completedAt,
                )
            }

            recent.sortByDescending { it.completedAt }
            visitedBiomes.sortByDescending { it.visitedAt }

            PlayerAdvancementStats(
                totalCompleted = totalCompleted,
                categories = categories,
                recent = if (recent.size > 15) recent.subList(0, 15).toList() else recent,
                visitedBiomes = visitedBiomes,
                totalBiomesTarget = resolvedBiomesTarget,
            )
        }.getOrDefault(PlayerAdvancementStats.EMPTY.copy(totalBiomesTarget = resolvedBiomesTarget))
    }

    fun computeSpecialty(skills: Map<String, PlayerSkillStat>): PlayerSpecialty {
        var gathering = 0
        var combat = 0
        var misc = 0
        var topKey: String? = null
        var topLevel = 0
        var topCategory = "balanced"

        for (meta in SkillMeta.ALL) {
            val level = skills[meta.key]?.level ?: 0
            when (meta.category) {
                "gathering" -> gathering += level
                "combat" -> combat += level
                else -> misc += level
            }
            if (level > topLevel) {
                topLevel = level
                topKey = meta.key
                topCategory = meta.category
            }
        }

        val total = gathering + combat + misc
        if (total == 0) {
            return PlayerSpecialty("newcomer", "신입 모험가", "balanced", null, 0, 0, 0)
        }

        var domCategory = "balanced"
        if (gathering >= combat && gathering >= misc && gathering > total * 0.42) {
            domCategory = "gathering"
        } else if (combat >= gathering && combat >= misc && combat > total * 0.42) {
            domCategory = "combat"
        } else if (misc >= gathering && misc >= combat && misc > total * 0.42) {
            domCategory = "misc"
        } else if (topKey != null) {
            domCategory = topCategory
        }

        val (key, title) = when (domCategory) {
            "gathering" -> if (gathering > 300) "gathering_master" to "대지의 마스터" else "gathering_expert" to "자연 채집가"
            "combat" -> if (combat > 300) "combat_legend" to "전설의 전사" else "combat_skilled" to "숙련된 전투가"
            "misc" -> if (misc > 200) "misc_artisan" to "만능 장인" else "misc_specialist" to "기술 전문가"
            else -> "balanced" to "균형잡힌 모험가"
        }

        return PlayerSpecialty(key, title, domCategory, topKey, gathering, combat, misc)
    }

    private fun resolveUuidFile(worldDir: Path, subDir: String, uuid: String): Path? {
        val primary = worldDir.resolve(subDir).resolve("$uuid.json")
        if (Files.isRegularFile(primary)) return primary
        val nested = worldDir.resolve("players").resolve(subDir).resolve("$uuid.json")
        if (Files.isRegularFile(nested)) return nested
        return null
    }

    private fun extractAdvancementCategory(key: String): String {
        val raw = stripNamespace(key)
        val slash = raw.indexOf('/')
        if (slash <= 0) return "other"
        return when (val cat = raw.substring(0, slash)) {
            "story", "adventure", "nether", "end", "husbandry" -> cat
            else -> "other"
        }
    }

    private fun getLong(obj: JsonObject?, key: String): Long {
        if (obj == null) return 0L
        val elem: JsonElement = obj.get(key) ?: return 0L
        return if (elem.isJsonPrimitive) runCatching { elem.asLong }.getOrDefault(0L) else 0L
    }

    private fun sumCategory(obj: JsonObject?): Long {
        if (obj == null) return 0L
        var total = 0L
        for ((_, value) in obj.entrySet()) {
            if (value.isJsonPrimitive) total += runCatching { value.asLong }.getOrDefault(0L)
        }
        return total
    }

    private data class SumResult(val total: Long, val top: List<VanillaStatItem>)

    private fun sumAndTop(obj: JsonObject?, limit: Int): SumResult {
        if (obj == null) return SumResult(0L, emptyList())
        var total = 0L
        val items = mutableListOf<VanillaStatItem>()
        for ((key, value) in obj.entrySet()) {
            if (!value.isJsonPrimitive) continue
            val count = runCatching { value.asLong }.getOrDefault(0L)
            total += count
            if (count > 0L) items += VanillaStatItem(stripNamespace(key), count)
        }
        items.sortByDescending { it.count }
        val capped = if (items.size > limit) items.subList(0, limit).toList() else items
        return SumResult(total, capped)
    }
}
