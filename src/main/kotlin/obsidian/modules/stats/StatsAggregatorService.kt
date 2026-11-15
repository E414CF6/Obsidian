package obsidian.modules.stats

import obsidian.core.database.DatabaseDialect
import obsidian.core.database.DatabaseManager
import obsidian.integration.LuckPermsHook
import obsidian.modules.playtime.RankService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Live snapshot for an online Bukkit player used to overlay uncommitted session state onto DB rows.
 */
data class OnlinePlayerSnapshot(
    val uuid: UUID,
    val username: String,
    val displayName: String?,
    val primaryGroup: String?,
    val currentRank: String?,
    val totalPlayTimeMs: Long?,
    val dailyPlayTimeMs: Long?,
    val dayKey: String?,
)

/**
 * Aggregates player and server telemetry natively from Obsidian repositories, online sessions,
 * and vanilla world JSON files (`stats/`, `advancements/`) into [StatsApiResponse] snapshots.
 */
class StatsAggregatorService(
    private val db: DatabaseManager,
    private val rankService: RankService? = null,
    private val luckPermsHook: LuckPermsHook? = null,
    private val logger: Logger? = null,
    private val worldDirProvider: () -> Path? = { resolveDefaultWorldDir() },
    private val onlinePlayersProvider: () -> Map<UUID, OnlinePlayerSnapshot> = {
        collectOnlineSnapshots(
            rankService,
            luckPermsHook
        )
    },
    private val skillsProvider: (UUID, String, Boolean) -> Map<String, PlayerSkillStat> = { uuid, _, isOnline ->
        collectExternalMcmmoSkills(uuid, isOnline)
    },
    private val claimsProvider: (UUID) -> PlayerClaims = { uuid -> collectExternalClaims(uuid) },
) {

    @Volatile
    var cacheTtlSeconds: Long = 15L
        set(value) {
            field = value.coerceAtLeast(1L)
        }

    @Volatile
    var sourceMode: String = "obsidian-native"

    private val lock = Any()

    @Volatile
    private var cachedResponse: StatsApiResponse? = null

    @Volatile
    private var lastCacheEpochSeconds: Long = 0L

    private val sessionHourTrack = ConcurrentHashMap<UUID, Long>()
    private val historyTable = db.table("stats_daily_playtime")

    fun ensureSchema() {
        db.querySync { conn ->
            val sql = when (db.dialect) {
                DatabaseDialect.SQLITE -> """
                    CREATE TABLE IF NOT EXISTS `$historyTable` (
                        `uuid` TEXT NOT NULL,
                        `date_int` INTEGER NOT NULL,
                        `hours_csv` TEXT NOT NULL,
                        `total_ms` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (`uuid`, `date_int`)
                    );
                """.trimIndent()

                DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                    CREATE TABLE IF NOT EXISTS `$historyTable` (
                        `uuid` VARCHAR(36) NOT NULL,
                        `date_int` INT NOT NULL,
                        `hours_csv` TEXT NOT NULL,
                        `total_ms` BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (`uuid`, `date_int`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.trimIndent()
            }
            conn.createStatement().use { st -> st.execute(sql) }
        }
    }

    /** Records an incremental playtime tick into the per-day 24-hour bucket table. */
    fun recordPlaytimeTick(uuid: UUID, nowMillis: Long = System.currentTimeMillis(), maxStepMs: Long = 120_000L) {
        val prev = sessionHourTrack.put(uuid, nowMillis) ?: return
        val delta = (nowMillis - prev).coerceIn(0L, maxStepMs)
        if (delta <= 0L) return

        val zdt = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        val dateInt = zdt.year * 10000 + zdt.monthValue * 100 + zdt.dayOfMonth
        val hourIdx = zdt.hour.coerceIn(0, 23)

        runCatching {
            db.querySync { conn ->
                val hours = LongArray(24)
                var currentTotal = 0L
                conn.prepareStatement(
                    "SELECT `hours_csv`, `total_ms` FROM `$historyTable` WHERE `uuid` = ? AND `date_int` = ? LIMIT 1;"
                ).use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.setInt(2, dateInt)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            parseHoursCsv(rs.getString("hours_csv"), hours)
                            currentTotal = rs.getLong("total_ms")
                        }
                    }
                }
                hours[hourIdx] += delta
                val newTotal = currentTotal + delta
                val csv = hours.joinToString(",")
                upsertDailyHistory(conn, uuid.toString(), dateInt, csv, newTotal)
            }
        }
    }

    fun clearSessionTrack(uuid: UUID) {
        sessionHourTrack.remove(uuid)
    }

    fun invalidateCache() {
        cachedResponse = null
        lastCacheEpochSeconds = 0L
    }

    fun getSnapshot(forceRefresh: Boolean = false): StatsApiResponse {
        val now = Instant.now().epochSecond
        val current = cachedResponse
        if (!forceRefresh && current != null && (now - lastCacheEpochSeconds) < cacheTtlSeconds) {
            return current
        }
        synchronized(lock) {
            val recheck = cachedResponse
            val nowInside = Instant.now().epochSecond
            if (!forceRefresh && recheck != null && (nowInside - lastCacheEpochSeconds) < cacheTtlSeconds) {
                return recheck
            }
            val fresh = collectStats()
            cachedResponse = fresh
            lastCacheEpochSeconds = Instant.now().epochSecond
            return fresh
        }
    }

    fun getLastCachedSnapshot(): StatsApiResponse? = cachedResponse

    fun queryPlayers(query: String?, forceRefresh: Boolean = false): StatsApiResponse {
        val snapshot = getSnapshot(forceRefresh)
        if (query.isNullOrBlank()) return snapshot
        val q = query.trim().lowercase()
        val filtered = snapshot.players.filter { p ->
            p.username.lowercase().contains(q) ||
                    (p.nickname?.lowercase()?.contains(q) == true) ||
                    (p.displayName?.lowercase()?.contains(q) == true) ||
                    p.uuid.lowercase().contains(q) ||
                    p.rank.lowercase().contains(q) ||
                    p.primaryGroup.lowercase().contains(q)
        }
        return StatsApiResponse(snapshot.overview, filtered)
    }

    fun findPlayer(idOrName: String, forceRefresh: Boolean = false): PlayerSummary? {
        val needle = idOrName.trim()
        if (needle.isEmpty()) return null
        val snapshot = getSnapshot(forceRefresh)
        return snapshot.players.firstOrNull {
            it.uuid.equals(needle, ignoreCase = true) || it.username.equals(needle, ignoreCase = true)
        }
    }

    private data class UserRow(
        val uuid: UUID,
        val username: String,
        val lastLogin: Long,
        val lastLogout: Long,
    )

    private data class PlayTimeRow(
        val username: String,
        val totalMillis: Long,
        val dailyMillis: Long,
        val dayKey: String,
        val currentRank: String,
    )

    private fun collectStats(): StatsApiResponse {
        val usersTable = db.table("users")
        val economyTable = db.table("economy")
        val playtimeTable = db.table("playtime")
        val chatTable = db.table("chat_profiles")

        val userRows = LinkedHashMap<UUID, UserRow>()
        val balances = HashMap<UUID, Double>()
        val economyNames = HashMap<UUID, String>()
        val playtimes = HashMap<UUID, PlayTimeRow>()
        val nicknames = HashMap<UUID, String>()
        val dailyHistoryByUuid = HashMap<UUID, MutableList<PlaytimeDayRecord>>()

        db.querySync { conn ->
            loadUsers(conn, usersTable, userRows)
            loadBalances(conn, economyTable, balances, economyNames)
            loadPlaytimes(conn, playtimeTable, playtimes)
            loadNicknames(conn, chatTable, nicknames)
            loadDailyHistory(conn, dailyHistoryByUuid)
        }

        val onlineMap = onlinePlayersProvider()
        val allUuids = LinkedHashSet<UUID>()
        allUuids += userRows.keys
        allUuids += economyNames.keys
        allUuids += playtimes.keys
        allUuids += onlineMap.keys

        val worldDir = worldDirProvider()
        val rankDist = LinkedHashMap<String, Int>()
        val summaries = ArrayList<PlayerSummary>(allUuids.size)

        var onlineCount = 0
        var totalBalance = 0.0
        var totalPlayTimeMs = 0L
        var totalPower = 0L
        var totalClaims = 0
        var totalClaimedArea = 0L
        var totalMined = 0L
        var totalMobKills = 0L
        var totalDistCm = 0L
        var totalAdv = 0L

        val todayStr = LocalDate.now().toString()
        val todayInt = todayStr.replace("-", "").toIntOrNull() ?: 0

        for (uuid in allUuids) {
            val user = userRows[uuid]
            val online = onlineMap[uuid]
            val pt = playtimes[uuid]

            val username = online?.username
                ?: user?.username
                ?: pt?.username
                ?: economyNames[uuid]
                ?: uuid.toString().take(8)

            val rawNick = nicknames[uuid]?.takeIf { it.isNotBlank() }
            val cleanNick = rawNick?.let { WorldDataReader.stripMinecraftCodes(it) }?.takeIf { it.isNotBlank() }
            val cleanDisplay =
                online?.displayName?.let { WorldDataReader.stripMinecraftCodes(it) }?.takeIf { it.isNotBlank() }

            val lastLogin = user?.lastLogin ?: 0L
            val lastLogout = user?.lastLogout ?: 0L
            val isOnline = online != null || (lastLogin > 0L && lastLogin > lastLogout)
            if (isOnline) onlineCount++

            val rawRank = online?.currentRank ?: pt?.currentRank ?: "default"
            val resolvedRank = rankService?.getRank(rawRank)?.displayName ?: rawRank
            val cleanRank = WorldDataReader.stripMinecraftCodes(resolvedRank).ifBlank { rawRank.ifBlank { "default" } }
            val primaryGroup = online?.primaryGroup?.takeIf { it.isNotBlank() } ?: cleanRank
            rankDist[cleanRank] = (rankDist[cleanRank] ?: 0) + 1

            val balance = balances[uuid] ?: 0.0
            val totalPt = maxOf(online?.totalPlayTimeMs ?: 0L, pt?.totalMillis ?: 0L)
            val dailyPt = maxOf(online?.dailyPlayTimeMs ?: 0L, if (pt?.dayKey == todayStr) pt.dailyMillis else 0L)

            val history = buildPlayerHistory(dailyHistoryByUuid[uuid], todayInt, todayStr, dailyPt)
            val hourlyDist = LongArray(24)
            for (day in history) {
                for (h in 0 until minOf(24, day.hours.size)) {
                    hourlyDist[h] += day.hours[h]
                }
            }

            val uuidStr = uuid.toString()
            val skills = skillsProvider(uuid, username, isOnline)
            val powerLevel = skills.values.sumOf { it.level.toLong() }
            val specialty = WorldDataReader.computeSpecialty(skills)
            val claims = claimsProvider(uuid)
            val vanillaStats = WorldDataReader.readVanillaStats(worldDir, uuidStr)
            val advancements = WorldDataReader.readAdvancements(worldDir, uuidStr)

            totalBalance += balance
            totalPlayTimeMs += totalPt
            totalPower += powerLevel
            totalClaims += claims.claimCount
            totalClaimedArea += claims.items.sumOf { it.area.toLong() }
            totalMined += vanillaStats.totalMined
            totalMobKills += vanillaStats.mobKills
            totalDistCm += vanillaStats.totalDistanceCm
            totalAdv += advancements.totalCompleted

            summaries += PlayerSummary(
                uuid = uuidStr,
                username = username,
                nickname = cleanNick,
                displayName = cleanDisplay ?: cleanNick ?: username,
                primaryGroup = primaryGroup,
                rank = cleanRank,
                balance = balance,
                totalPlayTimeMs = totalPt,
                lastLoginTime = lastLogin,
                lastLogoffTime = lastLogout,
                isOnline = isOnline,
                votes = 0L,
                powerLevel = powerLevel,
                skills = skills,
                specialty = specialty,
                claims = claims,
                playtimeHistory = history,
                hourlyDistribution = hourlyDist.toList(),
                vanillaStats = vanillaStats,
                advancements = advancements,
            )
        }

        summaries.sortByDescending { it.totalPlayTimeMs }

        val overview = ServerOverview(
            totalPlayers = summaries.size,
            onlinePlayers = onlineCount,
            totalBalance = totalBalance,
            totalPlayTimeMs = totalPlayTimeMs,
            totalPowerLevel = totalPower,
            totalClaims = totalClaims,
            totalClaimedArea = totalClaimedArea,
            totalMinedBlocks = totalMined,
            totalMobKills = totalMobKills,
            totalDistanceMeters = totalDistCm / 100L,
            totalAdvancements = totalAdv,
            rankDistribution = rankDist,
            queriedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            sourceMode = sourceMode,
        )

        return StatsApiResponse(overview, summaries)
    }

    private fun buildPlayerHistory(
        recorded: List<PlaytimeDayRecord>?,
        todayInt: Int,
        todayStr: String,
        todayDailyMs: Long,
    ): List<PlaytimeDayRecord> {
        val byDate = LinkedHashMap<Int, PlaytimeDayRecord>()
        if (recorded != null) {
            for (r in recorded) byDate[r.dateInt] = r
        }
        if (todayInt > 0 && todayDailyMs > 0L) {
            val existing = byDate[todayInt]
            if (existing == null) {
                val hours = MutableList(24) { 0L }
                val currentHour = Instant.now().atZone(ZoneId.systemDefault()).hour.coerceIn(0, 23)
                hours[currentHour] = todayDailyMs
                byDate[todayInt] = PlaytimeDayRecord(todayInt, todayStr, hours, todayDailyMs)
            } else if (todayDailyMs > existing.totalMs) {
                byDate[todayInt] = existing.copy(totalMs = todayDailyMs)
            }
        }
        return byDate.values.sortedBy { it.dateInt }
    }

    private fun loadUsers(conn: Connection, table: String, out: MutableMap<UUID, UserRow>) {
        runCatching {
            conn.prepareStatement("SELECT `uuid`, `username`, `last_login`, `last_logout` FROM `$table`;").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val uuid = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: continue
                        out[uuid] = UserRow(
                            uuid = uuid,
                            username = rs.getString("username") ?: uuid.toString().take(8),
                            lastLogin = rs.getLong("last_login"),
                            lastLogout = rs.getLong("last_logout"),
                        )
                    }
                }
            }
        }.onFailure { logger?.fine("Stats loadUsers skipped: ${it.message}") }
    }

    private fun loadBalances(
        conn: Connection,
        table: String,
        balances: MutableMap<UUID, Double>,
        names: MutableMap<UUID, String>,
    ) {
        runCatching {
            conn.prepareStatement("SELECT `uuid`, `username`, `balance` FROM `$table`;").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val uuid = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: continue
                        balances[uuid] = rs.getDouble("balance")
                        rs.getString("username")?.takeIf { it.isNotBlank() }?.let { names[uuid] = it }
                    }
                }
            }
        }.onFailure { logger?.fine("Stats loadBalances skipped: ${it.message}") }
    }

    private fun loadPlaytimes(conn: Connection, table: String, out: MutableMap<UUID, PlayTimeRow>) {
        runCatching {
            conn.prepareStatement(
                "SELECT `uuid`, `username`, `total_millis`, `daily_millis`, `day_key`, `current_rank` FROM `$table`;"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val uuid = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: continue
                        out[uuid] = PlayTimeRow(
                            username = rs.getString("username") ?: uuid.toString().take(8),
                            totalMillis = rs.getLong("total_millis"),
                            dailyMillis = rs.getLong("daily_millis"),
                            dayKey = rs.getString("day_key") ?: "",
                            currentRank = rs.getString("current_rank") ?: "default",
                        )
                    }
                }
            }
        }.onFailure { logger?.fine("Stats loadPlaytimes skipped: ${it.message}") }
    }

    private fun loadNicknames(conn: Connection, table: String, out: MutableMap<UUID, String>) {
        runCatching {
            conn.prepareStatement("SELECT `uuid`, `nickname` FROM `$table` WHERE `nickname` IS NOT NULL;").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val uuid = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: continue
                        val nick = rs.getString("nickname")
                        if (!nick.isNullOrBlank()) out[uuid] = nick
                    }
                }
            }
        }.onFailure { logger?.fine("Stats loadNicknames skipped: ${it.message}") }
    }

    private fun loadDailyHistory(conn: Connection, out: MutableMap<UUID, MutableList<PlaytimeDayRecord>>) {
        runCatching {
            conn.prepareStatement(
                "SELECT `uuid`, `date_int`, `hours_csv`, `total_ms` FROM `$historyTable` ORDER BY `date_int` ASC;"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val uuid = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: continue
                        val dateInt = rs.getInt("date_int")
                        val hours = LongArray(24)
                        parseHoursCsv(rs.getString("hours_csv"), hours)
                        val totalMs = rs.getLong("total_ms")
                        out.getOrPut(uuid) { mutableListOf() } += PlaytimeDayRecord(
                            dateInt = dateInt,
                            dateFormatted = formatDateInt(dateInt),
                            hours = hours.toList(),
                            totalMs = totalMs,
                        )
                    }
                }
            }
        }.onFailure { logger?.fine("Stats loadDailyHistory skipped: ${it.message}") }
    }

    private fun upsertDailyHistory(conn: Connection, uuid: String, dateInt: Int, hoursCsv: String, totalMs: Long) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$historyTable` (`uuid`, `date_int`, `hours_csv`, `total_ms`)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(`uuid`, `date_int`) DO UPDATE SET
                    `hours_csv` = excluded.`hours_csv`,
                    `total_ms` = excluded.`total_ms`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$historyTable` (`uuid`, `date_int`, `hours_csv`, `total_ms`)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `hours_csv` = VALUES(`hours_csv`),
                    `total_ms` = VALUES(`total_ms`);
            """.trimIndent()
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid)
            ps.setInt(2, dateInt)
            ps.setString(3, hoursCsv)
            ps.setLong(4, totalMs)
            ps.executeUpdate()
        }
    }

    private fun parseHoursCsv(csv: String?, out: LongArray) {
        if (csv.isNullOrBlank()) return
        val parts = csv.split(',')
        for (i in 0 until minOf(24, parts.size)) {
            out[i] = parts[i].trim().toLongOrNull() ?: 0L
        }
    }

    private fun formatDateInt(dateInt: Int): String {
        val s = dateInt.toString()
        return if (s.length == 8) "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}" else s
    }

    companion object {
        fun zeroSkills(): Map<String, PlayerSkillStat> {
            val map = LinkedHashMap<String, PlayerSkillStat>(SkillMeta.ALL.size)
            for (meta in SkillMeta.ALL) {
                map[meta.key] = PlayerSkillStat(0, 0, 0)
            }
            return map
        }

        private fun resolveDefaultWorldDir(): Path? {
            return runCatching {
                val primary = Bukkit.getWorlds().firstOrNull()?.worldFolder?.toPath()
                if (primary != null && Files.isDirectory(primary)) primary else null
            }.getOrNull()
        }

        private fun collectOnlineSnapshots(
            rankService: RankService?,
            luckPermsHook: LuckPermsHook?,
        ): Map<UUID, OnlinePlayerSnapshot> {
            return runCatching {
                val map = LinkedHashMap<UUID, OnlinePlayerSnapshot>()
                for (player: Player in Bukkit.getOnlinePlayers()) {
                    val ptSnapshot = rankService?.getOrLoadSnapshot(player.uniqueId, player.name)
                    map[player.uniqueId] = OnlinePlayerSnapshot(
                        uuid = player.uniqueId,
                        username = player.name,
                        displayName = runCatching {
                            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(player.displayName())
                        }.getOrDefault(player.name),
                        primaryGroup = luckPermsHook?.getPrimaryGroup(player),
                        currentRank = ptSnapshot?.currentRank,
                        totalPlayTimeMs = ptSnapshot?.totalMillis,
                        dailyPlayTimeMs = ptSnapshot?.dailyMillis,
                        dayKey = ptSnapshot?.dayKey,
                    )
                }
                map
            }.getOrDefault(emptyMap())
        }

        private fun collectExternalMcmmoSkills(uuid: UUID, isOnline: Boolean): Map<String, PlayerSkillStat> {
            val skills = zeroSkills().toMutableMap()
            if (!isOnline) return skills
            runCatching {
                if (!Bukkit.getPluginManager().isPluginEnabled("mcMMO")) return skills
                val player = Bukkit.getPlayer(uuid) ?: return skills
                val expApi = Class.forName("com.gmail.nossr50.api.ExperienceAPI")
                val getLevel = expApi.getMethod("getLevel", Player::class.java, String::class.java)
                val getXp = expApi.getMethod("getXP", Player::class.java, String::class.java)
                val getXpToNext = expApi.getMethod("getXPToNextLevel", Player::class.java, String::class.java)

                for (meta in SkillMeta.ALL) {
                    val skillName = meta.key.uppercase()
                    val level = (getLevel.invoke(null, player, skillName) as? Number)?.toInt() ?: 0
                    val xp = (getXp.invoke(null, player, skillName) as? Number)?.toInt() ?: 0
                    val xpToNext = (getXpToNext.invoke(null, player, skillName) as? Number)?.toInt() ?: 0
                    skills[meta.key] = PlayerSkillStat(level, xp, xpToNext)
                }
            }
            return skills
        }

        private fun collectExternalClaims(@Suppress("UNUSED_PARAMETER") uuid: UUID): PlayerClaims {
            // Optional GriefPrevention hook can be extended here if present; defaults to EMPTY cleanly.
            return PlayerClaims.EMPTY
        }
    }
}
