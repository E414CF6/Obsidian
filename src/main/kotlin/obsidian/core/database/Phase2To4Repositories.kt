package obsidian.core.database

import java.sql.ResultSet
import java.sql.Types
import java.util.*

// --- Records ---

data class EconomyRecord(
    val uuid: UUID,
    val username: String,
    val balance: Double,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class WorthRecord(
    val material: String,
    val sellPrice: Double,
    val buyPrice: Double? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class PlayTimeRecord(
    val uuid: UUID,
    val username: String,
    val totalMillis: Long = 0L,
    val dailyMillis: Long = 0L,
    val dayKey: String = "",
    val currentRank: String = "default",
    val claimedRewards: Set<String> = emptySet(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class KitClaimRecord(
    val uuid: UUID,
    val kitName: String,
    val claimCount: Int = 0,
    val lastClaimedAt: Long = 0L,
)

enum class PunishmentType {
    BAN,
    IP_BAN,
    MUTE,
    IP_MUTE,
    SHADOW_MUTE,
    WARN,
    KICK,
    JAIL,
}

data class PunishmentRecord(
    val id: String = UUID.randomUUID().toString(),
    val type: PunishmentType,
    val targetUuid: UUID? = null,
    val targetName: String? = null,
    val targetIp: String? = null,
    val actorUuid: UUID? = null,
    val actorName: String = "CONSOLE",
    val reason: String = "",
    val jailName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val active: Boolean = true,
    val removedByUuid: UUID? = null,
    val removedByName: String? = null,
    val removedAt: Long? = null,
    val removedReason: String? = null,
    val silent: Boolean = false,
    val templateId: String? = null,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && expiresAt <= now
}

data class NameHistoryRecord(
    val id: Long = 0,
    val uuid: UUID,
    val username: String,
    val changedAt: Long,
)

data class PunishmentAllowRecord(
    val uuid: UUID,
    val addedBy: String,
    val createdAt: Long,
)

data class PlayerIpRecord(
    val uuid: UUID,
    val ip: String,
    val username: String,
    val lastSeen: Long,
)

data class SavedInventoryRecord(
    val id: String,
    val ownerUuid: UUID,
    val name: String,
    val payloadBase64: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class ChatProfileRecord(
    val uuid: UUID,
    val nickname: String? = null,
    val msgToggle: Boolean = true,
    val socialSpy: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

// --- Repositories ---

class EconomyRepository(private val db: DatabaseManager) {

    private val economyTable = db.table("economy")
    private val worthTable = db.table("worth")

    suspend fun findByUuid(uuid: UUID): EconomyRecord? = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$economyTable` WHERE `uuid` = ? LIMIT 1;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                if (rs.next()) mapEconomy(rs) else null
            }
        }
    }

    suspend fun findByName(username: String): EconomyRecord? = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$economyTable` WHERE LOWER(`username`) = LOWER(?) LIMIT 1;").use { ps ->
            ps.setString(1, username)
            ps.executeQuery().use { rs ->
                if (rs.next()) mapEconomy(rs) else null
            }
        }
    }

    suspend fun upsertAll(records: Collection<EconomyRecord>) {
        if (records.isEmpty()) return
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$economyTable` (`uuid`, `username`, `balance`, `updated_at`)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(`uuid`) DO UPDATE SET
                    `username` = excluded.`username`,
                    `balance` = excluded.`balance`,
                    `updated_at` = excluded.`updated_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$economyTable` (`uuid`, `username`, `balance`, `updated_at`)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `username` = VALUES(`username`),
                    `balance` = VALUES(`balance`),
                    `updated_at` = VALUES(`updated_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(sql).use { ps ->
                    for (r in records) {
                        ps.setString(1, r.uuid.toString())
                        ps.setString(2, r.username)
                        ps.setDouble(3, r.balance)
                        ps.setLong(4, r.updatedAt)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Throwable) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
    }

    suspend fun topBalances(limit: Int): List<EconomyRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$economyTable` ORDER BY `balance` DESC LIMIT ?;").use { ps ->
            ps.setInt(1, limit.coerceAtLeast(1))
            ps.executeQuery().use { rs ->
                val list = mutableListOf<EconomyRecord>()
                while (rs.next()) list += mapEconomy(rs)
                list
            }
        }
    }

    suspend fun findAllWorth(): List<WorthRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$worthTable`;").use { ps ->
            ps.executeQuery().use { rs ->
                val list = mutableListOf<WorthRecord>()
                while (rs.next()) {
                    val buyRaw = rs.getDouble("buy_price")
                    val buy = if (rs.wasNull()) null else buyRaw
                    list += WorthRecord(
                        material = rs.getString("material"),
                        sellPrice = rs.getDouble("sell_price"),
                        buyPrice = buy,
                        updatedAt = rs.getLong("updated_at"),
                    )
                }
                list
            }
        }
    }

    suspend fun upsertWorth(record: WorthRecord) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$worthTable` (`material`, `sell_price`, `buy_price`, `updated_at`)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(`material`) DO UPDATE SET
                    `sell_price` = excluded.`sell_price`,
                    `buy_price` = excluded.`buy_price`,
                    `updated_at` = excluded.`updated_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$worthTable` (`material`, `sell_price`, `buy_price`, `updated_at`)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `sell_price` = VALUES(`sell_price`),
                    `buy_price` = VALUES(`buy_price`),
                    `updated_at` = VALUES(`updated_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, record.material.uppercase())
                ps.setDouble(2, record.sellPrice)
                if (record.buyPrice != null) ps.setDouble(3, record.buyPrice) else ps.setNull(3, Types.DOUBLE)
                ps.setLong(4, record.updatedAt)
                ps.executeUpdate()
            }
        }
    }

    suspend fun deleteWorth(material: String): Boolean = db.query { conn ->
        conn.prepareStatement("DELETE FROM `$worthTable` WHERE `material` = ?;").use { ps ->
            ps.setString(1, material.uppercase())
            ps.executeUpdate() > 0
        }
    }

    private fun mapEconomy(rs: ResultSet): EconomyRecord = EconomyRecord(
        uuid = UUID.fromString(rs.getString("uuid")),
        username = rs.getString("username"),
        balance = rs.getDouble("balance"),
        updatedAt = rs.getLong("updated_at"),
    )
}

