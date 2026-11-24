package obsidian

import kotlinx.coroutines.runBlocking
import obsidian.core.database.*
import obsidian.modules.automation.ConsoleFilterService
import obsidian.modules.utility.ServerOpsService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.math.PI

class Tier3And4FeaturesTest {

    @Test
    fun `alert repository CRUD operations`(@TempDir tempDir: Path) = runBlocking {
        DatabaseManager.forSqlite(tempDir.resolve("alerts.db").toFile()).use { db ->
            val repo = AlertRepository(db)
            val uuid = UUID.randomUUID()
            repo.upsert(
                AlertRecord(
                    uuid = uuid,
                    username = "SuspiciousPlayer",
                    addedBy = "Admin",
                    reason = "Check x-ray on join",
                    createdAt = 1000L,
                )
            )

            val found = repo.findByUuid(uuid)
            assertNotNull(found)
            assertEquals("SuspiciousPlayer", found!!.username)
            assertEquals("Check x-ray on join", found.reason)
            assertEquals(1, repo.findAll().size)

            assertTrue(repo.delete(uuid))
            assertNull(repo.findByUuid(uuid))
            assertFalse(repo.delete(uuid))
        }
    }

    @Test
    fun `ip lock repository add list and remove`(@TempDir tempDir: Path) = runBlocking {
        DatabaseManager.forSqlite(tempDir.resolve("iplocks.db").toFile()).use { db ->
            val repo = IpLockRepository(db)
            val uuid = UUID.randomUUID()
            repo.add(IpLockRecord(uuid, "192.168.1.10", "Admin", 1000L))
            repo.add(IpLockRecord(uuid, "10.0.0.5", "Admin", 2000L))

            val all = repo.findByUuid(uuid)
            assertEquals(2, all.size)
            assertTrue(all.any { it.ip == "192.168.1.10" })

            assertTrue(repo.remove(uuid, "192.168.1.10"))
            assertEquals(1, repo.findByUuid(uuid).size)
            assertEquals(1, repo.removeAll(uuid))
            assertTrue(repo.findByUuid(uuid).isEmpty())
        }
    }

    @Test
    fun `saved inventory delete by name or id`(@TempDir tempDir: Path) = runBlocking {
        DatabaseManager.forSqlite(tempDir.resolve("invs.db").toFile()).use { db ->
            val repo = SavedInventoryRepository(db)
            val uuid = UUID.randomUUID()
            repo.upsert(SavedInventoryRecord(id = "$uuid:pvp", ownerUuid = uuid, name = "pvp", payloadBase64 = "AA=="))
            assertNotNull(repo.findByNameOrId(uuid, "pvp"))
            assertTrue(repo.deleteByNameOrId(uuid, "pvp"))
            assertNull(repo.findByNameOrId(uuid, "pvp"))
            assertFalse(repo.deleteByNameOrId(uuid, "pvp"))
        }
    }

