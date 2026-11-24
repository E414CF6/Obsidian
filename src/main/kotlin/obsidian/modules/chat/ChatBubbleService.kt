package obsidian.modules.chat

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import obsidian.modules.moderation.ModerationService
import obsidian.modules.utility.FlightAndCombatService
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Displays temporary floating [TextDisplay] chat bubbles above players when they chat
 * (modeled after CMI's `ChatBubbleManager`).
 */
class ChatBubbleService(private val context: ObsidianContext) : Listener {

    private data class ActiveBubble(
        val display: TextDisplay,
        val task: ScheduledTask?,
    )

    private val activeBubbles = ConcurrentHashMap<UUID, ActiveBubble>()

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var offsetY: Double = 2.35
        private set

    @Volatile
    var baseSeconds: Double = 4.0
        private set

    @Volatile
    var maxSeconds: Double = 10.0
        private set

    @Volatile
    var extraSecondsPerChar: Double = 0.03
        private set

    @Volatile
    var lineWidth: Int = 180
        private set

    @Volatile
    var scale: Float = 1.0f
        private set

    @Volatile
    var backgroundAlpha: Int = 128
        private set

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        enabled = raw.getBoolean("chat.bubble.enabled", true)
        offsetY = raw.getDouble("chat.bubble.offset", 2.35)
        baseSeconds = raw.getDouble("chat.bubble.base-seconds", 4.0).coerceAtLeast(1.0)
        maxSeconds = raw.getDouble("chat.bubble.max-seconds", 10.0).coerceAtLeast(baseSeconds)
        extraSecondsPerChar = raw.getDouble("chat.bubble.extra-seconds-per-char", 0.03).coerceAtLeast(0.0)
        lineWidth = raw.getInt("chat.bubble.line-width", 180).coerceIn(60, 400)
        scale = raw.getDouble("chat.bubble.scale", 1.0).toFloat().coerceIn(0.3f, 3.0f)
        backgroundAlpha = raw.getInt("chat.bubble.background-alpha", 128).coerceIn(0, 255)
    }

    fun showBubble(player: Player, text: Component, plainLength: Int) {
        if (!enabled) return
        val opts = context.getService<FlightAndCombatService>()
        if (opts != null && !opts.isChatBubbleEnabled(player)) return
        val mod = context.getService<ModerationService>()
        if (mod != null && mod.isVanished(player.uniqueId)) return

        val durationSec = (baseSeconds + plainLength * extraSecondsPerChar).coerceIn(baseSeconds, maxSeconds)
        val expiresAt = System.currentTimeMillis() + (durationSec * 1000.0).toLong()

        context.scheduler.runForEntity(player) {
            if (!player.isOnline || player.isDead) return@runForEntity
            removeBubble(player.uniqueId)

            val spawnLoc = player.location.clone().add(0.0, offsetY, 0.0)
            val display = runCatching {
                player.world.spawn(spawnLoc, TextDisplay::class.java) { entity ->
                    entity.isPersistent = false
                    entity.text(text)
                    entity.billboard = Display.Billboard.CENTER
                    entity.lineWidth = lineWidth
                    entity.backgroundColor = Color.fromARGB(backgroundAlpha, 0, 0, 0)
                    entity.isSeeThrough = false
                    entity.isShadowed = true
                    entity.transformation = Transformation(
                        Vector3f(0f, 0f, 0f),
                        AxisAngle4f(0f, 0f, 0f, 1f),
                        Vector3f(scale, scale, scale),
                        AxisAngle4f(0f, 0f, 0f, 1f),
                    )
                }
            }.getOrNull() ?: return@runForEntity

            val followTask = context.scheduler.runForEntityTimer(
                player,
                initialDelayTicks = 2L,
                periodTicks = 2L,
                retired = { removeDisplayEntity(display) },
            ) { scheduled ->
                if (!player.isOnline || !display.isValid || System.currentTimeMillis() >= expiresAt ||
                    display.world != player.world
                ) {
                    scheduled.cancel()
                    activeBubbles.remove(player.uniqueId)
                    removeDisplayEntity(display)
                    return@runForEntityTimer
                }
                display.teleportAsync(player.location.clone().add(0.0, offsetY, 0.0))
            }

            activeBubbles[player.uniqueId] = ActiveBubble(display, followTask)
        }
    }

    fun removeBubble(uuid: UUID) {
        val active = activeBubbles.remove(uuid) ?: return
        active.task?.cancel()
        removeDisplayEntity(active.display)
    }

    fun shutdown() {
        for (uuid in activeBubbles.keys.toList()) {
            removeBubble(uuid)
        }
    }

    private fun removeDisplayEntity(display: TextDisplay) {
        if (!display.isValid) return
        if (!context.plugin.isEnabled) {
            runCatching { display.remove() }
            return
        }
        context.scheduler.runForEntity(display) {
            if (display.isValid) display.remove()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        removeBubble(event.player.uniqueId)
    }
}
