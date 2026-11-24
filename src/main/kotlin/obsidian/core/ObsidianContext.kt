package obsidian.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import obsidian.core.command.CommandAliasService
import obsidian.core.command.CooldownService
import obsidian.core.command.ObsidianCommandRegistry
import obsidian.core.command.WarmupService
import obsidian.core.config.ObsidianConfig
import obsidian.core.database.*
import obsidian.core.locale.LocaleService
import obsidian.core.module.ModuleRegistry
import obsidian.core.scheduler.ObsidianScheduler
import obsidian.core.user.UserService
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Central service container wiring core infrastructure for all Obsidian modules.
 */
class ObsidianContext(val plugin: JavaPlugin) {

    val config = ObsidianConfig(plugin)
    val locale = LocaleService(plugin, config)
    val scheduler = ObsidianScheduler(plugin)
    val database: DatabaseManager = DatabaseManager.fromConfig(plugin.dataFolder, config)

    val userRepository = UserRepository(database)
    val homeRepository = HomeRepository(database)
    val warpRepository = WarpRepository(database)
    val economyRepository = EconomyRepository(database)
    val playTimeRepository = PlayTimeRepository(database)
    val kitClaimRepository = KitClaimRepository(database)
    val punishmentRepository = PunishmentRepository(database)
    val savedInventoryRepository = SavedInventoryRepository(database)
    val chatProfileRepository = ChatProfileRepository(database)

    val users = UserService(userRepository, scheduler, config)
    val cooldowns = CooldownService(config)
    val warmups = WarmupService(config, locale, scheduler)
    val modules = ModuleRegistry(plugin, config)
    val commandRegistry = ObsidianCommandRegistry()
    val commandAliases = CommandAliasService(this)

    private val services = ConcurrentHashMap<Class<*>, Any>()

    fun <T : Any> registerService(type: Class<T>, instance: T) {
        services[type] = instance
    }

    inline fun <reified T : Any> registerService(instance: T) {
        registerService(T::class.java, instance)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getService(type: Class<T>): T? = services[type] as? T

    inline fun <reified T : Any> getService(): T? = getService(T::class.java)

    fun startAutoSave() {
        val interval = config.autoSaveIntervalSeconds
        scheduler.runAsyncTimer(interval, interval) {
            scheduler.asyncScope.launch {
                runCatching { users.flushDirty() }.onFailure {
                    if (it is CancellationException) {
                        ensureActive()
                    }
                }.onFailure { err ->
                    plugin.logger.warning("Autosave error: ${err.message}")
                }
            }
        }
    }

    fun reloadAll() {
        config.reload()
        locale.reload()
        cooldowns.reload()
        warmups.reload()
        modules.reloadAll()
        commandAliases.reload()
    }

    fun shutdown() {
        commandAliases.shutdown()
        modules.disableAll()
        users.flushAllBlocking()
        scheduler.shutdown()
        database.close()
    }
}
