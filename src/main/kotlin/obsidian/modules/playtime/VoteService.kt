package obsidian.modules.playtime

import kotlinx.coroutines.launch
import obsidian.core.ObsidianContext
import obsidian.core.command.SpecializedCommandExecutor
import obsidian.core.database.VoteRecord
import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks player vote counts, milestone rewards, cooldowns, and NuVotifier hook integration
 * (modeled after CMI's `VotifierManager`).
 */
class VoteService(private val context: ObsidianContext) : Listener {

    private val cache = ConcurrentHashMap<UUID, VoteRecord>()
    private val serviceCooldowns = ConcurrentHashMap<String, Long>()
    private val ipDailyVotes = ConcurrentHashMap<String, MutableList<Long>>()

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var cooldownSeconds: Int = 0
        private set

    @Volatile
    var maxVotesPerDay: Int = 0
        private set

    @Volatile
    private var commandsOnVote: List<String> = emptyList()

    @Volatile
    private var extraRewards: Map<Int, List<String>> = emptyMap()

    @Volatile
    private var votifierHooked: Boolean = false

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        enabled = raw.getBoolean("votes.enabled", true)
        cooldownSeconds = raw.getInt("votes.cooldown-seconds", 0).coerceAtLeast(0)
        maxVotesPerDay = raw.getInt("votes.max-votes-per-day", 0).coerceAtLeast(0)
        commandsOnVote = raw.getStringList("votes.commands-on-vote")

        val rewardsMap = LinkedHashMap<Int, List<String>>()
        val sec = raw.getConfigurationSection("votes.extra-rewards")
        if (sec != null) {
            for (key in sec.getKeys(false)) {
                val threshold = key.toIntOrNull() ?: continue
                val cmds = sec.getStringList(key)
                if (threshold > 0 && cmds.isNotEmpty()) {
                    rewardsMap[threshold] = cmds
                }
            }
        }
        extraRewards = rewardsMap
    }

    fun loadAllAsync() {
        context.scheduler.asyncScope.launch {
            runCatching {
                val all = context.voteRepository.findAll()
                cache.clear()
                for (rec in all) {
                    cache[rec.uuid] = rec
                }
            }.onFailure { err ->
                context.plugin.logger.warning("Failed to load vote records: ${err.message}")
            }
        }
    }

    /**
     * Dynamically hooks NuVotifier's `VotifierEvent` via reflection if NuVotifier is present,
     * avoiding a hard compile dependency.
     */
    @Suppress("UNCHECKED_CAST")
    fun registerVotifierHookIfPresent() {
        if (votifierHooked) return
        val eventClass = runCatching {
            Class.forName("com.vexsoftware.votifier.model.VotifierEvent") as Class<out Event>
        }.getOrNull() ?: return

        val executor = EventExecutor { _, event ->
            handleVotifierEvent(event)
        }
        Bukkit.getPluginManager().registerEvent(
            eventClass,
            this,
            EventPriority.MONITOR,
            executor,
            context.plugin,
            true,
        )
        votifierHooked = true
        context.plugin.logger.info("Hooked NuVotifier VotifierEvent for vote tracking and rewards.")
    }

    private fun handleVotifierEvent(event: Event) {
        if (!enabled) return
        runCatching {
            val vote = event.javaClass.getMethod("getVote").invoke(event) ?: return
            val voteClass = vote.javaClass
            val username = (voteClass.getMethod("getUsername").invoke(vote) as? String)?.trim().orEmpty()
            val serviceName = (voteClass.getMethod("getServiceName").invoke(vote) as? String)?.trim().orEmpty()
            val address = runCatching {
                voteClass.getMethod("getAddress").invoke(vote) as? String
            }.getOrNull()

            if (username.isEmpty()) return

            val online = Bukkit.getPlayerExact(username)
            val uuid = online?.uniqueId ?: Bukkit.getOfflinePlayerIfCached(username)?.uniqueId
            if (uuid == null) {
                // Fallback async lookup via user table
                context.scheduler.asyncScope.launch {
                    val user = context.userRepository.findByName(username) ?: return@launch
                    context.scheduler.runGlobal {
                        recordVote(user.uuid, user.username, serviceName, address)
                    }
                }
                return
            }
            recordVote(uuid, online?.name ?: username, serviceName, address)
        }.onFailure { err ->
            context.plugin.logger.warning("Failed to process VotifierEvent: ${err.message}")
        }
    }

    /**
     * Processes a vote for [uuid] ([username]), enforcing cooldown/IP limits and dispatching rewards.
     * Returns `true` if the vote was counted, or `false` if blocked by cooldown/daily IP limit.
     */
    fun recordVote(
        uuid: UUID,
        username: String,
        serviceName: String = "manual",
        ipAddress: String? = null,
    ): Boolean {
        if (!enabled) return false
        val now = System.currentTimeMillis()

        if (cooldownSeconds > 0) {
            val cdKey = "${uuid}:${serviceName.lowercase()}"
            val readyAt = serviceCooldowns[cdKey] ?: 0L
            if (now < readyAt) return false
            serviceCooldowns[cdKey] = now + cooldownSeconds * 1000L
        }

        if (maxVotesPerDay > 0 && !ipAddress.isNullOrBlank()) {
            val cutoff = now - 86_400_000L
            val timestamps = ipDailyVotes.computeIfAbsent(ipAddress) { mutableListOf() }
            synchronized(timestamps) {
                timestamps.removeIf { it < cutoff }
                if (timestamps.size >= maxVotesPerDay) return false
                timestamps += now
            }
        }

        val updated = cache.compute(uuid) { _, existing ->
            val current = existing?.voteCount ?: 0L
            VoteRecord(
                uuid = uuid,
                username = username,
                voteCount = current + 1L,
                lastVoteAt = now,
            )
        }!!

        persistAsync(updated)

        val online = Bukkit.getPlayer(uuid)
        val placeholders = mapOf(
            "player" to username,
            "service" to serviceName,
            "votes" to updated.voteCount.toString(),
        )

        if (commandsOnVote.isNotEmpty()) {
            val rendered = commandsOnVote.map { line ->
                line.replace("{service}", serviceName, ignoreCase = true)
                    .replace("{votes}", updated.voteCount.toString(), ignoreCase = true)
            }
            SpecializedCommandExecutor.executeAll(context, rendered, online, placeholders)
        }

        val milestoneCmds = extraRewards[updated.voteCount.toInt()]
        if (!milestoneCmds.isNullOrEmpty()) {
            val rendered = milestoneCmds.map { line ->
                line.replace("{service}", serviceName, ignoreCase = true)
                    .replace("{votes}", updated.voteCount.toString(), ignoreCase = true)
            }
            SpecializedCommandExecutor.executeAll(context, rendered, online, placeholders)
        }

        return true
    }

    fun getVoteCount(uuid: UUID): Long = cache[uuid]?.voteCount ?: 0L

    fun getAllVoteCounts(): Map<UUID, Long> = cache.mapValues { it.value.voteCount }

    fun getTopVotes(limit: Int = 10, offset: Int = 0): List<VoteRecord> =
        cache.values
            .asSequence()
            .filter { it.voteCount > 0L }
            .sortedWith(compareByDescending<VoteRecord> { it.voteCount }.thenBy { it.lastVoteAt })
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceAtLeast(1))
            .toList()

    fun setVotes(uuid: UUID, username: String, count: Long): VoteRecord {
        val safeCount = count.coerceAtLeast(0L)
        val rec = VoteRecord(
            uuid = uuid,
            username = username,
            voteCount = safeCount,
            lastVoteAt = cache[uuid]?.lastVoteAt ?: System.currentTimeMillis(),
        )
        cache[uuid] = rec
        persistAsync(rec)
        return rec
    }

    fun addVotes(uuid: UUID, username: String, delta: Long): VoteRecord {
        val updated = cache.compute(uuid) { _, existing ->
            val next = ((existing?.voteCount ?: 0L) + delta).coerceAtLeast(0L)
            VoteRecord(
                uuid = uuid,
                username = username,
                voteCount = next,
                lastVoteAt = if (delta > 0L) System.currentTimeMillis() else (existing?.lastVoteAt ?: 0L),
            )
        }!!
        persistAsync(updated)
        return updated
    }

    private fun persistAsync(record: VoteRecord) {
        context.scheduler.asyncScope.launch {
            context.voteRepository.upsert(record)
        }
    }
}
