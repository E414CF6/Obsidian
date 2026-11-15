package obsidian.core.database

import obsidian.core.user.UserProfile
import java.sql.ResultSet
import java.util.*

data class HomeRecord(
    val ownerUuid: UUID,
    val name: String,
    val location: StoredLocation,
    val createdAt: Long = System.currentTimeMillis(),
)

data class WarpRecord(
    val name: String,
    val creatorUuid: UUID?,
    val groupName: String?,
    val location: StoredLocation,
    val requiresPermission: Boolean = false,
    val hidden: Boolean = false,
    val iconMaterial: String = "ENDER_PEARL",
    val slot: Int? = null,
    val page: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Persistence repository for core [UserProfile] records.
 */
class UserRepository(private val db: DatabaseManager) {

    private val table = db.table("users")

    suspend fun findByUuid(uuid: UUID): UserProfile? = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` WHERE `uuid` = ? LIMIT 1;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                if (rs.next()) mapUser(rs) else null
            }
        }
    }

    suspend fun findByName(username: String): UserProfile? = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` WHERE LOWER(`username`) = LOWER(?) ORDER BY `last_login` DESC LIMIT 1;")
            .use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapUser(rs) else null
                }
            }
    }

    suspend fun upsertAll(profiles: Collection<UserProfile>) {
        if (profiles.isEmpty()) return
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$table` (`uuid`, `username`, `last_login`, `last_logout`, `back_location`, `death_location`, `god_mode`, `fly_mode`, `updated_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(`uuid`) DO UPDATE SET
                    `username` = excluded.`username`,
                    `last_login` = excluded.`last_login`,
                    `last_logout` = excluded.`last_logout`,
                    `back_location` = excluded.`back_location`,
                    `death_location` = excluded.`death_location`,
                    `god_mode` = excluded.`god_mode`,
                    `fly_mode` = excluded.`fly_mode`,
                    `updated_at` = excluded.`updated_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$table` (`uuid`, `username`, `last_login`, `last_logout`, `back_location`, `death_location`, `god_mode`, `fly_mode`, `updated_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `username` = VALUES(`username`),
                    `last_login` = VALUES(`last_login`),
                    `last_logout` = VALUES(`last_logout`),
                    `back_location` = VALUES(`back_location`),
                    `death_location` = VALUES(`death_location`),
                    `god_mode` = VALUES(`god_mode`),
                    `fly_mode` = VALUES(`fly_mode`),
                    `updated_at` = VALUES(`updated_at`);
            """.trimIndent()
        }
        val now = System.currentTimeMillis()
        db.query { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(sql).use { ps ->
                    for (p in profiles) {
                        ps.setString(1, p.uuid.toString())
                        ps.setString(2, p.username)
                        ps.setLong(3, p.lastLogin)
                        ps.setLong(4, p.lastLogout)
                        ps.setString(5, p.backLocation?.serialize())
                        ps.setString(6, p.deathLocation?.serialize())
                        ps.setBoolean(7, p.godMode)
                        ps.setBoolean(8, p.flyMode)
                        ps.setLong(9, now)
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

    private fun mapUser(rs: ResultSet): UserProfile = UserProfile(
        uuid = UUID.fromString(rs.getString("uuid")),
        username = rs.getString("username"),
        lastLogin = rs.getLong("last_login"),
        lastLogout = rs.getLong("last_logout"),
        backLocation = StoredLocation.deserialize(rs.getString("back_location")),
        deathLocation = StoredLocation.deserialize(rs.getString("death_location")),
        godMode = rs.getBoolean("god_mode"),
        flyMode = rs.getBoolean("fly_mode"),
    )
}

/**
 * Persistence repository for player homes (`obsidian_homes`).
 */
class HomeRepository(private val db: DatabaseManager) {

    private val table = db.table("homes")

    suspend fun findByOwner(ownerUuid: UUID): List<HomeRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` WHERE `owner_uuid` = ? ORDER BY `created_at` ASC;").use { ps ->
            ps.setString(1, ownerUuid.toString())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            HomeRecord(
                                ownerUuid = ownerUuid,
                                name = rs.getString("name"),
                                location = StoredLocation(
                                    world = rs.getString("world"),
                                    x = rs.getDouble("x"),
                                    y = rs.getDouble("y"),
                                    z = rs.getDouble("z"),
                                    yaw = rs.getFloat("yaw"),
                                    pitch = rs.getFloat("pitch"),
                                ),
                                createdAt = rs.getLong("created_at"),
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun upsert(record: HomeRecord) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$table` (`owner_uuid`, `name`, `world`, `x`, `y`, `z`, `yaw`, `pitch`, `created_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(`owner_uuid`, `name`) DO UPDATE SET
                    `world` = excluded.`world`,
                    `x` = excluded.`x`,
                    `y` = excluded.`y`,
                    `z` = excluded.`z`,
                    `yaw` = excluded.`yaw`,
                    `pitch` = excluded.`pitch`,
                    `created_at` = excluded.`created_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$table` (`owner_uuid`, `name`, `world`, `x`, `y`, `z`, `yaw`, `pitch`, `created_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `world` = VALUES(`world`),
                    `x` = VALUES(`x`),
                    `y` = VALUES(`y`),
                    `z` = VALUES(`z`),
                    `yaw` = VALUES(`yaw`),
                    `pitch` = VALUES(`pitch`),
                    `created_at` = VALUES(`created_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, record.ownerUuid.toString())
                ps.setString(2, record.name)
                ps.setString(3, record.location.world)
                ps.setDouble(4, record.location.x)
                ps.setDouble(5, record.location.y)
                ps.setDouble(6, record.location.z)
                ps.setFloat(7, record.location.yaw)
                ps.setFloat(8, record.location.pitch)
                ps.setLong(9, record.createdAt)
                ps.executeUpdate()
            }
        }
    }

    suspend fun delete(ownerUuid: UUID, name: String): Boolean = db.query { conn ->
        conn.prepareStatement("DELETE FROM `$table` WHERE `owner_uuid` = ? AND LOWER(`name`) = LOWER(?);").use { ps ->
            ps.setString(1, ownerUuid.toString())
            ps.setString(2, name)
            ps.executeUpdate() > 0
        }
    }
}

