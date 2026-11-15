package obsidian.integration

import obsidian.core.ObsidianContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Lightweight asynchronous Discord Webhook dispatcher using JDK 25 `HttpClient` (no external dependencies).
 */
class DiscordWebhookService(private val context: ObsidianContext) {

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Volatile
    var webhookUrl: String = ""
        private set

    @Volatile
    var username: String = "Obsidian"
        private set

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        webhookUrl = raw.getString("discord.webhook-url", "")?.trim() ?: ""
        username = raw.getString("discord.username", "Obsidian") ?: "Obsidian"
    }

    fun isConfigured(): Boolean =
        webhookUrl.startsWith("https://", ignoreCase = true) || webhookUrl.startsWith("http://", ignoreCase = true)

    fun sendEmbedAsync(title: String, description: String, colorRgb: Int = 0x7C3AED) {
        if (!isConfigured()) return
        val payload = buildEmbedJson(username, title, description, colorRgb)
        val request = HttpRequest.newBuilder().uri(URI.create(webhookUrl)).timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build()

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally { err ->
            context.plugin.logger.warning("Discord webhook failed: ${err.message}")
            null
        }
    }

    companion object {
        internal fun buildEmbedJson(
            botUsername: String,
            title: String,
            description: String,
            colorRgb: Int,
        ): String {
            return buildString {
                append("{")
                append("\"username\":\"").append(escapeJson(botUsername)).append("\",")
                append("\"embeds\":[{")
                append("\"title\":\"").append(escapeJson(title)).append("\",")
                append("\"description\":\"").append(escapeJson(description)).append("\",")
                append("\"color\":").append(colorRgb and 0xFFFFFF).append(",")
                append("\"timestamp\":\"").append(Instant.now().toString()).append("\"")
                append("}]}")
            }
        }

        internal fun escapeJson(value: String): String = buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
