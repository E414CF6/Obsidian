package obsidian

import obsidian.integration.DiscordWebhookService
import obsidian.modules.automation.ScheduleService
import obsidian.modules.proxy.ProxyModule
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.LocalTime

class Phase6HelpersTest {

    @Test
    fun `ProxyModule encodes standard BungeeCord plugin messages`() {
        val connect = ProxyModule.encodeConnect("lobby")
        DataInputStream(ByteArrayInputStream(connect)).use { inp ->
            assertEquals("Connect", inp.readUTF())
            assertEquals("lobby", inp.readUTF())
        }

        val connectOther = ProxyModule.encodeConnectOther("Steve", "survival")
        DataInputStream(ByteArrayInputStream(connectOther)).use { inp ->
            assertEquals("ConnectOther", inp.readUTF())
            assertEquals("Steve", inp.readUTF())
            assertEquals("survival", inp.readUTF())
        }

        val message = ProxyModule.encodeMessage("ALL", "Hello Proxy")
        DataInputStream(ByteArrayInputStream(message)).use { inp ->
            assertEquals("Message", inp.readUTF())
            assertEquals("ALL", inp.readUTF())
            assertEquals("Hello Proxy", inp.readUTF())
        }
    }

    @Test
    fun `ScheduleService parses HHmm daily times safely`() {
        assertEquals(LocalTime.of(4, 0), ScheduleService.parseTime("04:00"))
        assertEquals(LocalTime.of(9, 30), ScheduleService.parseTime("9:30"))
        assertNull(ScheduleService.parseTime("25:99"))
        assertNull(ScheduleService.parseTime("invalid"))
    }

    @Test
    fun `DiscordWebhookService builds escaped embed JSON payloads`() {
        val json = DiscordWebhookService.buildEmbedJson(
            botUsername = "Obsidian \"Bot\"",
            title = "Ban\nAlert",
            description = "Player\\Steve was banned",
            colorRgb = 0xEF4444,
        )
        assertTrue(json.contains("\"username\":\"Obsidian \\\"Bot\\\"\""))
        assertTrue(json.contains("\"title\":\"Ban\\nAlert\""))
        assertTrue(json.contains("\"description\":\"Player\\\\Steve was banned\""))
        assertTrue(json.contains("\"color\":15680580"))
    }
}