    @Test
    fun `solve math expression handles precedence functions and errors`() {
        assertEquals(14.0, ServerOpsService.solveMathExpression("2 + 3 * 4"), 1e-6)
        assertEquals(20.0, ServerOpsService.solveMathExpression("(2 + 3) * 4"), 1e-6)
        assertEquals(8.0, ServerOpsService.solveMathExpression("2^3"), 1e-6)
        assertEquals(5.0, ServerOpsService.solveMathExpression("sqrt(9) + abs(-2)"), 1e-6)
        assertEquals(PI * 2.0, ServerOpsService.solveMathExpression("2 * pi"), 1e-6)
        assertEquals(-7.5, ServerOpsService.solveMathExpression("-10 + 2.5"), 1e-6)
        assertThrows(IllegalArgumentException::class.java) {
            ServerOpsService.solveMathExpression("1 / 0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerOpsService.solveMathExpression("unknownfunc(5)")
        }
    }

    @Test
    fun `console filter matches regex patterns cleanly`() {
        val patterns = listOf(Regex("logged in with entity id"), Regex("issued server command: /login"))
        assertTrue(ConsoleFilterService.matchesAny("Player[/127.0.0.1:5000] logged in with entity id 42", patterns))
        assertTrue(ConsoleFilterService.matchesAny("Player issued server command: /login secret123", patterns))
        assertFalse(ConsoleFilterService.matchesAny("[Server] Backup completed in 120ms", patterns))
    }

    @Test
    fun `database stats backup migration and essentials cmi imports work`(@TempDir tempDir: Path) = runBlocking {
        val sourceFile = tempDir.resolve("src.db").toFile()
        val destFile = tempDir.resolve("dst.db").toFile()
        val pluginsDir = tempDir.resolve("plugins").toFile()

        // Seed EssentialsX fixtures
        val uuidEss = UUID.randomUUID()
        val essUserFile = File(pluginsDir, "Essentials/userdata/$uuidEss.yml").apply { parentFile.mkdirs() }
        essUserFile.writeText(
            """
            last-account-name: EssPlayer
            money: '1250.5'
            nickname: '&bHero'
            homes:
              base:
                world-name: world
                x: 100.5
                y: 64.0
                z: -20.25
                yaw: 90.0
                pitch: 0.0
            """.trimIndent()
        )
        val essWarpFile = File(pluginsDir, "Essentials/warps/shop.yml").apply { parentFile.mkdirs() }
        essWarpFile.writeText(
            """
            name: shop
            world-name: world
            x: 10.0
            y: 70.0
            z: 10.0
            yaw: 0.0
            pitch: 0.0
            """.trimIndent()
        )

        // Seed CMI fixtures
        val uuidCmi = UUID.randomUUID()
        val cmiPlayerFile = File(pluginsDir, "CMI/playerData/$uuidCmi.yml").apply { parentFile.mkdirs() }
        cmiPlayerFile.writeText(
            """
            Name: CmiPlayer
            Balance: 900.0
            Homes:
              mine: 'world_nether;12.0;50.0;-4.0;0.0;0.0'
            """.trimIndent()
        )
        val cmiWarpsFile = File(pluginsDir, "CMI/Saves/Warps.yml").apply { parentFile.mkdirs() }
        cmiWarpsFile.writeText(
            """
            arena:
              Location: 'world;50.0;80.0;50.0;180.0;0.0'
            """.trimIndent()
        )

        DatabaseManager.forSqlite(sourceFile).use { srcDb ->
            val userRepo = UserRepository(srcDb)
            val homeRepo = HomeRepository(srcDb)
            val warpRepo = WarpRepository(srcDb)
            val ecoRepo = EconomyRepository(srcDb)
            val chatRepo = ChatProfileRepository(srcDb)

            val essReport = DatabaseBackupAndMigrationService.importFromPluginsFolder(
                "essentials", pluginsDir, userRepo, homeRepo, warpRepo, ecoRepo, chatRepo
            )
            assertEquals(1, essReport.usersImported)
            assertEquals(1, essReport.homesImported)
            assertEquals(1, essReport.warpsImported)
            assertEquals(1250.5, ecoRepo.findByUuid(uuidEss)?.balance ?: 0.0, 1e-6)
            assertEquals("&bHero", chatRepo.findByUuid(uuidEss)?.nickname)

            val cmiReport = DatabaseBackupAndMigrationService.importFromPluginsFolder(
                "cmi", pluginsDir, userRepo, homeRepo, warpRepo, ecoRepo, chatRepo
            )
            assertEquals(1, cmiReport.usersImported)
            assertEquals(1, cmiReport.homesImported)
            assertEquals(1, cmiReport.warpsImported)
            assertEquals(900.0, ecoRepo.findByUuid(uuidCmi)?.balance ?: 0.0, 1e-6)

            val stats = DatabaseBackupAndMigrationService.collectStatsFor(srcDb)
            assertEquals(2L, stats["users"])
            assertEquals(2L, stats["homes"])
            assertEquals(2L, stats["warps"])

            val backupFile = DatabaseBackupAndMigrationService.createBackupFor(
                srcDb, tempDir.resolve("backups").toFile()
            )
            assertTrue(backupFile.exists() && backupFile.length() > 0L)

            DatabaseManager.forSqlite(destFile).use { dstDb ->
                val migReport = DatabaseBackupAndMigrationService.migrateBetween(srcDb, dstDb)
                assertTrue(migReport.tablesCopied >= 3)
                assertTrue(migReport.rowsCopied >= 6L)

                val dstStats = DatabaseBackupAndMigrationService.collectStatsFor(dstDb)
                assertEquals(2L, dstStats["users"])
                assertEquals(2L, dstStats["homes"])
                assertEquals(2L, dstStats["warps"])
            }
        }
    }
}
