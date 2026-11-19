package obsidian.core.command

import net.kyori.adventure.title.Title
import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import obsidian.integration.PlaceholderService
import obsidian.modules.economy.EconomyService
import org.bukkit.*
import org.bukkit.entity.Player
import java.time.Duration

/**
 * Shared command/action dispatcher for Portals, InteractiveCommands, AttachedCommands, Scheduler, and EventActions.
 * Supports prefixes: `console:`, `player:`, `op:`, `msg:`, `broadcast:`, `actionbar:`, `title:`, `sound:`, `teleport:`,
 * delays (`delay!<sec>`), and inline conditions (`if:<cond>? <action>`).
 */
object SpecializedCommandExecutor {

    fun executeAll(
        context: ObsidianContext,
        rawLines: List<String>,
        player: Player? = null,
        extraPlaceholders: Map<String, String> = emptyMap(),
    ) {
        if (rawLines.isEmpty()) return
        executeFromIndex(context, rawLines, 0, player, extraPlaceholders)
    }

    private fun executeFromIndex(
        context: ObsidianContext,
        lines: List<String>,
        index: Int,
        player: Player?,
        extraPlaceholders: Map<String, String>,
    ) {
        var i = index
        while (i < lines.size) {
            val raw = lines[i].trim()
            i++
            if (raw.isEmpty() || raw.startsWith("#")) continue

            val delayTicks = parseDelayTicks(raw)
            if (delayTicks != null) {
                val nextIdx = i
                if (nextIdx >= lines.size) return
                if (player != null && player.isOnline) {
                    context.scheduler.runForEntityDelayed(player, delayTicks) {
                        executeFromIndex(context, lines, nextIdx, player, extraPlaceholders)
                    }
                } else {
                    context.scheduler.runGlobalDelayed(delayTicks) {
                        executeFromIndex(context, lines, nextIdx, player, extraPlaceholders)
                    }
                }
                return
            }

            executeSingleLine(context, raw, player, extraPlaceholders)
        }
    }

    internal fun executeSingleLine(
        context: ObsidianContext,
        rawLine: String,
        player: Player?,
        extraPlaceholders: Map<String, String> = emptyMap(),
    ) {
        var line = rawLine.trim()
        if (line.startsWith("if:", ignoreCase = true) && line.contains('?')) {
            val splitIdx = line.indexOf('?')
            val condition = line.substring(3, splitIdx).trim()
            if (!evaluateCondition(context, condition, player)) return
            line = line.substring(splitIdx + 1).trim()
        }

        val resolved = resolvePlaceholders(context, line, player, extraPlaceholders)
        val lower = resolved.lowercase()

        when {
            lower.startsWith("console:") -> {
                val cmd = resolved.substring(8).trim().removePrefix("/")
                if (cmd.isNotEmpty()) {
                    context.scheduler.runGlobal {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                    }
                }
            }

            lower.startsWith("player:") -> {
                val target = player ?: return
                val cmd = resolved.substring(7).trim().removePrefix("/")
                if (cmd.isNotEmpty()) {
                    context.scheduler.runForEntity(target) {
                        Bukkit.dispatchCommand(target, cmd)
                    }
                }
            }

            lower.startsWith("op:") -> {
                val target = player ?: return
                val cmd = resolved.substring(3).trim().removePrefix("/")
                if (cmd.isNotEmpty()) {
                    context.scheduler.runForEntity(target) {
                        val wasOp = target.isOp
                        try {
                            if (!wasOp) target.isOp = true
                            Bukkit.dispatchCommand(target, cmd)
                        } finally {
                            if (!wasOp) target.isOp = false
                        }
                    }
                }
            }

            lower.startsWith("msg:") || lower.startsWith("message:") -> {
                val payload = resolved.substringAfter(':').trim()
                val comp = TextFormatter.format(payload)
                if (player != null) {
                    player.sendMessage(comp)
                } else {
                    Bukkit.getConsoleSender().sendMessage(comp)
                }
            }

            lower.startsWith("broadcast:") || lower.startsWith("bc:") -> {
                val payload = resolved.substringAfter(':').trim()
                Bukkit.broadcast(TextFormatter.format(payload))
            }

            lower.startsWith("actionbar:") -> {
                val target = player ?: return
                val payload = resolved.substring(10).trim()
                target.sendActionBar(TextFormatter.format(payload))
            }

            lower.startsWith("title:") -> {
                val target = player ?: return
                val parts = resolved.substring(6).split('|', limit = 2)
                val titleComp = TextFormatter.format(parts[0].trim())
                val subComp = TextFormatter.format(parts.getOrElse(1) { "" }.trim())
                target.showTitle(
                    Title.title(
                        titleComp,
                        subComp,
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(250)),
                    )
                )
            }

            lower.startsWith("sound:") -> {
                val target = player ?: return
                val parts = resolved.substring(6).split('|')
                val soundKeyRaw = parts[0].trim().lowercase().replace('.', '_').let { key ->
                    if (':' in key) key else "minecraft:$key"
                }
                val nsKey = NamespacedKey.fromString(soundKeyRaw) ?: return
                val sound = Registry.SOUNDS.get(nsKey) ?: return
                val volume = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 10f) ?: 1.0f
                val pitch = parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1.0f
                target.playSound(target.location, sound, SoundCategory.MASTER, volume, pitch)
            }

