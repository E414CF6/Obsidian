package obsidian.modules.playtime

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.bossbar.BossBar
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.PlayTimeRecord
import obsidian.modules.economy.EconomyService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.time.LocalDate
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class RankDefinition(
    val id: String,
    val displayName: String,
    val nextRank: String?,
    val requiredPlaytimeSeconds: Long,
    val cost: Double,
    val commands: List<String>,
    val color: String? = null,
    val lightColor: String? = null,
)

data class PlayTimeReward(
    val id: String,
    val displayName: String,
    val requiredPlaytimeSeconds: Long,
    val commands: List<String>,
)

/**
 * Tracks online/daily playtime, rank progression (`/rankup`), and milestone rewards (`/prewards`).
 */
class RankService(private val context: ObsidianContext) : Listener {

    private val ranksFile = YamlConfigFile(context.plugin, "ranks.yml")
    private val ranks = LinkedHashMap<String, RankDefinition>()
    private val rewards = LinkedHashMap<String, PlayTimeReward>()

    private data class SessionState(
        var record: PlayTimeRecord,
        var lastTickAt: Long,
    )

    private val sessions = ConcurrentHashMap<UUID, SessionState>()
    var autoRankup: Boolean = false
        private set

    init {
        reload()
    }

    fun reload() {
        ranksFile.reload()
        autoRankup = context.config.raw.getBoolean("playtime.ranks.auto-rankup", false)

        ranks.clear()
        ranksFile.yaml.getConfigurationSection("ranks")?.let { sec ->
            for (key in sec.getKeys(false)) {
                val node = sec.getConfigurationSection(key) ?: continue
                val next = node.getString("next-rank", "")?.takeIf { it.isNotBlank() }
                ranks[key.lowercase()] = RankDefinition(
                    id = key,
                    displayName = node.getString("display-name", key) ?: key,
                    nextRank = next,
                    requiredPlaytimeSeconds = node.getLong("required-playtime-seconds", 0L).coerceAtLeast(0L),
                    cost = node.getDouble("cost", 0.0).coerceAtLeast(0.0),
                    commands = node.getStringList("commands"),
                    color = node.getString("color")?.takeIf { it.isNotBlank() },
                    lightColor = node.getString("light-color")?.takeIf { it.isNotBlank() },
                )
            }
        }

        rewards.clear()
        ranksFile.yaml.getConfigurationSection("rewards")?.let { sec ->
            for (key in sec.getKeys(false)) {
                val node = sec.getConfigurationSection(key) ?: continue
                rewards[key.lowercase()] = PlayTimeReward(
                    id = key,
                    displayName = node.getString("display-name", key) ?: key,
                    requiredPlaytimeSeconds = node.getLong("required-playtime-seconds", 0L).coerceAtLeast(0L),
                    commands = node.getStringList("commands"),
                )
            }
        }
    }

    fun getRank(rankId: String): RankDefinition? = ranks[rankId.lowercase()]

    fun getAllRanks(): List<RankDefinition> = ranks.values.toList()

    fun getAllRewards(): List<PlayTimeReward> = rewards.values.toList()

    fun claimSingleReward(player: Player, rewardId: String): PlayTimeReward? {
        val state = sessions[player.uniqueId] ?: return null
        accumulateSession(state)
        val reward = rewards[rewardId.lowercase()] ?: return null
        val key = reward.id.lowercase()
        val claimed = state.record.claimedRewards.toMutableSet()
        if (key in claimed) return null
        if (state.record.totalMillis < reward.requiredPlaytimeSeconds * 1000L) return null
        claimed += key
        for (cmd in reward.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", player.name))
        }
        val updated = state.record.copy(claimedRewards = claimed, updatedAt = System.currentTimeMillis())
        state.record = updated
        context.scheduler.asyncScope.launch {
            context.playTimeRepository.upsertAll(listOf(updated))
        }
        return reward
    }

