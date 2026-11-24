package obsidian

import kotlinx.coroutines.runBlocking
import obsidian.core.database.*
import obsidian.core.user.UserProfile
import obsidian.modules.chat.ChatService
import obsidian.modules.stats.StatsAggregatorService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class Tier1FeaturesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `VoteRepository persists votes and StatsAggregatorService reads live vote counts`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("votes.db").toFile())
        db.use {
            val voteRepo = VoteRepository(db)
            val u1 = UUID.randomUUID()
            val u2 = UUID.randomUUID()

            UserRepository(db).upsertAll(
                listOf(
                    UserProfile(uuid = u1, username = "VoterOne"),
                    UserProfile(uuid = u2, username = "VoterTwo"),
                )
            )
            voteRepo.upsert(VoteRecord(uuid = u1, username = "VoterOne", voteCount = 12L, lastVoteAt = 1000L))
            voteRepo.upsert(VoteRecord(uuid = u2, username = "VoterTwo", voteCount = 27L, lastVoteAt = 2000L))

            val top = voteRepo.topVotes(10)
            assertEquals(listOf("VoterTwo", "VoterOne"), top.map { it.username })
            assertEquals(27L, voteRepo.findByUuid(u2)?.voteCount)

            val aggregator = StatsAggregatorService(
                db = db,
                worldDirProvider = { null },
                onlinePlayersProvider = { emptyMap() },
                skillsProvider = { _, _, _ -> StatsAggregatorService.zeroSkills() },
                metaProvider = { StatsAggregatorService.buildDefaultMeta() },
            )
            val response = aggregator.getSnapshot(forceRefresh = true)
            val p1 = response.players.first { it.username == "VoterOne" }
            val p2 = response.players.first { it.username == "VoterTwo" }
            assertEquals(12L, p1.votes)
            assertEquals(27L, p2.votes)
        }
    }

    @Test
    fun `DeliveryRepository handles pending, expired, atomic claim, and purge`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("delivery.db").toFile())
        db.use {
            val repo = DeliveryRepository(db)
            val target = UUID.randomUUID()
            val now = 100_000L

            repo.insert(
                DeliveryRecord(
                    id = "d-active",
                    targetUuid = target,
                    senderName = "Admin",
                    itemBase64 = "abc",
                    message = "Starter reward",
                    createdAt = now - 1000L,
                    expiresAt = now + 50_000L,
                )
            )
            repo.insert(
                DeliveryRecord(
                    id = "d-expired",
                    targetUuid = target,
                    senderName = "Admin",
                    itemBase64 = "xyz",
                    message = "Expired",
                    createdAt = now - 20_000L,
                    expiresAt = now - 1L,
                )
            )

            val pending = repo.findPending(target, now)
            assertEquals(1, pending.size)
            assertEquals("d-active", pending.first().id)

            assertTrue(repo.markClaimed("d-active", now + 10L))
            assertFalse(repo.markClaimed("d-active", now + 20L), "Second claim must fail atomically")
            assertTrue(repo.findPending(target, now + 30L).isEmpty())

            val purged = repo.purgeExpired(now)
            assertEquals(1, purged)
        }
    }

    @Test
    fun `ChatProfileRepository persists chatColor and silenceChat and normalizes tags`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("chat.db").toFile())
        db.use {
            val repo = ChatProfileRepository(db)
            val uuid = UUID.randomUUID()
            val record = ChatProfileRecord(
                uuid = uuid,
                nickname = "<gold>Hero</gold>",
                msgToggle = false,
                socialSpy = true,
                chatColor = "<#38bdf8>",
                silenceChat = true,
            )
            repo.upsert(record)

            val loaded = repo.findByUuid(uuid)
            assertNotNull(loaded)
            assertEquals("<#38bdf8>", loaded!!.chatColor)
            assertTrue(loaded.silenceChat)

            assertEquals("<aqua>", ChatService.normalizeChatColorTag("aqua"))
            assertEquals("<#ff55aa>", ChatService.normalizeChatColorTag("<#ff55aa>"))
            assertNull(ChatService.normalizeChatColorTag("reset"))
            assertNull(ChatService.normalizeChatColorTag("off"))
        }
    }

    @Test
    fun `SpawnerChargeRepository persists player spawner mining charges`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("spawner.db").toFile())
        db.use {
            val repo = SpawnerChargeRepository(db)
            val uuid = UUID.randomUUID()
            assertNull(repo.findByUuid(uuid))

            repo.upsert(SpawnerChargeRecord(uuid = uuid, charges = 5, lastUpdated = 5000L))
            assertEquals(5, repo.findByUuid(uuid)?.charges)

            repo.upsert(SpawnerChargeRecord(uuid = uuid, charges = 2, lastUpdated = 6000L))
            assertEquals(2, repo.findByUuid(uuid)?.charges)
        }
    }
}
