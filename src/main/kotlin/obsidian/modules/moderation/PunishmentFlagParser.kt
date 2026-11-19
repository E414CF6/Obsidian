package obsidian.modules.moderation

import obsidian.core.text.DurationParser

data class ParsedPunishmentInput(
    val silent: Boolean? = null,
    val noOverride: Boolean = false,
    val modify: Boolean = false,
    val customSender: String? = null,
    val durationMillis: Long? = null,
    val templateName: String? = null,
    val reason: String? = null,
)

object PunishmentFlagParser {

    /**
     * Parses command tokens into flags, duration, template name, and reason string.
     */
    fun parse(
        tokens: List<String>,
        knownTemplates: Set<String> = emptySet(),
        allowDuration: Boolean = true,
    ): ParsedPunishmentInput {
        var silent: Boolean? = null
        var noOverride = false
        var modify = false
        var customSender: String? = null
        var durationMillis: Long? = null
        var templateName: String? = null
        val reasonTokens = mutableListOf<String>()

        for (token in tokens) {
            when {
                token == "-s" -> silent = true
                token == "-p" -> silent = false
                token == "-N" -> noOverride = true
                token == "--modify" -> modify = true
                token.startsWith("--sender=") -> {
                    customSender = token.substringAfter("--sender=").trim().takeIf { it.isNotEmpty() }
                }

                allowDuration && durationMillis == null && (token.lowercase() in setOf(
                    "perm",
                    "permanent",
                    "forever",
                    "inf",
                    "infinite"
                ) || DurationParser.parseMillis(token) != null) -> {
                    durationMillis = if (token.lowercase() in setOf(
                            "perm",
                            "permanent",
                            "forever",
                            "inf",
                            "infinite"
                        )
                    ) 0L else DurationParser.parseMillis(token)
                }

                templateName == null && knownTemplates.isNotEmpty() && knownTemplates.any {
                    it.equals(
                        token,
                        ignoreCase = true
                    )
                } -> {
                    templateName = knownTemplates.first { it.equals(token, ignoreCase = true) }
                }

                else -> {
                    reasonTokens += token
                }
            }
        }

        val reason = reasonTokens.joinToString(" ").trim().takeIf { it.isNotEmpty() }
        return ParsedPunishmentInput(
            silent = silent,
            noOverride = noOverride,
            modify = modify,
            customSender = customSender,
            durationMillis = durationMillis,
            templateName = templateName,
            reason = reason,
        )
    }

    fun splitArgs(input: String): List<String> =
        input.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
}
