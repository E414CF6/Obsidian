package obsidian

import obsidian.core.command.SpecializedCommandExecutor
import obsidian.core.database.StoredLocation
import obsidian.core.database.WarpRecord
import obsidian.modules.automation.DenseChunkInfo
import obsidian.modules.moderation.PunishmentFlagParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class CmiAbsorptionTest {

    @Test
    fun `PunishmentFlagParser extracts flags and reason correctly`() {
        val parsed = PunishmentFlagParser.parse(
            listOf(
                "-s",
                "-N",
                "--modify",
                "--sender=AutoMod",
                "1h",
                "Griefing",
                "spawn",
                "area"
            )
        )
        assertEquals(java.lang.Boolean.TRUE, parsed.silent)
        assertTrue(parsed.noOverride)
        assertTrue(parsed.modify)
        assertEquals("AutoMod", parsed.customSender)
        assertEquals(3_600_000L, parsed.durationMillis)
        assertEquals("Griefing spawn area", parsed.reason)
    }

    @Test
    fun `WarpRecord group filtering and sorting`() {
        val loc = StoredLocation("world", 0.0, 64.0, 0.0)
        val w1 = WarpRecord("alpha", UUID.randomUUID(), "spawn", loc, false, false, "STONE", null, 1)
        val w2 = WarpRecord("beta", UUID.randomUUID(), "shops", loc, false, false, "STONE", null, 1)
        val w3 = WarpRecord("gamma", UUID.randomUUID(), "spawn", loc, false, false, "STONE", null, 1)
        val w4 = WarpRecord("delta", UUID.randomUUID(), null, loc, false, false, "STONE", null, 1)

        val all = listOf(w1, w2, w3, w4)
        val spawnGroup = all.filter { it.groupName.equals("spawn", ignoreCase = true) }
        assertEquals(2, spawnGroup.size)
        assertTrue(spawnGroup.any { it.name == "alpha" })
        assertTrue(spawnGroup.any { it.name == "gamma" })

        val groups = all.mapNotNull { it.groupName }.distinct().sorted()
        assertEquals(listOf("shops", "spawn"), groups)
    }

    @Test
    fun `DenseChunkInfo sorts by total mob count descending`() {
        val c1 = DenseChunkInfo("world", 0, 0, 15, 10, 5)
        val c2 = DenseChunkInfo("world", 1, 1, 45, 30, 15)
        val c3 = DenseChunkInfo("world", 2, 2, 25, 20, 5)

        val sorted = listOf(c1, c2, c3).sortedByDescending { it.totalCount }
        assertEquals(45, sorted[0].totalCount)
        assertEquals(25, sorted[1].totalCount)
        assertEquals(15, sorted[2].totalCount)
    }

    @Test
    fun `SpecializedCommandExecutor delay parser supports multiple formats`() {
        assertEquals(20L, SpecializedCommandExecutor.parseDelayTicks("delay!1"))
        assertEquals(60L, SpecializedCommandExecutor.parseDelayTicks("delay:3s"))
        assertEquals(40L, SpecializedCommandExecutor.parseDelayTicks("delay!2.0"))
    }
}
