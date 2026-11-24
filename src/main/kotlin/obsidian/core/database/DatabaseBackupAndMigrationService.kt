package obsidian.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.user.UserProfile
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.sql.Connection
import java.text.SimpleDateFormat
import java.util.*

/**
 * Provides database statistics (`/database stats`), atomic SQLite/SQL backups (`/database backup`),
 * cross-dialect migration (`/migratedatabase`), and data import from EssentialsX/CMI (`/importfrom`).
 */
class DatabaseBackupAndMigrationService(private val context: ObsidianContext) {

    data class MigrationReport(
        val tablesCopied: Int,
        val rowsCopied: Long,
        val targetDialect: DatabaseDialect,
    )

    data class ImportReport(
        val source: String,
        val usersImported: Int,
        val homesImported: Int,
        val warpsImported: Int,
    )

    suspend fun collectStats(db: DatabaseManager = context.database): Map<String, Long> =
        collectStatsFor(db)

    suspend fun createBackup(
        db: DatabaseManager = context.database,
        backupDir: File = File(context.plugin.dataFolder, "backups"),
    ): File = createBackupFor(db, backupDir)

    suspend fun migrateTo(
        targetDialect: DatabaseDialect,
        sourceDb: DatabaseManager = context.database,
    ): MigrationReport {
        val cfg = context.config
        val targetDb = when (targetDialect) {
            DatabaseDialect.SQLITE -> DatabaseManager.forSqlite(
                File(context.plugin.dataFolder, cfg.sqliteFile),
                "obsidian_",
            )
            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> {
                val ssl = if (cfg.mysqlUseSsl) "true" else "false"
                val hc = HikariConfig().apply {
                    jdbcUrl =
                        "jdbc:mysql://${cfg.mysqlHost}:${cfg.mysqlPort}/${cfg.mysqlDatabase}?useSSL=$ssl&autoReconnect=true&useUnicode=true&characterEncoding=utf8"
                    username = cfg.mysqlUsername
                    password = cfg.mysqlPassword
                    maximumPoolSize = cfg.mysqlPoolSize
                    poolName = "Obsidian-Migrate"
                }
                DatabaseManager(targetDialect, cfg.mysqlTablePrefix, HikariDataSource(hc)).apply {
                    initSchemaSafely()
                }
            }
        }
        return try {
            migrateBetween(sourceDb, targetDb)
        } finally {
            targetDb.close()
        }
    }

    suspend fun importFrom(
        source: String,
        pluginsDir: File = context.plugin.dataFolder.parentFile ?: File("plugins"),
    ): ImportReport {
        val report = importFromPluginsFolder(
            source = source,
            pluginsDir = pluginsDir,
            userRepo = context.userRepository,
            homeRepo = context.homeRepository,
            warpRepo = context.warpRepository,
            economyRepo = context.economyRepository,
            chatProfileRepo = context.chatProfileRepository,
        )
        if (source.equals("cmi", ignoreCase = true) && runCatching { Bukkit.getServer() }.isSuccess) {
            syncCmiWorthIfPresent(File(pluginsDir, "CMI"))
        }
        return report
    }

    private fun syncCmiWorthIfPresent(cmiDir: File) {
        val cmiWorth = listOf(
            File(cmiDir, "Settings/Worth.yml"),
            File(cmiDir, "Worth.yml"),
        ).firstOrNull { it.isFile } ?: return
        val worthYml = YamlConfiguration.loadConfiguration(cmiWorth)
        val obsWorth = YamlConfigFile(context.plugin, "worth.yml")
        for (key in worthYml.getKeys(false)) {
            val price = worthYml.getDouble("$key.SellPrice", worthYml.getDouble(key, -1.0))
            if (price >= 0.0) {
                obsWorth.yaml.set("items.${key.uppercase()}", price)
            }
        }
        obsWorth.save()
    }

