package obsidian

import kotlinx.coroutines.runBlocking
import obsidian.core.database.*
import obsidian.core.user.UserProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class DatabaseRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `UserRepository inserts updates and finds users by uuid and case-insensitive name`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("users.db").toFile())
        db.use {
            val repo = UserRepository(db)
            val uuid = UUID.randomUUID()
            val back = StoredLocation("world", 10.0, 64.0, -20.0, 45f, 0f)
            val death = StoredLocation("world_nether", 5.0, 40.0, 8.0)

            val initial = UserProfile(
                uuid = uuid,
                username = "Steve",
                lastLogin = 1000L,
                lastLogout = 500L,
                backLocation = back,
                deathLocation = death,
                godMode = true,
                flyMode = false,
            )
            repo.upsertAll(listOf(initial))

            val byUuid = repo.findByUuid(uuid)
            assertNotNull(byUuid)
            assertEquals("Steve", byUuid!!.username)
            assertEquals(back, byUuid.backLocation)
            assertEquals(death, byUuid.deathLocation)
            assertTrue(byUuid.godMode)
            assertFalse(byUuid.flyMode)

            val byName = repo.findByName("sTeVe")
            assertEquals(uuid, byName?.uuid)

            // Update existing profile
            val updated = initial.copy(username = "SteveRenamed", godMode = false, flyMode = true)
            repo.upsertAll(listOf(updated))

            val reloaded = repo.findByUuid(uuid)
            assertEquals("SteveRenamed", reloaded?.username)
            assertFalse(reloaded!!.godMode)
            assertTrue(reloaded.flyMode)
        }
    }

    @Test
    fun `HomeRepository upserts lists and deletes homes`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("homes.db").toFile())
        db.use {
            val repo = HomeRepository(db)
            val owner = UUID.randomUUID()
            val baseLoc = StoredLocation("world", 100.5, 70.0, 200.5, 180f, 5f)
            val farmLoc = StoredLocation("world", -50.0, 65.0, 12.0)

            repo.upsert(HomeRecord(owner, "base", baseLoc))
            repo.upsert(HomeRecord(owner, "farm", farmLoc))

            val homes = repo.findByOwner(owner)
            assertEquals(2, homes.size)
            assertEquals(baseLoc, homes.first { it.name == "base" }.location)

            // Upsert overwrite
            val movedLoc = StoredLocation("world_the_end", 0.0, 64.0, 0.0)
            repo.upsert(HomeRecord(owner, "base", movedLoc))
            val updatedHomes = repo.findByOwner(owner)
            assertEquals(2, updatedHomes.size)
            assertEquals(movedLoc, updatedHomes.first { it.name == "base" }.location)

            // Case-insensitive delete
            assertTrue(repo.delete(owner, "BASE"))
            assertFalse(repo.delete(owner, "missing"))
            val remaining = repo.findByOwner(owner)
            assertEquals(listOf("farm"), remaining.map { it.name })
        }
    }

    @Test
    fun `WarpRepository upserts lists and deletes warps`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("warps.db").toFile())
        db.use {
            val repo = WarpRepository(db)
            val creator = UUID.randomUUID()
            val spawnLoc = StoredLocation("world", 0.5, 64.0, 0.5)
            val marketLoc = StoredLocation("world", 250.0, 66.0, -100.0, 90f, 0f)

            repo.upsert(
                WarpRecord(
                    name = "Market",
                    creatorUuid = creator,
                    groupName = "shops",
                    location = marketLoc,
                    requiresPermission = true,
                    hidden = false,
                    iconMaterial = "EMERALD",
                    slot = 13,
                    page = 2,
                )
            )
            repo.upsert(
                WarpRecord(
                    name = "Arena",
                    creatorUuid = null,
                    groupName = null,
                    location = spawnLoc,
                )
            )

            val warps = repo.findAll()
            assertEquals(2, warps.size)
            val market = warps.first { it.name == "Market" }
            assertEquals(creator, market.creatorUuid)
            assertEquals("shops", market.groupName)
            assertEquals(marketLoc, market.location)
            assertTrue(market.requiresPermission)
            assertEquals("EMERALD", market.iconMaterial)
            assertEquals(13, market.slot)
            assertEquals(2, market.page)

            assertTrue(repo.delete("market"))
            assertNull(repo.findAll().firstOrNull { it.name.equals("Market", ignoreCase = true) })
        }
    }
}
