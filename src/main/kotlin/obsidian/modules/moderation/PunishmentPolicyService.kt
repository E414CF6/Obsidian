package obsidian.modules.moderation

import obsidian.core.ObsidianContext
import obsidian.core.database.PunishmentType
import obsidian.core.text.DurationParser
import obsidian.integration.LuckPermsHook
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

class PunishmentPolicyService(private val context: ObsidianContext) {

    private val luckPerms = LuckPermsHook()

    // Actor cooldowns: (actorName_type) -> lastTime
    private val actorCooldowns = ConcurrentHashMap<String, Long>()

    // Target cooldowns: (targetName_type) -> lastTime
    private val targetCooldowns = ConcurrentHashMap<String, Long>()

    var exemptEnabled: Boolean = true
        private set
    var useGroupWeights: Boolean = false
        private set
    var permitSameWeight: Boolean = true
        private set
    var allowExemptBypass: Boolean = false
        private set
    var exemptPlayers: List<String> = emptyList()
        private set
    var defaultCooldownMillis: Long = 5000L
        private set

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        exemptEnabled = raw.getBoolean("moderation.exempt.enabled", true)
        useGroupWeights = raw.getBoolean("moderation.exempt.use-group-weights", false)
        permitSameWeight = raw.getBoolean("moderation.exempt.permit-same-weight", true)
        allowExemptBypass = raw.getBoolean("moderation.exempt.allow-bypass", false)
        exemptPlayers = raw.getStringList("moderation.exempt.exempt-players")
        defaultCooldownMillis = (raw.getLong("moderation.cooldown-seconds", 5L) * 1000L).coerceAtLeast(0L)
    }

    /**
     * Checks if target is exempt from this punishment type by actor.
     * Returns Pair(isExempt, reason).
     */
    fun checkExempt(
        actor: CommandSender,
        targetPlayer: Player?,
        targetName: String,
        type: PunishmentType,
    ): Pair<Boolean, String?> {
        if (!exemptEnabled) return false to null
        if (actor !is Player) return false to null // Console can always punish

        val bypass =
            allowExemptBypass && (actor.hasPermission("obsidian.exempt.bypass") || actor.hasPermission("obsidian.admin"))
        if (bypass) return false to null

        val typeStr = type.name.lowercase()

        // Check configured exempt-players
        for (entry in exemptPlayers) {
            val parts = entry.split(":")
            val name = parts[0].trim()
            val entryType = parts.getOrNull(1)?.trim()?.lowercase()
            if (name.equals(
                    targetName,
                    ignoreCase = true
                ) || (targetPlayer != null && name.equals(targetPlayer.uniqueId.toString(), ignoreCase = true))
            ) {
                if (entryType == null || entryType == typeStr || (entryType == "ban" && type == PunishmentType.IP_BAN) || (entryType == "mute" && (type == PunishmentType.IP_MUTE || type == PunishmentType.SHADOW_MUTE))) {
                    return true to "Player is listed as exempt."
                }
            }
        }

        if (targetPlayer != null) {
            if (targetPlayer.hasPermission("obsidian.exempt") ||
                targetPlayer.hasPermission("obsidian.exempt.$typeStr") ||
                (type == PunishmentType.IP_BAN && targetPlayer.hasPermission("obsidian.exempt.ban")) ||
                ((type == PunishmentType.IP_MUTE || type == PunishmentType.SHADOW_MUTE) && targetPlayer.hasPermission("obsidian.exempt.mute"))
            ) {
                return true to "Player has exempt permission."
            }

            if (useGroupWeights && luckPerms.isAvailable()) {
                val actorWeight = luckPerms.getGroupWeight(actor)
                val targetWeight = luckPerms.getGroupWeight(targetPlayer)
                if (actorWeight < targetWeight) {
                    return true to "Target group weight ($targetWeight) is higher than actor ($actorWeight)."
                }
                if (actorWeight == targetWeight && !permitSameWeight) {
                    return true to "Cannot punish players in the same group weight tier ($actorWeight)."
                }
            }
        }

        return false to null
    }

    /**
     * Checks if actor exceeds their permission-based duration limit for this punishment type.
     * Returns Pair(allowed, errorMsg).
     */
    fun checkDurationLimit(
        actor: CommandSender,
        durationMillis: Long?,
        type: PunishmentType,
    ): Pair<Boolean, String?> {
        if (actor !is Player) return true to null
        if (actor.hasPermission("obsidian.admin") || actor.hasPermission("obsidian.group.unlimited")) {
            return true to null
        }

        val typeStr = when (type) {
            PunishmentType.IP_BAN -> "ban"
            PunishmentType.IP_MUTE, PunishmentType.SHADOW_MUTE -> "mute"
            else -> type.name.lowercase()
        }

        // Look for obsidian.limit.<type>.<duration> permissions
        var maxAllowedMillis = 0L
        var hasSpecificLimit = false

        for (attachment in actor.effectivePermissions) {
            if (!attachment.value) continue
            val perm = attachment.permission.lowercase()
            val prefix = "obsidian.limit.$typeStr."
            if (perm.startsWith(prefix)) {
                hasSpecificLimit = true
                val durStr = perm.removePrefix(prefix)
                val parsed = DurationParser.parseMillis(durStr)
                if (parsed != null && parsed > maxAllowedMillis) {
                    maxAllowedMillis = parsed
                }
            }
        }

        if (hasSpecificLimit) {
            if (durationMillis == null) {
                return false to "You are not permitted to issue permanent ${typeStr}s."
            }
            if (durationMillis > maxAllowedMillis) {
                val maxFormatted = DurationParser.formatExpiry(System.currentTimeMillis() + maxAllowedMillis)
                return false to "Maximum allowed duration for your rank is $maxFormatted."
            }
        }

        return true to null
    }

    /**
     * Checks and records punishment cooldown for actor and target.
     * Returns remaining cooldown in milliseconds (0 if allowed).
     */
    fun checkAndApplyCooldown(
        actor: CommandSender,
        targetName: String,
        type: PunishmentType,
    ): Long {
        if (actor !is Player) return 0L
        if (actor.hasPermission("obsidian.cooldown.bypass") || actor.hasPermission("obsidian.admin")) {
            return 0L
        }

        val now = System.currentTimeMillis()
        val actorKey = "${actor.name.lowercase()}_${type.name}"
        val targetKey = "${targetName.lowercase()}_${type.name}"

        val lastActorTime = actorCooldowns[actorKey] ?: 0L
        if (now - lastActorTime < defaultCooldownMillis) {
            return defaultCooldownMillis - (now - lastActorTime)
        }

        val lastTargetTime = targetCooldowns[targetKey] ?: 0L
        if (now - lastTargetTime < defaultCooldownMillis) {
            return defaultCooldownMillis - (now - lastTargetTime)
        }

        actorCooldowns[actorKey] = now
        targetCooldowns[targetKey] = now
        return 0L
    }
}
