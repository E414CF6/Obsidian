package obsidian

import kotlinx.coroutines.runBlocking
import obsidian.core.database.*
import obsidian.core.user.UserProfile
import obsidian.modules.stats.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.*

class StatsModuleTest {

    @Test
    fun `world data reader strips legacy hex and minimessage codes and parses vanilla files`(@TempDir tempDir: Path) {
        val cleaned = WorldDataReader.stripMinecraftCodes("{#ff8800}<gradient:#7c3aed:#06b6d4>&l테스트유저</gradient>&r")
        assertEquals("테스트유저", cleaned)
        assertEquals("stone", WorldDataReader.stripNamespace("minecraft:stone"))

        val uuid = UUID.randomUUID().toString()
        val statsDir = tempDir.resolve("stats")
        val advDir = tempDir.resolve("advancements")
        Files.createDirectories(statsDir)
        Files.createDirectories(advDir)

        Files.writeString(
            statsDir.resolve("$uuid.json"),
            """
            {
              "stats": {
                "minecraft:custom": {
                  "minecraft:deaths": 2,
                  "minecraft:mob_kills": 17,
                  "minecraft:walk_one_cm": 5000,
                  "minecraft:sprint_one_cm": 2500,
                  "minecraft:damage_dealt": 240
                },
                "minecraft:mined": {
                  "minecraft:diamond_ore": 6,
                  "minecraft:stone": 42
                }
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            advDir.resolve("$uuid.json"),
            """
            {
              "DataVersion": 3955,
              "minecraft:story/root": {
                "done": true,
                "criteria": { "crafting_table": "2026-11-15 10:00:00 +0900" }
              },
              "minecraft:adventure/adventuring_time": {
                "done": false,
                "criteria": { "minecraft:plains": "2026-11-15 10:05:00 +0900" }
              }
            }
            """.trimIndent(),
        )

        val vanilla = WorldDataReader.readVanillaStats(tempDir, uuid)
        assertEquals(2L, vanilla.deaths)
        assertEquals(17L, vanilla.mobKills)
        assertEquals(7500L, vanilla.totalDistanceCm)
        assertEquals(24.0, vanilla.damageDealt, 0.001)
        assertEquals(48L, vanilla.totalMined)
        assertEquals("stone", vanilla.topMined.first().id)

        val adv = WorldDataReader.readAdvancements(tempDir, uuid, totalBiomesTarget = 60)
        assertEquals(1L, adv.totalCompleted)
        assertEquals(1, adv.categories["story"])
        assertEquals(1, adv.visitedBiomes.size)
        assertEquals("plains", adv.visitedBiomes.first().id)
        assertEquals(60, adv.totalBiomesTarget)

        val specialty = WorldDataReader.computeSpecialty(
            mapOf("mining" to PlayerSkillStat(120, 40, 100), "swords" to PlayerSkillStat(20, 0, 100))
        )
        assertEquals("gathering", specialty.category)
        assertEquals("gathering_expert", specialty.key)
        assertEquals("mining", specialty.topSkillKey)
    }

    @Test
    fun `rsa auth generates pem keys and validates signed payloads`(@TempDir tempDir: Path) {
        val keysDir = tempDir.resolve("keys")
        val auth = RsaAuthService(keysDir, maxClockSkewSeconds = 60L)
        assertTrue(auth.ensureKeys())
        assertFalse(auth.ensureKeys())
        assertTrue(Files.exists(auth.publicKeyFile))
        assertTrue(Files.exists(auth.privateKeyFile))

        val privateKey = RsaAuthService.loadPrivateKey(auth.privateKeyFile)
        val now = Instant.now().epochSecond
        val sig = RsaAuthService.signPayload(privateKey, "GET", "/api/stats", now)

        assertTrue(auth.verifySignedRequest("GET", "/api/stats", now.toString(), sig, now))
        // Also verify millisecond timestamp format used by the Next.js web dashboard (`Date.now()`)
        val nowMs = now * 1000L + 250L
        val sigMs = RsaAuthService.signPayload(privateKey, "GET", "/api/stats", nowMs)
        assertTrue(auth.verifySignedRequest("GET", "/api/stats", nowMs.toString(), sigMs, now))

        assertFalse(auth.verifySignedRequest("GET", "/api/players/123", now.toString(), sig, now))
        assertFalse(auth.verifySignedRequest("GET", "/api/stats", (now - 120).toString(), sig, now))
    }

    @Test
    fun `aggregator and embedded http server serve signed stats end to end`(@TempDir tempDir: Path) {
        val db = DatabaseManager.forSqlite(tempDir.resolve("stats-test.db").toFile())

        val playerUuid = UUID.randomUUID()
        val offlineUuid = UUID.randomUUID()
        val today = LocalDate.now().toString()

        runBlocking {
            UserRepository(db).upsertAll(
                listOf(
                    UserProfile(uuid = playerUuid, username = "Alice", lastLogin = 2000L, lastLogout = 1000L),
                    UserProfile(uuid = offlineUuid, username = "Bob", lastLogin = 1000L, lastLogout = 1500L),
                )
            )
            EconomyRepository(db).upsertAll(
                listOf(
                    EconomyRecord(uuid = playerUuid, username = "Alice", balance = 1234.5),
                    EconomyRecord(uuid = offlineUuid, username = "Bob", balance = 500.0),
                )
            )
            PlayTimeRepository(db).upsertAll(
                listOf(
                    PlayTimeRecord(
                        uuid = playerUuid,
                        username = "Alice",
                        totalMillis = 3_600_000L,
                        dailyMillis = 600_000L,
                        dayKey = today,
                        currentRank = "Iron",
                    )
                )
            )
            ChatProfileRepository(db).upsert(ChatProfileRecord(uuid = playerUuid, nickname = "<aqua>별빛</aqua>"))
        }

        val statsDir = tempDir.resolve("world").resolve("stats")
        Files.createDirectories(statsDir)
        Files.writeString(
            statsDir.resolve("$playerUuid.json"),
            """{"stats":{"minecraft:mined":{"minecraft:stone":10},"minecraft:custom":{"minecraft:walk_one_cm":2000}}}""",
        )

        val rsa = RsaAuthService(tempDir.resolve("keys"), 300L).apply { ensureKeys() }
        val aggregator = StatsAggregatorService(
            db = db,
            worldDirProvider = { tempDir.resolve("world") },
            onlinePlayersProvider = {
                mapOf(
                    playerUuid to OnlinePlayerSnapshot(
                        uuid = playerUuid,
                        username = "Alice",
                        displayName = "<aqua>별빛</aqua>",
                        primaryGroup = "vip",
                        currentRank = "Iron",
                        totalPlayTimeMs = 3_700_000L,
                        dailyPlayTimeMs = 700_000L,
                        dayKey = today,
                    )
                )
            },
            skillsProvider = { _, _, _ -> StatsAggregatorService.zeroSkills() },
            metaProvider = {
                ServerMeta(
                    serverName = "Obsidian Test",
                    currency = CurrencyMeta(symbol = "₩", name = "Won", namePlural = "Won"),
                    ranks = listOf(
                        RankTierMeta("default", "Visitor", "member", 0L, 0.0, "#9ca3af", "#4b5563"),
                        RankTierMeta("member", "Member", null, 3600L, 500.0, "#4ade80", "#15803d"),
                    ),
                    capabilities = CapabilitiesMeta(mcmmo = false, claims = false, economy = true, playtime = true),
                )
            },
        ).apply {
            totalBiomesTarget = 58
            ensureSchema()
        }

        val http = StatsHttpServer(aggregator, rsa).apply {
            host = "127.0.0.1"
            port = 0
            requireAuth = true
        }
        assertTrue(http.start())

        try {
            val base = "http://127.0.0.1:${http.boundPort}"
            val client = HttpClient.newHttpClient()

            // /health is unauthenticated
            val healthRes = client.send(
                HttpRequest.newBuilder(URI("$base/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, healthRes.statusCode())
            assertTrue(healthRes.body().contains("\"ok\":true"))

            // unsigned /api/stats returns 401
            val unauthRes = client.send(
                HttpRequest.newBuilder(URI("$base/api/stats")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, unauthRes.statusCode())

            // signed /api/stats returns full payload
            val privateKey = RsaAuthService.loadPrivateKey(rsa.privateKeyFile)
            val ts = Instant.now().epochSecond
            val statsSig = RsaAuthService.signPayload(privateKey, "GET", "/api/stats", ts)
            val statsRes = client.send(
                HttpRequest.newBuilder(URI("$base/api/stats?q=별빛"))
                    .header("x-api-timestamp", ts.toString())
                    .header("x-api-signature", statsSig)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, statsRes.statusCode())
            val parsed = StatsJson.GSON.fromJson(statsRes.body(), StatsApiResponse::class.java)
            assertEquals(2, parsed.overview.totalPlayers)
            assertEquals(1, parsed.overview.onlinePlayers)
            assertEquals(1734.5, parsed.overview.totalBalance, 0.01)
            assertEquals(1, parsed.players.size)
            val alice = parsed.players.first()
            assertEquals("Alice", alice.username)
            assertEquals("별빛", alice.nickname)
            assertEquals("vip", alice.primaryGroup)
            assertEquals("Iron", alice.rank)
            assertEquals("Iron", alice.rankId)
            assertTrue(alice.isOnline)
            assertEquals(3_700_000L, alice.totalPlayTimeMs)
            assertEquals(10L, alice.vanillaStats.totalMined)
            assertEquals(58, alice.advancements.totalBiomesTarget)
            assertEquals("Obsidian Test", parsed.meta.serverName)
            assertEquals("₩", parsed.meta.currency.symbol)
            assertEquals(2, parsed.meta.ranks.size)
            assertFalse(parsed.meta.capabilities.mcmmo)

            // signed /api/players/{id} returns single player detail with meta
            val detailPath = "/api/players/$playerUuid"
            val detailSig = RsaAuthService.signPayload(privateKey, "GET", detailPath, ts)
            val detailRes = client.send(
                HttpRequest.newBuilder(URI("$base$detailPath"))
                    .header("x-api-timestamp", ts.toString())
                    .header("x-api-signature", detailSig)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, detailRes.statusCode())
            val detail = StatsJson.GSON.fromJson(detailRes.body(), PlayerDetailResponse::class.java)
            assertNotNull(detail.player)
            assertEquals(playerUuid.toString(), detail.player.uuid)
            assertEquals("Obsidian Test", detail.meta.serverName)
        } finally {
            http.stop()
            db.close()
        }
    }
}
