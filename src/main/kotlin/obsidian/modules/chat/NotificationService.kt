package obsidian.modules.chat

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.*
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles rich toasts, timed boss bar announcements, countdowns, and sound broadcasting.
 */
class NotificationService(private val context: ObsidianContext) {

    private val activeBars = ConcurrentHashMap<UUID, BossBar>()
    private val activeCounters = ConcurrentHashMap<String, ScheduledTask>()
    private val toastCounter = AtomicInteger(1)

    fun sendBossBar(
        players: Collection<Player>,
        message: String,
        durationSeconds: Int,
        color: BossBar.Color = BossBar.Color.YELLOW,
    ) {
        val title = TextFormatter.format(message)
        val bar = BossBar.bossBar(title, 1.0f, color, BossBar.Overlay.PROGRESS)
        for (player in players) {
            player.showBossBar(bar)
        }

        val totalTicks = (durationSeconds * 20L).coerceAtLeast(20L)
        val step = 1.0f / (durationSeconds.toFloat().coerceAtLeast(1f))
        var currentProgress = 1.0f

        val taskUuid = UUID.randomUUID()
        activeBars[taskUuid] = bar

        context.scheduler.runAsyncTimer(1L, 1L) {
            currentProgress -= step
            if (currentProgress <= 0.0f) {
                activeBars.remove(taskUuid)
                for (player in players) {
                    if (player.isOnline) player.hideBossBar(bar)
                }
            } else {
                bar.progress(currentProgress.coerceIn(0.0f, 1.0f))
            }
        }
    }

    fun startCountdown(
        players: Collection<Player>,
        seconds: Int,
        message: String,
    ): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        var remaining = seconds.coerceAtLeast(1)
        val initialSeconds = remaining.toFloat()

        val bar = BossBar.bossBar(
            context.locale.component(
                "chat.countdown-bar",
                mapOf("message" to message, "seconds" to remaining.toString())
            ),
            1.0f,
            BossBar.Color.RED,
            BossBar.Overlay.NOTCHED_10,
        )
        for (p in players) p.showBossBar(bar)

        val task = context.scheduler.runAsyncTimer(1L, 1L) {
            remaining--
            if (remaining <= 0) {
                stopCountdown(id)
                for (p in players) {
                    if (p.isOnline) {
                        p.hideBossBar(bar)
                        p.showTitle(
                            net.kyori.adventure.title.Title.title(
                                context.locale.component("chat.countdown-go"),
                                Component.empty(),
                            )
                        )
                    }
                }
            } else {
                bar.name(
                    context.locale.component(
                        "chat.countdown-bar",
                        mapOf("message" to message, "seconds" to remaining.toString()),
                    )
                )
                bar.progress((remaining / initialSeconds).coerceIn(0.0f, 1.0f))
            }
        }
        activeCounters[id] = task
        return id
    }

    fun stopCountdown(id: String): Boolean {
        val task = activeCounters.remove(id) ?: return false
        task.cancel()
        return true
    }

    fun playSound(
        players: Collection<Player>,
        soundName: String,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ) {
        val formattedKey = soundName.lowercase().replace('.', '_').let {
            if (':' in it) it else "minecraft:$it"
        }
        val nsKey = NamespacedKey.fromString(formattedKey) ?: return
        val sound = Registry.SOUNDS.get(nsKey) ?: return

        for (player in players) {
            context.scheduler.runForEntity(player) {
                player.playSound(player.location, sound, SoundCategory.MASTER, volume, pitch)
            }
        }
    }

    fun sendToast(
        players: Collection<Player>,
        icon: Material,
        title: String,
        frameType: String = "task",
    ) {
        val toastId = toastCounter.getAndIncrement()
        val key = NamespacedKey(context.plugin, "toast_$toastId")
        val iconKey = icon.key.asString()
        val frame = when (frameType.lowercase()) {
            "goal" -> "goal"
            "challenge" -> "challenge"
            else -> "task"
        }

        val json = """
        {
          "criteria": { "auto": { "trigger": "minecraft:impossible" } },
          "display": {
            "icon": { "id": "$iconKey" },
            "title": { "text": "$title" },
            "description": { "text": "" },
            "background": "minecraft:textures/gui/advancements/backgrounds/adventure.png",
            "frame": "$frame",
            "announce_to_chat": false,
            "show_toast": true,
            "hidden": true
          }
        }
        """.trimIndent()

        try {
            val adv = Bukkit.getUnsafe().loadAdvancement(key, json) ?: return
            for (player in players) {
                val progress = player.getAdvancementProgress(adv)
                progress.awardCriteria("auto")
                context.scheduler.runForEntityDelayed(player, 40L) {
                    runCatching {
                        progress.revokeCriteria("auto")
                        Bukkit.getUnsafe().removeAdvancement(key)
                    }
                }
            }
        } catch (_: Throwable) {
            // Fallback to title/actionbar if unsafe advancement loading is restricted
            val comp = TextFormatter.format("<yellow>[!]</yellow> <white>$title</white>")
            for (player in players) {
                context.actionBar.pushTransient(player, comp)
            }
        }
    }
}
