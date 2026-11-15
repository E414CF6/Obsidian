package obsidian

import kotlinx.coroutines.runBlocking
import obsidian.core.database.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class Phase2To4DatabaseTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `EconomyRepository manages balances leaderboard and worth prices`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("eco.db").toFile())
        db.use {
            val repo = EconomyRepository(db)
            val u1 = UUID.randomUUID()
            val u2 = UUID.randomUUID()
            repo.upsertAll(
                listOf(
                    EconomyRecord(u1, "Alice", 500.0),
                    EconomyRecord(u2, "Bob", 1250.5),
                )
            )
            assertEquals(500.0, repo.findByName("alice")?.balance)
            val top = repo.topBalances(10)
            assertEquals(listOf("Bob", "Alice"), top.map { it.username })

            repo.upsertWorth(WorthRecord("DIAMOND", 120.0, 250.0))
            val worth = repo.findAllWorth()
            assertEquals(1, worth.size)
            assertEquals(120.0, worth[0].sellPrice)
            assertEquals(250.0, worth[0].buyPrice)
            assertTrue(repo.deleteWorth("diamond"))
            assertTrue(repo.findAllWorth().isEmpty())
        }
    }

    @Test
    fun `PlayTimeRepository and KitClaimRepository persist progression and claims`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("pt.db").toFile())
        db.use {
            val ptRepo = PlayTimeRepository(db)
            val kitRepo = KitClaimRepository(db)
            val u1 = UUID.randomUUID()
            val u2 = UUID.randomUUID()

            ptRepo.upsertAll(
                listOf(
                    PlayTimeRecord(u1, "PlayerOne", 3600_000L, 1200_000L, "2026-11-15", "member", setOf("first_hour")),
                    PlayTimeRecord(
                        u2, "PlayerTwo", 7200_000L, 600_000L, "2026-11-15", "veteran", setOf("first_hour", "two_hours")
                    ),
                )
            )
            val loaded = ptRepo.findByUuid(u1)
            assertNotNull(loaded)
            assertEquals("member", loaded!!.currentRank)
            assertEquals(setOf("first_hour"), loaded.claimedRewards)
            assertEquals(listOf("PlayerTwo", "PlayerOne"), ptRepo.topTotal(10).map { it.username })

            kitRepo.upsert(KitClaimRecord(u1, "starter", claimCount = 2, lastClaimedAt = 123456L))
            val claims = kitRepo.findByUuid(u1)
            assertEquals(2, claims["starter"]?.claimCount)
            assertEquals(123456L, claims["starter"]?.lastClaimedAt)
        }
    }

    @Test
    fun `PunishmentRepository SavedInventoryRepository and ChatProfileRepository round-trip`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("modchat.db").toFile())
        db.use {
            val punRepo = PunishmentRepository(db)
            val invRepo = SavedInventoryRepository(db)
            val chatRepo = ChatProfileRepository(db)

            val targetUuid = UUID.randomUUID()
            val altUuid = UUID.randomUUID()
            val ip = "203.0.113.42"

            punRepo.recordPlayerIp(targetUuid, ip, "Griefer", 1000L)
            punRepo.recordPlayerIp(altUuid, ip, "GrieferAlt", 2000L)
            val alts = punRepo.findAccountsByIp(ip)
            assertEquals(listOf("GrieferAlt", "Griefer"), alts.map { it.username })

            val ban = PunishmentRecord(
                type = PunishmentType.IP_BAN,
                targetUuid = targetUuid,
                targetName = "Griefer",
                targetIp = ip,
                actorName = "Admin",
                reason = "Griefing",
                expiresAt = System.currentTimeMillis() + 60_000L,
            )
            punRepo.insert(ban)
            assertEquals(1, punRepo.findActive(altUuid, ip, PunishmentType.BAN, PunishmentType.IP_BAN).size)
            assertTrue(punRepo.deactivateForTarget(PunishmentType.IP_BAN, targetUuid, ip) > 0)
            assertTrue(punRepo.findActive(altUuid, ip, PunishmentType.BAN, PunishmentType.IP_BAN).isEmpty())

            val inv = SavedInventoryRecord(
                id = "$targetUuid:backup1",
                ownerUuid = targetUuid,
                name = "backup1",
                payloadBase64 = "AQIDBA==",
            )
            invRepo.upsert(inv)
            assertEquals("AQIDBA==", invRepo.findByNameOrId(targetUuid, "BACKUP1")?.payloadBase64)
            assertEquals(1, invRepo.findByOwner(targetUuid).size)

            val chat = ChatProfileRecord(
                uuid = targetUuid,
                nickname = "<gold>Hero</gold>",
                msgToggle = false,
                socialSpy = true,
            )
            chatRepo.upsert(chat)
            val loadedChat = chatRepo.findByUuid(targetUuid)
            assertNotNull(loadedChat)
            assertEquals("<gold>Hero</gold>", loadedChat!!.nickname)
            assertFalse(loadedChat.msgToggle)
            assertTrue(loadedChat.socialSpy)
            assertNull(chatRepo.findByUuid(UUID.randomUUID()))

            // Pre-ban unseen player by name + offline UUID, then check/unban by name
            val unseenUuid = UUID.nameUUIDFromBytes("OfflinePlayer:PreBannedUser".toByteArray(Charsets.UTF_8))
            punRepo.insert(
                PunishmentRecord(
                    type = PunishmentType.BAN,
                    targetUuid = unseenUuid,
                    targetName = "PreBannedUser",
                    actorName = "Console",
                    reason = "Pre-ban test",
                )
            )
            val matchedByName = punRepo.findActiveByNameOrIp("prebanneduser", PunishmentType.BAN)
            assertEquals(1, matchedByName.size)
            assertEquals("Pre-ban test", matchedByName[0].reason)
            assertTrue(punRepo.deactivateForTarget(PunishmentType.BAN, unseenUuid, "PreBannedUser") > 0)
            assertTrue(punRepo.findActiveByNameOrIp("PreBannedUser", PunishmentType.BAN).isEmpty())
        }
    }
}
