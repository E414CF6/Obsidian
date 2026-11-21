package obsidian.modules.mobfarm

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockRedstoneEvent
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class RedstoneChunkKey(
    val worldId: UUID,
    val chunkX: Int,
    val chunkZ: Int,
)

data class ChunkRedstoneActivity(
    val worldId: UUID,
    val chunkX: Int,
    val chunkZ: Int,
    val count: Int,
)

private data class RedstonePulse(
    val key: RedstoneChunkKey,
    val timestampMs: Long,
)

/**
 * Lock-free sliding-window redstone pulse tracker per chunk.
 */
class RedstoneTrackerService(
    private val settingsProvider: () -> MobFarmSettings,
) : Listener {

    private val ingestQueue = ConcurrentLinkedQueue<RedstonePulse>()
    private val events = ConcurrentHashMap<RedstoneChunkKey, ArrayDeque<Long>>()
    private val running = AtomicBoolean(false)
    private var worker: ScheduledExecutorService? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val exec = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "obsidian-redstone-tracker").apply { isDaemon = true }
        }
        worker = exec
        exec.scheduleAtFixedRate({ drainAndPrune(System.currentTimeMillis()) }, 50L, 50L, TimeUnit.MILLISECONDS)
    }

    fun shutdown() {
        running.set(false)
        worker?.shutdownNow()
        worker = null
        ingestQueue.clear()
        events.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRedstone(event: BlockRedstoneEvent) {
        val settings = settingsProvider()
        if (!settings.redstoneTrack) return
        if (event.newCurrent <= event.oldCurrent) return

        val block = event.block
        if (settings.isIgnoredWorld(block.world.name)) return

        record(block.world.uid, block.x shr 4, block.z shr 4, System.currentTimeMillis())
    }

    fun record(worldId: UUID, chunkX: Int, chunkZ: Int, timestampMs: Long = System.currentTimeMillis()) {
        ingestQueue.add(RedstonePulse(RedstoneChunkKey(worldId, chunkX, chunkZ), timestampMs))
    }

    internal fun drainAndPrune(nowMs: Long, windowMs: Long = settingsProvider().redstoneWindowMillis) {
        val cutoff = nowMs - windowMs
        while (true) {
            val pulse = ingestQueue.poll() ?: break
            val deque = events.computeIfAbsent(pulse.key) { ArrayDeque() }
            deque.addLast(pulse.timestampMs)
        }

        val iterator = events.entries.iterator()
        while (iterator.hasNext()) {
            val (_, deque) = iterator.next()
            while (deque.isNotEmpty() && deque.peekFirst() < cutoff) {
                deque.pollFirst()
            }
            if (deque.isEmpty()) {
                iterator.remove()
            }
        }
    }

    fun getTopActive(
        windowMs: Long = settingsProvider().redstoneWindowMillis,
        limit: Int = 10,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ChunkRedstoneActivity> {
        if (limit <= 0) return emptyList()
        drainAndPrune(nowMs, windowMs)
        val cutoff = nowMs - windowMs
        val results = ArrayList<ChunkRedstoneActivity>()

        for ((key, deque) in events) {
            var count = 0
            for (ts in deque) {
                if (ts >= cutoff) count++
            }
            if (count > 0) {
                results += ChunkRedstoneActivity(key.worldId, key.chunkX, key.chunkZ, count)
            }
        }

        return results.sortedByDescending { it.count }.take(limit)
    }
}