    companion object {
        val CORE_TABLES = listOf(
            "users",
            "homes",
            "warps",
            "economy",
            "playtime",
            "punishments",
            "saved_inventories",
            "chat_profiles",
            "votes",
            "deliveries",
            "spawner_charges",
            "player_alerts",
            "ip_locks",
        )

        suspend fun collectStatsFor(db: DatabaseManager): Map<String, Long> {
            val result = LinkedHashMap<String, Long>()
            db.query { conn ->
                for (logical in CORE_TABLES) {
                    val physical = db.table(logical)
                    val count = runCatching {
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT COUNT(*) FROM `$physical`").use { rs ->
                                if (rs.next()) rs.getLong(1) else 0L
                            }
                        }
                    }.getOrDefault(0L)
                    result[logical] = count
                }
            }
            return result
        }

        suspend fun createBackupFor(db: DatabaseManager, backupDir: File): File {
            if (!backupDir.exists()) backupDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())

            if (db.dialect == DatabaseDialect.SQLITE) {
                val outFile = File(backupDir, "obsidian-$stamp.db")
                val escaped = outFile.absolutePath.replace("'", "''")
                db.query { conn ->
                    conn.createStatement().use { st ->
                        st.execute("VACUUM INTO '$escaped'")
                    }
                }
                return outFile
            }

            val outFile = File(backupDir, "obsidian-$stamp.sql")
            val sb = StringBuilder()
            sb.append("-- Obsidian Database Backup ($stamp)\n")
            db.query { conn ->
                for (logical in CORE_TABLES) {
                    val physical = db.table(logical)
                    runCatching {
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT * FROM `$physical`").use { rs ->
                                val cols = rs.metaData.columnCount
                                while (rs.next()) {
                                    val vals = (1..cols).joinToString(", ") { i ->
                                        val v = rs.getObject(i)
                                        when (v) {
                                            null -> "NULL"
                                            is Number -> v.toString()
                                            else -> "'${v.toString().replace("'", "''")}'"
                                        }
                                    }
                                    sb.append("INSERT INTO `$physical` VALUES ($vals);\n")
                                }
                            }
                        }
                    }
                }
            }
            outFile.writeText(sb.toString())
            return outFile
        }

        suspend fun migrateBetween(sourceDb: DatabaseManager, targetDb: DatabaseManager): MigrationReport =
            withContext(Dispatchers.IO) {
                var tablesCopied = 0
                var rowsCopied = 0L
                targetDb.querySync { targetConn ->
                    sourceDb.querySync { srcConn ->
                        for (logical in CORE_TABLES) {
                            val copied = copyTable(
                                src = srcConn,
                                dst = targetConn,
                                srcTable = sourceDb.table(logical),
                                dstTable = targetDb.table(logical),
                                targetDialect = targetDb.dialect,
                            )
                            if (copied >= 0) {
                                tablesCopied++
                                rowsCopied += copied
                            }
                        }
                    }
                }
                MigrationReport(tablesCopied, rowsCopied, targetDb.dialect)
            }

        private fun copyTable(
            src: Connection,
            dst: Connection,
            srcTable: String,
            dstTable: String,
            targetDialect: DatabaseDialect,
        ): Long {
            return runCatching {
                src.createStatement().use { st ->
                    st.executeQuery("SELECT * FROM `$srcTable`").use { rs ->
                        val meta = rs.metaData
                        val cols = meta.columnCount
                        if (cols == 0) return@use 0L
                        val colNames = (1..cols).map { "`${meta.getColumnName(it)}`" }
                        val placeholders = List(cols) { "?" }.joinToString(", ")
                        val insertVerb = if (targetDialect == DatabaseDialect.SQLITE) {
                            "INSERT OR REPLACE INTO"
                        } else {
                            "REPLACE INTO"
                        }
                        val sql = "$insertVerb `$dstTable` (${colNames.joinToString(", ")}) VALUES ($placeholders)"
                        var count = 0L
                        dst.prepareStatement(sql).use { ps ->
                            while (rs.next()) {
                                for (i in 1..cols) {
                                    ps.setObject(i, rs.getObject(i))
                                }
                                ps.addBatch()
                                count++
                            }
                            if (count > 0) ps.executeBatch()
                        }
                        count
                    }
                }
            }.getOrDefault(-1L)
        }

        suspend fun importFromPluginsFolder(
            source: String,
            pluginsDir: File,
            userRepo: UserRepository,
            homeRepo: HomeRepository,
            warpRepo: WarpRepository,
            economyRepo: EconomyRepository? = null,
            chatProfileRepo: ChatProfileRepository? = null,
        ): ImportReport {
            return when (source.lowercase()) {
                "essentials", "essentialsx" -> importFromEssentials(
                    File(pluginsDir, "Essentials"),
                    userRepo,
                    homeRepo,
                    warpRepo,
                    economyRepo,
                    chatProfileRepo,
                )
                "cmi" -> importFromCmi(
                    File(pluginsDir, "CMI"),
                    userRepo,
                    homeRepo,
                    warpRepo,
                    economyRepo,
                    chatProfileRepo,
                )
                else -> ImportReport(source, 0, 0, 0)
            }
        }

        private suspend fun importFromEssentials(
            essDir: File,
            userRepo: UserRepository,
            homeRepo: HomeRepository,
            warpRepo: WarpRepository,
            economyRepo: EconomyRepository?,
            chatProfileRepo: ChatProfileRepository?,
        ): ImportReport {
            var users = 0
            var homes = 0
            var warps = 0

            val warpsDir = File(essDir, "warps")
            if (warpsDir.isDirectory) {
                warpsDir.listFiles { f -> f.extension.equals("yml", ignoreCase = true) }?.forEach { file ->
                    val yml = YamlConfiguration.loadConfiguration(file)
                    val name = yml.getString("name") ?: file.nameWithoutExtension
                    val world = yml.getString("world-name") ?: yml.getString("world") ?: return@forEach
                    val loc = StoredLocation(
                        world = world,
                        x = yml.getDouble("x"),
                        y = yml.getDouble("y"),
                        z = yml.getDouble("z"),
                        yaw = yml.getDouble("yaw").toFloat(),
                        pitch = yml.getDouble("pitch").toFloat(),
                    )
                    warpRepo.upsert(
                        WarpRecord(
                            name = name,
                            creatorUuid = null,
                            groupName = null,
                            location = loc,
                        )
                    )
                    warps++
                }
            }

            val usersDir = File(essDir, "userdata")
            val importedUsers = mutableListOf<UserProfile>()
            val importedBalances = mutableListOf<EconomyRecord>()
            if (usersDir.isDirectory) {
                usersDir.listFiles { f -> f.extension.equals("yml", ignoreCase = true) }?.forEach { file ->
                    val uuid = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@forEach
                    val yml = YamlConfiguration.loadConfiguration(file)
                    val username =
                        yml.getString("last-account-name") ?: yml.getString("lastAccountName") ?: uuid.toString()
                    val existing = userRepo.findByUuid(uuid)
                    importedUsers.add(existing?.apply { this.username = username } ?: UserProfile(uuid = uuid, username = username))

                    val balStr = yml.getString("money")
                    val importedBal = balStr?.toDoubleOrNull() ?: if (yml.contains("money")) yml.getDouble("money") else null
                    if (importedBal != null) {
                        importedBalances.add(EconomyRecord(uuid = uuid, username = username, balance = importedBal))
                    }

                    val nick = yml.getString("nickname")
                    if (!nick.isNullOrBlank() && chatProfileRepo != null) {
                        val existingChat = chatProfileRepo.findByUuid(uuid) ?: ChatProfileRecord(uuid = uuid)
                        chatProfileRepo.upsert(existingChat.copy(nickname = nick, updatedAt = System.currentTimeMillis()))
                    }
                    users++

                    val homesSec = yml.getConfigurationSection("homes")
                    if (homesSec != null) {
                        for (homeName in homesSec.getKeys(false)) {
                            val h = homesSec.getConfigurationSection(homeName) ?: continue
                            val world = h.getString("world-name") ?: h.getString("world") ?: continue
                            val loc = StoredLocation(
                                world = world,
                                x = h.getDouble("x"),
                                y = h.getDouble("y"),
                                z = h.getDouble("z"),
                                yaw = h.getDouble("yaw").toFloat(),
                                pitch = h.getDouble("pitch").toFloat(),
                            )
                            homeRepo.upsert(HomeRecord(uuid, homeName.lowercase(), loc))
                            homes++
                        }
                    }
                }
            }
            userRepo.upsertAll(importedUsers)
            economyRepo?.upsertAll(importedBalances)
            return ImportReport("essentials", users, homes, warps)
        }

        private suspend fun importFromCmi(
            cmiDir: File,
            userRepo: UserRepository,
            homeRepo: HomeRepository,
            warpRepo: WarpRepository,
            economyRepo: EconomyRepository?,
            chatProfileRepo: ChatProfileRepository?,
        ): ImportReport {
            var users = 0
            var homes = 0
            var warps = 0

            val warpsFile = listOf(
                File(cmiDir, "Saves/Warps.yml"),
                File(cmiDir, "warps.yml"),
            ).firstOrNull { it.isFile }
            if (warpsFile != null) {
                val yml = YamlConfiguration.loadConfiguration(warpsFile)
                for (key in yml.getKeys(false)) {
                    val sec = yml.getConfigurationSection(key) ?: continue
                    val locStr = sec.getString("Location") ?: sec.getString("location") ?: continue
                    val loc = parseCmiLocation(locStr) ?: continue
                    warpRepo.upsert(
                        WarpRecord(
                            name = key,
                            creatorUuid = null,
                            groupName = null,
                            location = loc,
                        )
                    )
                    warps++
                }
            }

            val playerDataDir = File(cmiDir, "playerData")
            val importedUsers = mutableListOf<UserProfile>()
            val importedBalances = mutableListOf<EconomyRecord>()
            if (playerDataDir.isDirectory) {
                playerDataDir.listFiles { f -> f.extension.equals("yml", ignoreCase = true) }?.forEach { file ->
                    val uuid = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@forEach
                    val yml = YamlConfiguration.loadConfiguration(file)
                    val name = yml.getString("Name") ?: uuid.toString()
                    val existing = userRepo.findByUuid(uuid)
                    importedUsers.add(existing?.apply { this.username = name } ?: UserProfile(uuid = uuid, username = name))

                    if (yml.contains("Balance")) {
                        importedBalances.add(EconomyRecord(uuid = uuid, username = name, balance = yml.getDouble("Balance")))
                    }
                    val nick = yml.getString("NickName")
                    if (!nick.isNullOrBlank() && chatProfileRepo != null) {
                        val existingChat = chatProfileRepo.findByUuid(uuid) ?: ChatProfileRecord(uuid = uuid)
                        chatProfileRepo.upsert(existingChat.copy(nickname = nick, updatedAt = System.currentTimeMillis()))
                    }
                    users++

                    val homesSec = yml.getConfigurationSection("Homes")
                    if (homesSec != null) {
                        for (homeKey in homesSec.getKeys(false)) {
                            val loc = parseCmiLocation(homesSec.getString(homeKey) ?: "") ?: continue
                            homeRepo.upsert(HomeRecord(uuid, homeKey.lowercase(), loc))
                            homes++
                        }
                    }
                }
            }
            userRepo.upsertAll(importedUsers)
            economyRepo?.upsertAll(importedBalances)
            return ImportReport("cmi", users, homes, warps)
        }

        /**
         * Parses CMI location strings formatted as `world;x;y;z;yaw;pitch` or `world:x:y:z:yaw:pitch`.
         */
        fun parseCmiLocation(raw: String): StoredLocation? {
            val sep = if (raw.contains(';')) ';' else ':'
            val parts = raw.split(sep)
            if (parts.size < 4) return null
            val world = parts[0].trim()
            val x = parts[1].toDoubleOrNull() ?: return null
            val y = parts[2].toDoubleOrNull() ?: return null
            val z = parts[3].toDoubleOrNull() ?: return null
            val yaw = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
            val pitch = parts.getOrNull(5)?.toFloatOrNull() ?: 0f
            return StoredLocation(world, x, y, z, yaw, pitch)
        }
    }
}