            lower.startsWith("teleport:") -> {
                val target = player ?: return
                val loc = parseSimpleLocation(resolved.substring(9).trim()) ?: return
                target.teleportAsync(loc)
            }

            lower.startsWith("saveditem:") || lower.startsWith("giveitem:") -> {
                val target = player ?: return
                val payload = resolved.substringAfter(':').trim()
                val parts = payload.split(' ')
                val id = parts[0]
                val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val service = context.getService<obsidian.modules.utility.SavedItemService>()
                service?.giveItem(target, id, amount)
            }

            else -> {
                val cmd = resolved.removePrefix("/")
                if (cmd.isNotEmpty()) {
                    context.scheduler.runGlobal {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                    }
                }
            }
        }
    }

    internal fun parseDelayTicks(line: String): Long? {
        val trimmed = line.trim().lowercase()
        val raw = when {
            trimmed.startsWith("delay!") -> trimmed.substring(6).trim()
            trimmed.startsWith("delay:") -> trimmed.substring(6).trim()
            else -> return null
        }
        val seconds = raw.removeSuffix("s").toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return (seconds * 20.0).toLong().coerceAtLeast(1L)
    }

    internal fun evaluateCondition(
        context: ObsidianContext,
        condition: String,
        player: Player?,
        onlineCountProvider: () -> Int = { Bukkit.getOnlinePlayers().size },
    ): Boolean {
        val negate = condition.startsWith("!")
        val expr = if (negate) condition.substring(1).trim() else condition.trim()
        val result = when {
            expr.startsWith("perm:", ignoreCase = true) -> {
                val perm = expr.substring(5).trim()
                player?.hasPermission(perm) == true
            }

            expr.startsWith("world:", ignoreCase = true) -> {
                val world = expr.substring(6).trim()
                player?.world?.name.equals(world, ignoreCase = true)
            }

            expr.startsWith("online>=", ignoreCase = true) -> {
                val min = expr.substring(8).trim().toIntOrNull() ?: return false
                onlineCountProvider() >= min
            }

            expr.startsWith("online<", ignoreCase = true) -> {
                val max = expr.substring(7).trim().toIntOrNull() ?: return false
                onlineCountProvider() < max
            }

            expr.startsWith("money>=", ignoreCase = true) -> {
                val min = expr.substring(7).trim().toDoubleOrNull() ?: return false
                val eco = context.getService<EconomyService>() ?: return false
                player != null && eco.has(player.uniqueId, min)
            }

            else -> false
        }
        return if (negate) !result else result
    }

    private fun resolvePlaceholders(
        context: ObsidianContext,
        input: String,
        player: Player?,
        extra: Map<String, String>,
    ): String {
        var out = input
        for ((k, v) in extra) {
            out = out.replace("{$k}", v)
        }
        if (player != null) {
            val loc = player.location
            out = out.replace("{world}", loc.world.name).replace("{x}", loc.blockX.toString())
                .replace("{y}", loc.blockY.toString()).replace("{z}", loc.blockZ.toString())
        }
        val ph = context.getService<PlaceholderService>()
        return if (ph != null) {
            ph.applyPlaceholders(player, out)
        } else {
            out.replace("{player}", player?.name ?: "CONSOLE")
                .replace("{online}", Bukkit.getOnlinePlayers().size.toString())
                .replace("{max_players}", Bukkit.getMaxPlayers().toString())
        }
    }

    private fun parseSimpleLocation(raw: String): Location? {
        val parts = raw.split(';')
        if (parts.size < 4) return null
        val world = Bukkit.getWorld(parts[0].trim()) ?: return null
        val x = parts[1].toDoubleOrNull() ?: return null
        val y = parts[2].toDoubleOrNull() ?: return null
        val z = parts[3].toDoubleOrNull() ?: return null
        val yaw = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
        val pitch = parts.getOrNull(5)?.toFloatOrNull() ?: 0f
        return Location(world, x, y, z, yaw, pitch)
    }
}
