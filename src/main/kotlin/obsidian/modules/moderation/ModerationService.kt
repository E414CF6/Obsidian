package obsidian.modules.moderation

import kotlinx.coroutines.launch
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.*
import obsidian.core.text.DurationParser
import obsidian.core.text.TextFormatter
import obsidian.integration.DiscordWebhookService
import obsidian.modules.economy.EconomyService
import obsidian.modules.playtime.RankService
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages bans, mutes, warnings, jails, cuffs, vanish, IP tracking, templates, and audit logs.
 */
class ModerationService(private val context: ObsidianContext) {

    private val jailsFile = YamlConfigFile(context.plugin, "jails.yml")
    private val jails = ConcurrentHashMap<String, StoredLocation>()

    val templates = PunishmentTemplateService(context)
    val policy = PunishmentPolicyService(context)

    // Active online/cached states keyed by UUID
    private val activeMutes = ConcurrentHashMap<UUID, PunishmentRecord>()
    private val activeJails = ConcurrentHashMap<UUID, PunishmentRecord>()
    private val cuffedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val vanishedPlayers = ConcurrentHashMap.newKeySet<UUID>()

    // Notification throttle cache: key -> lastSentMillis
    private val notificationThrottle = ConcurrentHashMap<String, Long>()

    var defaultBanReason: String = "Violated server rules"
        private set
    var defaultMuteReason: String = "Muted by staff"
        private set
    var defaultKickReason: String = "Kicked from the server"
        private set
    var defaultWarnReason: String = "Warning issued by staff"
        private set
    var blockedJailCommands: Set<String> = emptySet()
        private set
    var vanishNightVision: Boolean = true
        private set
    var vanishSilentChest: Boolean = true
        private set
    private var warnEscalations: Map<Int, String> = emptyMap()

    // Feature configuration
    var silentByDefault: Boolean = false
        private set
    var punishIpByDefault: Boolean = false
        private set
    var requireBanMuteReason: Boolean = false
        private set
    var requireKickReason: Boolean = false
        private set
    var requireUnbanUnmuteReason: Boolean = false
        private set
    var muteBlockedCommands: Set<String> = emptySet()
        private set
    var notifyBannedJoin: Boolean = true
        private set
    var notifyMutedChat: Boolean = true
        private set
    var dupeipOnJoin: Boolean = true
        private set
    var dupeipScanLimit: Int = 20
        private set
    var dupeipScanFilterMillis: Long = 7L * 24 * 3600 * 1000L
        private set
    var dupeipOnBannedAccount: Boolean = true
        private set
    var dupeipOnMutedAccount: Boolean = false
        private set
    var dupeipOnJoinThreshold: Int = 5
        private set
    var throttleSeconds: Long = 60L
        private set

    init {
        reload()
    }

    fun reload() {
        jailsFile.reload()
        templates.reload()
        policy.reload()
        val raw = context.config.raw

        defaultBanReason =
            raw.getString("moderation.default-ban-reason", "Violated server rules") ?: "Violated server rules"
        defaultMuteReason = raw.getString("moderation.default-mute-reason", "Muted by staff") ?: "Muted by staff"
        defaultKickReason =
            raw.getString("moderation.default-kick-reason", "Kicked from the server") ?: "Kicked from the server"
        defaultWarnReason =
            raw.getString("moderation.default-warn-reason", "Warning issued by staff") ?: "Warning issued by staff"
        blockedJailCommands = raw.getStringList("moderation.jail-blocked-commands").map { it.lowercase() }.toSet()
        vanishNightVision = raw.getBoolean("moderation.vanish.night-vision", true)
        vanishSilentChest = raw.getBoolean("moderation.vanish.silent-chest", true)

        silentByDefault = raw.getBoolean("moderation.silent-by-default", false)
        punishIpByDefault = raw.getBoolean("moderation.punish-ip", false)
        requireBanMuteReason = raw.getBoolean("moderation.require-ban-mute-reason", false)
        requireKickReason = raw.getBoolean("moderation.require-kick-reason", false)
        requireUnbanUnmuteReason = raw.getBoolean("moderation.require-unban-unmute-reason", false)
        muteBlockedCommands = raw.getStringList("moderation.mute-blocked-commands").map { it.lowercase() }.toSet()

        notifyBannedJoin = raw.getBoolean("moderation.notify.banned-player-join", true)
        notifyMutedChat = raw.getBoolean("moderation.notify.muted-player-chat", true)
        dupeipOnJoin = raw.getBoolean("moderation.notify.dupeip-on-join", true)
        dupeipScanLimit = raw.getInt("moderation.notify.dupeip-scan-limit", 20)
        val filterDurStr = raw.getString("moderation.notify.dupeip-scan-filter-duration", "7d") ?: "7d"
        dupeipScanFilterMillis = DurationParser.parseMillis(filterDurStr) ?: (7L * 24 * 3600 * 1000L)
        dupeipOnBannedAccount = raw.getBoolean("moderation.notify.dupeip-on-banned-account", true)
        dupeipOnMutedAccount = raw.getBoolean("moderation.notify.dupeip-on-muted-account", false)
        dupeipOnJoinThreshold = raw.getInt("moderation.notify.dupeip-on-join-threshold", 5)
        throttleSeconds = raw.getLong("moderation.notify.throttle-seconds", 60L).coerceAtLeast(1L)

        val escalations = mutableMapOf<Int, String>()
        raw.getConfigurationSection("moderation.warn-auto-thresholds")?.let { sec ->
            for (k in sec.getKeys(false)) {
                val threshold = k.toIntOrNull() ?: continue
                val cmd = sec.getString(k)?.takeIf { it.isNotBlank() } ?: continue
                escalations[threshold] = cmd
            }
        }
        warnEscalations = escalations

        jails.clear()
        jailsFile.yaml.getConfigurationSection("jails")?.let { sec ->
            for (name in sec.getKeys(false)) {
                val loc = StoredLocation.deserialize(sec.getString(name)) ?: continue
                jails[name.lowercase()] = loc
            }
        }
    }

