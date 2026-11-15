package obsidian

import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import obsidian.core.database.StoredLocation
import obsidian.core.text.TextFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TextFormatterTest {

    @Test
    fun `normalizeLegacyCodes converts ampersand and hex codes into MiniMessage tags`() {
        val normalized = TextFormatter.normalizeLegacyCodes("&aHello &#FF5500World&r!")
        assertEquals("<green>Hello <#FF5500>World<reset>!", normalized)
    }

    @Test
    fun `format replaces placeholders and preserves Adventure styling`() {
        val component = TextFormatter.format(
            "&aWelcome, <gold>{player}</gold>!",
            mapOf("player" to "Alex"),
        )
        val plain = PlainTextComponentSerializer.plainText().serialize(component)
        assertEquals("Welcome, Alex!", plain)
        assertEquals(NamedTextColor.GREEN, component.color())
    }

    @Test
    fun `formatDuration formats zero, seconds, minutes, and hours`() {
        assertEquals("0s", TextFormatter.formatDuration(0))
        assertEquals("1s", TextFormatter.formatDuration(1))
        assertEquals("5s", TextFormatter.formatDuration(4500))
        assertEquals("1m 5s", TextFormatter.formatDuration(65_000))
        assertEquals("1h 2m 3s", TextFormatter.formatDuration((3600 + 120 + 3) * 1000L))
    }

    @Test
    fun `StoredLocation serialize and deserialize round-trip`() {
        val original = StoredLocation("world_nether", 123.5, 64.0, -98.25, 90.0f, -15.5f)
        val encoded = original.serialize()
        val decoded = StoredLocation.deserialize(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `StoredLocation deserialize handles partial or invalid payloads`() {
        assertNull(StoredLocation.deserialize(null))
        assertNull(StoredLocation.deserialize(""))
        assertNull(StoredLocation.deserialize("world;1;2"))
        assertNull(StoredLocation.deserialize("world;bad;2;3"))

        val withoutRotation = StoredLocation.deserialize("world;10.0;70.0;20.0")
        assertEquals(StoredLocation("world", 10.0, 70.0, 20.0, 0f, 0f), withoutRotation)
    }
}
