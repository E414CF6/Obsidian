package obsidian.modules.protect

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.runBlocking
import obsidian.core.ObsidianContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free asynchronous consumer queue modeled after CoreProtect's Consumer pipeline.
 * Events enqueue records with zero main-thread I/O and flush in batches to [ProtectRepository].
 */
class ProtectQueueService(
    private val context: ObsidianContext,
    private val repository: ProtectRepository,
) {
    private val blockQueue = ConcurrentLinkedQueue<ProtectBlockEntry>()
    private val containerQueue = ConcurrentLinkedQueue<ProtectContainerEntry>()
    private val entityQueue = ConcurrentLinkedQueue<ProtectEntityEntry>()
    private val messageQueue = ConcurrentLinkedQueue<ProtectMessageEntry>()

    private val flushing = AtomicBoolean(false)
    val paused = AtomicBoolean(false)

    private val totalFlushedCount = AtomicLong(0L)
    private var timerTask: ScheduledTask? = null

    fun start(flushPeriodMillis: Long = 1000L) {
        stop()
        val periodSec = (flushPeriodMillis / 1000L).coerceAtLeast(1L)
        timerTask = context.scheduler.runAsyncTimer(periodSec, periodSec) {
            if (!paused.get()) {
                flushSync()
            }
        }
    }

    fun stop() {
        timerTask?.cancel()
        timerTask = null
    }

    fun enqueueBlock(entry: ProtectBlockEntry) {
        blockQueue.add(entry)
    }

    fun enqueueContainer(entry: ProtectContainerEntry) {
        containerQueue.add(entry)
    }

    fun enqueueEntity(entry: ProtectEntityEntry) {
        entityQueue.add(entry)
    }

    fun enqueueMessage(entry: ProtectMessageEntry) {
        messageQueue.add(entry)
    }

    fun pendingCount(): Int = blockQueue.size + containerQueue.size + entityQueue.size + messageQueue.size

    fun pendingBreakdown(): String =
        "blocks=${blockQueue.size}, containers=${containerQueue.size}, entities=${entityQueue.size}, messages=${messageQueue.size}"

    fun totalFlushed(): Long = totalFlushedCount.get()

    /**
     * Drains all queued records into a single SQL transaction. Safe to call from async thread,
     * `/co consumer flush`, or synchronously on plugin shutdown.
     */
    fun flushSync(): Int {
        if (!flushing.compareAndSet(false, true)) return 0
        return try {
            val blocks = drainUpTo(blockQueue, 2000)
            val containers = drainUpTo(containerQueue, 2000)
            val entities = drainUpTo(entityQueue, 2000)
            val messages = drainUpTo(messageQueue, 2000)
            val count = blocks.size + containers.size + entities.size + messages.size
            if (count == 0) return 0

            runBlocking {
                repository.insertBatch(blocks, containers, entities, messages)
            }
            totalFlushedCount.addAndGet(count.toLong())
            count
        } catch (err: Throwable) {
            context.plugin.logger.warning("Protect consumer flush failed: ${err.message}")
            0
        } finally {
            flushing.set(false)
        }
    }

    fun flushAllBlocking() {
        while (pendingCount() > 0) {
            val flushed = flushSync()
            if (flushed == 0) break
        }
    }

    private fun <T> drainUpTo(queue: ConcurrentLinkedQueue<T>, maxItems: Int): List<T> {
        if (queue.isEmpty()) return emptyList()
        val list = ArrayList<T>(minOf(queue.size, maxItems))
        while (list.size < maxItems) {
            val item = queue.poll() ?: break
            list += item
        }
        return list
    }
}