    fun isThrottled(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = notificationThrottle[key] ?: 0L
        if (now - last < throttleSeconds * 1000L) {
            return true
        }
        notificationThrottle[key] = now
        return false
    }

    // --- Target lookup ---

    data class TargetIdentity(
        val uuid: UUID?,
        val name: String,
        val ip: String? = null,
        val onlinePlayer: Player? = null,
    )

    suspend fun resolveTarget(input: String, allowUnseenName: Boolean = false): TargetIdentity? {
        val online = Bukkit.getPlayerExact(input) ?: Bukkit.getPlayer(input)
        if (online != null) {
            return TargetIdentity(
                uuid = online.uniqueId,
                name = online.name,
                ip = online.address?.address?.hostAddress,
                onlinePlayer = online,
            )
        }
        val user = context.users.findByName(input)
        if (user != null) {
            val latestIp = context.punishmentRepository.findIpsByUuid(user.uuid).firstOrNull()?.ip
            return TargetIdentity(user.uuid, user.username, latestIp, null)
        }
        runCatching { UUID.fromString(input) }.getOrNull()?.let { parsedUuid ->
            val u = context.users.getOrLoad(parsedUuid)
            val ip = context.punishmentRepository.findIpsByUuid(parsedUuid).firstOrNull()?.ip
            return TargetIdentity(parsedUuid, u?.username ?: parsedUuid.toString(), ip, null)
        }
        if (isIpOrWildcard(input)) {
            return TargetIdentity(null, input, input, null)
        }
        val lastUuid = context.punishmentRepository.findLastUuid(input)
        if (lastUuid != null) {
            val latestIp = context.punishmentRepository.findIpsByUuid(lastUuid).firstOrNull()?.ip
            return TargetIdentity(lastUuid, input, latestIp, null)
        }
        val offline = Bukkit.getOfflinePlayerIfCached(input)
        if (offline != null && offline.name != null) {
            return TargetIdentity(offline.uniqueId, offline.name!!, null, null)
        }
        if (allowUnseenName) {
            val offlineUuid = UUID.nameUUIDFromBytes("OfflinePlayer:$input".toByteArray(Charsets.UTF_8))
            return TargetIdentity(offlineUuid, input, null, null)
        }
        return null
    }

    // --- Bans ---

    suspend fun checkActiveBan(uuid: UUID?, ip: String?, name: String? = null): PunishmentRecord? {
        val now = System.currentTimeMillis()
        val byIdOrIp = context.punishmentRepository.findActive(uuid, ip, PunishmentType.BAN, PunishmentType.IP_BAN)
        val combined = if (!name.isNullOrBlank()) {
            byIdOrIp + context.punishmentRepository.findActiveByNameOrIp(name, PunishmentType.BAN)
        } else {
            byIdOrIp
        }
        for (rec in combined) {
            if (rec.isExpired(now)) {
                context.punishmentRepository.deactivate(rec.id)
                continue
            }
            return rec
        }
        if (!ip.isNullOrBlank()) {
            val wildcardBans = context.punishmentRepository.findActiveByIpWildcard(ip, PunishmentType.IP_BAN)
            for (rec in wildcardBans) {
                if (rec.isExpired(now)) {
                    context.punishmentRepository.deactivate(rec.id)
                    continue
                }
                return rec
            }
        }
        return null
    }

