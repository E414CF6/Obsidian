package obsidian.core.text

import java.util.regex.Pattern

/**
 * Parses and formats compact human-readable duration strings (e.g. `30m`, `1d12h`, `permanent`).
 */
object DurationParser {

    private val tokenPattern = Pattern.compile("(\\d+)(mo|[smhdwy])", Pattern.CASE_INSENSITIVE)

    /**
     * Parses [raw] into positive duration milliseconds, or `null` if [raw] represents a permanent duration
     * (`perm`, `permanent`, `forever`, `inf`), or throws [IllegalArgumentException] on invalid syntax.
     */
    fun parseOrPermanent(raw: String): Long? {
        val trimmed = raw.trim().lowercase()
        if (trimmed in setOf("perm", "permanent", "forever", "inf", "infinite")) {
            return null
        }
        val millis = parseMillis(trimmed)
            ?: throw IllegalArgumentException("Invalid duration: $raw")
        return millis
    }

    /**
     * Parses [raw] into duration milliseconds, or returns `null` if invalid or non-positive.
     * Supports bare seconds (`60`) and compound tokens (`1d12h30m`).
     */
    fun parseMillis(raw: String): Long? {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty()) return null

        trimmed.toLongOrNull()?.let { bareSeconds ->
            return if (bareSeconds > 0L) bareSeconds * 1000L else null
        }

        val matcher = tokenPattern.matcher(trimmed)
        var totalSeconds = 0L
        var matchedChars = 0
        while (matcher.find()) {
            if (matcher.start() != matchedChars) return null
            matchedChars = matcher.end()
            val value = matcher.group(1).toLongOrNull() ?: return null
            val unitMultiplier = when (matcher.group(2).lowercase()) {
                "s" -> 1L
                "m" -> 60L
                "h" -> 3600L
                "d" -> 86400L
                "w" -> 604800L
                "mo" -> 2592000L
                "y" -> 31536000L
                else -> return null
            }
            totalSeconds += value * unitMultiplier
        }
        if (matchedChars != trimmed.length || totalSeconds <= 0L) return null
        return totalSeconds * 1000L
    }

    fun formatExpiry(expiresAt: Long?, now: Long = System.currentTimeMillis()): String {
        if (expiresAt == null) return "Permanent"
        val remaining = (expiresAt - now).coerceAtLeast(0L)
        return TextFormatter.formatDuration(remaining)
    }
}
