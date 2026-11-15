package obsidian

import kotlinx.coroutines.runBlocking
import obsidian.core.database.DatabaseManager
import obsidian.modules.protect.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProtectModuleTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ProtectCommandParser parses durations and time ranges accurately`() {
        assertEquals(1_800_000L, ProtectCommandParser.parseDurationMillis("30m"))
        assertEquals(129_600_000L, ProtectCommandParser.parseDurationMillis("1d12h"))
        assertEquals(7_776_000_000L, ProtectCommandParser.parseDurationMillis("3mo"))
        assertNull(ProtectCommandParser.parseDurationMillis("invalid"))

        val now = 1_700_000_000_000L
        val (since, until) = ProtectCommandParser.parseTimeRange("2d-1h", now)
        assertEquals(now - 2 * 86_400_000L, since)
        assertEquals(now - 3_600_000L, until)
    }

    @Test
    fun `ProtectCommandParser parses user action material radius and hash flags`() {
        val now = 1_700_000_000_000L
        val q = ProtectCommandParser.parseFilterArgs(
            tokens = listOf(
                "u:Steve,Alex",
                "-u:#tnt",
                "t:1h",
                "r:25",
                "a:+block,-container",
                "i:diamond_block,chest",
                "e:dirt",
                "#preview",
                "#count"
            ),
            defaultWorld = "world",
            centerX = 10,
            centerY = 64,
            centerZ = -20,
            defaultRadius = 10,
            maxRadius = 100,
            nowMillis = now,
        ).getOrThrow()

        assertEquals(listOf("Steve", "Alex"), q.users)
        assertEquals(listOf("#tnt"), q.excludedUsers)
        assertEquals(now - 3_600_000L, q.sinceMillis)
        assertEquals(25, q.radius)
        assertEquals("world", q.world)
        assertEquals(setOf(ProtectFilterAction.BLOCK_PLACE, ProtectFilterAction.CONTAINER_REMOVE), q.actions)
        assertEquals(setOf("DIAMOND_BLOCK", "CHEST"), q.includeMaterials)
        assertEquals(setOf("DIRT"), q.excludeMaterials)
        assertTrue(q.preview)
        assertTrue(q.countOnly)

        val globalQ = ProtectCommandParser.parseFilterArgs(
            tokens = listOf("a:chat", "f:grief", "#global"),
            defaultWorld = "world",
            centerX = 0,
            centerY = 64,
            centerZ = 0,
            defaultRadius = 10,
        ).getOrThrow()
        assertNull(globalQ.world)
        assertNull(globalQ.radius)
        assertEquals("grief", globalQ.textFilter)
        assertEquals(setOf(ProtectFilterAction.CHAT), globalQ.actions)
    }

    @Test
    fun `ProtectRepository inserts queries toggles rollback and purges all four audit tables`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("protect.db").toFile())
        db.use {
            val repo = ProtectRepository(db)
            val tOld = 1_700_000_000_000L
            val tNew = 1_700_005_000_000L

            repo.insertBatch(
                blocks = listOf(
                    ProtectBlockEntry(
                        time = tOld, actor = "Griefer", world = "world", x = 10, y = 64, z = 10,
                        action = ProtectActions.BLOCK_BREAK, oldMaterial = "DIAMOND_BLOCK", newMaterial = "AIR",
                    ),
                    ProtectBlockEntry(
                        time = tNew, actor = "Builder", world = "world", x = 11, y = 64, z = 10,
                        action = ProtectActions.BLOCK_PLACE, oldMaterial = "AIR", newMaterial = "OAK_PLANKS",
                    ),
                    ProtectBlockEntry(
                        time = tNew, actor = "FarUser", world = "world", x = 500, y = 64, z = 500,
                        action = ProtectActions.BLOCK_PLACE, oldMaterial = "AIR", newMaterial = "STONE",
                    ),
                ),
                containers = listOf(
                    ProtectContainerEntry(
                        time = tNew,
                        actor = "Thief",
                        world = "world",
                        x = 10,
                        y = 64,
                        z = 10,
                        action = ProtectActions.CONTAINER_REMOVE,
                        material = "DIAMOND",
                        amount = 16,
                        itemBase64 = "BASE64",
                    )
                ),
                entities = listOf(
                    ProtectEntityEntry(
                        time = tNew, actor = "Griefer", world = "world", x = 12, y = 64, z = 10,
                        action = ProtectActions.ENTITY_KILL, entityType = "VILLAGER", entitySnapshot = "name=VGVzdA==",
                    )
                ),
                messages = listOf(
                    ProtectMessageEntry(
                        time = tNew, actor = "Griefer", world = "world", x = 10, y = 64, z = 10,
                        kind = ProtectActions.MSG_CHAT, message = "free diamonds here",
                    )
                ),
            )

            val counts = repo.tableCounts()
            assertEquals(3L, counts.blocks)
            assertEquals(1L, counts.containers)
            assertEquals(1L, counts.entities)
            assertEquals(1L, counts.messages)

            val inspectedBlock = repo.inspectBlock("world", 10, 64, 10)
            assertEquals(1, inspectedBlock.size)
            assertEquals("Griefer", inspectedBlock[0].actor)

            val inspectedChest = repo.inspectContainer("world", 10, 64, 10)
            assertEquals(1, inspectedChest.size)
            assertEquals(16, inspectedChest[0].amount)

            val nearby = repo.queryBlocks(
                ProtectQuery(world = "world", centerX = 10, centerY = 64, centerZ = 10, radius = 5),
                rolledBackFilter = false,
            )
            assertEquals(2, nearby.size)

            val grieferBreakIds = nearby.filter { it.actor == "Griefer" }.map { it.id }
            repo.setBlocksRolledBack(grieferBreakIds, true)

            val byIds = repo.queryBlocksByIds(grieferBreakIds, ascending = true)
            assertTrue(byIds.single().rolledBack)

            val msgs =
                repo.queryMessages(ProtectQuery(actions = setOf(ProtectFilterAction.CHAT), textFilter = "diamonds"))
            assertEquals(1, msgs.size)
            assertEquals("Griefer", msgs[0].actor)

            val purged = repo.purgeBefore(cutoffMillis = tNew)
            assertEquals(1, purged.deletedBlocks)
            assertEquals(0, purged.deletedContainers)

            val afterPurge = repo.tableCounts()
            assertEquals(2L, afterPurge.blocks)
            assertFalse(repo.queryBlocks(ProtectQuery(users = listOf("FarUser"))).isEmpty())
        }
    }
}
