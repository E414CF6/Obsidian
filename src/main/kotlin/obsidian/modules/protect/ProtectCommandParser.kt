package obsidian.modules.protect

import java.util.*

/**
 * CoreProtect-compatible parameter parser (`u:`, `-u:`, `t:`, `r:`, `a:`, `i:`, `e:`, `f:`, `#flags`).
 * Pure logic with zero Bukkit runtime dependency for deterministic unit testing.
 */
object ProtectCommandParser {

    private val DURATION_TOKEN = Regex("(\\d+(?:\\.\\d+)?)(mo|y|w|d|h|m|s)")

    /**
     * Parses compound CoreProtect duration strings such as `30m`, `1d12h`, `2w`, `1y3mo`.
     * Returns duration in milliseconds, or `null` if invalid.
     */
    fun parseDurationMillis(raw: String): Long? {
        val cleaned = raw.trim().lowercase(Locale.ROOT)
        if (cleaned.isEmpty()) return null
        val matches = DURATION_TOKEN.findAll(cleaned).toList()
        if (matches.isEmpty()) return null
        val reconstructed = matches.joinToString("") { it.value }
        if (reconstructed != cleaned) return null

        var totalSeconds = 0.0
        for (m in matches) {
            val amount = m.groupValues[1].toDoubleOrNull() ?: return null
            val unitSeconds = when (m.groupValues[2]) {
                "s" -> 1.0
                "m" -> 60.0
                "h" -> 3_600.0
                "d" -> 86_400.0
                "w" -> 604_800.0
                "mo" -> 2_592_000.0
                "y" -> 31_536_000.0
                else -> return null
            }
            totalSeconds += amount * unitSeconds
        }
        return (totalSeconds * 1000.0).toLong().coerceAtLeast(1000L)
    }

    /**
     * Parses `t:<time>` or range `t:<older>-<newer>` relative to [nowMillis].
     * Returns `(sinceMillis, untilMillis)`.
     */
    fun parseTimeRange(raw: String, nowMillis: Long = System.currentTimeMillis()): Pair<Long?, Long?> {
        val cleaned = raw.trim().lowercase(Locale.ROOT)
        if (cleaned.isEmpty()) return null to null
        if ('-' in cleaned) {
            val parts = cleaned.split('-', limit = 2)
            val olderDur = parseDurationMillis(parts[0])
            val newerDur = parseDurationMillis(parts[1])
            val t1 = olderDur?.let { nowMillis - it }
            val t2 = newerDur?.let { nowMillis - it }
            if (t1 != null && t2 != null) {
                return minOf(t1, t2) to maxOf(t1, t2)
            }
            return t1 to t2
        }
        val dur = parseDurationMillis(cleaned) ?: return null to null
        return (nowMillis - dur) to null
    }