    suspend fun ban(
        target: TargetIdentity,
        actor: CommandSender,
        durationMillis: Long?,
        reason: String?,
        ipBan: Boolean = false,
        silent: Boolean = silentByDefault,
        templateName: String? = null,
        noOverride: Boolean = false,
        modify: Boolean = false,
        customSender: String? = null,
    ): Pair<PunishmentRecord?, String?> {
        val type = if (ipBan || punishIpByDefault) PunishmentType.IP_BAN else PunishmentType.BAN

        // 1. Check exemptions
        val (isExempt, exemptReason) = policy.checkExempt(actor, target.onlinePlayer, target.name, type)
        if (isExempt) {
            return null to (exemptReason ?: "Target is exempt from punishment.")
        }

        // 2. Resolve template or ladder if provided
        var effectiveDuration = durationMillis
        var effectiveReason = reason
        var templateActions: List<String> = emptyList()

        if (templateName != null) {
            val tmpl = templates.getTemplate(templateName, type)
            if (tmpl == null) {
                return null to "Unknown template '$templateName'."
            }
            if (tmpl.permission != null && !actor.hasPermission(tmpl.permission) && !actor.hasPermission("obsidian.admin")) {
                return null to "You do not have permission to use template '$templateName'."
            }
            val strikes = context.punishmentRepository.countTemplateStrikes(
                target.uuid, target.ip, tmpl.name, System.currentTimeMillis() - tmpl.expireLadderMillis, tmpl.ipTemplate
            )
            val step = templates.evaluateStep(tmpl, strikes)
            if (effectiveDuration == null && step.durationMillis != null) {
                effectiveDuration = step.durationMillis
            }
            if (effectiveReason.isNullOrBlank()) {
                effectiveReason = step.reason ?: tmpl.defaultReason
            }
            templateActions = step.actions
        }

        val resolvedReason = effectiveReason?.takeIf { it.isNotBlank() } ?: defaultBanReason

        // 3. Duration limit check
        val (allowedDur, durError) = policy.checkDurationLimit(actor, effectiveDuration, type)
        if (!allowedDur) {
            return null to durError
        }

        // 4. Cooldown check
        val remainingCooldown = policy.checkAndApplyCooldown(actor, target.name, type)
        if (remainingCooldown > 0L) {
            val secs = (remainingCooldown / 1000L).coerceAtLeast(1L)
            return null to "You must wait ${secs}s before issuing another punishment."
        }

        // 5. Check existing active ban for -N and --modify
        val existing = checkActiveBan(target.uuid, target.ip, target.name)
        if (existing != null) {
            if (noOverride) {
                return null to "Target is already banned (-N prevented override)."
            }
            if (modify) {
                val newExpiry = effectiveDuration?.let { System.currentTimeMillis() + it }
                context.punishmentRepository.updatePunishment(existing.id, resolvedReason, newExpiry)
                return existing.copy(reason = resolvedReason, expiresAt = newExpiry) to null
            }
        }

        val now = System.currentTimeMillis()
        val actorUuid = (actor as? Player)?.uniqueId
        val actorName = customSender ?: actor.name
        val record = PunishmentRecord(
            type = type,
            targetUuid = target.uuid,
            targetName = target.name,
            targetIp = if (type == PunishmentType.IP_BAN) (target.ip ?: target.name) else null,
            actorUuid = actorUuid,
            actorName = actorName,
            reason = resolvedReason,
            createdAt = now,
            expiresAt = effectiveDuration?.let { now + it },
            active = true,
            silent = silent,
            templateId = templateName,
        )

        context.punishmentRepository.deactivateForTarget(type, target.uuid, record.targetIp)
        context.punishmentRepository.insert(record)

        // Execute template actions if any
        if (templateActions.isNotEmpty()) {
            context.scheduler.runGlobal {
                for (action in templateActions) {
                    val cmd = action.replace("\$playerName", target.name).replace("{player}", target.name)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.removePrefix("/"))
                }
            }
        }

