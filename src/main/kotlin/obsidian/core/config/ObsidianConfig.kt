package obsidian.core.config

import org.bukkit.plugin.Plugin

/**
 * Typed configuration snapshot loaded from `config.yml`.
 */
class ObsidianConfig(private val plugin: Plugin) {

    private val configFile = YamlConfigFile(plugin, "config.yml")

    var language: String = "ko"
        private set
    var storageMethod: String = "sqlite"
        private set
    var autoSaveIntervalSeconds: Long = 60L
        private set
    var forceSaveOnLogout: Boolean = true
        private set
    var sqliteFile: String = "obsidian.db"
        private set
    var mysqlHost: String = "localhost"
        private set
    var mysqlPort: Int = 3306
        private set
    var mysqlDatabase: String = "minecraft"
        private set
    var mysqlUsername: String = "root"
        private set
    var mysqlPassword: String = ""
        private set
    var mysqlTablePrefix: String = "obsidian_"
        private set
    var mysqlUseSsl: Boolean = false
        private set
    var mysqlPoolSize: Int = 10
        private set
    var registerStandaloneAliases: Boolean = true
        private set

    init {
        reload()
    }

    val raw get() = configFile.yaml

    fun reload() {
        configFile.reload()
        val y = configFile.yaml
        language = y.getString("language", "ko") ?: "ko"
        storageMethod = (y.getString("storage.method", "sqlite") ?: "sqlite").lowercase()
        autoSaveIntervalSeconds = y.getLong("storage.auto-save-interval-seconds", 60L).coerceAtLeast(10L)
        forceSaveOnLogout = y.getBoolean("storage.force-save-on-logout", true)
        sqliteFile = y.getString("storage.sqlite.file", "obsidian.db") ?: "obsidian.db"
        mysqlHost = y.getString("storage.mysql.host", "localhost") ?: "localhost"
        mysqlPort = y.getInt("storage.mysql.port", 3306)
        mysqlDatabase = y.getString("storage.mysql.database", "minecraft") ?: "minecraft"
        mysqlUsername = y.getString("storage.mysql.username", "root") ?: "root"
        mysqlPassword = y.getString("storage.mysql.password", "") ?: ""
        mysqlTablePrefix = y.getString("storage.mysql.table-prefix", "obsidian_") ?: "obsidian_"
        mysqlUseSsl = y.getBoolean("storage.mysql.use-ssl", false)
        mysqlPoolSize = y.getInt("storage.mysql.pool-size", 10).coerceIn(1, 50)
        registerStandaloneAliases = y.getBoolean("commands.register-standalone-aliases", true)
    }

    fun isModuleEnabled(moduleId: String, defaultEnabled: Boolean = true): Boolean =
        raw.getBoolean("modules.$moduleId", defaultEnabled)

    fun save() = configFile.save()
}
