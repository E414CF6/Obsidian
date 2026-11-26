package obsidian.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import obsidian.core.config.ObsidianConfig
import java.io.File
import java.sql.Connection
import java.sql.Statement

enum class DatabaseDialect {
    SQLITE,
    MYSQL,
    MARIADB,
}

/**
 * Manages the HikariCP connection pool and schema initialization for SQLite, MySQL, and MariaDB.
 */
class DatabaseManager(
    val dialect: DatabaseDialect,
    val tablePrefix: String,
    private val dataSource: HikariDataSource,
) : AutoCloseable {

    fun table(name: String): String = "$tablePrefix$name"

    suspend fun <T> query(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }

    fun <T> querySync(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun initSchemaSafely() {
        val longTextType = "TEXT"
        querySync { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("users")}` (
                        `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `username` VARCHAR(32) NOT NULL,
                        `last_login` BIGINT NOT NULL DEFAULT 0,
                        `last_logout` BIGINT NOT NULL DEFAULT 0,
                        `back_location` $longTextType,
                        `death_location` $longTextType,
                        `god_mode` BOOLEAN NOT NULL DEFAULT FALSE,
                        `fly_mode` BOOLEAN NOT NULL DEFAULT FALSE,
                        `updated_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("homes")}` (
                        `owner_uuid` VARCHAR(36) NOT NULL,
                        `name` VARCHAR(64) NOT NULL,
                        `world` VARCHAR(128) NOT NULL,
                        `x` DOUBLE NOT NULL,
                        `y` DOUBLE NOT NULL,
                        `z` DOUBLE NOT NULL,
                        `yaw` FLOAT NOT NULL DEFAULT 0,
                        `pitch` FLOAT NOT NULL DEFAULT 0,
                        `created_at` BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (`owner_uuid`, `name`)
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("warps")}` (
                        `name` VARCHAR(64) NOT NULL PRIMARY KEY,
                        `creator_uuid` VARCHAR(36),
                        `group_name` VARCHAR(64),
                        `world` VARCHAR(128) NOT NULL,
                        `x` DOUBLE NOT NULL,
                        `y` DOUBLE NOT NULL,
                        `z` DOUBLE NOT NULL,
                        `yaw` FLOAT NOT NULL DEFAULT 0,
                        `pitch` FLOAT NOT NULL DEFAULT 0,
                        `requires_permission` BOOLEAN NOT NULL DEFAULT FALSE,
                        `hidden` BOOLEAN NOT NULL DEFAULT FALSE,
                        `icon_material` VARCHAR(64) NOT NULL DEFAULT 'ENDER_PEARL',
                        `slot` INT,
                        `page` INT NOT NULL DEFAULT 1,
                        `created_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("economy")}` (
                        `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `username` VARCHAR(32) NOT NULL,
                        `balance` DOUBLE NOT NULL DEFAULT 0,
                        `updated_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("worth")}` (
                        `material` VARCHAR(64) NOT NULL PRIMARY KEY,
                        `sell_price` DOUBLE NOT NULL,
                        `buy_price` DOUBLE,
                        `updated_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("playtime")}` (
                        `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `username` VARCHAR(32) NOT NULL,
                        `total_millis` BIGINT NOT NULL DEFAULT 0,
                        `daily_millis` BIGINT NOT NULL DEFAULT 0,
                        `day_key` VARCHAR(16) NOT NULL DEFAULT '',
                        `current_rank` VARCHAR(64) NOT NULL DEFAULT 'default',
                        `claimed_rewards` TEXT NOT NULL DEFAULT '',
                        `updated_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("kit_claims")}` (
                        `uuid` VARCHAR(36) NOT NULL,
                        `kit_name` VARCHAR(64) NOT NULL,
                        `claim_count` INT NOT NULL DEFAULT 0,
                        `last_claimed_at` BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (`uuid`, `kit_name`)
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("punishments")}` (
                        `id` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `type` VARCHAR(24) NOT NULL,
                        `target_uuid` VARCHAR(36),
                        `target_name` VARCHAR(32),
                        `target_ip` VARCHAR(64),
                        `actor_uuid` VARCHAR(36),
                        `actor_name` VARCHAR(32) NOT NULL,
                        `reason` TEXT NOT NULL,
                        `jail_name` VARCHAR(64),
                        `created_at` BIGINT NOT NULL DEFAULT 0,
                        `expires_at` BIGINT,
                        `active` BOOLEAN NOT NULL DEFAULT TRUE,
                        `removed_by_uuid` VARCHAR(36),
                        `removed_by_name` VARCHAR(32),
                        `removed_at` BIGINT,
                        `removed_reason` TEXT,
                        `silent` BOOLEAN NOT NULL DEFAULT FALSE,
                        `template_id` VARCHAR(64)
                    );
                    """.trimIndent()
                )
                val punTable = table("punishments")
                ensureColumn(st, punTable, "removed_by_uuid", "VARCHAR(36)")
                ensureColumn(st, punTable, "removed_by_name", "VARCHAR(32)")
                ensureColumn(st, punTable, "removed_at", "BIGINT")
                ensureColumn(st, punTable, "removed_reason", "TEXT")
                ensureColumn(st, punTable, "silent", "BOOLEAN NOT NULL DEFAULT FALSE")
                ensureColumn(st, punTable, "template_id", "VARCHAR(64)")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("player_ips")}` (
                        `uuid` VARCHAR(36) NOT NULL,
                        `ip` VARCHAR(64) NOT NULL,
                        `username` VARCHAR(32) NOT NULL DEFAULT '',
                        `last_seen` BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (`uuid`, `ip`)
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("saved_inventories")}` (
                        `id` VARCHAR(64) NOT NULL PRIMARY KEY,
                        `owner_uuid` VARCHAR(36) NOT NULL,
                        `name` VARCHAR(64) NOT NULL,
                        `payload_base64` TEXT NOT NULL,
                        `created_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("chat_profiles")}` (
                        `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `nickname` VARCHAR(128),
                        `msg_toggle` BOOLEAN NOT NULL DEFAULT TRUE,
                        `social_spy` BOOLEAN NOT NULL DEFAULT FALSE,
                        `updated_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                val chatTable = table("chat_profiles")
                ensureColumn(st, chatTable, "chat_color", "VARCHAR(64)")
                ensureColumn(st, chatTable, "silence_chat", "BOOLEAN NOT NULL DEFAULT FALSE")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("votes")}` (
                        `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `username` VARCHAR(32) NOT NULL DEFAULT '',
                        `vote_count` BIGINT NOT NULL DEFAULT 0,
                        `last_vote_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("deliveries")}` (
                        `id` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `target_uuid` VARCHAR(36) NOT NULL,
                        `sender_name` VARCHAR(64) NOT NULL,
                        `item_base64` $longTextType,
                        `message` $longTextType NOT NULL,
                        `created_at` BIGINT NOT NULL DEFAULT 0,
                        `expires_at` BIGINT,
                        `claimed_at` BIGINT
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("spawner_charges")}` (
                        `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `charges` INT NOT NULL DEFAULT 0,
                        `last_updated` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("player_alerts")}` (
                        `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `username` VARCHAR(32) NOT NULL DEFAULT '',
                        `added_by` VARCHAR(32) NOT NULL,
                        `reason` $longTextType NOT NULL,
                        `created_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("ip_locks")}` (
                        `uuid` VARCHAR(36) NOT NULL,
                        `ip` VARCHAR(64) NOT NULL,
                        `added_by` VARCHAR(32) NOT NULL,
                        `created_at` BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (`uuid`, `ip`)
                    );
                    """.trimIndent()
                )
                val autoIncId = when (dialect) {
                    DatabaseDialect.SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT"
                    DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
                }
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("name_history")}` (
                        `id` $autoIncId,
                        `uuid` VARCHAR(36) NOT NULL,
                        `username` VARCHAR(32) NOT NULL,
                        `changed_at` BIGINT NOT NULL
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("punishment_allow")}` (
                        `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `added_by` VARCHAR(32) NOT NULL,
                        `created_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                if (dialect == DatabaseDialect.SQLITE) {
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `idx_${tablePrefix}users_name` ON `${table("users")}` (`username`);"
                    )
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `idx_${tablePrefix}punishments_target` ON `${table("punishments")}` (`target_uuid`, `type`, `active`);"
                    )
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `idx_${tablePrefix}punishments_ip` ON `${table("punishments")}` (`target_ip`, `type`, `active`);"
                    )
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `idx_${tablePrefix}punishments_actor` ON `${table("punishments")}` (`actor_uuid`, `active`, `created_at`);"
                    )
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `idx_${tablePrefix}name_history_uuid` ON `${table("name_history")}` (`uuid`, `changed_at`);"
                    )
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `idx_${tablePrefix}name_history_name` ON `${table("name_history")}` (`username`);"
                    )
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `idx_${tablePrefix}ips_ip` ON `${table("player_ips")}` (`ip`);"
                    )
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `idx_${tablePrefix}votes_count` ON `${table("votes")}` (`vote_count` DESC);"
                    )
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `idx_${tablePrefix}deliveries_target` ON `${table("deliveries")}` (`target_uuid`, `claimed_at`, `created_at`);"
                    )
                }
            }
        }
    }

    private fun ensureColumn(
        st: Statement,
        tableName: String,
        columnName: String,
        columnDef: String,
    ) {
        var exists = false
        if (dialect == DatabaseDialect.SQLITE) {
            runCatching {
                st.executeQuery("PRAGMA table_info(`$tableName`);").use { rs ->
                    while (rs.next()) {
                        if (rs.getString("name").equals(columnName, ignoreCase = true)) {
                            exists = true
                            break
                        }
                    }
                }
            }
        } else {
            runCatching {
                st.executeQuery(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_NAME = '$tableName' AND COLUMN_NAME = '$columnName';"
                ).use { rs ->
                    if (rs.next()) exists = true
                }
            }
        }
        if (!exists) {
            runCatching {
                st.executeUpdate("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDef;")
            }
        }
    }

    override fun close() {
        if (!dataSource.isClosed) {
            dataSource.close()
        }
    }

    companion object {
        fun forSqlite(dbFile: File, tablePrefix: String = "obsidian_"): DatabaseManager {
            dbFile.parentFile?.mkdirs()
            val hc = HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 1
                poolName = "Obsidian-SQLite"
                addDataSourceProperty("journal_mode", "WAL")
                addDataSourceProperty("synchronous", "NORMAL")
                addDataSourceProperty("busy_timeout", "5000")
            }
            return DatabaseManager(DatabaseDialect.SQLITE, tablePrefix, HikariDataSource(hc)).apply {
                initSchemaSafely()
            }
        }

        fun fromConfig(dataFolder: File, config: ObsidianConfig): DatabaseManager {
            return when (config.storageMethod) {
                "mysql", "mariadb" -> {
                    val dialect =
                        if (config.storageMethod == "mariadb") DatabaseDialect.MARIADB else DatabaseDialect.MYSQL
                    val ssl = if (config.mysqlUseSsl) "true" else "false"
                    val hc = HikariConfig().apply {
                        jdbcUrl =
                            "jdbc:mysql://${config.mysqlHost}:${config.mysqlPort}/${config.mysqlDatabase}?useSSL=$ssl&autoReconnect=true&useUnicode=true&characterEncoding=utf8"
                        username = config.mysqlUsername
                        password = config.mysqlPassword
                        maximumPoolSize = config.mysqlPoolSize
                        poolName = "Obsidian-MySQL"
                    }
                    DatabaseManager(dialect, config.mysqlTablePrefix, HikariDataSource(hc)).apply {
                        initSchemaSafely()
                    }
                }

                else -> forSqlite(File(dataFolder, config.sqliteFile), "obsidian_")
            }
        }
    }
}
