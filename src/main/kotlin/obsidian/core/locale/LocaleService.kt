package obsidian.core.locale

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import obsidian.core.config.ObsidianConfig
import obsidian.core.config.YamlConfigFile
import obsidian.core.text.TextFormatter
import org.bukkit.plugin.Plugin

/**
 * Loads localized message templates (`locales/<lang>.yml`) and renders Adventure [Component] messages.
 */
class LocaleService(
    private val plugin: Plugin,
    private val config: ObsidianConfig,
) {
    private var primary: YamlConfigFile? = null
    private var fallback: YamlConfigFile = YamlConfigFile(plugin, "locales/en_us.yml")

    init {
        reload()
    }

    private fun normalizeLanguage(rawLang: String): String {
        return when (val clean = rawLang.trim().lowercase().replace('-', '_')) {
            "en", "en_us" -> "en_us"
            "ko", "ko_kr" -> "ko_kr"
            else -> clean
        }
    }

    fun reload() {
        fallback = YamlConfigFile(plugin, "locales/en_us.yml")
        val lang = normalizeLanguage(config.language)
        primary = if (lang == "en_us") {
            fallback
        } else {
            val fileName = "locales/$lang.yml"
            YamlConfigFile(plugin, fileName)
        }
    }

    private fun rawTemplate(key: String): String {
        return primary?.yaml?.getString(key) ?: fallback.yaml.getString(key) ?: key
    }

    fun raw(key: String): String {
        return rawTemplate(key).removePrefix("<error>")
    }

    fun raw(key: String, vararg placeholders: Pair<String, Any?>): String {
        var resolved = raw(key)
        for ((k, v) in placeholders) {
            resolved = resolved.replace("{$k}", v?.toString() ?: "")
        }
        return resolved
    }

    fun rawList(key: String, vararg placeholders: Pair<String, Any?>): List<String> {
        val list = primary?.yaml?.getStringList(key)?.takeIf { it.isNotEmpty() }
            ?: fallback.yaml.getStringList(key)
        if (placeholders.isEmpty()) return list
        return list.map { line ->
            var resolved = line
            for ((k, v) in placeholders) {
                resolved = resolved.replace("{$k}", v?.toString() ?: "")
            }
            resolved
        }
    }

    fun prefix(): Component = TextFormatter.format(raw("prefix"))

    fun errorPrefix(): Component = TextFormatter.format(raw("error-prefix"))

    fun component(key: String, placeholders: Map<String, String> = emptyMap()): Component =
        TextFormatter.format(raw(key), placeholders)

    fun prefixedComponent(key: String, placeholders: Map<String, String> = emptyMap()): Component {
        val template = rawTemplate(key)
        val isError = template.startsWith("<error>")
        val body = if (isError) template.removePrefix("<error>") else template
        val pfx = if (isError) errorPrefix() else prefix()
        return pfx.append(TextFormatter.format(body, placeholders))
    }

    fun send(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendMessage(prefixedComponent(key, map))
    }

    fun sendRaw(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendMessage(component(key, map))
    }

    fun sendActionBar(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendActionBar(component(key, map))
    }
}
