package obsidian.modules.chat

import obsidian.core.ObsidianContext
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Pure & configurable chat filter supporting anti-spam, anti-caps, anti-advertising, and blocked words.
 */
class ChatFilterService(private val context: ObsidianContext) {

    var enabled: Boolean = true
        private set
    var spamCooldownMs: Long = 1200L
        private set
    var antiCaps: Boolean = true
        private set
    var capsMinLength: Int = 6
        private set
    var capsThresholdPercent: Int = 65
        private set
    var blockUrls: Boolean = true
        private set
    var whitelistedDomains: List<String> = emptyList()
        private set
    var blockedWords: List<String> = emptyList()
        private set

    private val lastMessageAt = ConcurrentHashMap<UUID, Long>()

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        enabled = raw.getBoolean("chat.filter.enabled", true)
        spamCooldownMs = raw.getLong("chat.filter.spam-cooldown-ms", 1200L).coerceAtLeast(0L)
        antiCaps = raw.getBoolean("chat.filter.anti-caps", true)
        capsMinLength = raw.getInt("chat.filter.caps-min-length", 6).coerceAtLeast(1)
        capsThresholdPercent = raw.getInt("chat.filter.caps-threshold-percent", 65).coerceIn(1, 100)
        blockUrls = raw.getBoolean("chat.filter.block-urls", true)
        whitelistedDomains = raw.getStringList("chat.filter.whitelisted-domains").map { it.lowercase() }
        blockedWords = raw.getStringList("chat.filter.blocked-words").filter { it.isNotBlank() }
    }

    fun checkAndProcess(
        senderUuid: UUID?,
        rawMessage: String,
        bypass: Boolean,
        now: Long = System.currentTimeMillis()
    ): FilterOutcome {
        if (!enabled || bypass) return FilterOutcome.Allowed(rawMessage)

        if (senderUuid != null && spamCooldownMs > 0L) {
            val prev = lastMessageAt[senderUuid]
            if (prev != null && (now - prev) < spamCooldownMs) {
                return FilterOutcome.Blocked("chat.filter-spam")
            }
            lastMessageAt[senderUuid] = now
        }

        if (blockUrls && containsDisallowedUrl(rawMessage, whitelistedDomains)) {
            return FilterOutcome.Blocked("chat.filter-url")
        }

        var processed = maskBlockedWords(rawMessage, blockedWords)
        if (antiCaps) {
            processed = normalizeExcessiveCaps(processed, capsMinLength, capsThresholdPercent)
        }
        return FilterOutcome.Allowed(processed)
    }

    sealed interface FilterOutcome {
        data class Allowed(val processedMessage: String) : FilterOutcome
        data class Blocked(val localeKey: String) : FilterOutcome
    }

    companion object {
        private val urlPattern = Pattern.compile(
            """(?:https?://)?(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}(?:/[^\s]*)?""",
            Pattern.CASE_INSENSITIVE,
        )

        fun containsDisallowedUrl(text: String, whitelistedDomains: Collection<String>): Boolean {
            val matcher = urlPattern.matcher(text)
            while (matcher.find()) {
                val match = matcher.group().lowercase()
                val allowed = whitelistedDomains.any { domain -> match.contains(domain.lowercase()) }
                if (!allowed) return true
            }
            return false
        }

        fun maskBlockedWords(text: String, blockedWords: Collection<String>): String {
            if (blockedWords.isEmpty()) return text
            var result = text
            for (word in blockedWords) {
                if (word.isBlank()) continue
                val regex = Regex(Regex.escape(word), RegexOption.IGNORE_CASE)
                result = regex.replace(result) { match -> "*".repeat(match.value.length) }
            }
            return result
        }

        fun normalizeExcessiveCaps(text: String, minLength: Int, thresholdPercent: Int): String {
            val letters = text.count { it.isLetter() }
            if (letters < minLength) return text
            val upper = text.count { it.isUpperCase() }
            val percent = (upper * 100.0) / letters
            return if (percent >= thresholdPercent) text.lowercase() else text
        }
    }
}