        return record to null
    }

    fun applyBanEffects(target: TargetIdentity, actor: CommandSender, record: PunishmentRecord, ipBan: Boolean) {
        val expiresStr = DurationParser.formatExpiry(record.expiresAt, record.createdAt)
        val kickComponent = TextFormatter.format(
            context.locale.raw("moderation.banned-screen"),
            mapOf(
                "reason" to record.reason,
                "actor" to record.actorName,
                "expires" to expiresStr,
            ),
        )
        target.onlinePlayer?.let { p ->
            context.scheduler.runForEntity(p) { p.kick(kickComponent) }
        }
        if (ipBan && record.targetIp != null) {
            for (online in Bukkit.getOnlinePlayers()) {
                val onlineIp = online.address?.address?.hostAddress
                if (onlineIp != null && context.punishmentRepository.ipMatches(record.targetIp, onlineIp)) {
                    context.scheduler.runForEntity(online) { online.kick(kickComponent) }
                }
            }
        }

        val prefix = if (record.silent) context.locale.raw("moderation.silent-tag") else ""
        val broadcast = TextFormatter.format(
            prefix + context.locale.raw("moderation.ban-broadcast"),
            mapOf(
                "target" to target.name,
                "actor" to record.actorName,
                "reason" to record.reason,
                "expires" to expiresStr,
            ),
        )

        if (record.silent) {
            for (p in Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("obsidian.notify.silent") || p.hasPermission("obsidian.admin")) {
                    p.sendMessage(broadcast)
                }
            }
            Bukkit.getConsoleSender().sendMessage(broadcast)
        } else {
            Bukkit.broadcast(broadcast)
        }

        context.getService<DiscordWebhookService>()?.sendEmbedAsync(
            title = if (ipBan) "IP Ban Issued" else "Ban Issued",
            description = "Target: ${target.name}\nActor: ${record.actorName}\nReason: ${record.reason}\nExpires: $expiresStr\nSilent: ${record.silent}",
            colorRgb = 0xEF4444,
        )
    }

    suspend fun unban(
        query: String,
        actor: CommandSender,
        reason: String? = null,
    ): Boolean {
        val identity = resolveTarget(query)
        val actorUuid = (actor as? Player)?.uniqueId
        var deactivated = 0
        if (identity != null) {
            deactivated += context.punishmentRepository.deactivateForTarget(
                PunishmentType.BAN, identity.uuid, null, actorUuid, actor.name, reason
            )
            deactivated += context.punishmentRepository.deactivateForTarget(
                PunishmentType.IP_BAN, identity.uuid, identity.ip, actorUuid, actor.name, reason
            )
        }
        val byQuery =
            context.punishmentRepository.findActiveByNameOrIp(query, PunishmentType.BAN, PunishmentType.IP_BAN)
        for (rec in byQuery) {
            context.punishmentRepository.deactivate(rec.id, actorUuid, actor.name, reason)
            deactivated++
        }
        return deactivated > 0
    }

    // --- Mutes & ShadowMutes ---

    fun getActiveMute(uuid: UUID): PunishmentRecord? {
        val rec = activeMutes[uuid] ?: return null
        if (rec.isExpired()) {
            activeMutes.remove(uuid)
            context.scheduler.asyncScope.launch { context.punishmentRepository.deactivate(rec.id) }
            return null
        }
        return rec
    }

    suspend fun checkActiveMute(uuid: UUID?, ip: String?): PunishmentRecord? {
        val now = System.currentTimeMillis()
        val byId = uuid?.let { activeMutes[it] }
        if (byId != null && !byId.isExpired(now)) return byId

        val active = context.punishmentRepository.findActive(
            uuid, ip, PunishmentType.MUTE, PunishmentType.IP_MUTE, PunishmentType.SHADOW_MUTE
        )
        for (rec in active) {
            if (rec.isExpired(now)) {
                context.punishmentRepository.deactivate(rec.id)
                continue
            }
            if (uuid != null) activeMutes[uuid] = rec
            return rec
        }
        if (!ip.isNullOrBlank()) {
            val wildcardMutes = context.punishmentRepository.findActiveByIpWildcard(
                ip, PunishmentType.IP_MUTE
            )
            for (rec in wildcardMutes) {
                if (rec.isExpired(now)) {
                    context.punishmentRepository.deactivate(rec.id)
                    continue
                }
                return rec
            }
        }
        return null
    }

    suspend fun mute(
        target: TargetIdentity,
        actor: CommandSender,
        durationMillis: Long?,
        reason: String?,
        shadow: Boolean = false,
        ipMute: Boolean = false,
        silent: Boolean = silentByDefault,
        templateName: String? = null,
        noOverride: Boolean = false,
        modify: Boolean = false,
        customSender: String? = null,
    ): Pair<PunishmentRecord?, String?> {
        val type = when {
            shadow -> PunishmentType.SHADOW_MUTE
            ipMute || punishIpByDefault -> PunishmentType.IP_MUTE
            else -> PunishmentType.MUTE
        }

        val (isExempt, exemptReason) = policy.checkExempt(actor, target.onlinePlayer, target.name, type)
        if (isExempt) {
            return null to (exemptReason ?: "Target is exempt from mutes.")
        }

        var effectiveDuration = durationMillis
        var effectiveReason = reason
        var templateActions: List<String> = emptyList()

        if (templateName != null) {
            val tmpl = templates.getTemplate(templateName, type)
            if (tmpl == null) {
                return null to "Unknown template '$templateName'."
            }
            if (tmpl.permission != null && !actor.hasPermission(tmpl.permission) && !actor.hasPermission("obsidian.admin")) {
                return null to "You do not have permission to use template '$templateName'."
            }
            val strikes = context.punishmentRepository.countTemplateStrikes(
                target.uuid, target.ip, tmpl.name, System.currentTimeMillis() - tmpl.expireLadderMillis, tmpl.ipTemplate
            )
            val step = templates.evaluateStep(tmpl, strikes)
            if (effectiveDuration == null && step.durationMillis != null) {
                effectiveDuration = step.durationMillis
            }
            if (effectiveReason.isNullOrBlank()) {
                effectiveReason = step.reason ?: tmpl.defaultReason
            }
            templateActions = step.actions
        }

        val resolvedReason = effectiveReason?.takeIf { it.isNotBlank() } ?: defaultMuteReason

        val (allowedDur, durError) = policy.checkDurationLimit(actor, effectiveDuration, type)
        if (!allowedDur) {
            return null to durError
        }

        val remainingCooldown = policy.checkAndApplyCooldown(actor, target.name, type)
        if (remainingCooldown > 0L) {
            val secs = (remainingCooldown / 1000L).coerceAtLeast(1L)
            return null to "You must wait ${secs}s before issuing another punishment."
        }

        val existing = checkActiveMute(target.uuid, target.ip)
        if (existing != null) {
            if (noOverride) {
                return null to "Target is already muted (-N prevented override)."
            }
            if (modify) {
                val newExpiry = effectiveDuration?.let { System.currentTimeMillis() + it }
                context.punishmentRepository.updatePunishment(existing.id, resolvedReason, newExpiry)
                return existing.copy(reason = resolvedReason, expiresAt = newExpiry) to null
            }
        }

        val now = System.currentTimeMillis()
        val actorUuid = (actor as? Player)?.uniqueId
        val actorName = customSender ?: actor.name
        val rec = PunishmentRecord(
            type = type,
            targetUuid = target.uuid,
            targetName = target.name,
            targetIp = if (type == PunishmentType.IP_MUTE) (target.ip ?: target.name) else null,
            actorUuid = actorUuid,
            actorName = actorName,
            reason = resolvedReason,
            createdAt = now,
            expiresAt = effectiveDuration?.let { now + it },
            active = true,
            silent = silent,
            templateId = templateName,
        )

        if (target.uuid != null) {
            context.punishmentRepository.deactivateForTarget(PunishmentType.MUTE, target.uuid)
            context.punishmentRepository.deactivateForTarget(PunishmentType.IP_MUTE, target.uuid, rec.targetIp)
            context.punishmentRepository.deactivateForTarget(PunishmentType.SHADOW_MUTE, target.uuid)
            activeMutes[target.uuid] = rec
        }
        context.punishmentRepository.insert(rec)

        if (templateActions.isNotEmpty()) {
            context.scheduler.runGlobal {
                for (action in templateActions) {
                    val cmd = action.replace("\$playerName", target.name).replace("{player}", target.name)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.removePrefix("/"))
                }
            }
        }

        return rec to null
    }

    fun applyMuteEffects(target: TargetIdentity, actor: CommandSender, rec: PunishmentRecord, shadow: Boolean) {
        val expiresStr = DurationParser.formatExpiry(rec.expiresAt, rec.createdAt)
        context.locale.send(
            actor,
            "moderation.muted",
            "target" to target.name,
            "expires" to expiresStr,
            "reason" to rec.reason,
        )
        if (!shadow) {
            target.onlinePlayer?.let { p ->
                context.locale.send(
                    p,
                    "moderation.muted-notify",
                    "expires" to expiresStr,
                    "reason" to rec.reason,
                )
            }
        }
    }

    suspend fun unmute(
        target: TargetIdentity,
        actor: CommandSender,
        reason: String? = null,
    ): Boolean {
        val uuid = target.uuid
        val actorUuid = (actor as? Player)?.uniqueId
        if (uuid != null) activeMutes.remove(uuid)
        val removed = context.punishmentRepository.deactivateForTarget(
            PunishmentType.MUTE, uuid, target.ip, actorUuid, actor.name, reason
        ) + context.punishmentRepository.deactivateForTarget(
            PunishmentType.IP_MUTE, uuid, target.ip, actorUuid, actor.name, reason
        ) + context.punishmentRepository.deactivateForTarget(
            PunishmentType.SHADOW_MUTE, uuid, target.ip, actorUuid, actor.name, reason
        )
        return removed > 0
    }

    // --- Warnings ---

    suspend fun warn(
        target: TargetIdentity,
        actor: CommandSender,
        reason: String?,
        silent: Boolean = silentByDefault,
        templateName: String? = null,
        customSender: String? = null,
    ): Pair<Int, String?> {
        val uuid = target.uuid ?: return 0 to null
        val (isExempt, exemptReason) = policy.checkExempt(actor, target.onlinePlayer, target.name, PunishmentType.WARN)
        if (isExempt) {
            return 0 to (exemptReason ?: "Target is exempt from warnings.")
        }

        var effectiveReason = reason
        var templateActions: List<String> = emptyList()

        if (templateName != null) {
            val tmpl = templates.getTemplate(templateName, PunishmentType.WARN)
            if (tmpl != null) {
                val strikes = context.punishmentRepository.countTemplateStrikes(
                    target.uuid,
                    target.ip,
                    tmpl.name,
                    System.currentTimeMillis() - tmpl.expireLadderMillis,
                    tmpl.ipTemplate
                )
                val step = templates.evaluateStep(tmpl, strikes)
                if (effectiveReason.isNullOrBlank()) effectiveReason = step.reason ?: tmpl.defaultReason
                templateActions = step.actions
            }
        }

        val resolvedReason = effectiveReason?.takeIf { it.isNotBlank() } ?: defaultWarnReason
        val actorUuid = (actor as? Player)?.uniqueId
        val actorName = customSender ?: actor.name
        val rec = PunishmentRecord(
            type = PunishmentType.WARN,
            targetUuid = uuid,
            targetName = target.name,
            actorUuid = actorUuid,
            actorName = actorName,
            reason = resolvedReason,
            active = true,
            silent = silent,
            templateId = templateName,
        )
        context.punishmentRepository.insert(rec)

        if (templateActions.isNotEmpty()) {
            context.scheduler.runGlobal {
                for (action in templateActions) {
                    val cmd = action.replace("\$playerName", target.name).replace("{player}", target.name)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.removePrefix("/"))
                }
            }
        }

        val allWarnings = context.punishmentRepository.findActive(uuid, null, PunishmentType.WARN)
        return allWarnings.size to resolvedReason
    }

    fun applyWarnEffects(target: TargetIdentity, actor: CommandSender, count: Int, reason: String) {
        context.locale.send(
            actor,
            "moderation.warned",
            "target" to target.name,
            "count" to count.toString(),
            "reason" to reason,
        )
        target.onlinePlayer?.let { p ->
            context.locale.send(
                p,
                "moderation.warned-notify",
                "count" to count.toString(),
                "actor" to actor.name,
                "reason" to reason,
            )
        }
        warnEscalations[count]?.let { cmd ->
            val resolvedCmd = cmd.replace("{player}", target.name)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolvedCmd)
        }
    }

    suspend fun getActiveWarnings(uuid: UUID): List<PunishmentRecord> =
        context.punishmentRepository.findActive(uuid, null, PunishmentType.WARN)

    suspend fun clearWarnings(uuid: UUID): Int =
        context.punishmentRepository.deactivateForTarget(PunishmentType.WARN, uuid)

    suspend fun unwarn(
        uuid: UUID,
        actor: CommandSender,
        reason: String? = null,
    ): Boolean {
        val active = context.punishmentRepository.findActive(uuid, null, PunishmentType.WARN)
        val latest = active.firstOrNull() ?: return false
        val actorUuid = (actor as? Player)?.uniqueId
        context.punishmentRepository.deactivate(latest.id, actorUuid, actor.name, reason)
        return true
    }

    // --- Kicks ---

    fun recordKick(target: TargetIdentity, actor: CommandSender, reason: String) {
        val now = System.currentTimeMillis()
        val actorUuid = (actor as? Player)?.uniqueId
        val rec = PunishmentRecord(
            type = PunishmentType.KICK,
            targetUuid = target.uuid,
            targetName = target.name,
            targetIp = target.ip,
            actorUuid = actorUuid,
            actorName = actor.name,
            reason = reason,
            createdAt = now,
            active = false,
        )
        context.scheduler.asyncScope.launch {
            context.punishmentRepository.insert(rec)
        }
    }

    // --- Staff Audit & Rollback ---

    suspend fun rollbackStaff(
        staffQuery: String,
        durationMillis: Long,
        actor: CommandSender,
    ): Int {
        val identity = resolveTarget(staffQuery)
        val since = System.currentTimeMillis() - durationMillis
        return context.punishmentRepository.rollbackByStaff(
            identity?.uuid, staffQuery, since, actor.name
        )
    }

    suspend fun pruneHistory(
        targetQuery: String,
        durationMillis: Long?,
    ): Int {
        val identity = resolveTarget(targetQuery) ?: return 0
        val before = durationMillis?.let { System.currentTimeMillis() - it }
        return context.punishmentRepository.pruneHistory(identity.uuid, identity.ip, before)
    }

    // --- Alt Account (DupeIP) Detection ---

    suspend fun scanAlts(targetUuid: UUID?, ip: String?): List<PlayerIpRecord> {
        if (ip.isNullOrBlank()) return emptyList()
        val accounts = context.punishmentRepository.findAccountsByIp(ip)
        val cutoff = System.currentTimeMillis() - dupeipScanFilterMillis
        return accounts.filter { it.lastSeen >= cutoff }.take(dupeipScanLimit)
    }

    suspend fun hasActiveBannedAlt(accounts: List<PlayerIpRecord>, currentUuid: UUID?): Boolean {
        for (acc in accounts) {
            if (acc.uuid == currentUuid) continue
            val ban =
                context.punishmentRepository.findActive(acc.uuid, acc.ip, PunishmentType.BAN, PunishmentType.IP_BAN)
            if (ban.any { !it.isExpired() }) return true
        }
        return false
    }

    suspend fun hasActiveMutedAlt(accounts: List<PlayerIpRecord>, currentUuid: UUID?): Boolean {
        for (acc in accounts) {
            if (acc.uuid == currentUuid) continue
            val mute =
                context.punishmentRepository.findActive(acc.uuid, acc.ip, PunishmentType.MUTE, PunishmentType.IP_MUTE)
            if (mute.any { !it.isExpired() }) return true
        }
        return false
    }

    // --- Jails ---

    fun getJailNames(): List<String> = jails.keys.sorted()

    fun getJailLocation(name: String): StoredLocation? = jails[name.lowercase()]

    fun setJail(name: String, location: Location): Boolean {
        val stored = StoredLocation.fromBukkit(location) ?: return false
        val key = name.lowercase()
        jails[key] = stored
        jailsFile.yaml.set("jails.$key", stored.serialize())
        jailsFile.save()
        return true
    }

    fun removeJail(name: String): Boolean {
        val key = name.lowercase()
        if (jails.remove(key) == null) return false
        jailsFile.yaml.set("jails.$key", null)
        jailsFile.save()
        return true
    }

    fun getActiveJail(uuid: UUID): PunishmentRecord? {
        val rec = activeJails[uuid] ?: return null
        if (rec.isExpired()) {
            activeJails.remove(uuid)
            context.scheduler.asyncScope.launch { context.punishmentRepository.deactivate(rec.id) }
            return null
        }
        return rec
    }

    suspend fun jail(
        target: TargetIdentity,
        actor: CommandSender,
        jailName: String,
        durationMillis: Long?,
        reason: String?,
    ): PunishmentRecord? {
        val uuid = target.uuid ?: return null
        getJailLocation(jailName) ?: return null
        val now = System.currentTimeMillis()
        val resolvedReason = reason?.takeIf { it.isNotBlank() } ?: "Jailed by staff"
        val rec = PunishmentRecord(
            type = PunishmentType.JAIL,
            targetUuid = uuid,
            targetName = target.name,
            actorUuid = (actor as? Player)?.uniqueId,
            actorName = actor.name,
            reason = resolvedReason,
            jailName = jailName.lowercase(),
            createdAt = now,
            expiresAt = durationMillis?.let { now + it },
            active = true,
        )
        context.punishmentRepository.deactivateForTarget(PunishmentType.JAIL, uuid)
        context.punishmentRepository.insert(rec)
        activeJails[uuid] = rec
        return rec
    }

    fun applyJailEffects(target: TargetIdentity, actor: CommandSender, jailName: String, rec: PunishmentRecord) {
        val jailLoc = getJailLocation(jailName)
        val expiresStr = DurationParser.formatExpiry(rec.expiresAt, rec.createdAt)
        target.onlinePlayer?.let { p ->
            val bukkitLoc = jailLoc?.toBukkitLocation()
            if (bukkitLoc != null) {
                context.scheduler.runForEntity(p) { p.teleportAsync(bukkitLoc) }
            }
            context.locale.send(
                p,
                "moderation.jailed-notify",
                "jail" to jailName,
                "expires" to expiresStr,
                "reason" to rec.reason,
            )
        }
        context.locale.send(
            actor,
            "moderation.jailed",
            "target" to target.name,
            "jail" to jailName,
            "expires" to expiresStr,
        )
    }

    suspend fun unjail(target: TargetIdentity): Boolean {
        val uuid = target.uuid ?: return false
        activeJails.remove(uuid)
        return context.punishmentRepository.deactivateForTarget(PunishmentType.JAIL, uuid) > 0
    }

    // --- Cuffs ---

    fun isCuffed(uuid: UUID): Boolean = uuid in cuffedPlayers

    fun toggleCuff(target: Player): Boolean {
        return if (cuffedPlayers.add(target.uniqueId)) true else {
            cuffedPlayers.remove(target.uniqueId)
            false
        }
    }

    // --- Vanish ---

    fun isVanished(uuid: UUID): Boolean = uuid in vanishedPlayers

    fun setVanished(player: Player, vanish: Boolean) {
        if (vanish) {
            vanishedPlayers.add(player.uniqueId)
            if (vanishNightVision) {
                player.addPotionEffect(
                    PotionEffect(
                        PotionEffectType.NIGHT_VISION, Int.MAX_VALUE, 0, false, false, false
                    )
                )
            }
            for (viewer in Bukkit.getOnlinePlayers()) {
                if (viewer != player && !viewer.hasPermission("obsidian.vanish.see") && !viewer.hasPermission("obsidian.admin")) {
                    viewer.hidePlayer(context.plugin, player)
                }
            }
        } else {
            vanishedPlayers.remove(player.uniqueId)
            player.removePotionEffect(PotionEffectType.NIGHT_VISION)
            for (viewer in Bukkit.getOnlinePlayers()) {
                if (viewer != player) {
                    viewer.showPlayer(context.plugin, player)
                }
            }
        }
    }

    fun toggleVanish(player: Player): Boolean {
        val next = !isVanished(player.uniqueId)
        setVanished(player, next)
        return next
    }

    fun applyVanishVisibilityForViewer(viewer: Player) {
        val canSee = viewer.hasPermission("obsidian.vanish.see") || viewer.hasPermission("obsidian.admin")
        for (vanishedUuid in vanishedPlayers) {
            val vanished = Bukkit.getPlayer(vanishedUuid) ?: continue
            if (vanished == viewer) continue
            if (canSee) viewer.showPlayer(context.plugin, vanished) else viewer.hidePlayer(context.plugin, vanished)
        }
    }

    // --- Login state sync ---

    fun loadOnlineModerationState(player: Player) {
        val uuid = player.uniqueId
        val ip = player.address?.address?.hostAddress
        context.scheduler.asyncScope.launch {
            if (!ip.isNullOrBlank()) {
                context.punishmentRepository.recordPlayerIp(uuid, ip, player.name)
            }
            context.punishmentRepository.recordNameHistory(uuid, player.name)

            val now = System.currentTimeMillis()
            val active = context.punishmentRepository.findActive(
                uuid,
                ip,
                PunishmentType.MUTE,
                PunishmentType.IP_MUTE,
                PunishmentType.SHADOW_MUTE,
                PunishmentType.JAIL,
            )
            activeMutes.remove(uuid)
            activeJails.remove(uuid)
            for (rec in active) {
                if (rec.isExpired(now)) {
                    context.punishmentRepository.deactivate(rec.id)
                    continue
                }
                when (rec.type) {
                    PunishmentType.MUTE, PunishmentType.IP_MUTE, PunishmentType.SHADOW_MUTE -> activeMutes[uuid] = rec
                    PunishmentType.JAIL -> {
                        activeJails[uuid] = rec
                        val loc = rec.jailName?.let(::getJailLocation)?.toBukkitLocation()
                        if (loc != null) {
                            context.scheduler.runForEntity(player) { player.teleportAsync(loc) }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    fun clearOnlineTransientState(uuid: UUID) {
        activeMutes.remove(uuid)
        activeJails.remove(uuid)
        vanishedPlayers.remove(uuid)
    }

    // --- Inventory snapshots ---

    suspend fun saveInventorySnapshot(owner: Player, snapshotName: String): SavedInventoryRecord {
        val bytes = ItemStack.serializeItemsAsBytes(owner.inventory.contents)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val record = SavedInventoryRecord(
            id = "${owner.uniqueId}:${snapshotName.lowercase()}",
            ownerUuid = owner.uniqueId,
            name = snapshotName,
            payloadBase64 = encoded,
        )
        context.savedInventoryRepository.upsert(record)
        return record
    }

    suspend fun loadInventorySnapshot(target: Player, nameOrId: String): Boolean {
        val rec = context.savedInventoryRepository.findByNameOrId(target.uniqueId, nameOrId) ?: return false
        val bytes = Base64.getDecoder().decode(rec.payloadBase64)
        val items = ItemStack.deserializeItemsFromBytes(bytes)
        context.scheduler.runForEntity(target) {
            target.inventory.contents = items
        }
        return true
    }

    // --- Info summary helper ---

    fun formatInfoLines(
        profile: obsidian.core.user.UserProfile,
        online: Player?,
        ip: String?,
        warningsCount: Int,
        activeMute: PunishmentRecord?,
        activeJail: PunishmentRecord?,
    ): List<String> {
        val eco = context.getService<EconomyService>()
        val rankSvc = context.getService<RankService>()
        val ptRec = rankSvc?.getOrLoadSnapshot(profile.uuid, profile.username)
        val status = context.locale.raw(if (online != null) "moderation.status-online" else "moderation.status-offline")
        return buildList {
            add(context.locale.raw("moderation.whois-header", "player" to profile.username))
            add(context.locale.raw("moderation.whois-uuid", "uuid" to profile.uuid))
            add(
                if (ip != null) {
                    context.locale.raw("moderation.whois-status-ip", "status" to status, "ip" to ip)
                } else {
                    context.locale.raw("moderation.whois-status", "status" to status)
                }
            )
            if (eco != null) {
                add(
                    context.locale.raw(
                        "moderation.whois-balance",
                        "balance" to eco.formatMoney(eco.getBalance(profile.uuid))
                    )
                )
            }
            if (ptRec != null) {
                add(
                    context.locale.raw(
                        "moderation.whois-playtime",
                        "playtime" to TextFormatter.formatDuration(ptRec.totalMillis),
                        "rank" to ptRec.currentRank,
                    )
                )
            }
            add(
                context.locale.raw(
                    "moderation.whois-modes",
                    "god" to profile.godMode,
                    "fly" to profile.flyMode,
                    "vanished" to isVanished(profile.uuid),
                )
            )
            add(
                context.locale.raw(
                    "moderation.whois-punishments",
                    "warnings" to warningsCount,
                    "muted" to (activeMute != null),
                    "jailed" to (activeJail != null),
                )
            )
        }
    }

    companion object {
        private val ipRegex = Regex("""^\d{1,3}(\.\d{1,3}){3}$|^[0-9a-fA-F:]+$""")
        private val wildcardRegex = Regex("""^\d{1,3}(\.(\d{1,3}|\*)){1,3}$""")

        fun isIpAddress(value: String): Boolean = ipRegex.matches(value.trim())

        fun isIpOrWildcard(value: String): Boolean =
            ipRegex.matches(value.trim()) || wildcardRegex.matches(value.trim())
    }
}