    fun getOrLoadSnapshot(uuid: UUID, nameHint: String? = null): PlayTimeRecord {
        sessions[uuid]?.let { state ->
            accumulateSession(state)
            return state.record
        }
        val db = runBlocking { context.playTimeRepository.findByUuid(uuid) }
        if (db != null) return db
        return PlayTimeRecord(
            uuid = uuid,
            username = nameHint ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8),
            dayKey = LocalDate.now().toString(),
        )
    }

    fun getCurrentRankDisplay(uuid: UUID): String {
        val rankId = getOrLoadSnapshot(uuid).currentRank
        return getRank(rankId)?.displayName ?: rankId
    }

    fun tickAllOnline() {
        val now = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            val state = sessions[player.uniqueId] ?: continue
            accumulateSession(state, now)
            if (autoRankup) {
                tryRankUp(player, chargeCost = true, silentIfNotReady = true)
            }
        }
        context.scheduler.asyncScope.launch {
            flushAll()
        }
    }

    suspend fun flushAll() {
        val records = sessions.values.map { it.record }
        if (records.isNotEmpty()) {
            context.playTimeRepository.upsertAll(records)
        }
    }

    fun flushAllBlocking() {
        val now = System.currentTimeMillis()
        sessions.values.forEach { accumulateSession(it, now) }
        runBlocking { flushAll() }
    }

    fun tryRankUp(player: Player, chargeCost: Boolean = true, silentIfNotReady: Boolean = false): RankUpResult {
        val state = sessions[player.uniqueId] ?: return RankUpResult.NotReady(null)
        accumulateSession(state)
        val current = getRank(state.record.currentRank) ?: RankDefinition(
            state.record.currentRank, state.record.currentRank, null, 0L, 0.0, emptyList()
        )
        val nextId = current.nextRank ?: return RankUpResult.AlreadyMax(current)
        val nextRank = getRank(nextId) ?: return RankUpResult.AlreadyMax(current)

        val requiredMillis = nextRank.requiredPlaytimeSeconds * 1000L
        if (state.record.totalMillis < requiredMillis) {
            return RankUpResult.NotReady(nextRank)
        }
        val eco = context.getService<EconomyService>()
        if (chargeCost && nextRank.cost > 0.0 && eco != null) {
            if (!eco.withdraw(player.uniqueId, nextRank.cost, player.name)) {
                return RankUpResult.NotReady(nextRank)
            }
        }

        val updated = state.record.copy(
            currentRank = nextRank.id,
            updatedAt = System.currentTimeMillis(),
        )
        state.record = updated
        context.scheduler.asyncScope.launch {
            context.playTimeRepository.upsertAll(listOf(updated))
        }
        for (cmd in nextRank.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", player.name))
        }
        if (!silentIfNotReady || autoRankup) {
            context.locale.send(
                player,
                "playtime.rank-up-success",
                "old" to current.displayName,
                "new" to nextRank.displayName,
            )
            context.bossBar.showTimed(
                player = player,
                id = "rankup_${player.uniqueId}",
                title = context.locale.component(
                    "playtime.rank-up-bossbar",
                    mapOf("old" to current.displayName, "new" to nextRank.displayName),
                ),
                durationMillis = 5000L,
                color = BossBar.Color.PURPLE,
            )
        }
        return RankUpResult.Promoted(current, nextRank)
    }

    fun claimAvailableRewards(player: Player): List<PlayTimeReward> {
        val state = sessions[player.uniqueId] ?: return emptyList()
        accumulateSession(state)
        val claimed = state.record.claimedRewards.toMutableSet()
        val newlyClaimed = mutableListOf<PlayTimeReward>()

        for (reward in rewards.values) {
            val key = reward.id.lowercase()
            if (key in claimed) continue
            if (state.record.totalMillis >= reward.requiredPlaytimeSeconds * 1000L) {
                claimed += key
                newlyClaimed += reward
                for (cmd in reward.commands) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", player.name))
                }
            }
        }
        if (newlyClaimed.isNotEmpty()) {
            val updated = state.record.copy(claimedRewards = claimed, updatedAt = System.currentTimeMillis())
            state.record = updated
            context.scheduler.asyncScope.launch {
                context.playTimeRepository.upsertAll(listOf(updated))
            }
        }
        return newlyClaimed
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        context.scheduler.asyncScope.launch {
            val loaded = context.playTimeRepository.findByUuid(player.uniqueId)
            val rec = if (loaded != null) {
                val dailyReset = if (loaded.dayKey == today) loaded.dailyMillis else 0L
                loaded.copy(username = player.name, dailyMillis = dailyReset, dayKey = today)
            } else {
                PlayTimeRecord(
                    uuid = player.uniqueId,
                    username = player.name,
                    dayKey = today,
                    currentRank = "default",
                )
            }
            sessions[player.uniqueId] = SessionState(rec, now)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val state = sessions.remove(event.player.uniqueId) ?: return
        accumulateSession(state)
        context.scheduler.asyncScope.launch {
            context.playTimeRepository.upsertAll(listOf(state.record))
        }
    }

    private fun accumulateSession(state: SessionState, now: Long = System.currentTimeMillis()) {
        val delta = (now - state.lastTickAt).coerceAtLeast(0L)
        val today = LocalDate.now().toString()
        val dailyBase = if (state.record.dayKey == today) state.record.dailyMillis else 0L
        state.record = state.record.copy(
            totalMillis = state.record.totalMillis + delta,
            dailyMillis = dailyBase + delta,
            dayKey = today,
            updatedAt = now,
        )
        state.lastTickAt = now
    }

    sealed interface RankUpResult {
        data class Promoted(val oldRank: RankDefinition, val newRank: RankDefinition) : RankUpResult
        data class AlreadyMax(val currentRank: RankDefinition) : RankUpResult
        data class NotReady(val nextRank: RankDefinition?) : RankUpResult
    }
}