class PlayTimeRepository(private val db: DatabaseManager) {

    private val table = db.table("playtime")

    suspend fun findByUuid(uuid: UUID): PlayTimeRecord? = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` WHERE `uuid` = ? LIMIT 1;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                if (rs.next()) mapRecord(rs) else null
            }
        }
    }

    suspend fun upsertAll(records: Collection<PlayTimeRecord>) {
        if (records.isEmpty()) return
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$table` (`uuid`, `username`, `total_millis`, `daily_millis`, `day_key`, `current_rank`, `claimed_rewards`, `updated_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(`uuid`) DO UPDATE SET
                    `username` = excluded.`username`,
                    `total_millis` = excluded.`total_millis`,
                    `daily_millis` = excluded.`daily_millis`,
                    `day_key` = excluded.`day_key`,
                    `current_rank` = excluded.`current_rank`,
                    `claimed_rewards` = excluded.`claimed_rewards`,
                    `updated_at` = excluded.`updated_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$table` (`uuid`, `username`, `total_millis`, `daily_millis`, `day_key`, `current_rank`, `claimed_rewards`, `updated_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `username` = VALUES(`username`),
                    `total_millis` = VALUES(`total_millis`),
                    `daily_millis` = VALUES(`daily_millis`),
                    `day_key` = VALUES(`day_key`),
                    `current_rank` = VALUES(`current_rank`),
                    `claimed_rewards` = VALUES(`claimed_rewards`),
                    `updated_at` = VALUES(`updated_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(sql).use { ps ->
                    for (r in records) {
                        ps.setString(1, r.uuid.toString())
                        ps.setString(2, r.username)
                        ps.setLong(3, r.totalMillis)
                        ps.setLong(4, r.dailyMillis)
                        ps.setString(5, r.dayKey)
                        ps.setString(6, r.currentRank)
                        ps.setString(7, r.claimedRewards.joinToString(","))
                        ps.setLong(8, r.updatedAt)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Throwable) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
    }

    suspend fun topTotal(limit: Int): List<PlayTimeRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` ORDER BY `total_millis` DESC LIMIT ?;").use { ps ->
            ps.setInt(1, limit.coerceAtLeast(1))
            ps.executeQuery().use { rs ->
                val list = mutableListOf<PlayTimeRecord>()
                while (rs.next()) list += mapRecord(rs)
                list
            }
        }
    }

