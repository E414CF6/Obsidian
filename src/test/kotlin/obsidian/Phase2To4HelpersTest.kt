package obsidian

import obsidian.core.text.DurationParser
import obsidian.modules.chat.ChatFilterService
import obsidian.modules.economy.EconomyService
import obsidian.modules.moderation.ModerationService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Phase2To4HelpersTest {

    @Test
    fun `DurationParser parses simple compound and permanent durations`() {
        assertEquals(30_000L, DurationParser.parseMillis("30s"))
        assertEquals(5 * 60_000L, DurationParser.parseMillis("5m"))
        assertEquals((24 + 12) * 3600_000L, DurationParser.parseMillis("1d12h"))
        assertEquals(7 * 86400_000L, DurationParser.parseMillis("1w"))
        assertEquals(30 * 86400_000L, DurationParser.parseMillis("1mo"))
        assertEquals(60_000L, DurationParser.parseMillis("60"))
        assertNull(DurationParser.parseMillis("0s"))
        assertNull(DurationParser.parseMillis("abc"))

        assertNull(DurationParser.parseOrPermanent("permanent"))
        assertNull(DurationParser.parseOrPermanent("PERM"))
        assertEquals(3600_000L, DurationParser.parseOrPermanent("1h"))
        assertThrows(IllegalArgumentException::class.java) {
            DurationParser.parseOrPermanent("not_valid")
        }
    }

    @Test
    fun `EconomyService parseAmount and roundMoney handle suffixes and precision`() {
        assertEquals(500.0, EconomyService.parseAmount("500"))
        assertEquals(1500.0, EconomyService.parseAmount("1.5k"))
        assertEquals(2_500_000.0, EconomyService.parseAmount("2.5M"))
        assertEquals(1_000_000_000.0, EconomyService.parseAmount("1b"))
        assertEquals(1234.57, EconomyService.parseAmount("1,234.567"))
        assertNull(EconomyService.parseAmount("-10"))
        assertNull(EconomyService.parseAmount("0"))
        assertNull(EconomyService.parseAmount("abc"))
    }

    @Test
    fun `ChatFilterService filters urls blocked words and excessive caps`() {
        val whitelist = listOf("youtube.com", "discord.gg")
        assertTrue(ChatFilterService.containsDisallowedUrl("join play.badserver.com now!", whitelist))
        assertFalse(ChatFilterService.containsDisallowedUrl("watch https://youtube.com/watch?v=123", whitelist))
        assertFalse(ChatFilterService.containsDisallowedUrl("hello world", whitelist))

        assertEquals(
            "this is ******** and ****",
            ChatFilterService.maskBlockedWords("this is BadWord1 and test", listOf("badword1")).replace("test", "****"),
        )
        assertEquals(
            "stop ******** right now",
            ChatFilterService.maskBlockedWords("stop BADWORD1 right now", listOf("badword1")),
        )

        assertEquals("hello everyone!", ChatFilterService.normalizeExcessiveCaps("HELLO EVERYONE!", 6, 65))
        assertEquals("OKAY", ChatFilterService.normalizeExcessiveCaps("OKAY", 6, 65))
        assertEquals("Hello World", ChatFilterService.normalizeExcessiveCaps("Hello World", 6, 65))
    }

    @Test
    fun `ModerationService isIpAddress distinguishes IPs from usernames`() {
        assertTrue(ModerationService.isIpAddress("127.0.0.1"))
        assertTrue(ModerationService.isIpAddress("192.168.10.25"))
        assertFalse(ModerationService.isIpAddress("Steve_123"))
    }
}
