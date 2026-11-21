package obsidian

import obsidian.modules.mobfarm.*
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class MobFarmModuleTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `MobFarmConfig resolves chunk groups and excluded chunks accurately`() {
        val r1 = MobFarmConfig.resolveGroupChunks(10, -5, 1)
        assertEquals(listOf(ChunkCoord(10, -5)), r1)

        val r2 = MobFarmConfig.resolveGroupChunks(0, 0, 2)
        assertEquals(9, r2.size)
        assertTrue(ChunkCoord(-1, -1) in r2 && ChunkCoord(1, 1) in r2)

        val r3 = MobFarmConfig.resolveGroupChunks(0, 0, 5) // clamped to 3 -> 5x5
        assertEquals(25, r3.size)
        assertTrue(ChunkCoord(-2, 2) in r3)

        assertEquals("world" to ChunkCoord(5, -1), MobFarmConfig.parseExcludedChunk("World:5:-1"))
        assertNull(MobFarmConfig.parseExcludedChunk("invalid"))
    }

    @Test
    fun `MobFarmConfig parses mobfarm yaml entity types groups and block limits`() {
        val yamlStr = """
            entity-spawn:
              check: true
              prevent-mob-build: true
              group-chunks: true
              group-chunks-radius: 3
            excluded-chunks:
              - "world:4:-2"
            ignore:
              worlds:
                - "Creative"
            block-types:
              limit: true
              list:
                HOPPER: 12
                OBSERVER: 8
            entity-groups:
              Monsters:
                use: true
                limit: 35
                remove-existing: true
                worlds:
                  - "world"
                list:
                  - ZOMBIE
                  - SKELETON
            entity-types:
              ZOMBIE:
                use: true
                limit: 25
                disable-ai: true
                world-limit:
                  world_nether: 15
        """.trimIndent()
        val yaml = YamlConfiguration.loadConfiguration(StringReader(yamlStr))
        val settings = MobFarmConfig.parseFromYaml(yaml)

        assertTrue(settings.checkOnEntitySpawn)
        assertTrue(settings.preventMobBuild)
        assertEquals(3, settings.groupChunksRadius)
        assertTrue(settings.isExcludedChunk("WORLD", 4, -2))
        assertTrue(settings.isIgnoredWorld("creative"))
        assertEquals(12, settings.blockLimits[Material.HOPPER])
        assertEquals(8, settings.blockLimits[Material.OBSERVER])

        val zombieRule = settings.getEntityTypeRule("ZOMBIE")
        assertNotNull(zombieRule)
        assertEquals(25, zombieRule!!.limitForWorld("world"))
        assertEquals(15, zombieRule.limitForWorld("world_nether"))
        assertTrue(zombieRule.disableAi)

        assertEquals("Monsters", settings.getGroupForEntity("ZOMBIE", "world")?.groupName)
        assertNull(settings.getGroupForEntity("ZOMBIE", "world_the_end"))
    }

    @Test
    fun `HopperControlService dynamically throttles and resets after idle window`() {
        val entry = HopperTrackerEntry(cooldown = 8)
        val defaultCd = 8
        val slowest = 40
        val adjustEvery = 3

        var now = 1_000L
        HopperControlService.updateEntryCooldown(entry, now, defaultCd, slowest, adjustEvery)
        assertEquals(8, entry.cooldown)

        for (i in 1..3) {
            now += 400L // within 8*50+100 = 500ms
            HopperControlService.updateEntryCooldown(entry, now, defaultCd, slowest, adjustEvery)
        }
        assertEquals(9, entry.cooldown)

        // Idle longer than cooldown * 50 + 100 resets to default
        val idleEntry = HopperTrackerEntry(triggerCount = 5, triggerCountSinceLastUpdate = 2, cooldown = 20, lastTransferMs = 1_000L)
        val resetCd = HopperControlService.updateEntryCooldown(idleEntry, 2_500L, defaultCd, slowest, adjustEvery)
        assertEquals(defaultCd, resetCd)

        // Clamp at slowestRate
        val maxEntry = HopperTrackerEntry(triggerCount = 5, triggerCountSinceLastUpdate = 2, cooldown = slowest, lastTransferMs = 1_000L)
        val clampedCd = HopperControlService.updateEntryCooldown(maxEntry, 1_100L, defaultCd, slowest, adjustEvery)
        assertEquals(slowest, clampedCd)
    }

    @Test
    fun `RedstoneTrackerService slides window and orders chunks descending`() {
        val tracker = RedstoneTrackerService { MobFarmConfig.defaultSettings().copy(redstoneTrack = true) }
        val worldId = UUID.randomUUID()

        tracker.record(worldId, 1, 1, 10_000L)
        tracker.record(worldId, 1, 1, 50_000L)
        tracker.record(worldId, 1, 1, 55_000L)
        tracker.record(worldId, 2, 2, 40_000L)
        tracker.record(worldId, 2, 2, 45_000L)
        tracker.record(worldId, 2, 2, 52_000L)

        // At t = 75_000 with 60_000ms window, cutoff = 15_000 -> (1,1) has 2, (2,2) has 3
        val top = tracker.getTopActive(windowMs = 60_000L, limit = 10, nowMs = 75_000L)
        assertEquals(2, top.size)
        assertEquals(2, top[0].chunkX)
        assertEquals(3, top[0].count)
        assertEquals(1, top[1].chunkX)
        assertEquals(2, top[1].count)
    }

    @Test
    fun `MobFarmCleanerService selectExcess handles spawning manual removeExisting disableAi and exemptions`() {
        val candidates = listOf(
            EntityCandidate(0, "ZOMBIE", ticksLived = 100, exempt = true),  // named/tamed exempt
            EntityCandidate(1, "ZOMBIE", ticksLived = 50, exempt = false),
            EntityCandidate(2, "ZOMBIE", ticksLived = 200, exempt = false),
            EntityCandidate(3, "ZOMBIE", ticksLived = 300, exempt = false),
        )

        // Manual clean with limit 2: excess = 2; skips exempt index 0, removes youngest-ticksLived first (50, 200)
        val manualSel = MobFarmCleanerService.selectExcess(
            candidates = candidates,
            limit = 2,
            mode = CleanMode.MANUAL,
            removeExisting = true,
            disableAi = false,
            maxToRemove = 10,
        )
        assertFalse(manualSel.cancelSpawningOriginal)
        assertEquals(listOf(1, 2), manualSel.toRemoveIndices)

        // DisableAI mode: preserves entities and targets excess non-exempt for AI disabling
        val aiSel = MobFarmCleanerService.selectExcess(
            candidates = candidates,
            limit = 2,
            mode = CleanMode.AUTO,
            removeExisting = true,
            disableAi = true,
            maxToRemove = 10,
        )
        assertTrue(aiSel.toRemoveIndices.isEmpty())
        assertEquals(listOf(1, 2), aiSel.toDisableAiIndices)

        // Spawning mode at limit=4: cancels new spawn without removing existing (4 - 4 = 0)
        val spawnAtLimit = MobFarmCleanerService.selectExcess(
            candidates = candidates,
            limit = 4,
            mode = CleanMode.SPAWNING,
            removeExisting = true,
            disableAi = false,
            maxToRemove = 10,
        )
        assertTrue(spawnAtLimit.cancelSpawningOriginal)
        assertTrue(spawnAtLimit.toRemoveIndices.isEmpty())

        // Spawning mode with disableAi=true: allows spawn and flags AI disabling on spawning entity
        val spawnDisableAi = MobFarmCleanerService.selectExcess(
            candidates = candidates,
            limit = 3,
            mode = CleanMode.SPAWNING,
            removeExisting = true,
            disableAi = true,
            maxToRemove = 10,
        )
        assertFalse(spawnDisableAi.cancelSpawningOriginal)
        assertTrue(spawnDisableAi.disableAiOnSpawningOriginal)
    }

    @Test
    fun `MobFarmLogWriter gates by threshold and writes UTF-8 daily logs`() {
        assertFalse(MobFarmLogWriter.shouldLog(enabled = false, minCount = 5, totalCleaned = 10))
        assertFalse(MobFarmLogWriter.shouldLog(enabled = true, minCount = 5, totalCleaned = 4))
        assertTrue(MobFarmLogWriter.shouldLog(enabled = true, minCount = 5, totalCleaned = 5))

        val logger = MobFarmLogWriter(tempDir.toFile())
        try {
            val logFolder = tempDir.resolve("logs/mobfarm").toFile()
            logger.writeLine(logFolder, "2026_11_21", MobFarmLogWriter.formatLogLine(1_700_000_000_000L, "Steve", 12, "Zombie:12"))
            val logFile = logFolder.toPath().resolve("2026_11_21.log")
            assertTrue(Files.exists(logFile))
            val content = Files.readString(logFile)
            assertTrue(content.contains("[Steve] Removed 12: Zombie:12"))
        } finally {
            logger.shutdown()
        }
    }
}