/**
 * Persistence repository for server warps (`obsidian_warps`).
 */
class WarpRepository(private val db: DatabaseManager) {

    private val table = db.table("warps")

    suspend fun findAll(): List<WarpRecord> = db.query { conn ->
        conn.prepareStatement("SELECT * FROM `$table` ORDER BY `page` ASC, `name` ASC;").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val creatorRaw = rs.getString("creator_uuid")
                        val slotObj = rs.getObject("slot")
                        add(
                            WarpRecord(
                                name = rs.getString("name"),
                                creatorUuid = creatorRaw?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                                groupName = rs.getString("group_name"),
                                location = StoredLocation(
                                    world = rs.getString("world"),
                                    x = rs.getDouble("x"),
                                    y = rs.getDouble("y"),
                                    z = rs.getDouble("z"),
                                    yaw = rs.getFloat("yaw"),
                                    pitch = rs.getFloat("pitch"),
                                ),
                                requiresPermission = rs.getBoolean("requires_permission"),
                                hidden = rs.getBoolean("hidden"),
                                iconMaterial = rs.getString("icon_material") ?: "ENDER_PEARL",
                                slot = (slotObj as? Number)?.toInt(),
                                page = rs.getInt("page").coerceAtLeast(1),
                                createdAt = rs.getLong("created_at"),
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun upsert(record: WarpRecord) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$table` (`name`, `creator_uuid`, `group_name`, `world`, `x`, `y`, `z`, `yaw`, `pitch`, `requires_permission`, `hidden`, `icon_material`, `slot`, `page`, `created_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(`name`) DO UPDATE SET
                    `creator_uuid` = excluded.`creator_uuid`,
                    `group_name` = excluded.`group_name`,
                    `world` = excluded.`world`,
                    `x` = excluded.`x`,
                    `y` = excluded.`y`,
                    `z` = excluded.`z`,
                    `yaw` = excluded.`yaw`,
                    `pitch` = excluded.`pitch`,
                    `requires_permission` = excluded.`requires_permission`,
                    `hidden` = excluded.`hidden`,
                    `icon_material` = excluded.`icon_material`,
                    `slot` = excluded.`slot`,
                    `page` = excluded.`page`,
                    `created_at` = excluded.`created_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$table` (`name`, `creator_uuid`, `group_name`, `world`, `x`, `y`, `z`, `yaw`, `pitch`, `requires_permission`, `hidden`, `icon_material`, `slot`, `page`, `created_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `creator_uuid` = VALUES(`creator_uuid`),
                    `group_name` = VALUES(`group_name`),
                    `world` = VALUES(`world`),
                    `x` = VALUES(`x`),
                    `y` = VALUES(`y`),
                    `z` = VALUES(`z`),
                    `yaw` = VALUES(`yaw`),
                    `pitch` = VALUES(`pitch`),
                    `requires_permission` = VALUES(`requires_permission`),
                    `hidden` = VALUES(`hidden`),
                    `icon_material` = VALUES(`icon_material`),
                    `slot` = VALUES(`slot`),
                    `page` = VALUES(`page`),
                    `created_at` = VALUES(`created_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, record.name)
                ps.setString(2, record.creatorUuid?.toString())
                ps.setString(3, record.groupName)
                ps.setString(4, record.location.world)
                ps.setDouble(5, record.location.x)
                ps.setDouble(6, record.location.y)
                ps.setDouble(7, record.location.z)
                ps.setFloat(8, record.location.yaw)
                ps.setFloat(9, record.location.pitch)
                ps.setBoolean(10, record.requiresPermission)
                ps.setBoolean(11, record.hidden)
                ps.setString(12, record.iconMaterial)
                if (record.slot == null) ps.setNull(13, java.sql.Types.INTEGER) else ps.setInt(13, record.slot)
                ps.setInt(14, record.page)
                ps.setLong(15, record.createdAt)
                ps.executeUpdate()
            }
        }
    }

    suspend fun delete(name: String): Boolean = db.query { conn ->
        conn.prepareStatement("DELETE FROM `$table` WHERE LOWER(`name`) = LOWER(?);").use { ps ->
            ps.setString(1, name)
            ps.executeUpdate() > 0
        }
    }
}
