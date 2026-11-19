package obsidian.modules.automation

import obsidian.core.ObsidianContext
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent

data class DenseChunkInfo(
    val worldName: String,
    val chunkX: Int,
    val chunkZ: Int,
    val totalCount: Int,
    val monsterCount: Int,
    val animalCount: Int,
)

/**
 * Monitors and limits entity density per chunk to prevent mob farm lag.
 * Folia-safe region scheduling for chunk inspections and entity removal.
 */
class MobFarmLimiterService(private val context: ObsidianContext) : Listener {

    var enabled: Boolean = true
        private set
    var chunkMonsterLimit: Int = 30
        private set
    var chunkAnimalLimit: Int = 30
        private set
    var chunkTotalLimit: Int = 50
        private set
    var exemptNamed: Boolean = true
        private set
    var exemptTamed: Boolean = true
        private set

    init {
        reload()
    }

    fun reload() {
        val conf = context.plugin.config
        enabled = conf.getBoolean("automation.mobfarm.enabled", true)
        chunkMonsterLimit = conf.getInt("automation.mobfarm.chunk-monster-limit", 30)
        chunkAnimalLimit = conf.getInt("automation.mobfarm.chunk-animal-limit", 30)
        chunkTotalLimit = conf.getInt("automation.mobfarm.chunk-total-limit", 50)
        exemptNamed = conf.getBoolean("automation.mobfarm.exempt-named", true)
        exemptTamed = conf.getBoolean("automation.mobfarm.exempt-tamed", true)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (!enabled) return
        val entity = event.entity
        if (entity is Player) return

        val chunk = event.location.chunk
        var monsters = 0
        var animals = 0
        var total = 0

        for (e in chunk.entities) {
            if (e !is LivingEntity || e is Player) continue
            total++
            if (e is Monster) monsters++
            else if (e is Animals) animals++
        }

        val cancel = when {
            total >= chunkTotalLimit -> true
            entity is Monster && monsters >= chunkMonsterLimit -> true
            entity is Animals && animals >= chunkAnimalLimit -> true
            else -> false
        }

        if (cancel) {
            event.isCancelled = true
        }
    }

    /**
     * Cleans up excess mobs in the given chunk, respecting named/tamed exemptions.
     * Returns the number of removed entities.
     * Must be called on the chunk's region thread.
     */
    fun cleanChunk(chunk: Chunk): Int {
        var removed = 0
        val monsters = mutableListOf<LivingEntity>()
        val animals = mutableListOf<LivingEntity>()
        val others = mutableListOf<LivingEntity>()

        for (e in chunk.entities) {
            if (e !is LivingEntity || e is Player) continue
            if (exemptNamed && e.customName() != null) continue
            if (exemptTamed && e is Tameable && e.isTamed) continue

            when (e) {
                is Monster -> monsters += e
                is Animals -> animals += e
                else -> others += e
            }
        }

        // Cull excess monsters
        if (monsters.size > chunkMonsterLimit) {
            val toRemove = monsters.size - chunkMonsterLimit
            for (i in 0 until toRemove) {
                monsters[i].remove()
                removed++
            }
        }

        // Cull excess animals
        if (animals.size > chunkAnimalLimit) {
            val toRemove = animals.size - chunkAnimalLimit
            for (i in 0 until toRemove) {
                animals[i].remove()
                removed++
            }
        }

        // Cull excess total
        val remainingTotal = (monsters.size.coerceAtMost(chunkMonsterLimit)) +
                (animals.size.coerceAtMost(chunkAnimalLimit)) + others.size
        if (remainingTotal > chunkTotalLimit) {
            val toRemove = remainingTotal - chunkTotalLimit
            var culled = 0
            for (o in others) {
                if (culled >= toRemove) break
                o.remove()
                removed++
                culled++
            }
        }

        return removed
    }

    /**
     * Evaluates loaded chunks in the specified world and returns top dense chunks.
     */
    fun getTopDenseChunks(world: World, limit: Int = 10): List<DenseChunkInfo> {
        val list = mutableListOf<DenseChunkInfo>()
        for (chunk in world.loadedChunks) {
            var monsters = 0
            var animals = 0
            var total = 0
            for (e in chunk.entities) {
                if (e !is LivingEntity || e is Player) continue
                total++
                if (e is Monster) monsters++
                else if (e is Animals) animals++
            }
            if (total > 0) {
                list += DenseChunkInfo(world.name, chunk.x, chunk.z, total, monsters, animals)
            }
        }
        return list.sortedByDescending { it.totalCount }.take(limit)
    }
}
