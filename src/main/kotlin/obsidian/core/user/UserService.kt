package obsidian.core.user

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import obsidian.core.config.ObsidianConfig
import obsidian.core.database.StoredLocation
import obsidian.core.database.UserRepository
import obsidian.core.scheduler.ObsidianScheduler
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Core persisted identity and shared player state.
 * Domain-specific data (homes, economy, moderation, etc.) lives in each module rather than here.
 */
data class UserProfile(
    val uuid: UUID,
    @Volatile var username: String,
    @Volatile var lastLogin: Long = System.currentTimeMillis(),
    @Volatile var lastLogout: Long = 0L,
    @Volatile var backLocation: StoredLocation? = null,
    @Volatile var deathLocation: StoredLocation? = null,
    @Volatile var godMode: Boolean = false,
    @Volatile var flyMode: Boolean = false,
) {
    @Volatile
    var dirty: Boolean = false

    fun markDirty() {
        dirty = true
    }
}

/**
 * Thread-safe user profile cache with async database loading, periodic dirty flush, and logout persistence.
 */
class UserService(
    private val repository: UserRepository,
    private val scheduler: ObsidianScheduler,
    private val config: ObsidianConfig,
) : Listener {

    private val byUuid = ConcurrentHashMap<UUID, UserProfile>()
    private val uuidByName = ConcurrentHashMap<String, UUID>()

    fun getCached(uuid: UUID): UserProfile? = byUuid[uuid]

    fun getCached(player: Player): UserProfile {
        val existing = byUuid[player.uniqueId]
        if (existing != null) {
            if (existing.username != player.name) {
                uuidByName.remove(existing.username.lowercase())
                existing.username = player.name
                uuidByName[player.name.lowercase()] = player.uniqueId
                existing.markDirty()
            }
            return existing
        }
        val created = UserProfile(uuid = player.uniqueId, username = player.name).apply { markDirty() }
        byUuid[player.uniqueId] = created
        uuidByName[player.name.lowercase()] = player.uniqueId
        return created
    }

    suspend fun getOrLoad(uuid: UUID, fallbackName: String? = null): UserProfile? {
        byUuid[uuid]?.let { return it }
        val loaded = repository.findByUuid(uuid)
        if (loaded != null) {
            byUuid[uuid] = loaded
            uuidByName[loaded.username.lowercase()] = uuid
            return loaded
        }
        if (fallbackName != null) {
            val created = UserProfile(uuid = uuid, username = fallbackName).apply { markDirty() }
            byUuid[uuid] = created
            uuidByName[fallbackName.lowercase()] = uuid
            return created
        }
        return null
    }

    suspend fun findByName(name: String): UserProfile? {
        uuidByName[name.lowercase()]?.let { uuid ->
            byUuid[uuid]?.let { return it }
        }
        val loaded = repository.findByName(name) ?: return null
        byUuid[loaded.uuid] = loaded
        uuidByName[loaded.username.lowercase()] = loaded.uuid
        return loaded
    }

    suspend fun flushDirty(): Int {
        val dirtyProfiles = byUuid.values.filter { it.dirty }
        if (dirtyProfiles.isEmpty()) return 0
        repository.upsertAll(dirtyProfiles)
        dirtyProfiles.forEach { it.dirty = false }
        return dirtyProfiles.size
    }

    fun flushAllBlocking() {
        val allDirty = byUuid.values.filter { it.dirty }
        if (allDirty.isEmpty()) return
        runBlocking {
            repository.upsertAll(allDirty)
            allDirty.forEach { it.dirty = false }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val now = System.currentTimeMillis()
        scheduler.asyncScope.launch {
            val profile =
                repository.findByUuid(player.uniqueId) ?: UserProfile(uuid = player.uniqueId, username = player.name)
            profile.username = player.name
            profile.lastLogin = now
            profile.markDirty()
            byUuid[player.uniqueId] = profile
            uuidByName[player.name.lowercase()] = player.uniqueId

            scheduler.runForEntity(player) {
                if (profile.flyMode && player.hasPermission("obsidian.command.fly")) {
                    player.allowFlight = true
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val profile = byUuid[player.uniqueId] ?: return
        profile.lastLogout = System.currentTimeMillis()
        profile.flyMode = player.allowFlight
        profile.markDirty()
        if (config.forceSaveOnLogout) {
            scheduler.asyncScope.launch {
                repository.upsertAll(listOf(profile))
                profile.dirty = false
            }
        }
    }
}
