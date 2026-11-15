package obsidian

import obsidian.core.command.SpecializedCommandExecutor
import obsidian.core.database.StoredLocation
import obsidian.modules.visual.CuboidRegion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Phase5HelpersTest {

    @Test
    fun `CuboidRegion computes bounds dimensions volume and containment`() {
        val a = StoredLocation("world", 10.2, 64.0, -5.8)
        val b = StoredLocation("world", -2.9, 70.0, 4.1)
        val region = CuboidRegion.fromStored(a, b)
        assertNotNull(region)
        assertEquals(-3, region!!.minX)
        assertEquals(10, region.maxX)
        assertEquals(64, region.minY)
        assertEquals(70, region.maxY)
        assertEquals(-6, region.minZ)
        assertEquals(4, region.maxZ)

        assertEquals(14, region.widthX())
        assertEquals(7, region.heightY())
        assertEquals(11, region.lengthZ())
        assertEquals(14L * 7L * 11L, region.volume())

        assertTrue(region.contains("world", 0, 65, 0))
        assertFalse(region.contains("world_nether", 0, 65, 0))
        assertFalse(region.contains("world", 11, 65, 0))

        val roundTrip = CuboidRegion.deserialize(region.serialize())
        assertEquals(region, roundTrip)
        assertNull(CuboidRegion.fromStored(a, StoredLocation("world_nether", 0.0, 0.0, 0.0)))
    }

    @Test
    fun `SpecializedCommandExecutor parses delay tokens cleanly`() {
        assertEquals(40L, SpecializedCommandExecutor.parseDelayTicks("delay!2"))
        assertEquals(30L, SpecializedCommandExecutor.parseDelayTicks("delay:1.5s"))
        assertEquals(1L, SpecializedCommandExecutor.parseDelayTicks("delay!0.01"))
        assertNull(SpecializedCommandExecutor.parseDelayTicks("delay!0"))
        assertNull(SpecializedCommandExecutor.parseDelayTicks("delay!abc"))
        assertNull(SpecializedCommandExecutor.parseDelayTicks("console: say hi"))
    }
}