    fun parseFilterArgs(
        tokens: List<String>,
        defaultWorld: String?,
        centerX: Int?,
        centerY: Int?,
        centerZ: Int?,
        defaultRadius: Int?,
        maxRadius: Int = 100,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<ProtectQuery> {
        val users = mutableListOf<String>()
        val excludedUsers = mutableListOf<String>()
        var sinceMillis: Long? = null
        var untilMillis: Long? = null
        var world: String? = defaultWorld
        var explicitGlobal = false
        var radius: Int? = defaultRadius
        val actions = linkedSetOf<ProtectFilterAction>()
        val includes = linkedSetOf<String>()
        val excludes = linkedSetOf<String>()
        var textFilter: String? = null
        var preview = false
        var countOnly = false
        var verbose = false
        var silent = false
        var useSelection = false
        var page = 1

        var i = 0
        while (i < tokens.size) {
            val raw = tokens[i].trim()
            if (raw.isEmpty()) {
                i++
                continue
            }
            val lower = raw.lowercase(Locale.ROOT)

            // Hash modifiers (#preview, #count, #verbose, #silent, #global, #worldedit, #selection)
            if (lower.startsWith("#") && ':' !in lower) {
                when (lower) {
                    "#preview", "#p" -> preview = true
                    "#count", "#c" -> countOnly = true
                    "#verbose", "#v" -> verbose = true
                    "#silent", "#s" -> silent = true
                    "#global", "#g" -> {
                        explicitGlobal = true
                        radius = null
                        world = null
                    }

                    "#we", "#worldedit", "#sel", "#selection" -> useSelection = true
                    else -> return Result.failure(IllegalArgumentException("Unknown flag: $raw"))
                }
                i++
                continue
            }

            // Combined spaced prefix form `u: Steve` -> join next token
            val kv = if (raw.endsWith(":") && i + 1 < tokens.size) {
                val combined = raw + tokens[i + 1]
                i++
                combined
            } else {
                raw
            }

            val colonIdx = kv.indexOf(':')
            if (colonIdx <= 0) {
                // Allow bare page integer or action/user hint? CoreProtect allows bare page in /co l <page>
                val pageNum = raw.toIntOrNull()
                if (pageNum != null && pageNum > 0) {
                    page = pageNum
                    i++
                    continue
                }
                return Result.failure(IllegalArgumentException("Invalid parameter '$raw'. Expected key:value (e.g. u:Steve t:1h r:10)."))
            }

            val key = kv.substring(0, colonIdx).lowercase(Locale.ROOT)
            val value = kv.substring(colonIdx + 1).trim()
            if (value.isEmpty()) {
                return Result.failure(IllegalArgumentException("Missing value for '$key:'"))
            }

            when (key) {
                "u", "user", "users" -> {
                    value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { u ->
                        if (u.startsWith("-")) {
                            excludedUsers += u.removePrefix("-")
                        } else {
                            users += u
                        }
                    }
                }

                "-u", "eu", "exclude-user", "exclude-users" -> {
                    value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { excludedUsers += it }
                }

                "t", "time" -> {
                    val (s, u) = parseTimeRange(value, nowMillis)
                    if (s == null && u == null) {
                        return Result.failure(IllegalArgumentException("Invalid time format '$value' (e.g. 30m, 2h, 1d12h)."))
                    }
                    sinceMillis = s
                    untilMillis = u
                }

                "r", "radius" -> {
                    when (val rv = value.lowercase(Locale.ROOT)) {
                        "#global", "global" -> {
                            explicitGlobal = true
                            radius = null
                            world = null
                        }

                        "#we", "#worldedit", "#sel", "#selection" -> {
                            useSelection = true
                            radius = null
                        }

                        else -> {
                            val num = rv.toIntOrNull()
                            if (num != null) {
                                if (num <= 0) {
                                    return Result.failure(IllegalArgumentException("Radius must be > 0."))
                                }
                                radius = num.coerceAtMost(maxRadius)
                            } else {
                                // World name filter (e.g., r:world_nether)
                                world = value.removePrefix("#")
                                radius = null
                            }
                        }
                    }
                }

                "w", "world" -> {
                    world = value
                }

                "a", "action" -> {
                    value.split(',').map { it.trim().lowercase(Locale.ROOT).removePrefix("#") }
                        .filter { it.isNotEmpty() }.forEach { act ->
                            when (act) {
                                "block", "blocks" -> {
                                    actions += ProtectFilterAction.BLOCK_BREAK
                                    actions += ProtectFilterAction.BLOCK_PLACE
                                }

                                "+block", "block+", "place", "placed", "block-place" -> actions += ProtectFilterAction.BLOCK_PLACE

                                "-block", "block-", "break", "broke", "remove", "destroy", "block-break" -> actions += ProtectFilterAction.BLOCK_BREAK

                                "click", "clicks", "interact", "interaction" -> actions += ProtectFilterAction.INTERACT

                                "container", "containers", "chest", "inventory", "transaction" -> {
                                    actions += ProtectFilterAction.CONTAINER_REMOVE
                                    actions += ProtectFilterAction.CONTAINER_ADD
                                }

                                "+container", "container+", "add-container" -> actions += ProtectFilterAction.CONTAINER_ADD

                                "-container", "container-", "remove-container" -> actions += ProtectFilterAction.CONTAINER_REMOVE

                                "kill", "kills", "death", "deaths", "entity-kill" -> actions += ProtectFilterAction.ENTITY_KILL

                                "spawn", "spawns", "entity-spawn" -> actions += ProtectFilterAction.ENTITY_SPAWN

                                "chat", "message", "messages" -> actions += ProtectFilterAction.CHAT

                                "command", "commands", "cmd" -> actions += ProtectFilterAction.COMMAND

                                "session", "sessions", "login", "logout" -> actions += ProtectFilterAction.SESSION

                                "sign", "signs" -> actions += ProtectFilterAction.SIGN

                                else -> return Result.failure(IllegalArgumentException("Unknown action '$act'."))
                            }
                        }
                }

                "i", "b", "include", "block", "blocks" -> {
                    value.split(',').map { it.trim().uppercase(Locale.ROOT).removePrefix("MINECRAFT:") }
                        .filter { it.isNotEmpty() }.forEach { includes += it }
                }

                "e", "exclude" -> {
                    value.split(',').map { it.trim().uppercase(Locale.ROOT).removePrefix("MINECRAFT:") }
                        .filter { it.isNotEmpty() }.forEach { excludes += it }
                }

                "f", "filter" -> {
                    textFilter = value
                }

                "p", "page" -> {
                    page = value.toIntOrNull()?.coerceAtLeast(1) ?: 1
                }

                else -> return Result.failure(IllegalArgumentException("Unknown parameter '$key:'."))
            }

            i++
        }

        val resolvedWorld = if (explicitGlobal) null else world
        return Result.success(
            ProtectQuery(
                users = users,
                excludedUsers = excludedUsers,
                sinceMillis = sinceMillis,
                untilMillis = untilMillis,
                world = resolvedWorld,
                centerX = if (explicitGlobal || useSelection) null else centerX,
                centerY = if (explicitGlobal || useSelection) null else centerY,
                centerZ = if (explicitGlobal || useSelection) null else centerZ,
                radius = if (explicitGlobal || useSelection) null else radius,
                actions = actions,
                includeMaterials = includes,
                excludeMaterials = excludes,
                textFilter = textFilter,
                preview = preview,
                countOnly = countOnly,
                verbose = verbose,
                silent = silent,
                useSelection = useSelection,
                page = page,
            )
        )
    }

    fun formatRelativeTime(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diffSec = ((nowMillis - timestampMillis) / 1000.0).coerceAtLeast(0.0)
        return when {
            diffSec < 60.0 -> "${diffSec.toInt()}s ago"
            diffSec < 3600.0 -> String.format(Locale.US, "%.1fm ago", diffSec / 60.0)
            diffSec < 86400.0 -> String.format(Locale.US, "%.1fh ago", diffSec / 3600.0)
            else -> String.format(Locale.US, "%.1fd ago", diffSec / 86400.0)
        }
    }
}
