package obsidian.modules.moderation

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.database.PunishmentType
import obsidian.core.module.ObsidianModule
import obsidian.core.text.DurationParser
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.event.Listener
import java.text.SimpleDateFormat
import java.util.*

/**
 * Moderation, punishment, jail, vanish, IP tracking, and inventory inspection module.
 */
class ModerationModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "moderation"
    override val description: String = "Bans, mutes, warnings, jails, vanish, cuffs, and inventory snapshots"

    val moderationService = ModerationService(context)
    private val listeners = ModerationListeners(context, moderationService)
    val maintenanceAndNoteService = MaintenanceAndNoteService(context)
    val silentChestService = SilentChestService(context)
    val patrolService = PatrolService(context)
    val scanService = ScanService(context)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    override fun onEnable() {
        context.registerService(moderationService)
        context.registerService(silentChestService)
        context.registerService(patrolService)
        context.registerService(scanService)
        maintenanceAndNoteService.load()
    }

    override fun onDisable() {
        maintenanceAndNoteService.save()
    }

    override fun onReload() {
        moderationService.reload()
        maintenanceAndNoteService.load()
    }

    override fun listeners(): List<Listener> = listOf(listeners, maintenanceAndNoteService, silentChestService)

    override fun registerCommands(registrar: Commands) {
        registerBanCommands(registrar)
        registerKick(registrar)
        registerMuteCommands(registrar)
        registerWarnCommands(registrar)
        registerJailCommands(registrar)
        registerCuffAndVanish(registrar)
        registerInventoryCommands(registrar)
        registerInfoCommands(registrar)
        registerMaintenanceAndNotes(registrar)
        registerAdminInvestigation(registrar)
        registerAuditCommands(registrar)
    }

    private fun registerBanCommands(registrar: Commands) {
        val banNode = Commands.literal("ban")
            .requires { it.sender.hasPermission("obsidian.command.ban") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    executeBanWithTail(
                        ctx.source.sender, StringArgumentType.getString(ctx, "target"), null, ipBan = false
                    )
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("tail", StringArgumentType.greedyString()).executes { ctx ->
                        executeBanWithTail(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "target"),
                            StringArgumentType.getString(ctx, "tail"),
                            ipBan = false,
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, banNode, "Permanently or temporarily ban a player", emptyList())

        val tempBanNode = Commands.literal("tempban")
            .requires { it.sender.hasPermission("obsidian.command.tempban") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.then(
                    Commands.argument("duration", StringArgumentType.word()).executes { ctx ->
                        val dur = DurationParser.parseMillis(StringArgumentType.getString(ctx, "duration"))
                        if (dur == null) {
                            context.locale.send(
                                ctx.source.sender,
                                "general.invalid-number",
                                "input" to StringArgumentType.getString(ctx, "duration")
                            )
                            return@executes 0
                        }
                        executeBanWithTail(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "target"),
                            null,
                            ipBan = false,
                            forcedDuration = dur
                        )
                        Command.SINGLE_SUCCESS
                    }.then(
                        Commands.argument("tail", StringArgumentType.greedyString()).executes { ctx ->
                            val dur = DurationParser.parseMillis(StringArgumentType.getString(ctx, "duration"))
                            if (dur == null) {
                                context.locale.send(
                                    ctx.source.sender,
                                    "general.invalid-number",
                                    "input" to StringArgumentType.getString(ctx, "duration")
                                )
                                return@executes 0
                            }
                            executeBanWithTail(
                                ctx.source.sender,
                                StringArgumentType.getString(ctx, "target"),
                                StringArgumentType.getString(ctx, "tail"),
                                ipBan = false,
                                forcedDuration = dur,
                            )
                            Command.SINGLE_SUCCESS
                        })
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, tempBanNode, "Temporarily ban a player", emptyList())

        val ipBanNode = Commands.literal("ipban")
            .requires { it.sender.hasPermission("obsidian.command.ipban") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    executeBanWithTail(
                        ctx.source.sender, StringArgumentType.getString(ctx, "target"), null, ipBan = true
                    )
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("tail", StringArgumentType.greedyString()).executes { ctx ->
                        executeBanWithTail(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "target"),
                            StringArgumentType.getString(ctx, "tail"),
                            ipBan = true,
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            ipBanNode,
            "Ban a player IP address or wildcard IP",
            listOf("banip", "ban-ip"),
        )

        val unbanNode = Commands.literal("unban")
            .requires { it.sender.hasPermission("obsidian.command.unban") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).executes { ctx ->
                    executeUnban(ctx.source.sender, StringArgumentType.getString(ctx, "target"), null)
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                        executeUnban(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "target"),
                            StringArgumentType.getString(ctx, "reason"),
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            unbanNode,
            "Unban a player or IP",
            listOf("pardon", "unbanip"),
        )

        val checkBanNode = Commands.literal("checkban")
            .requires { it.sender.hasPermission("obsidian.command.checkban") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val query = StringArgumentType.getString(ctx, "target")
                CommandHelper.dispatchAsync(
                    context,
                    sender,
                    asyncWork = {
                        val id = moderationService.resolveTarget(query, allowUnseenName = true)
                        val ip = id?.ip ?: if (ModerationService.isIpOrWildcard(query)) query else null
                        moderationService.checkActiveBan(id?.uuid, ip, id?.name ?: query)
                    },
                    onResult = { ban ->
                        if (ban == null) {
                            context.locale.send(sender, "moderation.not-banned", "target" to query)
                        } else {
                            context.locale.send(
                                sender,
                                "moderation.ban-check",
                                "target" to (ban.targetName ?: ban.targetIp ?: query),
                                "actor" to ban.actorName,
                                "reason" to ban.reason,
                                "expires" to DurationParser.formatExpiry(ban.expiresAt),
                            )
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, checkBanNode, "Check active ban info", emptyList())
    }

    private fun executeBanWithTail(
        sender: CommandSender,
        targetName: String,
        tail: String?,
        ipBan: Boolean,
        forcedDuration: Long? = null,
    ) {
        val tokens = tail?.let { PunishmentFlagParser.splitArgs(it) } ?: emptyList()
        val knownTemplates =
            moderationService.templates.getKnownTemplateNames(if (ipBan) PunishmentType.IP_BAN else PunishmentType.BAN)
        val parsed = PunishmentFlagParser.parse(tokens, knownTemplates, allowDuration = forcedDuration == null)
        val dur = forcedDuration ?: parsed.durationMillis

        if (moderationService.requireBanMuteReason && parsed.reason.isNullOrBlank() && parsed.templateName == null) {
            context.locale.send(sender, "moderation.reason-required")
            return
        }

        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val identity = moderationService.resolveTarget(targetName, allowUnseenName = !ipBan)
                if (identity == null || (ipBan && identity.ip == null && !ModerationService.isIpOrWildcard(targetName))) {
                    null
                } else {
                    val resolvedId = if (ipBan && identity.ip == null && ModerationService.isIpOrWildcard(targetName)) {
                        ModerationService.TargetIdentity(null, targetName, targetName, null)
                    } else identity
                    val (rec, err) = moderationService.ban(
                        resolvedId,
                        sender,
                        dur,
                        parsed.reason,
                        ipBan = ipBan,
                        silent = parsed.silent ?: moderationService.silentByDefault,
                        templateName = parsed.templateName,
                        noOverride = parsed.noOverride,
                        modify = parsed.modify,
                        customSender = parsed.customSender,
                    )
                    Triple(resolvedId, rec, err)
                }
            },
            onResult = { res ->
                if (res == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    val (identity, record, error) = res
                    if (error != null) {
                        context.locale.send(sender, "moderation.punishment-error", "error" to error)
                    } else if (record != null) {
                        moderationService.applyBanEffects(identity, sender, record, ipBan)
                    }
                }
            },
        )
    }

    private fun executeUnban(sender: CommandSender, query: String, reason: String?) {
        if (moderationService.requireUnbanUnmuteReason && reason.isNullOrBlank()) {
            context.locale.send(sender, "moderation.reason-required")
            return
        }
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = { moderationService.unban(query, sender, reason) },
            onResult = { removed ->
                if (removed) {
                    context.locale.send(sender, "moderation.unbanned", "target" to query)
                } else {
                    context.locale.send(sender, "moderation.not-banned", "target" to query)
                }
            },
        )
    }

    private fun registerKick(registrar: Commands) {
        val node = Commands.literal("kick")
            .requires { it.sender.hasPermission("obsidian.command.kick") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val sender = ctx.source.sender
                    val targetName = StringArgumentType.getString(ctx, "target")
                    if (moderationService.requireKickReason) {
                        context.locale.send(sender, "moderation.reason-required")
                        return@executes 0
                    }
                    executeKick(sender, targetName, moderationService.defaultKickReason)
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                        val sender = ctx.source.sender
                        val targetName = StringArgumentType.getString(ctx, "target")
                        val reason = StringArgumentType.getString(ctx, "reason")
                        executeKick(sender, targetName, reason)
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, node, "Kick an online player", emptyList())
    }

    private fun executeKick(sender: CommandSender, targetName: String, reason: String) {
        val target = Bukkit.getPlayerExact(targetName) ?: Bukkit.getPlayer(targetName)
        if (target == null) {
            context.locale.send(sender, "general.player-not-found", "player" to targetName)
            return
        }
        val (isExempt, _) = moderationService.policy.checkExempt(sender, target, target.name, PunishmentType.KICK)
        if (isExempt) {
            context.locale.send(sender, "moderation.punishment-error", "error" to "Target is exempt from kicks.")
            return
        }
        val targetIdentity =
            ModerationService.TargetIdentity(target.uniqueId, target.name, target.address?.address?.hostAddress, target)
        moderationService.recordKick(targetIdentity, sender, reason)
        target.kick(TextFormatter.format("<red>$reason</red>"))
        context.locale.send(sender, "moderation.kicked", "target" to target.name, "reason" to reason)
    }

    private fun registerMuteCommands(registrar: Commands) {
        registerMuteLiteral(registrar, "mute", shadow = false, ipMute = false)
        registerMuteLiteral(registrar, "shadowmute", shadow = true, ipMute = false)
        registerMuteLiteral(
            registrar, "ipmute", shadow = false, ipMute = true, aliases = listOf("muteip", "tempipmute")
        )

        val tempMuteNode = Commands.literal("tempmute")
            .requires { it.sender.hasPermission("obsidian.command.tempmute") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.then(
                    Commands.argument("duration", StringArgumentType.word()).executes { ctx ->
                        val dur = DurationParser.parseMillis(StringArgumentType.getString(ctx, "duration"))
                        if (dur == null) {
                            context.locale.send(
                                ctx.source.sender,
                                "general.invalid-number",
                                "input" to StringArgumentType.getString(ctx, "duration")
                            )
                            return@executes 0
                        }
                        executeMuteWithTail(
                            ctx.source.sender, StringArgumentType.getString(ctx, "target"), null, forcedDuration = dur
                        )
                        Command.SINGLE_SUCCESS
                    }.then(
                        Commands.argument("tail", StringArgumentType.greedyString()).executes { ctx ->
                            val dur = DurationParser.parseMillis(StringArgumentType.getString(ctx, "duration"))
                            if (dur == null) {
                                context.locale.send(
                                    ctx.source.sender,
                                    "general.invalid-number",
                                    "input" to StringArgumentType.getString(ctx, "duration")
                                )
                                return@executes 0
                            }
                            executeMuteWithTail(
                                ctx.source.sender,
                                StringArgumentType.getString(ctx, "target"),
                                StringArgumentType.getString(ctx, "tail"),
                                forcedDuration = dur,
                            )
                            Command.SINGLE_SUCCESS
                        })
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, tempMuteNode, "Temporarily mute a player", emptyList())

        val checkMuteNode = Commands.literal("checkmute")
            .requires { it.sender.hasPermission("obsidian.command.checkmute") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val query = StringArgumentType.getString(ctx, "target")
                CommandHelper.dispatchAsync(
                    context,
                    sender,
                    asyncWork = {
                        val id = moderationService.resolveTarget(query, allowUnseenName = true)
                        val ip = id?.ip ?: if (ModerationService.isIpOrWildcard(query)) query else null
                        moderationService.checkActiveMute(id?.uuid, ip)
                    },
                    onResult = { mute ->
                        if (mute == null) {
                            context.locale.send(sender, "moderation.not-muted", "target" to query)
                        } else {
                            context.locale.send(
                                sender,
                                "moderation.mute-check",
                                "target" to (mute.targetName ?: mute.targetIp ?: query),
                                "actor" to mute.actorName,
                                "reason" to mute.reason,
                                "expires" to DurationParser.formatExpiry(mute.expiresAt),
                            )
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, checkMuteNode, "Check active mute info", emptyList())

        val unmuteNode = Commands.literal("unmute")
            .requires { it.sender.hasPermission("obsidian.command.unmute") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                executeUnmute(ctx.source.sender, StringArgumentType.getString(ctx, "target"), null)
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                executeUnmute(
                    ctx.source.sender,
                    StringArgumentType.getString(ctx, "target"),
                    StringArgumentType.getString(ctx, "reason"),
                )
                Command.SINGLE_SUCCESS
            })).build()
        registrar.register(context.plugin.pluginMeta, unmuteNode, "Unmute a player", emptyList())
    }

    private fun registerMuteLiteral(
        registrar: Commands,
        literal: String,
        shadow: Boolean,
        ipMute: Boolean,
        aliases: List<String> = emptyList(),
    ) {
        val node = Commands.literal(literal)
            .requires { it.sender.hasPermission("obsidian.command.$literal") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    executeMuteWithTail(
                        ctx.source.sender, StringArgumentType.getString(ctx, "target"), null, shadow, ipMute
                    )
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("tail", StringArgumentType.greedyString()).executes { ctx ->
                        executeMuteWithTail(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "target"),
                            StringArgumentType.getString(ctx, "tail"),
                            shadow,
                            ipMute,
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            if (shadow) "Shadow mute a player" else if (ipMute) "Mute a player IP address" else "Mute a player",
            aliases,
        )
    }

    private fun executeMuteWithTail(
        sender: CommandSender,
        targetName: String,
        tail: String?,
        shadow: Boolean = false,
        ipMute: Boolean = false,
        forcedDuration: Long? = null,
    ) {
        val tokens = tail?.let { PunishmentFlagParser.splitArgs(it) } ?: emptyList()
        val knownTemplates = moderationService.templates.getKnownTemplateNames(PunishmentType.MUTE)
        val parsed = PunishmentFlagParser.parse(tokens, knownTemplates, allowDuration = forcedDuration == null)
        val dur = forcedDuration ?: parsed.durationMillis

        if (moderationService.requireBanMuteReason && parsed.reason.isNullOrBlank() && parsed.templateName == null) {
            context.locale.send(sender, "moderation.reason-required")
            return
        }

        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val identity = moderationService.resolveTarget(targetName, allowUnseenName = true)
                if (identity == null || (ipMute && identity.ip == null && !ModerationService.isIpOrWildcard(targetName))) {
                    null
                } else {
                    val resolvedId =
                        if (ipMute && identity.ip == null && ModerationService.isIpOrWildcard(targetName)) {
                            ModerationService.TargetIdentity(null, targetName, targetName, null)
                        } else identity
                    val (rec, err) = moderationService.mute(
                        resolvedId,
                        sender,
                        dur,
                        parsed.reason,
                        shadow = shadow,
                        ipMute = ipMute,
                        silent = parsed.silent ?: moderationService.silentByDefault,
                        templateName = parsed.templateName,
                        noOverride = parsed.noOverride,
                        modify = parsed.modify,
                        customSender = parsed.customSender,
                    )
                    Triple(resolvedId, rec, err)
                }
            },
            onResult = { res ->
                if (res == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    val (identity, record, error) = res
                    if (error != null) {
                        context.locale.send(sender, "moderation.punishment-error", "error" to error)
                    } else if (record != null) {
                        moderationService.applyMuteEffects(identity, sender, record, shadow)
                    }
                }
            },
        )
    }

    private fun executeUnmute(sender: CommandSender, query: String, reason: String?) {
        if (moderationService.requireUnbanUnmuteReason && reason.isNullOrBlank()) {
            context.locale.send(sender, "moderation.reason-required")
            return
        }
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val id = moderationService.resolveTarget(query)
                if (id == null) null else id to moderationService.unmute(id, sender, reason)
            },
            onResult = { res ->
                if (res != null && res.second) {
                    context.locale.send(sender, "moderation.unmuted", "target" to res.first.name)
                } else {
                    context.locale.send(sender, "moderation.not-muted", "target" to query)
                }
            },
        )
    }

    private fun registerWarnCommands(registrar: Commands) {
        val warnNode = Commands.literal("warn")
            .requires { it.sender.hasPermission("obsidian.command.warn") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    executeWarnWithTail(ctx.source.sender, StringArgumentType.getString(ctx, "target"), null)
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("tail", StringArgumentType.greedyString()).executes { ctx ->
                        executeWarnWithTail(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "target"),
                            StringArgumentType.getString(ctx, "tail"),
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, warnNode, "Warn a player", emptyList())

        val checkWarnNode = Commands.literal("checkwarn")
            .requires { it.sender.hasPermission("obsidian.command.checkwarn") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val query = StringArgumentType.getString(ctx, "target")
                CommandHelper.dispatchAsync(
                    context,
                    sender,
                    asyncWork = {
                        val id = moderationService.resolveTarget(query)
                        if (id?.uuid == null) null else id to moderationService.getActiveWarnings(id.uuid)
                    },
                    onResult = { res ->
                        if (res == null) {
                            context.locale.send(sender, "general.player-not-found", "player" to query)
                        } else {
                            val (id, list) = res
                            if (list.isEmpty()) {
                                context.locale.send(sender, "moderation.not-warned", "target" to id.name)
                            } else {
                                context.locale.send(
                                    sender, "moderation.warn-check", "target" to id.name, "count" to list.size
                                )
                            }
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, checkWarnNode, "Check player warning count", emptyList())

        val unwarnNode = Commands.literal("unwarn")
            .requires { it.sender.hasPermission("obsidian.command.unwarn") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    executeUnwarn(ctx.source.sender, StringArgumentType.getString(ctx, "target"), null)
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                        executeUnwarn(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "target"),
                            StringArgumentType.getString(ctx, "reason"),
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, unwarnNode, "Remove the latest warning for a player", emptyList())

        val warningsNode = Commands.literal("warnings")
            .requires { it.sender.hasPermission("obsidian.command.warnings") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                showWarnings(player, player.name)
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                showWarnings(ctx.source.sender, StringArgumentType.getString(ctx, "target"))
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, warningsNode, "View active player warnings", emptyList())

        val clearWarnNode = Commands.literal("clearwarnings")
            .requires { it.sender.hasPermission("obsidian.command.clearwarnings") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).executes { ctx ->
                    val sender = ctx.source.sender
                    val query = StringArgumentType.getString(ctx, "target")
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = {
                            val id = moderationService.resolveTarget(query)
                            if (id?.uuid == null) null else {
                                moderationService.clearWarnings(id.uuid)
                                id
                            }
                        },
                        onResult = { id ->
                            if (id == null) {
                                context.locale.send(sender, "general.player-not-found", "player" to query)
                            } else {
                                context.locale.send(sender, "moderation.warnings-cleared", "target" to id.name)
                            }
                        },
                    )
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, clearWarnNode, "Clear warnings for a player", emptyList())
    }

    private fun executeWarnWithTail(
        sender: CommandSender,
        targetName: String,
        tail: String?,
    ) {
        val tokens = tail?.let { PunishmentFlagParser.splitArgs(it) } ?: emptyList()
        val knownTemplates = moderationService.templates.getKnownTemplateNames(PunishmentType.WARN)
        val parsed = PunishmentFlagParser.parse(tokens, knownTemplates, allowDuration = false)

        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val identity = moderationService.resolveTarget(targetName)
                if (identity?.uuid == null) null
                else {
                    val (count, reasonOrErr) = moderationService.warn(
                        identity,
                        sender,
                        parsed.reason,
                        silent = parsed.silent ?: moderationService.silentByDefault,
                        templateName = parsed.templateName,
                        customSender = parsed.customSender,
                    )
                    Triple(identity, count, reasonOrErr)
                }
            },
            onResult = { res ->
                if (res == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    val (identity, count, reason) = res
                    if (count <= 0 && reason != null) {
                        context.locale.send(sender, "moderation.punishment-error", "error" to reason)
                    } else if (reason != null) {
                        moderationService.applyWarnEffects(identity, sender, count, reason)
                    }
                }
            },
        )
    }

    private fun executeUnwarn(sender: CommandSender, query: String, reason: String?) {
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val id = moderationService.resolveTarget(query)
                if (id?.uuid == null) null
                else id to moderationService.unwarn(id.uuid, sender, reason)
            },
            onResult = { res ->
                if (res == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to query)
                } else {
                    val (id, success) = res
                    if (success) {
                        context.locale.send(sender, "moderation.unwarned", "target" to id.name)
                    } else {
                        context.locale.send(sender, "moderation.not-warned", "target" to id.name)
                    }
                }
            },
        )
    }

    private fun showWarnings(sender: CommandSender, targetName: String) {
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val id = moderationService.resolveTarget(targetName)
                if (id?.uuid == null) null else id to moderationService.getActiveWarnings(id.uuid)
            },
            onResult = { res ->
                if (res == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                    return@dispatchAsync
                }
                val (id, list) = res
                if (list.isEmpty()) {
                    context.locale.send(sender, "moderation.warnings-empty", "target" to id.name)
                    return@dispatchAsync
                }
                context.locale.send(
                    sender,
                    "moderation.warnings-header",
                    "target" to id.name,
                    "count" to list.size.toString(),
                )
                list.forEachIndexed { i, w ->
                    context.locale.sendRaw(
                        sender,
                        "moderation.warning-entry",
                        "index" to (i + 1),
                        "reason" to w.reason,
                        "actor" to w.actorName,
                    )
                }
            },
        )
    }

    private fun registerJailCommands(registrar: Commands) {
        val setJailNode = Commands.literal("setjail")
            .requires { it.sender.hasPermission("obsidian.command.setjail") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val name = StringArgumentType.getString(ctx, "name")
                    moderationService.setJail(name, player.location)
                    context.locale.send(player, "moderation.jail-set", "jail" to name)
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, setJailNode, "Create a jail location", emptyList())

        val delJailNode = Commands.literal("deljail")
            .requires { it.sender.hasPermission("obsidian.command.deljail") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("name", StringArgumentType.word()).suggests { _, b ->
                moderationService.getJailNames().filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val name = StringArgumentType.getString(ctx, "name")
                if (moderationService.removeJail(name)) {
                    context.locale.send(sender, "moderation.jail-removed", "jail" to name)
                } else {
                    context.locale.send(sender, "moderation.jail-not-found", "jail" to name)
                }
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            delJailNode,
            "Delete a jail location",
            listOf("removejail"),
        )

        val jailNode = Commands.literal("jail")
            .requires { it.sender.hasPermission("obsidian.command.jail") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.then(
                Commands.argument("duration", StringArgumentType.word()).executes { ctx ->
                    val defaultJail = moderationService.getJailNames().firstOrNull()
                    executeJail(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "target"),
                        StringArgumentType.getString(ctx, "duration"),
                        defaultJail ?: "",
                        null,
                    )
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("jail", StringArgumentType.word()).suggests { _, b ->
                        moderationService.getJailNames().filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        executeJail(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "target"),
                            StringArgumentType.getString(ctx, "duration"),
                            StringArgumentType.getString(ctx, "jail"),
                            null,
                        )
                        Command.SINGLE_SUCCESS
                    }.then(
                        Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                            executeJail(
                                ctx.source.sender,
                                StringArgumentType.getString(ctx, "target"),
                                StringArgumentType.getString(ctx, "duration"),
                                StringArgumentType.getString(ctx, "jail"),
                                StringArgumentType.getString(ctx, "reason"),
                            )
                            Command.SINGLE_SUCCESS
                        })
                )
            )
            ).build()
        registrar.register(context.plugin.pluginMeta, jailNode, "Jail a player", emptyList())

        val unjailNode = Commands.literal("unjail")
            .requires { it.sender.hasPermission("obsidian.command.unjail") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).executes { ctx ->
                    val sender = ctx.source.sender
                    val query = StringArgumentType.getString(ctx, "target")
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = {
                            val id = moderationService.resolveTarget(query)
                            if (id != null) moderationService.unjail(id)
                            id
                        },
                        onResult = { id ->
                            context.locale.send(sender, "moderation.unjailed", "target" to (id?.name ?: query))
                        },
                    )
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, unjailNode, "Release a player from jail", emptyList())
    }

    private fun executeJail(
        sender: CommandSender,
        targetName: String,
        rawDuration: String,
        jailName: String,
        reason: String?,
    ) {
        if (jailName.isBlank() || moderationService.getJailLocation(jailName) == null) {
            context.locale.send(sender, "moderation.jail-not-found", "jail" to jailName.ifBlank { "default" })
            return
        }
        val dur = runCatching { DurationParser.parseOrPermanent(rawDuration) }
        if (dur.isFailure) {
            context.locale.send(sender, "general.invalid-number", "input" to rawDuration)
            return
        }
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val id = moderationService.resolveTarget(targetName)
                if (id?.uuid == null) null else {
                    val rec = moderationService.jail(id, sender, jailName, dur.getOrNull(), reason)
                    rec?.let { id to it }
                }
            },
            onResult = { res ->
                if (res == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    moderationService.applyJailEffects(res.first, sender, jailName, res.second)
                }
            },
        )
    }

    private fun registerCuffAndVanish(registrar: Commands) {
        val cuffNode = Commands.literal("cuff")
            .requires { it.sender.hasPermission("obsidian.command.cuff") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                    val sender = ctx.source.sender
                    val target =
                        ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                            .firstOrNull() ?: return@executes 0
                    val state = moderationService.toggleCuff(target)
                    context.locale.send(
                        sender,
                        "moderation.cuff-toggled",
                        "target" to target.name,
                        "state" to if (state) "ON" else "OFF"
                    )
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, cuffNode, "Toggle handcuffs on a player", emptyList())

        val vanishNode = Commands.literal("vanish")
            .requires { it.sender.hasPermission("obsidian.command.vanish") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val next = moderationService.toggleVanish(player)
                context.locale.send(
                    player, "moderation.vanish-toggled", "target" to player.name, "state" to if (next) "ON" else "OFF"
                )
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("target", ArgumentTypes.player())
                    .requires { it.sender.hasPermission("obsidian.command.vanish.others") || it.sender.hasPermission("obsidian.admin") }
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        val next = moderationService.toggleVanish(target)
                        context.locale.send(
                            sender,
                            "moderation.vanish-toggled",
                            "target" to target.name,
                            "state" to if (next) "ON" else "OFF"
                        )
                        Command.SINGLE_SUCCESS
                    }).build()
        registrar.register(
            context.plugin.pluginMeta,
            vanishNode,
            "Toggle vanish visibility",
            listOf("v"),
        )
    }

    private fun registerInventoryCommands(registrar: Commands) {
        val invNode = Commands.literal("inv")
            .requires { it.sender.hasPermission("obsidian.command.inv") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                    val viewer = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val target =
                        ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                            .firstOrNull() ?: return@executes 0
                    viewer.openInventory(target.inventory)
                    context.locale.send(viewer, "moderation.inv-opened", "target" to target.name)
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(
            context.plugin.pluginMeta,
            invNode,
            "Inspect a player's inventory",
            listOf("invsee"),
        )

        val invSaveNode = Commands.literal("invsave")
            .requires { it.sender.hasPermission("obsidian.command.invsave") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val snapName = StringArgumentType.getString(ctx, "name")
                    CommandHelper.dispatchAsync(
                        context,
                        player,
                        asyncWork = { moderationService.saveInventorySnapshot(player, snapName) },
                        onResult = {
                            context.locale.send(
                                player, "moderation.inv-saved", "name" to snapName, "target" to player.name
                            )
                        },
                    )
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                        val sender = ctx.source.sender
                        val snapName = StringArgumentType.getString(ctx, "name")
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        CommandHelper.dispatchAsync(
                            context,
                            sender,
                            asyncWork = { moderationService.saveInventorySnapshot(target, snapName) },
                            onResult = {
                                context.locale.send(
                                    sender, "moderation.inv-saved", "name" to snapName, "target" to target.name
                                )
                            },
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, invSaveNode, "Save player inventory snapshot", emptyList())

        val invLoadNode = Commands.literal("invload")
            .requires { it.sender.hasPermission("obsidian.command.invload") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val snapName = StringArgumentType.getString(ctx, "name")
                    CommandHelper.dispatchAsync(
                        context,
                        player,
                        asyncWork = { moderationService.loadInventorySnapshot(player, snapName) },
                        onResult = { ok ->
                            if (ok) context.locale.send(
                                player, "moderation.inv-loaded", "name" to snapName, "target" to player.name
                            )
                            else context.locale.send(player, "moderation.inv-not-found", "name" to snapName)
                        },
                    )
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                        val sender = ctx.source.sender
                        val snapName = StringArgumentType.getString(ctx, "name")
                        val target =
                            ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                                .firstOrNull() ?: return@executes 0
                        CommandHelper.dispatchAsync(
                            context,
                            sender,
                            asyncWork = { moderationService.loadInventorySnapshot(target, snapName) },
                            onResult = { ok ->
                                if (ok) context.locale.send(
                                    sender, "moderation.inv-loaded", "name" to snapName, "target" to target.name
                                )
                                else context.locale.send(sender, "moderation.inv-not-found", "name" to snapName)
                            },
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, invLoadNode, "Restore saved inventory snapshot", emptyList())
    }

    private fun registerInfoCommands(registrar: Commands) {
        val seenNode = Commands.literal("seen")
            .requires { it.sender.hasPermission("obsidian.command.seen") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val query = StringArgumentType.getString(ctx, "target")
                val online = Bukkit.getPlayerExact(query) ?: Bukkit.getPlayer(query)
                if (online != null) {
                    val profile = context.users.getCached(online)
                    val elapsed = System.currentTimeMillis() - profile.lastLogin
                    context.locale.send(
                        sender,
                        "moderation.seen-online",
                        "target" to online.name,
                        "time" to TextFormatter.formatDuration(elapsed)
                    )
                    return@executes Command.SINGLE_SUCCESS
                }
                CommandHelper.dispatchAsync(
                    context,
                    sender,
                    asyncWork = { context.users.findByName(query) },
                    onResult = { user ->
                        if (user == null) {
                            context.locale.send(sender, "general.player-not-found", "player" to query)
                        } else {
                            val refTime = if (user.lastLogout > 0L) user.lastLogout else user.lastLogin
                            val elapsed = (System.currentTimeMillis() - refTime).coerceAtLeast(0L)
                            context.locale.send(
                                sender,
                                "moderation.seen-offline",
                                "target" to user.username,
                                "time" to TextFormatter.formatDuration(elapsed)
                            )
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, seenNode, "Check when a player was last online", emptyList())

        val infoNode = Commands.literal("info")
            .requires { it.sender.hasPermission("obsidian.command.info") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val query = StringArgumentType.getString(ctx, "target")
                CommandHelper.dispatchAsync(
                    context,
                    sender,
                    asyncWork = {
                        val id = moderationService.resolveTarget(query)
                        val profile = id?.uuid?.let { context.users.getOrLoad(it, id.name) }
                        if (profile == null) {
                            null
                        } else {
                            val warnings = moderationService.getActiveWarnings(profile.uuid).size
                            val mute = moderationService.getActiveMute(profile.uuid)
                            val jail = moderationService.getActiveJail(profile.uuid)
                            moderationService.formatInfoLines(
                                profile, id.onlinePlayer, id.ip, warnings, mute, jail
                            )
                        }
                    },
                    onResult = { lines ->
                        if (lines == null) {
                            context.locale.send(sender, "general.player-not-found", "player" to query)
                        } else {
                            lines.forEach { sender.sendMessage(TextFormatter.format(it)) }
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, infoNode, "View detailed player summary", emptyList())

        val sameIpNode = Commands.literal("sameip")
            .requires { it.sender.hasPermission("obsidian.command.sameip") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val query = StringArgumentType.getString(ctx, "target")
                CommandHelper.dispatchAsync(
                    context,
                    sender,
                    asyncWork = {
                        val id = moderationService.resolveTarget(query)
                        val ip = id?.ip ?: if (ModerationService.isIpAddress(query)) query else null
                        ip to ip?.let { context.punishmentRepository.findAccountsByIp(it) }.orEmpty()
                    },
                    onResult = { (ip, matches) ->
                        if (matches.isEmpty()) {
                            context.locale.send(sender, "moderation.sameip-none", "target" to query)
                            return@dispatchAsync
                        }
                        context.locale.send(sender, "moderation.sameip-header", "target" to "$query ($ip)")
                        matches.forEach { rec ->
                            context.locale.sendRaw(
                                sender,
                                "moderation.sameip-entry",
                                "username" to rec.username,
                                "uuid" to rec.uuid,
                            )
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            sameIpNode,
            "Find accounts associated with the same IP",
            listOf("alts"),
        )
    }

    private fun registerMaintenanceAndNotes(registrar: Commands) {
        // /maintenance [on|off|reason]
        val maintenanceNode = Commands.literal("maintenance")
            .requires { it.sender.hasPermission("obsidian.command.maintenance") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val next = !maintenanceAndNoteService.maintenanceEnabled
                maintenanceAndNoteService.setMaintenance(next)
                context.locale.send(
                    ctx.source.sender,
                    "moderation.maintenance-toggled",
                    "state" to if (next) "on" else "off",
                    "reason" to maintenanceAndNoteService.maintenanceReason,
                )
                Command.SINGLE_SUCCESS
            }.then(Commands.literal("on").executes { ctx ->
                maintenanceAndNoteService.setMaintenance(true)
                context.locale.send(
                    ctx.source.sender,
                    "moderation.maintenance-toggled",
                    "state" to "on",
                    "reason" to maintenanceAndNoteService.maintenanceReason
                )
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                val reason = StringArgumentType.getString(ctx, "reason")
                maintenanceAndNoteService.setMaintenance(true, reason)
                context.locale.send(
                    ctx.source.sender, "moderation.maintenance-toggled", "state" to "on", "reason" to reason
                )
                Command.SINGLE_SUCCESS
            })).then(Commands.literal("off").executes { ctx ->
                maintenanceAndNoteService.setMaintenance(false)
                context.locale.send(
                    ctx.source.sender,
                    "moderation.maintenance-toggled",
                    "state" to "off",
                    "reason" to maintenanceAndNoteService.maintenanceReason
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, maintenanceNode, "Toggle server maintenance mode", emptyList())

        // /note <add|list|clear> <player> [text]
        val noteNode = Commands.literal("note")
            .requires { it.sender.hasPermission("obsidian.command.note") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("add").then(
                    Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                        b.buildFuture()
                    }.then(Commands.argument("text", StringArgumentType.greedyString()).executes { ctx ->
                        val sender = ctx.source.sender
                        val targetName = StringArgumentType.getString(ctx, "target")
                        val text = StringArgumentType.getString(ctx, "text")
                        val target =
                            Bukkit.getPlayerExact(targetName) ?: Bukkit.getOfflinePlayerIfCached(targetName) ?: run {
                                context.locale.send(sender, "general.player-not-found", "player" to targetName)
                                return@executes 0
                            }
                        maintenanceAndNoteService.addNote(target.uniqueId, sender.name, text)
                        context.locale.send(sender, "moderation.note-added", "target" to (target.name ?: targetName))
                        Command.SINGLE_SUCCESS
                    })
                )
            ).then(
                Commands.literal("list")
                    .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val sender = ctx.source.sender
                        val targetName = StringArgumentType.getString(ctx, "target")
                        val target =
                            Bukkit.getPlayerExact(targetName) ?: Bukkit.getOfflinePlayerIfCached(targetName) ?: run {
                                context.locale.send(sender, "general.player-not-found", "player" to targetName)
                                return@executes 0
                            }
                        val list = maintenanceAndNoteService.getNotes(target.uniqueId)
                        if (list.isEmpty()) {
                            context.locale.send(
                                sender, "moderation.note-empty", "target" to (target.name ?: targetName)
                            )
                        } else {
                            context.locale.send(
                                sender,
                                "moderation.note-header",
                                "target" to (target.name ?: targetName),
                                "count" to list.size
                            )
                            list.forEachIndexed { idx, note ->
                                context.locale.sendRaw(
                                    sender,
                                    "moderation.note-entry",
                                    "index" to (idx + 1),
                                    "author" to note.author,
                                    "date" to maintenanceAndNoteService.formatDate(note.timestamp),
                                    "text" to note.text,
                                )
                            }
                        }
                        Command.SINGLE_SUCCESS
                    })
            ).then(
                Commands.literal("clear")
                    .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val sender = ctx.source.sender
                        val targetName = StringArgumentType.getString(ctx, "target")
                        val target =
                            Bukkit.getPlayerExact(targetName) ?: Bukkit.getOfflinePlayerIfCached(targetName) ?: run {
                                context.locale.send(sender, "general.player-not-found", "player" to targetName)
                                return@executes 0
                            }
                        val count = maintenanceAndNoteService.clearNotes(target.uniqueId)
                        context.locale.send(
                            sender, "moderation.note-cleared", "target" to (target.name ?: targetName), "count" to count
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, noteNode, "Manage staff notes on players", emptyList())
    }

    private fun registerAdminInvestigation(registrar: Commands) {
        // /silentchest (/sc)
        val scNode = Commands.literal("silentchest")
            .requires { it.sender.hasPermission("obsidian.command.silentchest") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val enabled = silentChestService.toggleSilent(player)
                context.locale.send(player, "moderation.silentchest-toggled", "state" to if (enabled) "on" else "off")
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            scNode,
            "Toggle silent container inspection mode",
            listOf("sc"),
        )

        // /patrol
        val patrolNode = Commands.literal("patrol")
            .requires { it.sender.hasPermission("obsidian.command.patrol") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val target = patrolService.patrolNext(player)
                if (target == null) {
                    context.locale.send(player, "moderation.patrol-none")
                } else {
                    context.locale.send(player, "moderation.patrol-teleported", "target" to target.name)
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta, patrolNode, "Teleport to the next online player for inspection", emptyList()
        )

        // /scan <material> [minAmount]
        val scanNode = Commands.literal("scan")
            .requires { it.sender.hasPermission("obsidian.command.scan") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("material", StringArgumentType.word()).suggests { _, b ->
                    org.bukkit.Material.entries.asSequence().map { it.name.lowercase() }
                        .filter { it.startsWith(b.remaining, true) }.take(40).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val sender = ctx.source.sender
                    val matName = StringArgumentType.getString(ctx, "material")
                    val mat = org.bukkit.Material.matchMaterial(matName) ?: run {
                        context.locale.send(sender, "utility.invalid-item", "item" to matName)
                        return@executes 0
                    }
                    executeScan(sender, mat, 1)
                }.then(
                    Commands.argument("minAmount", IntegerArgumentType.integer(1)).executes { ctx ->
                        val sender = ctx.source.sender
                        val matName = StringArgumentType.getString(ctx, "material")
                        val mat = org.bukkit.Material.matchMaterial(matName) ?: run {
                            context.locale.send(sender, "utility.invalid-item", "item" to matName)
                            return@executes 0
                        }
                        val minAmount = IntegerArgumentType.getInteger(ctx, "minAmount")
                        executeScan(sender, mat, minAmount)
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            scanNode,
            "Scan players and loaded containers for materials",
            listOf("search"),
        )
    }

    private fun executeScan(
        sender: CommandSender, material: org.bukkit.Material, minAmount: Int
    ): Int {
        context.locale.send(sender, "moderation.scan-started", "material" to material.name)
        scanService.scan(material, minAmount).thenAccept { result ->
            if (result.totalCount <= 0) {
                context.locale.send(sender, "moderation.scan-none", "material" to material.name)
                return@thenAccept
            }
            context.locale.send(
                sender, "moderation.scan-header", "material" to material.name, "total" to result.totalCount
            )
            for ((pName, count) in result.playerMatches.take(10)) {
                context.locale.sendRaw(sender, "moderation.scan-player-entry", "player" to pName, "count" to count)
            }
            for (c in result.containerMatches.take(10)) {
                context.locale.sendRaw(
                    sender,
                    "moderation.scan-container-entry",
                    "world" to c.world,
                    "x" to c.x,
                    "y" to c.y,
                    "z" to c.z,
                    "count" to c.count,
                )
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun formatDate(ts: Long): String = synchronized(dateFormat) {
        dateFormat.format(Date(ts))
    }

    private fun registerAuditCommands(registrar: Commands) {
        // /banlist [page]
        val banListNode = Commands.literal("banlist")
            .requires { it.sender.hasPermission("obsidian.command.banlist") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                displayPunishmentList(ctx.source.sender, listOf(PunishmentType.BAN, PunishmentType.IP_BAN), "Bans", 1)
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                val page = IntegerArgumentType.getInteger(ctx, "page")
                displayPunishmentList(
                    ctx.source.sender, listOf(PunishmentType.BAN, PunishmentType.IP_BAN), "Bans", page
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            banListNode,
            "View active bans",
            listOf("listbans", "lbanlist"),
        )

        // /mutelist [page]
        val muteListNode = Commands.literal("mutelist")
            .requires { it.sender.hasPermission("obsidian.command.mutelist") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                displayPunishmentList(
                    ctx.source.sender,
                    listOf(PunishmentType.MUTE, PunishmentType.IP_MUTE, PunishmentType.SHADOW_MUTE),
                    "Mutes",
                    1
                )
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                val page = IntegerArgumentType.getInteger(ctx, "page")
                displayPunishmentList(
                    ctx.source.sender,
                    listOf(PunishmentType.MUTE, PunishmentType.IP_MUTE, PunishmentType.SHADOW_MUTE),
                    "Mutes",
                    page
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            muteListNode,
            "View active mutes",
            listOf("listmutes", "lmutelist"),
        )

        // /warnlist [page]
        val warnListNode = Commands.literal("warnlist")
            .requires { it.sender.hasPermission("obsidian.command.warnlist") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                displayPunishmentList(ctx.source.sender, listOf(PunishmentType.WARN), "Warnings", 1)
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                val page = IntegerArgumentType.getInteger(ctx, "page")
                displayPunishmentList(ctx.source.sender, listOf(PunishmentType.WARN), "Warnings", page)
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            warnListNode,
            "View active warnings",
            listOf("listwarns", "lwarnlist"),
        )

        // /history <target> [page]
        val historyNode = Commands.literal("history")
            .requires { it.sender.hasPermission("obsidian.command.history") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val query = StringArgumentType.getString(ctx, "target")
                    displayHistory(ctx.source.sender, query, 1)
                    Command.SINGLE_SUCCESS
                }.then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                    val query = StringArgumentType.getString(ctx, "target")
                    val page = IntegerArgumentType.getInteger(ctx, "page")
                    displayHistory(ctx.source.sender, query, page)
                    Command.SINGLE_SUCCESS
                })
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            historyNode,
            "View punishment history for a player",
            listOf("hist", "lhistory"),
        )

        // /staffhistory <staff> [page]
        val staffHistoryNode = Commands.literal("staffhistory")
            .requires { it.sender.hasPermission("obsidian.command.staffhistory") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("staff", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val staffName = StringArgumentType.getString(ctx, "staff")
                    displayStaffHistory(ctx.source.sender, staffName, 1)
                    Command.SINGLE_SUCCESS
                }.then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                    val staffName = StringArgumentType.getString(ctx, "staff")
                    val page = IntegerArgumentType.getInteger(ctx, "page")
                    displayStaffHistory(ctx.source.sender, staffName, page)
                    Command.SINGLE_SUCCESS
                })
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            staffHistoryNode,
            "View punishments issued by a staff member",
            listOf("shistory", "staffhist"),
        )

        // /staffrollback <staff> <duration>
        val staffRollbackNode = Commands.literal("staffrollback")
            .requires { it.sender.hasPermission("obsidian.command.staffrollback") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("staff", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.then(Commands.argument("duration", StringArgumentType.word()).executes { ctx ->
                    val sender = ctx.source.sender
                    val staffName = StringArgumentType.getString(ctx, "staff")
                    val durStr = StringArgumentType.getString(ctx, "duration")
                    val durMillis = DurationParser.parseMillis(durStr)
                    if (durMillis == null) {
                        context.locale.send(sender, "general.invalid-number", "input" to durStr)
                        return@executes 0
                    }
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = { moderationService.rollbackStaff(staffName, durMillis, sender) },
                        onResult = { count ->
                            context.locale.send(
                                sender, "moderation.rollback-success", "staff" to staffName, "count" to count
                            )
                        },
                    )
                    Command.SINGLE_SUCCESS
                })
            ).build()
        registrar.register(
            context.plugin.pluginMeta, staffRollbackNode, "Rollback punishments issued by a staff member", emptyList()
        )

        // /prunehistory <target> [duration]
        val pruneHistoryNode = Commands.literal("prunehistory")
            .requires { it.sender.hasPermission("obsidian.command.prunehistory") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val sender = ctx.source.sender
                    val query = StringArgumentType.getString(ctx, "target")
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = { moderationService.pruneHistory(query, null) },
                        onResult = { count ->
                            context.locale.send(sender, "moderation.prune-success", "target" to query, "count" to count)
                        },
                    )
                    Command.SINGLE_SUCCESS
                }.then(Commands.argument("duration", StringArgumentType.word()).executes { ctx ->
                    val sender = ctx.source.sender
                    val query = StringArgumentType.getString(ctx, "target")
                    val durStr = StringArgumentType.getString(ctx, "duration")
                    val durMillis = DurationParser.parseMillis(durStr)
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = { moderationService.pruneHistory(query, durMillis) },
                        onResult = { count ->
                            context.locale.send(sender, "moderation.prune-success", "target" to query, "count" to count)
                        },
                    )
                    Command.SINGLE_SUCCESS
                })
            ).build()
        registrar.register(
            context.plugin.pluginMeta, pruneHistoryNode, "Prune inactive punishments for a target", emptyList()
        )

        // /iphistory <target>
        val ipHistoryNode = Commands.literal("iphistory")
            .requires { it.sender.hasPermission("obsidian.command.iphistory") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val query = StringArgumentType.getString(ctx, "target")
                CommandHelper.dispatchAsync(
                    context,
                    sender,
                    asyncWork = {
                        val id = moderationService.resolveTarget(query)
                        val uuid = id?.uuid
                        if (uuid == null) null else id to context.punishmentRepository.findIpsByUuid(uuid)
                    },
                    onResult = { res ->
                        if (res == null || res.second.isEmpty()) {
                            context.locale.send(sender, "moderation.sameip-none", "target" to query)
                        } else {
                            val (id, ips) = res
                            context.locale.sendRaw(
                                sender,
                                "moderation.iphistory-header",
                                "player" to id.name,
                                "count" to ips.size,
                            )
                            for (ip in ips) {
                                context.locale.sendRaw(
                                    sender,
                                    "moderation.iphistory-entry",
                                    "ip" to ip.ip,
                                    "date" to formatDate(ip.lastSeen),
                                )
                            }
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, ipHistoryNode, "View IP login history for a player", emptyList())

        // /namehistory <target>
        val nameHistoryNode = Commands.literal("namehistory")
            .requires { it.sender.hasPermission("obsidian.command.namehistory") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val query = StringArgumentType.getString(ctx, "target")
                CommandHelper.dispatchAsync(
                    context,
                    sender,
                    asyncWork = {
                        val id = moderationService.resolveTarget(query)
                        val uuid = id?.uuid
                        if (uuid == null) null else id to context.punishmentRepository.findNameHistory(uuid)
                    },
                    onResult = { res ->
                        if (res == null || res.second.isEmpty()) {
                            context.locale.send(sender, "general.player-not-found", "player" to query)
                        } else {
                            val (id, names) = res
                            context.locale.sendRaw(
                                sender,
                                "moderation.namehistory-header",
                                "player" to id.name,
                                "count" to names.size,
                            )
                            for (n in names) {
                                context.locale.sendRaw(
                                    sender,
                                    "moderation.namehistory-entry",
                                    "username" to n.username,
                                    "date" to formatDate(n.changedAt),
                                )
                            }
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta, nameHistoryNode, "View username history for a player", emptyList()
        )

        // /lastuuid <target>
        val lastUuidNode = Commands.literal("lastuuid")
            .requires { it.sender.hasPermission("obsidian.command.lastuuid") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                val query = StringArgumentType.getString(ctx, "target")
                CommandHelper.dispatchAsync(
                    context,
                    sender,
                    asyncWork = {
                        val id = moderationService.resolveTarget(query)
                        id?.uuid ?: context.punishmentRepository.findLastUuid(query)
                    },
                    onResult = { uuid ->
                        if (uuid == null) {
                            context.locale.send(sender, "general.player-not-found", "player" to query)
                        } else {
                            context.locale.sendRaw(
                                sender,
                                "moderation.lastuuid-result",
                                "query" to query,
                                "uuid" to uuid
                            )
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, lastUuidNode, "View last known UUID of a username", emptyList())

        // /ipreport
        val ipReportNode = Commands.literal("ipreport")
            .requires { it.sender.hasPermission("obsidian.command.ipreport") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sender = ctx.source.sender
                val onlineByIp = Bukkit.getOnlinePlayers().groupBy { it.address?.address?.hostAddress ?: "unknown" }
                    .filter { it.key != "unknown" && it.value.size >= 2 }
                if (onlineByIp.isEmpty()) {
                    context.locale.send(sender, "moderation.ipreport-none")
                } else {
                    context.locale.sendRaw(sender, "moderation.ipreport-header")
                    for ((ip, players) in onlineByIp) {
                        val names = players.joinToString(", ") { it.name }
                        context.locale.sendRaw(
                            sender,
                            "moderation.ipreport-entry",
                            "ip" to ip,
                            "count" to players.size,
                            "players" to names,
                        )
                    }
                }
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta, ipReportNode, "Check online players sharing the same IP", emptyList()
        )

        // /allow <add|remove|check> <player>
        val allowNode = Commands.literal("allow")
            .requires { it.sender.hasPermission("obsidian.command.allow") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("add").then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val sender = ctx.source.sender
                    val query = StringArgumentType.getString(ctx, "target")
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = {
                            val id = moderationService.resolveTarget(query)
                            if (id?.uuid == null) null else {
                                context.punishmentRepository.addAllow(id.uuid, sender.name)
                                id
                            }
                        },
                        onResult = { id ->
                            if (id == null) {
                                context.locale.send(sender, "general.player-not-found", "player" to query)
                            } else {
                                context.locale.send(sender, "moderation.allow-added", "target" to id.name)
                            }
                        },
                    )
                    Command.SINGLE_SUCCESS
                })
            ).then(
                Commands.literal("remove")
                    .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val sender = ctx.source.sender
                        val query = StringArgumentType.getString(ctx, "target")
                        CommandHelper.dispatchAsync(
                            context,
                            sender,
                            asyncWork = {
                                val id = moderationService.resolveTarget(query)
                                if (id?.uuid == null) null else {
                                    context.punishmentRepository.removeAllow(id.uuid)
                                    id
                                }
                            },
                            onResult = { id ->
                                if (id == null) {
                                    context.locale.send(sender, "general.player-not-found", "player" to query)
                                } else {
                                    context.locale.send(sender, "moderation.allow-removed", "target" to id.name)
                                }
                            },
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).then(
                Commands.literal("check")
                    .then(Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val sender = ctx.source.sender
                        val query = StringArgumentType.getString(ctx, "target")
                        CommandHelper.dispatchAsync(
                            context,
                            sender,
                            asyncWork = {
                                val id = moderationService.resolveTarget(query)
                                if (id?.uuid == null) null else id to context.punishmentRepository.isAllowed(id.uuid)
                            },
                            onResult = { res ->
                                if (res == null) {
                                    context.locale.send(sender, "general.player-not-found", "player" to query)
                                } else {
                                    val status = context.locale.raw(
                                        if (res.second) "moderation.allow-allowed" else "moderation.allow-not-allowed"
                                    )
                                    context.locale.send(
                                        sender,
                                        "moderation.allow-status",
                                        "target" to res.first.name,
                                        "status" to status
                                    )
                                }
                            },
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, allowNode, "Manage punishment exception allow list", emptyList())

        // /lockdown [reason|end]
        val lockdownNode = Commands.literal("lockdown")
            .requires { it.sender.hasPermission("obsidian.command.lockdown") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val next = !maintenanceAndNoteService.maintenanceEnabled
                maintenanceAndNoteService.setMaintenance(next)
                context.locale.send(
                    ctx.source.sender,
                    "moderation.maintenance-toggled",
                    "state" to if (next) "on" else "off",
                    "reason" to maintenanceAndNoteService.maintenanceReason,
                )
                Command.SINGLE_SUCCESS
            }.then(Commands.literal("end").executes { ctx ->
                maintenanceAndNoteService.setMaintenance(false)
                context.locale.send(
                    ctx.source.sender,
                    "moderation.maintenance-toggled",
                    "state" to "off",
                    "reason" to maintenanceAndNoteService.maintenanceReason
                )
                Command.SINGLE_SUCCESS
            }).then(Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                val reason = StringArgumentType.getString(ctx, "reason")
                if (reason.equals("end", ignoreCase = true) || reason.equals("off", ignoreCase = true)) {
                    maintenanceAndNoteService.setMaintenance(false)
                    context.locale.send(
                        ctx.source.sender,
                        "moderation.maintenance-toggled",
                        "state" to "off",
                        "reason" to maintenanceAndNoteService.maintenanceReason
                    )
                } else {
                    maintenanceAndNoteService.setMaintenance(true, reason)
                    context.locale.send(
                        ctx.source.sender, "moderation.maintenance-toggled", "state" to "on", "reason" to reason
                    )
                }
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            lockdownNode,
            "Server lockdown management",
            listOf("llockdown"),
        )
    }

    private fun displayPunishmentList(
        sender: CommandSender,
        types: List<PunishmentType>,
        title: String,
        page: Int,
    ) {
        val pageSize = 5
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val total = context.punishmentRepository.countActive(types)
                val list = context.punishmentRepository.listActive(types, pageSize, offset)
                total to list
            },
            onResult = { (total, list) ->
                if (list.isEmpty() && page == 1) {
                    context.locale.sendRaw(sender, "moderation.list-empty", "title" to title)
                    return@dispatchAsync
                }
                val maxPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
                context.locale.sendRaw(
                    sender,
                    "moderation.list-header",
                    "title" to title,
                    "page" to page,
                    "maxPages" to maxPages,
                    "total" to total,
                )
                for (rec in list) {
                    val target = rec.targetName ?: rec.targetIp ?: "Unknown"
                    val expires = DurationParser.formatExpiry(rec.expiresAt, rec.createdAt)
                    val silentTag = if (rec.silent) context.locale.raw("moderation.silent-tag") else ""
                    context.locale.sendRaw(
                        sender,
                        "moderation.list-entry",
                        "silent" to silentTag,
                        "target" to target,
                        "actor" to rec.actorName,
                        "reason" to rec.reason,
                        "expires" to expires,
                    )
                }
            },
        )
    }

    private fun displayHistory(
        sender: CommandSender,
        query: String,
        page: Int,
    ) {
        val pageSize = 5
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val id = moderationService.resolveTarget(query, allowUnseenName = true)
                val ip = id?.ip ?: if (ModerationService.isIpOrWildcard(query)) query else null
                val total = context.punishmentRepository.countHistory(id?.uuid, ip)
                val list = context.punishmentRepository.findHistory(id?.uuid, ip, pageSize, offset)
                Triple(id?.name ?: query, total, list)
            },
            onResult = { (name, total, list) ->
                if (list.isEmpty() && page == 1) {
                    context.locale.sendRaw(sender, "moderation.history-empty", "target" to name)
                    return@dispatchAsync
                }
                val maxPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
                context.locale.sendRaw(
                    sender,
                    "moderation.history-header",
                    "target" to name,
                    "page" to page,
                    "maxPages" to maxPages,
                    "total" to total,
                )
                for (rec in list) {
                    val status = context.locale.raw(
                        if (rec.active) "moderation.status-active" else "moderation.status-expired"
                    )
                    val silentTag = if (rec.silent) context.locale.raw("moderation.silent-tag") else ""
                    val date = formatDate(rec.createdAt)
                    context.locale.sendRaw(
                        sender,
                        "moderation.history-entry",
                        "silent" to silentTag,
                        "type" to rec.type,
                        "status" to status,
                        "actor" to rec.actorName,
                        "reason" to rec.reason,
                        "date" to date,
                    )
                    if (rec.removedByName != null) {
                        context.locale.sendRaw(
                            sender,
                            "moderation.history-removed",
                            "actor" to rec.removedByName,
                            "reason" to (rec.removedReason ?: "-"),
                        )
                    }
                }
            },
        )
    }

    private fun displayStaffHistory(
        sender: CommandSender,
        staffName: String,
        page: Int,
    ) {
        val pageSize = 5
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val id = moderationService.resolveTarget(staffName)
                val total = context.punishmentRepository.countStaffHistory(id?.uuid, staffName)
                val list = context.punishmentRepository.findStaffHistory(id?.uuid, staffName, pageSize, offset)
                total to list
            },
            onResult = { (total, list) ->
                if (list.isEmpty() && page == 1) {
                    context.locale.sendRaw(sender, "moderation.staffhistory-empty", "staff" to staffName)
                    return@dispatchAsync
                }
                val maxPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
                context.locale.sendRaw(
                    sender,
                    "moderation.staffhistory-header",
                    "staff" to staffName,
                    "page" to page,
                    "maxPages" to maxPages,
                    "total" to total,
                )
                for (rec in list) {
                    val target = rec.targetName ?: rec.targetIp ?: "Unknown"
                    val status = context.locale.raw(
                        if (rec.active) "moderation.status-active" else "moderation.status-inactive"
                    )
                    val date = formatDate(rec.createdAt)
                    context.locale.sendRaw(
                        sender,
                        "moderation.staffhistory-entry",
                        "type" to rec.type,
                        "target" to target,
                        "status" to status,
                        "reason" to rec.reason,
                        "date" to date,
                    )
                }
            },
        )
    }
}
