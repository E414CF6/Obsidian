package obsidian

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LocaleParityTest {

    private fun collectLeafKeys(yamlPath: Path): Set<String> {
        val cfg = YamlConfiguration.loadConfiguration(yamlPath.toFile())
        return cfg.getKeys(true)
            .filter { key -> !cfg.isConfigurationSection(key) }
            .toSet()
    }

    @Test
    fun `locales en_us and ko_kr have identical leaf keys`() {
        val localesDir = Path.of("src/main/resources/locales")
        val enKeys = collectLeafKeys(localesDir.resolve("en_us.yml"))
        val koKeys = collectLeafKeys(localesDir.resolve("ko_kr.yml"))

        assertTrue(enKeys.isNotEmpty(), "locales/en_us.yml should not be empty")
        assertEquals(emptySet<String>(), enKeys - koKeys, "Keys missing in locales/ko_kr.yml")
        assertEquals(emptySet<String>(), koKeys - enKeys, "Keys missing in locales/en_us.yml")
    }

    @Test
    fun `all literal locale keys referenced in Kotlin sources exist in locale files`() {
        val enKeys = collectLeafKeys(Path.of("src/main/resources/locales/en_us.yml"))
        val localeCallRegex = Regex(
            """(?:locale|messages)\.(?:(?:send|sendRaw|sendActionBar)\(\s*[^,\n]+,\s*"([a-z0-9_.-]+)"|(?:get|component|prefixedComponent|raw|rawList)\(\s*"([a-z0-9_.-]+)")"""
        )

        val missing = sortedSetOf<String>()
        Files.walk(Path.of("src/main/kotlin")).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readString(file)
                for (match in localeCallRegex.findAll(text)) {
                    val key = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    if (key !in enKeys) {
                        missing += "$key (${file.fileName})"
                    }
                }
            }
        }

        assertEquals(emptySet<String>(), missing, "Missing locale keys referenced in source code")
    }
}