    private fun mapRecord(rs: ResultSet): PlayTimeRecord {
        val rawRewards = rs.getString("claimed_rewards") ?: ""
        val rewards = rawRewards.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        return PlayTimeRecord(
            uuid = UUID.fromString(rs.getString("uuid")),
            username = rs.getString("username"),
            totalMillis = rs.getLong("total_millis"),
            dailyMillis = rs.getLong("daily_millis"),
            dayKey = rs.getString("day_key"),
            currentRank = rs.getString("current_rank"),
            claimedRewards = rewards,
            updatedAt = rs.getLong("updated_at"),
        )
    }
}

class KitClaimRepository(private val db: DatabaseManager) {

    private val table = db.table("kit_claims")

    suspend fun findByUuid(uuid: UUID): Map<String, KitClaimRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` WHERE `uuid` = ?;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                val map = mutableMapOf<String, KitClaimRecord>()
                while (rs.next()) {
                    val rec = KitClaimRecord(
                        uuid = uuid,
                        kitName = rs.getString("kit_name"),
                        claimCount = rs.getInt("claim_count"),
                        lastClaimedAt = rs.getLong("last_claimed_at"),
                    )
                    map[rec.kitName.lowercase()] = rec
                }
                map
            }
        }
    }

    suspend fun upsert(record: KitClaimRecord) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$table` (`uuid`, `kit_name`, `claim_count`, `last_claimed_at`)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(`uuid`, `kit_name`) DO UPDATE SET
                    `claim_count` = excluded.`claim_count`,
                    `last_claimed_at` = excluded.`last_claimed_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$table` (`uuid`, `kit_name`, `claim_count`, `last_claimed_at`)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `claim_count` = VALUES(`claim_count`),
                    `last_claimed_at` = VALUES(`last_claimed_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, record.uuid.toString())
                ps.setString(2, record.kitName.lowercase())
                ps.setInt(3, record.claimCount)
                ps.setLong(4, record.lastClaimedAt)
                ps.executeUpdate()
            }
        }
    }
}

class PunishmentRepository(private val db: DatabaseManager) {

    private val punishmentsTable = db.table("punishments")
    private val ipsTable = db.table("player_ips")
    private val nameHistoryTable = db.table("name_history")
    private val allowTable = db.table("punishment_allow")

    suspend fun insert(record: PunishmentRecord) {
        val sql = """
            INSERT INTO `$punishmentsTable` (
                `id`, `type`, `target_uuid`, `target_name`, `target_ip`, `actor_uuid`, `actor_name`,
                `reason`, `jail_name`, `created_at`, `expires_at`, `active`,
                `removed_by_uuid`, `removed_by_name`, `removed_at`, `removed_reason`, `silent`, `template_id`
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """.trimIndent()
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, record.id)
                ps.setString(2, record.type.name)
                ps.setString(3, record.targetUuid?.toString())
                ps.setString(4, record.targetName)
                ps.setString(5, record.targetIp)
                ps.setString(6, record.actorUuid?.toString())
                ps.setString(7, record.actorName)
                ps.setString(8, record.reason)
                ps.setString(9, record.jailName)
                ps.setLong(10, record.createdAt)
                if (record.expiresAt != null) ps.setLong(11, record.expiresAt) else ps.setNull(11, Types.BIGINT)
                ps.setBoolean(12, record.active)
                ps.setString(13, record.removedByUuid?.toString())
                ps.setString(14, record.removedByName)
                if (record.removedAt != null) ps.setLong(15, record.removedAt) else ps.setNull(15, Types.BIGINT)
                ps.setString(16, record.removedReason)
                ps.setBoolean(17, record.silent)
                ps.setString(18, record.templateId)
                ps.executeUpdate()
            }
        }
    }

    suspend fun deactivate(
        id: String,
        actorUuid: UUID? = null,
        actorName: String? = null,
        reason: String? = null,
    ) {
        val now = System.currentTimeMillis()
        db.query { conn ->
            conn.prepareStatement(
                "UPDATE `$punishmentsTable` SET `active` = FALSE, `removed_by_uuid` = ?, `removed_by_name` = ?, `removed_at` = ?, `removed_reason` = ? WHERE `id` = ?;"
            ).use { ps ->
                ps.setString(1, actorUuid?.toString())
                ps.setString(2, actorName)
                ps.setLong(3, now)
                ps.setString(4, reason)
                ps.setString(5, id)
                ps.executeUpdate()
            }
        }
    }

    suspend fun deactivateForTarget(
        type: PunishmentType,
        targetUuid: UUID?,
        targetIp: String? = null,
        actorUuid: UUID? = null,
        actorName: String? = null,
        reason: String? = null,
    ): Int = db.query { conn ->
        var changed = 0
        val now = System.currentTimeMillis()
        if (targetUuid != null) {
            conn.prepareStatement(
                "UPDATE `$punishmentsTable` SET `active` = FALSE, `removed_by_uuid` = ?, `removed_by_name` = ?, `removed_at` = ?, `removed_reason` = ? WHERE `type` = ? AND `target_uuid` = ? AND `active` = TRUE;"
            ).use { ps ->
                ps.setString(1, actorUuid?.toString())
                ps.setString(2, actorName)
                ps.setLong(3, now)
                ps.setString(4, reason)
                ps.setString(5, type.name)
                ps.setString(6, targetUuid.toString())
                changed += ps.executeUpdate()
            }
        }
        if (targetIp != null) {
            conn.prepareStatement(
                "UPDATE `$punishmentsTable` SET `active` = FALSE, `removed_by_uuid` = ?, `removed_by_name` = ?, `removed_at` = ?, `removed_reason` = ? WHERE `type` = ? AND `target_ip` = ? AND `active` = TRUE;"
            ).use { ps ->
                ps.setString(1, actorUuid?.toString())
                ps.setString(2, actorName)
                ps.setLong(3, now)
                ps.setString(4, reason)
                ps.setString(5, type.name)
                ps.setString(6, targetIp)
                changed += ps.executeUpdate()
            }
        }
        changed
    }

    suspend fun updatePunishment(
        id: String,
        reason: String? = null,
        expiresAt: Long? = null,
        silent: Boolean? = null,
    ): Boolean = db.query { conn ->
        val sql =
            "UPDATE `$punishmentsTable` SET `reason` = COALESCE(?, `reason`), `expires_at` = COALESCE(?, `expires_at`), `silent` = COALESCE(?, `silent`) WHERE `id` = ? AND `active` = TRUE;"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, reason)
            if (expiresAt != null) ps.setLong(2, expiresAt) else ps.setNull(2, Types.BIGINT)
            if (silent != null) ps.setBoolean(3, silent) else ps.setNull(3, Types.BOOLEAN)
            ps.setString(4, id)
            ps.executeUpdate() > 0
        }
    }

    suspend fun findActive(targetUuid: UUID?, targetIp: String?, vararg types: PunishmentType): List<PunishmentRecord> {
        if (types.isEmpty()) return emptyList()
        val typePlaceholders = types.joinToString(",") { "?" }
        val sql = buildString {
            append("SELECT * FROM `$punishmentsTable` WHERE `active` = TRUE AND `type` IN ($typePlaceholders) AND (")
            val conditions = mutableListOf<String>()
            if (targetUuid != null) conditions += "`target_uuid` = ?"
            if (targetIp != null) conditions += "`target_ip` = ?"
            if (conditions.isEmpty()) return emptyList()
            append(conditions.joinToString(" OR "))
            append(") ORDER BY `created_at` DESC;")
        }
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                for (t in types) ps.setString(idx++, t.name)
                if (targetUuid != null) ps.setString(idx++, targetUuid.toString())
                if (targetIp != null) ps.setString(idx, targetIp)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<PunishmentRecord>()
                    while (rs.next()) list += mapPunishment(rs)
                    list
                }
            }
        }
    }

    suspend fun findActiveByNameOrIp(query: String, vararg types: PunishmentType): List<PunishmentRecord> {
        if (types.isEmpty()) return emptyList()
        val typePlaceholders = types.joinToString(",") { "?" }
        val sql =
            "SELECT * FROM `$punishmentsTable` WHERE `active` = TRUE AND `type` IN ($typePlaceholders) AND (LOWER(`target_name`) = LOWER(?) OR `target_ip` = ?) ORDER BY `created_at` DESC;"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                for (t in types) ps.setString(idx++, t.name)
                ps.setString(idx++, query)
                ps.setString(idx, query)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<PunishmentRecord>()
                    while (rs.next()) list += mapPunishment(rs)
                    list
                }
            }
        }
    }

    suspend fun findActiveByIpWildcard(ip: String, vararg types: PunishmentType): List<PunishmentRecord> {
        if (types.isEmpty()) return emptyList()
        val typePlaceholders = types.joinToString(",") { "?" }
        val sql =
            "SELECT * FROM `$punishmentsTable` WHERE `active` = TRUE AND `type` IN ($typePlaceholders) AND `target_ip` IS NOT NULL ORDER BY `created_at` DESC;"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                for (t in types) ps.setString(idx++, t.name)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<PunishmentRecord>()
                    while (rs.next()) {
                        val rec = mapPunishment(rs)
                        val targetIp = rec.targetIp
                        if (targetIp != null && ipMatches(targetIp, ip)) {
                            list += rec
                        }
                    }
                    list
                }
            }
        }
    }

    fun ipMatches(pattern: String, ip: String): Boolean {
        if (pattern == ip) return true
        if (!pattern.contains("*")) return false
        val regexPattern = "^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$"
        return Regex(regexPattern).matches(ip)
    }

    suspend fun findHistory(
        targetUuid: UUID? = null,
        targetIp: String? = null,
        limit: Int = 10,
        offset: Int = 0,
        type: PunishmentType? = null,
    ): List<PunishmentRecord> {
        val conditions = mutableListOf<String>()
        if (targetUuid != null) conditions += "`target_uuid` = ?"
        if (targetIp != null) conditions += "`target_ip` = ?"
        if (type != null) conditions += "`type` = ?"
        val where = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        val sql = "SELECT * FROM `$punishmentsTable` $where ORDER BY `created_at` DESC LIMIT ? OFFSET ?;"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (targetUuid != null) ps.setString(idx++, targetUuid.toString())
                if (targetIp != null) ps.setString(idx++, targetIp)
                if (type != null) ps.setString(idx++, type.name)
                ps.setInt(idx++, limit)
                ps.setInt(idx, offset)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<PunishmentRecord>()
                    while (rs.next()) list += mapPunishment(rs)
                    list
                }
            }
        }
    }

    suspend fun countHistory(
        targetUuid: UUID? = null,
        targetIp: String? = null,
        type: PunishmentType? = null,
    ): Int {
        val conditions = mutableListOf<String>()
        if (targetUuid != null) conditions += "`target_uuid` = ?"
        if (targetIp != null) conditions += "`target_ip` = ?"
        if (type != null) conditions += "`type` = ?"
        val where = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        val sql = "SELECT COUNT(*) FROM `$punishmentsTable` $where;"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (targetUuid != null) ps.setString(idx++, targetUuid.toString())
                if (targetIp != null) ps.setString(idx++, targetIp)
                if (type != null) ps.setString(idx, type.name)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    suspend fun findStaffHistory(
        actorUuid: UUID?,
        actorName: String?,
        limit: Int = 10,
        offset: Int = 0,
    ): List<PunishmentRecord> {
        val conditions = mutableListOf<String>()
        if (actorUuid != null) conditions += "`actor_uuid` = ?"
        if (actorName != null) conditions += "LOWER(`actor_name`) = LOWER(?)"
        if (conditions.isEmpty()) return emptyList()
        val sql =
            "SELECT * FROM `$punishmentsTable` WHERE (${conditions.joinToString(" OR ")}) ORDER BY `created_at` DESC LIMIT ? OFFSET ?;"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (actorUuid != null) ps.setString(idx++, actorUuid.toString())
                if (actorName != null) ps.setString(idx++, actorName)
                ps.setInt(idx++, limit)
                ps.setInt(idx, offset)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<PunishmentRecord>()
                    while (rs.next()) list += mapPunishment(rs)
                    list
                }
            }
        }
    }

    suspend fun countStaffHistory(actorUuid: UUID?, actorName: String?): Int {
        val conditions = mutableListOf<String>()
        if (actorUuid != null) conditions += "`actor_uuid` = ?"
        if (actorName != null) conditions += "LOWER(`actor_name`) = LOWER(?)"
        if (conditions.isEmpty()) return 0
        val sql = "SELECT COUNT(*) FROM `$punishmentsTable` WHERE (${conditions.joinToString(" OR ")});"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (actorUuid != null) ps.setString(idx++, actorUuid.toString())
                if (actorName != null) ps.setString(idx, actorName)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    suspend fun rollbackByStaff(
        actorUuid: UUID?,
        actorName: String?,
        sinceMillis: Long,
        removedBy: String,
    ): Int {
        val now = System.currentTimeMillis()
        val conditions = mutableListOf<String>()
        if (actorUuid != null) conditions += "`actor_uuid` = ?"
        if (actorName != null) conditions += "LOWER(`actor_name`) = LOWER(?)"
        if (conditions.isEmpty()) return 0
        val sql =
            "UPDATE `$punishmentsTable` SET `active` = FALSE, `removed_by_name` = ?, `removed_at` = ?, `removed_reason` = 'Staff rollback' WHERE `active` = TRUE AND `created_at` >= ? AND (${
                conditions.joinToString(" OR ")
            });"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, removedBy)
                ps.setLong(idx++, now)
                ps.setLong(idx++, sinceMillis)
                if (actorUuid != null) ps.setString(idx++, actorUuid.toString())
                if (actorName != null) ps.setString(idx, actorName)
                ps.executeUpdate()
            }
        }
    }

    suspend fun pruneHistory(targetUuid: UUID? = null, targetIp: String? = null, beforeMillis: Long? = null): Int {
        val conditions = mutableListOf<String>()
        if (targetUuid != null) conditions += "`target_uuid` = ?"
        if (targetIp != null) conditions += "`target_ip` = ?"
        val targetCond = if (conditions.isNotEmpty()) "AND (${conditions.joinToString(" OR ")})" else ""
        val timeCond = if (beforeMillis != null) "AND `created_at` < ?" else ""
        val sql = "DELETE FROM `$punishmentsTable` WHERE `active` = FALSE $timeCond $targetCond;"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (beforeMillis != null) ps.setLong(idx++, beforeMillis)
                if (targetUuid != null) ps.setString(idx++, targetUuid.toString())
                if (targetIp != null) ps.setString(idx, targetIp)
                ps.executeUpdate()
            }
        }
    }

    suspend fun listActive(
        types: List<PunishmentType>,
        limit: Int = 10,
        offset: Int = 0,
    ): List<PunishmentRecord> {
        if (types.isEmpty()) return emptyList()
        val typePlaceholders = types.joinToString(",") { "?" }
        val sql =
            "SELECT * FROM `$punishmentsTable` WHERE `active` = TRUE AND `type` IN ($typePlaceholders) ORDER BY `created_at` DESC LIMIT ? OFFSET ?;"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                for (t in types) ps.setString(idx++, t.name)
                ps.setInt(idx++, limit)
                ps.setInt(idx, offset)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<PunishmentRecord>()
                    while (rs.next()) list += mapPunishment(rs)
                    list
                }
            }
        }
    }

    suspend fun countActive(types: List<PunishmentType>): Int {
        if (types.isEmpty()) return 0
        val typePlaceholders = types.joinToString(",") { "?" }
        val sql = "SELECT COUNT(*) FROM `$punishmentsTable` WHERE `active` = TRUE AND `type` IN ($typePlaceholders);"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                for (t in types) ps.setString(idx++, t.name)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    suspend fun countTemplateStrikes(
        targetUuid: UUID?,
        targetIp: String?,
        templateId: String,
        sinceMillis: Long,
        boundToIp: Boolean,
    ): Int {
        val conditions = mutableListOf<String>()
        if (targetUuid != null) conditions += "`target_uuid` = ?"
        if (boundToIp && targetIp != null) conditions += "`target_ip` = ?"
        if (conditions.isEmpty()) return 0
        val sql = "SELECT COUNT(*) FROM `$punishmentsTable` WHERE `template_id` = ? AND `created_at` >= ? AND (${
            conditions.joinToString(" OR ")
        });"
        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, templateId)
                ps.setLong(idx++, sinceMillis)
                if (targetUuid != null) ps.setString(idx++, targetUuid.toString())
                if (boundToIp && targetIp != null) ps.setString(idx, targetIp)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    suspend fun recordNameHistory(uuid: UUID, username: String, timestamp: Long = System.currentTimeMillis()) {
        val last = db.query { conn ->
            conn.prepareStatement("SELECT `username` FROM `$nameHistoryTable` WHERE `uuid` = ? ORDER BY `changed_at` DESC LIMIT 1;")
                .use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("username") else null
                    }
                }
        }
        if (last == null || !last.equals(username, ignoreCase = true)) {
            val sql = "INSERT INTO `$nameHistoryTable` (`uuid`, `username`, `changed_at`) VALUES (?, ?, ?);"
            db.query { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.setString(2, username)
                    ps.setLong(3, timestamp)
                    ps.executeUpdate()
                }
            }
        }
    }

    suspend fun findNameHistory(uuid: UUID): List<NameHistoryRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$nameHistoryTable` WHERE `uuid` = ? ORDER BY `changed_at` DESC;")
            .use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<NameHistoryRecord>()
                    while (rs.next()) {
                        list += NameHistoryRecord(
                            id = rs.getLong("id"),
                            uuid = UUID.fromString(rs.getString("uuid")),
                            username = rs.getString("username"),
                            changedAt = rs.getLong("changed_at"),
                        )
                    }
                    list
                }
            }
    }

    suspend fun findLastUuid(username: String): UUID? = db.query { conn ->
        conn.prepareStatement("SELECT `uuid` FROM `$nameHistoryTable` WHERE LOWER(`username`) = LOWER(?) ORDER BY `changed_at` DESC LIMIT 1;")
            .use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs ->
                    if (rs.next()) UUID.fromString(rs.getString("uuid")) else null
                }
            }
    }

    suspend fun addAllow(uuid: UUID, addedBy: String, timestamp: Long = System.currentTimeMillis()) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$allowTable` (`uuid`, `added_by`, `created_at`)
                VALUES (?, ?, ?)
                ON CONFLICT(`uuid`) DO UPDATE SET `added_by` = excluded.`added_by`, `created_at` = excluded.`created_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$allowTable` (`uuid`, `added_by`, `created_at`)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE `added_by` = VALUES(`added_by`), `created_at` = VALUES(`created_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, addedBy)
                ps.setLong(3, timestamp)
                ps.executeUpdate()
            }
        }
    }

    suspend fun removeAllow(uuid: UUID): Boolean = db.query { conn ->
        conn.prepareStatement("DELETE FROM `$allowTable` WHERE `uuid` = ?;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeUpdate() > 0
        }
    }

    suspend fun isAllowed(uuid: UUID): Boolean = db.query { conn ->
        conn.prepareStatement("SELECT 1 FROM `$allowTable` WHERE `uuid` = ? LIMIT 1;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    suspend fun recordPlayerIp(uuid: UUID, ip: String, username: String, timestamp: Long = System.currentTimeMillis()) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$ipsTable` (`uuid`, `ip`, `username`, `last_seen`)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(`uuid`, `ip`) DO UPDATE SET
                    `username` = excluded.`username`,
                    `last_seen` = excluded.`last_seen`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$ipsTable` (`uuid`, `ip`, `username`, `last_seen`)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `username` = VALUES(`username`),
                    `last_seen` = VALUES(`last_seen`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, ip)
                ps.setString(3, username)
                ps.setLong(4, timestamp)
                ps.executeUpdate()
            }
        }
    }

    suspend fun findIpsByUuid(uuid: UUID): List<PlayerIpRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$ipsTable` WHERE `uuid` = ? ORDER BY `last_seen` DESC;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                val list = mutableListOf<PlayerIpRecord>()
                while (rs.next()) list += mapIp(rs)
                list
            }
        }
    }

    suspend fun findAccountsByIp(ip: String): List<PlayerIpRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$ipsTable` WHERE `ip` = ? ORDER BY `last_seen` DESC;").use { ps ->
            ps.setString(1, ip)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<PlayerIpRecord>()
                while (rs.next()) list += mapIp(rs)
                list
            }
        }
    }

    private fun mapPunishment(rs: ResultSet): PunishmentRecord {
        val expRaw = rs.getLong("expires_at")
        val expiresAt = if (rs.wasNull()) null else expRaw
        val remRaw = runCatching { rs.getLong("removed_at") }.getOrNull() ?: 0L
        val removedAt = if (remRaw == 0L) null else remRaw
        val removedByUuid = runCatching { rs.getString("removed_by_uuid")?.let(UUID::fromString) }.getOrNull()
        val removedByName = runCatching { rs.getString("removed_by_name") }.getOrNull()
        val removedReason = runCatching { rs.getString("removed_reason") }.getOrNull()
        val silent = runCatching { rs.getBoolean("silent") }.getOrDefault(false)
        val templateId = runCatching { rs.getString("template_id") }.getOrNull()

        return PunishmentRecord(
            id = rs.getString("id"),
            type = PunishmentType.valueOf(rs.getString("type")),
            targetUuid = rs.getString("target_uuid")?.let(UUID::fromString),
            targetName = rs.getString("target_name"),
            targetIp = rs.getString("target_ip"),
            actorUuid = rs.getString("actor_uuid")?.let(UUID::fromString),
            actorName = rs.getString("actor_name"),
            reason = rs.getString("reason"),
            jailName = rs.getString("jail_name"),
            createdAt = rs.getLong("created_at"),
            expiresAt = expiresAt,
            active = rs.getBoolean("active"),
            removedByUuid = removedByUuid,
            removedByName = removedByName,
            removedAt = removedAt,
            removedReason = removedReason,
            silent = silent,
            templateId = templateId,
        )
    }

    private fun mapIp(rs: ResultSet): PlayerIpRecord = PlayerIpRecord(
        uuid = UUID.fromString(rs.getString("uuid")),
        ip = rs.getString("ip"),
        username = rs.getString("username"),
        lastSeen = rs.getLong("last_seen"),
    )
}

class SavedInventoryRepository(private val db: DatabaseManager) {

    private val table = db.table("saved_inventories")

    suspend fun upsert(record: SavedInventoryRecord) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$table` (`id`, `owner_uuid`, `name`, `payload_base64`, `created_at`)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(`id`) DO UPDATE SET
                    `name` = excluded.`name`,
                    `payload_base64` = excluded.`payload_base64`,
                    `created_at` = excluded.`created_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$table` (`id`, `owner_uuid`, `name`, `payload_base64`, `created_at`)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `name` = VALUES(`name`),
                    `payload_base64` = VALUES(`payload_base64`),
                    `created_at` = VALUES(`created_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, record.id)
                ps.setString(2, record.ownerUuid.toString())
                ps.setString(3, record.name)
                ps.setString(4, record.payloadBase64)
                ps.setLong(5, record.createdAt)
                ps.executeUpdate()
            }
        }
    }

    suspend fun findByOwner(ownerUuid: UUID): List<SavedInventoryRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` WHERE `owner_uuid` = ? ORDER BY `created_at` DESC;").use { ps ->
            ps.setString(1, ownerUuid.toString())
            ps.executeQuery().use { rs ->
                val list = mutableListOf<SavedInventoryRecord>()
                while (rs.next()) list += mapRecord(rs)
                list
            }
        }
    }

    suspend fun findByNameOrId(ownerUuid: UUID, nameOrId: String): SavedInventoryRecord? = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` WHERE `id` = ? OR (`owner_uuid` = ? AND LOWER(`name`) = LOWER(?)) ORDER BY `created_at` DESC LIMIT 1;")
            .use { ps ->
                ps.setString(1, nameOrId)
                ps.setString(2, ownerUuid.toString())
                ps.setString(3, nameOrId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRecord(rs) else null
                }
            }
    }

    private fun mapRecord(rs: ResultSet): SavedInventoryRecord = SavedInventoryRecord(
        id = rs.getString("id"),
        ownerUuid = UUID.fromString(rs.getString("owner_uuid")),
        name = rs.getString("name"),
        payloadBase64 = rs.getString("payload_base64"),
        createdAt = rs.getLong("created_at"),
    )
}

class ChatProfileRepository(private val db: DatabaseManager) {

    private val table = db.table("chat_profiles")

    suspend fun findByUuid(uuid: UUID): ChatProfileRecord? = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` WHERE `uuid` = ? LIMIT 1;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    ChatProfileRecord(
                        uuid = uuid,
                        nickname = rs.getString("nickname"),
                        msgToggle = rs.getBoolean("msg_toggle"),
                        socialSpy = rs.getBoolean("social_spy"),
                        updatedAt = rs.getLong("updated_at"),
                    )
                } else null
            }
        }
    }

    suspend fun upsert(record: ChatProfileRecord) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$table` (`uuid`, `nickname`, `msg_toggle`, `social_spy`, `updated_at`)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(`uuid`) DO UPDATE SET
                    `nickname` = excluded.`nickname`,
                    `msg_toggle` = excluded.`msg_toggle`,
                    `social_spy` = excluded.`social_spy`,
                    `updated_at` = excluded.`updated_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$table` (`uuid`, `nickname`, `msg_toggle`, `social_spy`, `updated_at`)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `nickname` = VALUES(`nickname`),
                    `msg_toggle` = VALUES(`msg_toggle`),
                    `social_spy` = VALUES(`social_spy`),
                    `updated_at` = VALUES(`updated_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, record.uuid.toString())
                ps.setString(2, record.nickname)
                ps.setBoolean(3, record.msgToggle)
                ps.setBoolean(4, record.socialSpy)
                ps.setLong(5, record.updatedAt)
                ps.executeUpdate()
            }
        }
    }
}
