package obsidian.modules.stats

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName

/**
 * Shared JSON serialization configuration & wire-compatible DTO models for Obsidian `stats` REST API & `web/` dashboard.
 */
object StatsJson {
    val GSON: Gson = GsonBuilder().serializeNulls().create()
}

data class CurrencyMeta(
    val symbol: String = "$",
    val name: String = "Dollar",
    val namePlural: String = "Dollars",
    val formatPattern: String = "#,##0.##",
)

data class RankTierMeta(
    val id: String,
    val label: String,
    val nextRank: String?,
    val requiredPlaytimeSeconds: Long,
    val cost: Double,
    val color: String,
    val lightColor: String,
)

data class CapabilitiesMeta(
    val mcmmo: Boolean = false,
    val claims: Boolean = false,
    val economy: Boolean = true,
    val playtime: Boolean = true,
)

data class ServerMeta(
    val serverName: String = "Obsidian",
    val currency: CurrencyMeta = CurrencyMeta(),
    val ranks: List<RankTierMeta> = emptyList(),
    val capabilities: CapabilitiesMeta = CapabilitiesMeta(),
)

data class StatsApiResponse(
    val overview: ServerOverview,
    val players: List<PlayerSummary>,
    val meta: ServerMeta = ServerMeta(),
)

data class PlayerDetailResponse(
    val player: PlayerSummary,
    val queriedAt: String,
    val sourceMode: String,
    val meta: ServerMeta = ServerMeta(),
)

data class ServerOverview(
    val totalPlayers: Int,
    val onlinePlayers: Int,
    val totalBalance: Double,
    val totalPlayTimeMs: Long,
    val totalPowerLevel: Long,
    val totalClaims: Int,
    val totalClaimedArea: Long,
    val totalMinedBlocks: Long,
    val totalMobKills: Long,
    val totalDistanceMeters: Long,
    val totalAdvancements: Long,
    val rankDistribution: Map<String, Int>,
    val queriedAt: String,
    val sourceMode: String,
)

data class PlayerSummary(
    val uuid: String,
    val username: String,
    val nickname: String?,
    val displayName: String?,
    val primaryGroup: String,
    val rank: String,
    val rankId: String = rank,
    val balance: Double,
    val totalPlayTimeMs: Long,
    val lastLoginTime: Long,
    val lastLogoffTime: Long,
    @SerializedName("isOnline") val isOnline: Boolean,
    val votes: Long,
    val powerLevel: Long,
    val skills: Map<String, PlayerSkillStat>,
    val specialty: PlayerSpecialty,
    val claims: PlayerClaims,
    val playtimeHistory: List<PlaytimeDayRecord>,
    val hourlyDistribution: List<Long>,
    val vanillaStats: PlayerVanillaStats,
    val advancements: PlayerAdvancementStats,
)

data class PlayerSkillStat(
    val level: Int,
    val xp: Int,
    val xpToNext: Int,
)

data class PlayerSpecialty(
    val key: String,
    val title: String,
    val category: String,
    val topSkillKey: String?,
    val gatheringLevel: Int,
    val combatLevel: Int,
    val miscLevel: Int,
)

data class PlayerClaims(
    val accruedBlocks: Int,
    val bonusBlocks: Int,
    val totalAvailableBlocks: Int,
    val usedBlocks: Int,
    val remainingBlocks: Int,
    val claimCount: Int,
    val items: List<ClaimItem>,
) {
    companion object {
        val EMPTY = PlayerClaims(0, 0, 0, 0, 0, 0, emptyList())
    }
}

data class ClaimItem(
    val id: Long,
    val world: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    val width: Int,
    val length: Int,
    val area: Int,
    val builders: List<String>,
    val containers: List<String>,
    val accessors: List<String>,
    val managers: List<String>,
)

data class PlaytimeDayRecord(
    val dateInt: Int,
    val dateFormatted: String,
    val hours: List<Long>,
    val totalMs: Long,
)

data class VanillaStatItem(
    val id: String,
    val count: Long,
)

data class PlayerVanillaStats(
    val deaths: Long,
    val timeSinceDeathMs: Long,
    val mobKills: Long,
    val playerKills: Long,
    val damageDealt: Double,
    val damageTaken: Double,
    val jumps: Long,
    val leaveGameCount: Long,
    val walkCm: Long,
    val sprintCm: Long,
    val swimCm: Long,
    val flyCm: Long,
    val vehicleCm: Long,
    val totalDistanceCm: Long,
    val totalMined: Long,
    val topMined: List<VanillaStatItem>,
    val totalUsed: Long,
    val topUsed: List<VanillaStatItem>,
    val totalCrafted: Long,
    val topCrafted: List<VanillaStatItem>,
    val totalBroken: Long,
    val totalPickedUp: Long,
) {
    companion object {
        val EMPTY = PlayerVanillaStats(
            deaths = 0L,
            timeSinceDeathMs = 0L,
            mobKills = 0L,
            playerKills = 0L,
            damageDealt = 0.0,
            damageTaken = 0.0,
            jumps = 0L,
            leaveGameCount = 0L,
            walkCm = 0L,
            sprintCm = 0L,
            swimCm = 0L,
            flyCm = 0L,
            vehicleCm = 0L,
            totalDistanceCm = 0L,
            totalMined = 0L,
            topMined = emptyList(),
            totalUsed = 0L,
            topUsed = emptyList(),
            totalCrafted = 0L,
            topCrafted = emptyList(),
            totalBroken = 0L,
            totalPickedUp = 0L,
        )
    }
}

data class RecentAdvancementItem(
    val key: String,
    val category: String,
    val completedAt: String,
)

data class VisitedBiomeItem(
    val id: String,
    val visitedAt: String,
)

data class PlayerAdvancementStats(
    val totalCompleted: Long,
    val categories: Map<String, Int>,
    val recent: List<RecentAdvancementItem>,
    val visitedBiomes: List<VisitedBiomeItem>,
    val totalBiomesTarget: Int,
) {
    companion object {
        val EMPTY = PlayerAdvancementStats(
            totalCompleted = 0L,
            categories = mapOf("story" to 0, "adventure" to 0, "nether" to 0, "end" to 0, "husbandry" to 0),
            recent = emptyList(),
            visitedBiomes = emptyList(),
            totalBiomesTarget = 54,
        )
    }
}

data class SkillMeta(
    val key: String,
    val labelKo: String,
    val category: String,
) {
    companion object {
        val ALL: List<SkillMeta> = listOf(
            SkillMeta("mining", "채광", "gathering"),
            SkillMeta("woodcutting", "벌목", "gathering"),
            SkillMeta("herbalism", "약초학", "gathering"),
            SkillMeta("excavation", "발굴", "gathering"),
            SkillMeta("fishing", "낚시", "gathering"),
            SkillMeta("swords", "검술", "combat"),
            SkillMeta("axes", "도끼술", "combat"),
            SkillMeta("unarmed", "격투", "combat"),
            SkillMeta("archery", "궁술", "combat"),
            SkillMeta("taming", "조련", "combat"),
            SkillMeta("crossbows", "석궁", "combat"),
            SkillMeta("tridents", "삼지창", "combat"),
            SkillMeta("maces", "철퇴", "combat"),
            SkillMeta("spears", "창술", "combat"),
            SkillMeta("repair", "수리", "misc"),
            SkillMeta("acrobatics", "곡예", "misc"),
            SkillMeta("alchemy", "연금술", "misc"),
        )
    }
}
