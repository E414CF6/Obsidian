package obsidian

import kotlinx.coroutines.runBlocking
import obsidian.core.database.DatabaseManager
import obsidian.core.database.PunishmentRecord
import obsidian.core.database.PunishmentRepository
import obsidian.core.database.PunishmentType
import obsidian.modules.moderation.PunishmentFlagParser
import obsidian.modules.moderation.PunishmentTemplateService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class LiteBansIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `PunishmentFlagParser correctly parses flags durations templates and sender`() {
        val input = listOf("-s", "--modify", "-N", "--sender=HeadAdmin", "spam", "1d12h", "Spammed", "discord", "link")
        val parsed = PunishmentFlagParser.parse(input, knownTemplates = setOf("spam"))

        assertEquals(true, parsed.silent)
        assertTrue(parsed.noOverride)
        assertTrue(parsed.modify)
        assertEquals("HeadAdmin", parsed.customSender)
        assertEquals("spam", parsed.templateName)
        assertEquals(129600000L, parsed.durationMillis) // 1d 12h = 36h = 36 * 3600 * 1000 = 129600000 ms
        assertEquals("Spammed discord link", parsed.reason)
    }

    @Test
    fun `PunishmentFlagParser handles short flags and permanent duration`() {
        val input = listOf("-p", "perm", "Toxic", "behavior")
        val parsed = PunishmentFlagParser.parse(input)

        assertEquals(false, parsed.silent)
        assertFalse(parsed.noOverride)
        assertFalse(parsed.modify)
        assertNull(parsed.templateName)
        assertNull(parsed.customSender)
        assertEquals(0L, parsed.durationMillis)
        assertEquals("Toxic behavior", parsed.reason)
    }

    @Test
    fun `PunishmentFlagParser handles no flags and empty remaining text`() {
        val parsed = PunishmentFlagParser.parse(emptyList())
        assertNull(parsed.silent)
        assertFalse(parsed.noOverride)
        assertFalse(parsed.modify)
        assertNull(parsed.templateName)
        assertNull(parsed.durationMillis)
        assertNull(parsed.reason)
    }

    @Test
    fun `PunishmentTemplateService parses templates and matches ladder strikes`() {
        val tempFile = tempDir.resolve("templates.yml").toFile()
        tempFile.writeText(
            """
            ban-templates:
              grief:
                message: "Griefing - Strike {strike}"
                expire: "30d"
                ladder:
                  1:
                    duration: "1d"
                  2:
                    duration: "7d"
                  3:
                    duration: "perm"
                    actions:
                      - "say {player} was permanently banned for griefing"
            mute-templates:
              spam:
                message: "Chat Spamming"
                duration: "30m"
            """.trimIndent()
        )

        val service = PunishmentTemplateService(directFile = tempFile)

        val griefTpl = service.getTemplate("grief", PunishmentType.BAN)
        assertNotNull(griefTpl)
        assertNotNull(service.getTemplate("spam", PunishmentType.MUTE))
        assertNull(service.getTemplate("unknown", PunishmentType.WARN))

        // Strike 1
        val res1 = service.evaluateStep(griefTpl!!, strikeCount = 0)
        assertEquals(86400000L, res1.durationMillis) // 1d
        assertTrue(res1.actions.isEmpty())

        // Strike 2
        val res2 = service.evaluateStep(griefTpl, strikeCount = 1)
        assertEquals(7L * 86400000L, res2.durationMillis) // 7d

        // Strike 3
        val res3 = service.evaluateStep(griefTpl, strikeCount = 2)
        assertEquals(0L, res3.durationMillis) // perm
        assertEquals(1, res3.actions.size)
        assertTrue(res3.actions[0].contains("{player}"))

        // Template strike expire duration (30d)
        assertEquals(30L * 86400000L, griefTpl.expireLadderMillis)
    }

    @Test
    fun `PunishmentRepository features round-trip in SQLite`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("repository_test.db").toFile())
        db.use {
            val repo = PunishmentRepository(db)

            val staffUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val targetAltUuid = UUID.randomUUID()
            val ip = "192.168.1.55"

            // 1. Name history & last UUID
            repo.recordNameHistory(targetUuid, "OriginalPlayer", 1000L)
            repo.recordNameHistory(targetUuid, "RenamedPlayer", 2000L)
            val names = repo.findNameHistory(targetUuid)
            assertEquals(2, names.size)
            assertEquals("RenamedPlayer", names[0].username)
            assertEquals("OriginalPlayer", names[1].username)

            val foundUuid = repo.findLastUuid("OriginalPlayer")
            assertEquals(targetUuid, foundUuid)

            // 2. Punishment Allow-List
            assertFalse(repo.isAllowed(targetUuid))
            repo.addAllow(targetUuid, "Admin")
            assertTrue(repo.isAllowed(targetUuid))
            repo.removeAllow(targetUuid)
            assertFalse(repo.isAllowed(targetUuid))

            // 3. IP Wildcard Matching
            val wildcardBan = PunishmentRecord(
                type = PunishmentType.IP_BAN,
                targetUuid = null,
                targetName = "WildcardBan",
                targetIp = "192.168.1.*",
                actorUuid = staffUuid,
                actorName = "Admin",
                reason = "Subnet ban",
                createdAt = 1000L,
            )
            repo.insert(wildcardBan)

            val matching = repo.findActiveByIpWildcard(ip, PunishmentType.IP_BAN)
            assertEquals(1, matching.size)
            assertEquals("Subnet ban", matching[0].reason)

            assertTrue(repo.ipMatches("192.168.1.*", "192.168.1.55"))
            assertTrue(repo.ipMatches("10.0.*.*", "10.0.5.21"))
            assertFalse(repo.ipMatches("192.168.1.*", "192.168.2.1"))

            // 4. Update punishment (--modify)
            val insertedBan = matching[0]
            val updated = repo.updatePunishment(
                id = insertedBan.id,
                reason = "Updated subnet ban",
                expiresAt = 5000000L,
                silent = true,
            )
            assertTrue(updated)
            val reloaded = repo.findHistory(type = PunishmentType.IP_BAN, limit = 10, offset = 0)
            assertEquals(1, reloaded.size)
            assertEquals("Updated subnet ban", reloaded[0].reason)
            assertEquals(5000000L, reloaded[0].expiresAt)
            assertTrue(reloaded[0].silent)

            // 5. Staff history & staff rollback
            val staffBan1 = PunishmentRecord(
                type = PunishmentType.BAN,
                targetUuid = targetUuid,
                targetName = "Target1",
                actorUuid = staffUuid,
                actorName = "AdminStaff",
                reason = "Bad action",
                createdAt = 2000L,
            )
            val staffBan2 = PunishmentRecord(
                type = PunishmentType.BAN,
                targetUuid = targetAltUuid,
                targetName = "Target2",
                actorUuid = staffUuid,
                actorName = "AdminStaff",
                reason = "Bad action 2",
                createdAt = 3000L,
            )
            repo.insert(staffBan1)
            repo.insert(staffBan2)

            val staffPuns = repo.findStaffHistory(actorUuid = staffUuid, actorName = null, limit = 10, offset = 0)
            assertEquals(3, staffPuns.size) // wildcard ban + staffBan1 + staffBan2
            assertEquals(3, repo.countStaffHistory(actorUuid = staffUuid, actorName = null))

            // Rollback staff actions within last 1 hour
            val rolledBackCount = repo.rollbackByStaff(
                actorUuid = staffUuid,
                actorName = "AdminStaff",
                sinceMillis = 0L,
                removedBy = "Owner",
            )
            assertEquals(3, rolledBackCount)

            // Check that active punishments are now 0
            val activeBans = repo.listActive(listOf(PunishmentType.BAN), limit = 10, offset = 0)
            assertEquals(0, activeBans.size)

            // 6. Prune history
            val pruned = repo.pruneHistory(beforeMillis = System.currentTimeMillis() + 1000000L)
            assertEquals(3, pruned)
            assertEquals(0, repo.countHistory(targetUuid = null, targetIp = null))
        }
    }
}
