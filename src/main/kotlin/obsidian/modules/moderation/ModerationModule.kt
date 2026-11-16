package obsidian.modules.moderation

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.DurationParser
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.event.Listener

/**
 * Moderation, punishment, jail, vanish, IP tracking, and inventory inspection module.
 */
class ModerationModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "moderation"
    override val description: String = "Bans, mutes, warnings, jails, vanish, cuffs, and inventory snapshots"

    val moderationService = ModerationService(context)
    private val listeners = ModerationListeners(context, moderationService)

    override fun onEnable() {
        context.registerService(moderationService)
    }

    override fun onReload() {
        moderationService.reload()
    }

    override fun listeners(): List<Listener> = listOf(listeners)

    override fun registerCommands(registrar: Commands) {
        registerBanCommands(registrar)
        registerKick(registrar)
        registerMuteCommands(registrar)
        registerWarnCommands(registrar)
        registerJailCommands(registrar)
        registerCuffAndVanish(registrar)
        registerInventoryCommands(registrar)
        registerInfoCommands(registrar)
    }

    private fun registerBanCommands(registrar: Commands) {
        val banNode = Commands.literal("ban")
            .requires { it.sender.hasPermission("obsidian.command.ban") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                executeBan(
                    ctx.source.sender, StringArgumentType.getString(ctx, "target"), null, null, ipBan = false
                )
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                    executeBan(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "target"),
                        null,
                        StringArgumentType.getString(ctx, "reason"),
                        ipBan = false,
                    )
                    Command.SINGLE_SUCCESS
                })
            ).build()
        registrar.register(context.plugin.pluginMeta, banNode, "Permanently ban a player", emptyList())

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
                    executeBan(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "target"),
                        dur,
                        null,
                        ipBan = false
                    )
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                        val dur = DurationParser.parseMillis(StringArgumentType.getString(ctx, "duration"))
                        if (dur == null) {
                            context.locale.send(
                                ctx.source.sender,
                                "general.invalid-number",
                                "input" to StringArgumentType.getString(ctx, "duration")
                            )
                            return@executes 0
                        }
                        executeBan(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "target"),
                            dur,
                            StringArgumentType.getString(ctx, "reason"),
                            ipBan = false,
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
                executeBan(
                    ctx.source.sender, StringArgumentType.getString(ctx, "target"), null, null, ipBan = true
                )
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("tail", StringArgumentType.greedyString()).executes { ctx ->
                    val tail = StringArgumentType.getString(ctx, "tail").trim()
                    val firstToken = tail.substringBefore(' ')
                    val parsedDur = DurationParser.parseMillis(firstToken)
                    val reason = if (parsedDur != null) tail.substringAfter(' ', "").trim()
                        .ifEmpty { null } else tail
                    executeBan(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "target"),
                        parsedDur,
                        reason,
                        ipBan = true
                    )
                    Command.SINGLE_SUCCESS
                })
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            ipBanNode,
            "Ban a player IP address",
            if (context.config.registerStandaloneAliases) listOf("banip") else emptyList(),
        )

        val unbanNode = Commands.literal("unban")
            .requires { it.sender.hasPermission("obsidian.command.unban") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).executes { ctx ->
                    val sender = ctx.source.sender
                    val query = StringArgumentType.getString(ctx, "target")
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = { moderationService.unban(query) },
                        onResult = { removed ->
                            if (removed) {
                                context.locale.send(sender, "moderation.unbanned", "target" to query)
                            } else {
                                context.locale.send(sender, "moderation.not-banned", "target" to query)
                            }
                        },
                    )
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(
            context.plugin.pluginMeta,
            unbanNode,
            "Unban a player or IP",
            if (context.config.registerStandaloneAliases) listOf("pardon") else emptyList(),
        )

        val checkBanNode = Commands.literal("checkban")
            .requires { it.sender.hasPermission("obsidian.command.checkban") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).executes { ctx ->
                    val sender = ctx.source.sender
                    val query = StringArgumentType.getString(ctx, "target")
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = {
                            val id = moderationService.resolveTarget(query, allowUnseenName = true)
                            val ip = id?.ip ?: if (ModerationService.isIpAddress(query)) query else null
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

    private fun executeBan(
        sender: CommandSender,
        targetName: String,
        durationMillis: Long?,
        reason: String?,
        ipBan: Boolean,
    ) {
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val identity = moderationService.resolveTarget(targetName, allowUnseenName = !ipBan)
                if (identity == null || (ipBan && identity.ip == null)) {
                    null
                } else {
                    identity to moderationService.ban(identity, sender, durationMillis, reason, ipBan)
                }
            },
            onResult = { res ->
                if (res == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    moderationService.applyBanEffects(res.first, sender, res.second, ipBan)
                }
            },
        )
    }

    private fun registerKick(registrar: Commands) {
        val node = Commands.literal("kick")
            .requires { it.sender.hasPermission("obsidian.command.kick") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).executes { ctx ->
                    val sender = ctx.source.sender
                    val target =
                        ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java).resolve(ctx.source)
                            .firstOrNull() ?: return@executes 0
                    val reason = moderationService.defaultKickReason
                    target.kick(TextFormatter.format("<red>$reason</red>"))
                    context.locale.send(sender, "moderation.kicked", "target" to target.name, "reason" to reason)
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                        val sender = ctx.source.sender
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
                        val reason = StringArgumentType.getString(ctx, "reason")
                        target.kick(TextFormatter.format("<red>$reason</red>"))
                        context.locale.send(
                            sender, "moderation.kicked", "target" to target.name, "reason" to reason
                        )
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, node, "Kick an online player", emptyList())
    }

    private fun registerMuteCommands(registrar: Commands) {
        registerMuteLiteral(registrar, "mute", shadow = false)
        registerMuteLiteral(registrar, "shadowmute", shadow = true)

        val unmuteNode = Commands.literal("unmute")
            .requires { it.sender.hasPermission("obsidian.command.unmute") || it.sender.hasPermission("obsidian.admin") }
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
                        if (id == null) null else id to moderationService.unmute(id)
                    },
                    onResult = { res ->
                        if (res != null && res.second) {
                            context.locale.send(sender, "moderation.unmuted", "target" to res.first.name)
                        } else {
                            context.locale.send(sender, "moderation.not-muted", "target" to query)
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, unmuteNode, "Unmute a player", emptyList())
    }

    private fun registerMuteLiteral(registrar: Commands, literal: String, shadow: Boolean) {
        val node = Commands.literal(literal)
            .requires { it.sender.hasPermission("obsidian.command.$literal") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                b.buildFuture()
            }.executes { ctx ->
                executeMute(ctx.source.sender, StringArgumentType.getString(ctx, "target"), null, shadow)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("tail", StringArgumentType.greedyString()).executes { ctx ->
                    executeMute(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "target"),
                        StringArgumentType.getString(ctx, "tail"),
                        shadow,
                    )
                    Command.SINGLE_SUCCESS
                })
            ).build()
        registrar.register(
            context.plugin.pluginMeta, node, if (shadow) "Shadow mute a player" else "Mute a player", emptyList()
        )
    }

    private fun executeMute(sender: CommandSender, targetName: String, tail: String?, shadow: Boolean) {
        val parsedTail = tail?.trim().orEmpty()
        var dur: Long? = null
        var reason: String? = null
        if (parsedTail.isNotEmpty()) {
            val first = parsedTail.substringBefore(' ')
            val maybeDur = runCatching { DurationParser.parseOrPermanent(first) }
            if (maybeDur.isSuccess) {
                dur = maybeDur.getOrNull()
                reason = parsedTail.substringAfter(' ', "").trim().ifEmpty { null }
            } else {
                reason = parsedTail
            }
        }
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val id = moderationService.resolveTarget(targetName)
                if (id == null || id.uuid == null) null
                else id to moderationService.mute(id, sender, dur, reason, shadow)
            },
            onResult = { res ->
                if (res == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    moderationService.applyMuteEffects(res.first, sender, res.second, shadow)
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
                executeWarn(ctx.source.sender, StringArgumentType.getString(ctx, "target"), null)
                Command.SINGLE_SUCCESS
            }.then(
                Commands.argument("reason", StringArgumentType.greedyString()).executes { ctx ->
                    executeWarn(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "target"),
                        StringArgumentType.getString(ctx, "reason"),
                    )
                    Command.SINGLE_SUCCESS
                })
            ).build()
        registrar.register(context.plugin.pluginMeta, warnNode, "Warn a player", emptyList())

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

    private fun executeWarn(sender: CommandSender, targetName: String, reason: String?) {
        CommandHelper.dispatchAsync(
            context,
            sender,
            asyncWork = {
                val id = moderationService.resolveTarget(targetName)
                if (id?.uuid == null) null else id to moderationService.warn(id, sender, reason)
            },
            onResult = { res ->
                if (res == null) {
                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                } else {
                    moderationService.applyWarnEffects(res.first, sender, res.second.first, res.second.second)
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
                    sender.sendMessage(TextFormatter.format("<gray>${i + 1}.</gray> <white>${w.reason}</white> <dark_gray>(by ${w.actorName})</dark_gray>"))
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
            if (context.config.registerStandaloneAliases) listOf("removejail") else emptyList(),
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
                moderationService.getJailNames().filter { it.startsWith(b.remaining, true) }
                    .forEach(b::suggest)
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
            }
            .then(
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
            if (context.config.registerStandaloneAliases) listOf("v") else emptyList(),
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
            if (context.config.registerStandaloneAliases) listOf("invsee") else emptyList(),
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
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
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
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
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
                            sender.sendMessage(TextFormatter.format("<gray>-</gray> <white>${rec.username}</white> <dark_gray>(${rec.uuid})</dark_gray>"))
                        }
                    },
                )
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(
            context.plugin.pluginMeta,
            sameIpNode,
            "Find accounts associated with the same IP",
            if (context.config.registerStandaloneAliases) listOf("alts") else emptyList(),
        )
    }
}
