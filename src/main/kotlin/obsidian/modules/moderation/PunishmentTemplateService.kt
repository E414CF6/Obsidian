package obsidian.modules.moderation

import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.PunishmentType
import obsidian.core.text.DurationParser
import org.bukkit.configuration.ConfigurationSection
import java.util.concurrent.ConcurrentHashMap

data class TemplateStep(
    val stepIndex: Int,
    val durationStr: String? = null,
    val durationMillis: Long? = null,
    val reason: String? = null,
    val message: String? = null,
    val permission: String? = null,
    val actions: List<String> = emptyList(),
)

data class PunishmentTemplate(
    val name: String,
    val type: PunishmentType,
    val defaultReason: String? = null,
    val defaultMessage: String? = null,
    val defaultDurationStr: String? = null,
    val defaultDurationMillis: Long? = null,
    val permission: String? = null,
    val expireLadderMillis: Long = 30L * 24 * 3600 * 1000L,
    val ipTemplate: Boolean = false,
    val ladder: List<TemplateStep> = emptyList(),
)

class PunishmentTemplateService(
    private val context: ObsidianContext? = null,
    private val directFile: java.io.File? = null,
) {

    private val configFile = if (context != null) YamlConfigFile(context.plugin, "templates.yml") else null
    private val templates = ConcurrentHashMap<String, PunishmentTemplate>()

    init {
        reload()
    }

    fun reload() {
        configFile?.reload()
        templates.clear()
        val yaml = configFile?.yaml ?: (if (directFile != null && directFile.exists()) {
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directFile)
        } else null) ?: return

        loadSection(yaml.getConfigurationSection("ban-templates"), PunishmentType.BAN)
        loadSection(yaml.getConfigurationSection("mute-templates"), PunishmentType.MUTE)
        loadSection(yaml.getConfigurationSection("warn-templates"), PunishmentType.WARN)
        loadSection(yaml.getConfigurationSection("kick-templates"), PunishmentType.KICK)
    }

    private fun loadSection(section: ConfigurationSection?, type: PunishmentType) {
        if (section == null) return
        for (name in section.getKeys(false)) {
            val sec = section.getConfigurationSection(name) ?: continue
            val reason = sec.getString("reason")
            val message = sec.getString("message")
            val durStr = sec.getString("duration")
            val durMillis = when {
                durStr == null -> null
                durStr.lowercase() in setOf("perm", "permanent", "forever", "inf", "infinite", "0") -> 0L
                else -> DurationParser.parseMillis(durStr)
            }
            val perm = sec.getString("permission")
            val expireStr = sec.getString("expire_ladder", "30d") ?: "30d"
            val expireMillis = DurationParser.parseMillis(expireStr) ?: (30L * 24 * 3600 * 1000L)
            val ipTemplate = sec.getBoolean("ip_template", false)

            val steps = mutableListOf<TemplateStep>()
            sec.getConfigurationSection("ladder")?.let { ladderSec ->
                val keys = ladderSec.getKeys(false).toList()
                val sortedKeys = sortLadderKeys(keys)
                for ((idx, key) in sortedKeys.withIndex()) {
                    val stepSec = ladderSec.getConfigurationSection(key)
                    val sDur = stepSec?.getString("duration") ?: durStr
                    val sDurMillis = when {
                        sDur == null -> durMillis
                        sDur.lowercase() in setOf("perm", "permanent", "forever", "inf", "infinite", "0") -> 0L
                        else -> DurationParser.parseMillis(sDur) ?: durMillis
                    }
                    val sReason = stepSec?.getString("reason") ?: reason
                    val sMessage = stepSec?.getString("message") ?: message
                    val sPerm = stepSec?.getString("permission") ?: perm
                    val sActions = stepSec?.getStringList("actions") ?: emptyList()

                    steps += TemplateStep(
                        stepIndex = idx + 1,
                        durationStr = sDur,
                        durationMillis = sDurMillis,
                        reason = sReason,
                        message = sMessage,
                        permission = sPerm,
                        actions = sActions,
                    )
                }
            }

            val template = PunishmentTemplate(
                name = name,
                type = type,
                defaultReason = reason,
                defaultMessage = message,
                defaultDurationStr = durStr,
                defaultDurationMillis = durMillis,
                permission = perm,
                expireLadderMillis = expireMillis,
                ipTemplate = ipTemplate,
                ladder = steps,
            )
            templates[name.lowercase()] = template
        }
    }

    private fun sortLadderKeys(keys: List<String>): List<String> {
        val ordinalMap = mapOf(
            "first" to 1, "1st" to 1, "1" to 1,
            "second" to 2, "2nd" to 2, "2" to 2,
            "third" to 3, "3rd" to 3, "3" to 3,
            "fourth" to 4, "4th" to 4, "4" to 4,
            "fifth" to 5, "5th" to 5, "5" to 5,
            "sixth" to 6, "6th" to 6, "6" to 6,
            "seventh" to 7, "7th" to 7, "7" to 7,
            "eighth" to 8, "8th" to 8, "8" to 8,
            "ninth" to 9, "9th" to 9, "9" to 9,
            "tenth" to 10, "10th" to 10, "10" to 10,
        )
        // If keys match known ordinals, sort by ordinal value. Otherwise preserve order.
        val hasKnownOrdinal = keys.any { it.lowercase() in ordinalMap }
        return if (hasKnownOrdinal) {
            keys.sortedBy { ordinalMap[it.lowercase()] ?: Int.MAX_VALUE }
        } else {
            keys
        }
    }

    fun getTemplate(name: String, type: PunishmentType): PunishmentTemplate? {
        val t = templates[name.lowercase()] ?: return null
        // BAN templates can also apply to IP_BAN; MUTE templates can apply to IP_MUTE / SHADOW_MUTE
        val matchesType = when (type) {
            PunishmentType.BAN, PunishmentType.IP_BAN -> t.type == PunishmentType.BAN
            PunishmentType.MUTE, PunishmentType.IP_MUTE, PunishmentType.SHADOW_MUTE -> t.type == PunishmentType.MUTE
            PunishmentType.WARN -> t.type == PunishmentType.WARN
            PunishmentType.KICK -> t.type == PunishmentType.KICK
            else -> t.type == type
        }
        return if (matchesType) t else null
    }

    fun getKnownTemplateNames(type: PunishmentType): Set<String> {
        return templates.values
            .filter {
                when (type) {
                    PunishmentType.BAN, PunishmentType.IP_BAN -> it.type == PunishmentType.BAN
                    PunishmentType.MUTE, PunishmentType.IP_MUTE, PunishmentType.SHADOW_MUTE -> it.type == PunishmentType.MUTE
                    PunishmentType.WARN -> it.type == PunishmentType.WARN
                    PunishmentType.KICK -> it.type == PunishmentType.KICK
                    else -> it.type == type
                }
            }
            .map { it.name }
            .toSet()
    }

    /**
     * Given the number of previous strikes, returns the active step to apply.
     */
    fun evaluateStep(template: PunishmentTemplate, strikeCount: Int): TemplateStep {
        if (template.ladder.isEmpty()) {
            return TemplateStep(
                stepIndex = 1,
                durationStr = template.defaultDurationStr,
                durationMillis = template.defaultDurationMillis,
                reason = template.defaultReason,
                message = template.defaultMessage,
                permission = template.permission,
                actions = emptyList(),
            )
        }
        val targetIndex = (strikeCount).coerceIn(0, template.ladder.size - 1)
        return template.ladder[targetIndex]
    }
}
