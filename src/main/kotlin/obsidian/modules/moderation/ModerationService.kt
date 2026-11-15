package obsidian.modules.moderation

import kotlinx.coroutines.launch
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.PunishmentRecord
import obsidian.core.database.PunishmentType
import obsidian.core.database.SavedInventoryRecord
import obsidian.core.database.StoredLocation
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
 * Manages bans, mutes, warnings, jails, cuffs, vanish, IP lookup, and saved inventories.
 */
class ModerationService(private val context: ObsidianContext) {

    private val jailsFile = YamlConfigFile(context.plugin, "jails.yml")
    private val jails = ConcurrentHashMap<String, StoredLocation>()

    // Active online/cached states keyed by UUID
    private val activeMutes = ConcurrentHashMap<UUID, PunishmentRecord>()
    private val activeJails = ConcurrentHashMap<UUID, PunishmentRecord>()
    private val cuffedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val vanishedPlayers = ConcurrentHashMap.newKeySet<UUID>()

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

    init {
        reload()
    }

    fun reload() {
        jailsFile.reload()
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
        if (isIpAddress(input)) {
            return TargetIdentity(null, input, input, null)
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
        return null
    }

    suspend fun ban(
        target: TargetIdentity,
        actor: CommandSender,
        durationMillis: Long?,
        reason: String?,
        ipBan: Boolean = false,
    ): PunishmentRecord {
        val now = System.currentTimeMillis()
        val resolvedReason = reason?.takeIf { it.isNotBlank() } ?: defaultBanReason
        val type = if (ipBan) PunishmentType.IP_BAN else PunishmentType.BAN
        val actorUuid = (actor as? Player)?.uniqueId
        val record = PunishmentRecord(
            type = type,
            targetUuid = target.uuid,
            targetName = target.name,
            targetIp = if (ipBan) target.ip else null,
            actorUuid = actorUuid,
            actorName = actor.name,
            reason = resolvedReason,
            createdAt = now,
            expiresAt = durationMillis?.let { now + it },
            active = true,
        )
        context.punishmentRepository.deactivateForTarget(type, target.uuid, record.targetIp)
        context.punishmentRepository.insert(record)
        return record
    }

    fun applyBanEffects(target: TargetIdentity, actor: CommandSender, record: PunishmentRecord, ipBan: Boolean) {
        val expiresStr = DurationParser.formatExpiry(record.expiresAt, record.createdAt)
        val kickComponent = TextFormatter.format(
            context.locale.raw("moderation.banned-screen"),
            mapOf(
                "reason" to record.reason,
                "actor" to actor.name,
                "expires" to expiresStr,
            ),
        )
        target.onlinePlayer?.let { p ->
            context.scheduler.runForEntity(p) { p.kick(kickComponent) }
        }
        if (ipBan && record.targetIp != null) {
            for (online in Bukkit.getOnlinePlayers()) {
                if (online.address?.address?.hostAddress == record.targetIp) {
                    context.scheduler.runForEntity(online) { online.kick(kickComponent) }
                }
            }
        }
        val broadcast = TextFormatter.format(
            context.locale.raw("moderation.ban-broadcast"),
            mapOf(
                "target" to target.name,
                "actor" to actor.name,
                "reason" to record.reason,
                "expires" to expiresStr,
            ),
        )
        Bukkit.broadcast(broadcast)
        context.getService<DiscordWebhookService>()?.sendEmbedAsync(
            title = if (ipBan) "IP Ban Issued" else "Ban Issued",
            description = "Target: ${target.name}\nActor: ${actor.name}\nReason: ${record.reason}\nExpires: $expiresStr",
            colorRgb = 0xEF4444,
        )
    }

    suspend fun unban(query: String): Boolean {
        val identity = resolveTarget(query)
        var deactivated = 0
        if (identity != null) {
            deactivated += context.punishmentRepository.deactivateForTarget(PunishmentType.BAN, identity.uuid, null)
            deactivated += context.punishmentRepository.deactivateForTarget(
                PunishmentType.IP_BAN,
                identity.uuid,
                identity.ip
            )
        }
        val byQuery =
            context.punishmentRepository.findActiveByNameOrIp(query, PunishmentType.BAN, PunishmentType.IP_BAN)
        for (rec in byQuery) {
            context.punishmentRepository.deactivate(rec.id)
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

    suspend fun mute(
        target: TargetIdentity,
        actor: CommandSender,
        durationMillis: Long?,
        reason: String?,
        shadow: Boolean = false,
    ): PunishmentRecord {
        val now = System.currentTimeMillis()
        val resolvedReason = reason?.takeIf { it.isNotBlank() } ?: defaultMuteReason
        val type = if (shadow) PunishmentType.SHADOW_MUTE else PunishmentType.MUTE
        val rec = PunishmentRecord(
            type = type,
            targetUuid = target.uuid,
            targetName = target.name,
            actorUuid = (actor as? Player)?.uniqueId,
            actorName = actor.name,
            reason = resolvedReason,
            createdAt = now,
            expiresAt = durationMillis?.let { now + it },
            active = true,
        )
        if (target.uuid != null) {
            context.punishmentRepository.deactivateForTarget(PunishmentType.MUTE, target.uuid)
            context.punishmentRepository.deactivateForTarget(PunishmentType.SHADOW_MUTE, target.uuid)
            activeMutes[target.uuid] = rec
        }
        context.punishmentRepository.insert(rec)
        return rec
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

    suspend fun unmute(target: TargetIdentity): Boolean {
        val uuid = target.uuid ?: return false
        activeMutes.remove(uuid)
        val removed = context.punishmentRepository.deactivateForTarget(PunishmentType.MUTE, uuid) +
                context.punishmentRepository.deactivateForTarget(PunishmentType.SHADOW_MUTE, uuid)
        return removed > 0
    }

    // --- Warnings ---

    suspend fun warn(target: TargetIdentity, actor: CommandSender, reason: String?): Pair<Int, String> {
        val uuid = target.uuid ?: return 0 to defaultWarnReason
        val resolvedReason = reason?.takeIf { it.isNotBlank() } ?: defaultWarnReason
        val rec = PunishmentRecord(
            type = PunishmentType.WARN,
            targetUuid = uuid,
            targetName = target.name,
            actorUuid = (actor as? Player)?.uniqueId,
            actorName = actor.name,
            reason = resolvedReason,
            active = true,
        )
        context.punishmentRepository.insert(rec)
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
                        PotionEffectType.NIGHT_VISION,
                        Int.MAX_VALUE,
                        0,
                        false,
                        false,
                        false
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
            val now = System.currentTimeMillis()
            val active = context.punishmentRepository.findActive(
                uuid,
                null,
                PunishmentType.MUTE,
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
                    PunishmentType.MUTE, PunishmentType.SHADOW_MUTE -> activeMutes[uuid] = rec
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
        val status = if (online != null) "<green>ONLINE</green>" else "<gray>OFFLINE</gray>"
        return buildList {
            add("<gradient:#7c3aed:#06b6d4><bold>Player Info: ${profile.username}</bold></gradient>")
            add("<gray>UUID:</gray> <white>${profile.uuid}</white>")
            add("<gray>Status:</gray> $status" + (ip?.let { " <dark_gray>|</dark_gray> <gray>IP:</gray> <white>$it</white>" }
                ?: ""))
            if (eco != null) {
                add("<gray>Balance:</gray> <gold>${eco.formatMoney(eco.getBalance(profile.uuid))}</gold>")
            }
            if (ptRec != null) {
                add("<gray>PlayTime:</gray> <aqua>${TextFormatter.formatDuration(ptRec.totalMillis)}</aqua> <dark_gray>|</dark_gray> <gray>Rank:</gray> <aqua>${ptRec.currentRank}</aqua>")
            }
            add(
                "<gray>God:</gray> ${profile.godMode} <dark_gray>|</dark_gray> <gray>Fly:</gray> ${profile.flyMode} <dark_gray>|</dark_gray> <gray>Vanished:</gray> ${
                    isVanished(
                        profile.uuid
                    )
                }"
            )
            add("<gray>Warnings:</gray> <yellow>$warningsCount</yellow> <dark_gray>|</dark_gray> <gray>Muted:</gray> ${activeMute != null} <dark_gray>|</dark_gray> <gray>Jailed:</gray> ${activeJail != null}")
        }
    }

    companion object {
        private val ipRegex = Regex("""^\d{1,3}(\.\d{1,3}){3}$|^[0-9a-fA-F:]+$""")

        fun isIpAddress(value: String): Boolean = ipRegex.matches(value.trim())
    }
}
