package obsidian.modules.chat

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import java.time.Duration

/**
 * Chat formatting, filtering, private messaging, nicknames, TabList, death messages, and custom text module.
 */
class ChatModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "chat"
    override val description: String =
        "Chat formatting, anti-spam filter, messaging, nicknames, TabList, and custom text"

    val filterService = ChatFilterService(context)
    val chatBubbleService = ChatBubbleService(context)
    val chatService = ChatService(context, filterService).apply {
        chatBubbleService = this@ChatModule.chatBubbleService
    }
    val tabListService = TabListService(context)
    val deathMessageService = DeathMessageService(context)
    val customTextService = CustomTextService(context)
    val mailService = MailService(context)
    val notificationService = NotificationService(context)

    override fun onEnable() {
        context.registerService(chatService)
        context.registerService(chatBubbleService)
        context.registerService(notificationService)
        mailService.load()
        val interval = tabListService.updateIntervalSeconds
        context.scheduler.runGlobalTimer(interval, interval) {
            tabListService.updateAll()
        }
    }

    override fun onDisable() {
        chatBubbleService.shutdown()
        mailService.save()
    }

    override fun onReload() {
        filterService.reload()
        chatBubbleService.reload()
        chatService.reload()
        tabListService.reload()
        deathMessageService.reload()
        customTextService.reload()
        mailService.load()
        tabListService.updateAll()
    }

    override fun listeners(): List<Listener> =
        listOf(chatService, chatBubbleService, tabListService, deathMessageService, mailService)

    override fun registerCommands(registrar: Commands) {
        registerPrivateMessaging(registrar)
        registerMailAndIgnore(registrar)
        registerNickCommands(registrar)
        registerChatControlCommands(registrar)
        registerStaffAndHelpOp(registrar)
        registerAnnouncementCommands(registrar)
        registerCustomText(registrar)
        registerNotifications(registrar)
    }

    private fun registerPrivateMessaging(registrar: Commands) {
        val msgNode = Commands.literal("msg")
            .requires { it.sender.hasPermission("obsidian.command.msg") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", ArgumentTypes.player()).then(
                    Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                        val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                            .resolve(ctx.source).firstOrNull() ?: return@executes 0
                        val message = StringArgumentType.getString(ctx, "message")
                        chatService.sendPrivateMessage(sender, target, message)
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            msgNode,
            "Send a private message",
            listOf("tell", "whisper", "w", "pm"),
        )

        val replyNode = Commands.literal("reply")
            .requires { it.sender.hasPermission("obsidian.command.reply") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                    val sender = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val target = chatService.getReplyTarget(sender.uniqueId)
                    if (target == null || !target.isOnline) {
                        context.locale.send(sender, "chat.no-reply-target")
                        return@executes 0
                    }
                    chatService.sendPrivateMessage(sender, target, StringArgumentType.getString(ctx, "message"))
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(
            context.plugin.pluginMeta,
            replyNode,
            "Reply to last private message",
            listOf("r"),
        )

        val msgToggleNode = Commands.literal("msgtoggle")
            .requires { it.sender.hasPermission("obsidian.command.msgtoggle") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val enabled = chatService.toggleMsgReceive(player)
                context.locale.send(player, "chat.msgtoggle", "state" to if (enabled) "ON" else "OFF")
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, msgToggleNode, "Toggle receiving private messages", emptyList())

        val spyNode = Commands.literal("socialspy")
            .requires { it.sender.hasPermission("obsidian.socialspy") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val enabled = chatService.toggleSocialSpy(player)
                context.locale.send(player, "chat.socialspy-toggled", "state" to if (enabled) "ON" else "OFF")
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, spyNode, "Toggle private message monitoring", emptyList())
    }

    private fun registerNickCommands(registrar: Commands) {
        val nickNode = Commands.literal("nick")
            .requires { it.sender.hasPermission("obsidian.command.nick") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("off").executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    chatService.setNickname(player, null)
                    context.locale.send(player, "chat.nick-cleared", "target" to player.name)
                    Command.SINGLE_SUCCESS
                }).then(
                Commands.argument("nickname", StringArgumentType.greedyString()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val rawNick = StringArgumentType.getString(ctx, "nickname").trim()
                    if (rawNick.equals("reset", ignoreCase = true) || rawNick.equals("clear", ignoreCase = true)) {
                        chatService.setNickname(player, null)
                        context.locale.send(player, "chat.nick-cleared", "target" to player.name)
                    } else {
                        chatService.setNickname(player, rawNick)
                        context.locale.send(player, "chat.nick-set", "target" to player.name, "nick" to rawNick)
                    }
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, nickNode, "Change display nickname", emptyList())

        val realNameNode = Commands.literal("realname")
            .requires { it.sender.hasPermission("obsidian.command.realname") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("nickname", StringArgumentType.greedyString()).executes { ctx ->
                    val sender = ctx.source.sender
                    val query = StringArgumentType.getString(ctx, "nickname")
                    val found = chatService.findByNickname(query)
                    if (found == null) {
                        context.locale.send(sender, "chat.realname-not-found", "nick" to query)
                    } else {
                        context.locale.send(
                            sender, "chat.realname-result", "nick" to query, "realname" to found.name
                        )
                    }
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, realNameNode, "Find real player name by nickname", emptyList())
    }

    private fun registerChatControlCommands(registrar: Commands) {
        val muteChatNode = Commands.literal("mutechat")
            .requires { it.sender.hasPermission("obsidian.command.mutechat") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val actor = ctx.source.sender.name
                chatService.globalChatMuted = !chatService.globalChatMuted
                val key = if (chatService.globalChatMuted) "chat.chat-muted" else "chat.chat-unmuted"
                val comp = TextFormatter.format(context.locale.raw(key), mapOf("actor" to actor))
                Bukkit.broadcast(comp)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, muteChatNode, "Toggle global chat mute", emptyList())

        val clearChatNode = Commands.literal("clearchat")
            .requires { it.sender.hasPermission("obsidian.command.clearchat") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val blank = Component.empty()
                for (online in Bukkit.getOnlinePlayers()) {
                    if (!online.hasPermission("obsidian.admin")) {
                        repeat(80) { online.sendMessage(blank) }
                    }
                }
                val notice = TextFormatter.format(
                    context.locale.raw("chat.chat-cleared"), mapOf("actor" to ctx.source.sender.name)
                )
                Bukkit.broadcast(notice)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(
            context.plugin.pluginMeta,
            clearChatNode,
            "Clear chat history for players",
            listOf("cc"),
        )

        val slowmodeNode = Commands.literal("slowmode")
            .requires { it.sender.hasPermission("obsidian.command.slowmode") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val sec = chatService.slowmodeSeconds
                if (sec <= 0) {
                    context.locale.send(ctx.source.sender, "chat.slowmode-off")
                } else {
                    context.locale.send(ctx.source.sender, "chat.slowmode-status", "seconds" to sec)
                }
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.literal("off").executes { ctx ->
                    chatService.slowmodeSeconds = 0
                    Bukkit.broadcast(
                        TextFormatter.format(
                            context.locale.raw("chat.slowmode-disabled"),
                            mapOf("actor" to ctx.source.sender.name)
                        )
                    )
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.argument("seconds", IntegerArgumentType.integer(0, 3600)).executes { ctx ->
                    val sec = IntegerArgumentType.getInteger(ctx, "seconds")
                    chatService.slowmodeSeconds = sec
                    if (sec == 0) {
                        Bukkit.broadcast(
                            TextFormatter.format(
                                context.locale.raw("chat.slowmode-disabled"),
                                mapOf("actor" to ctx.source.sender.name)
                            )
                        )
                    } else {
                        Bukkit.broadcast(
                            TextFormatter.format(
                                context.locale.raw("chat.slowmode-enabled"),
                                mapOf("actor" to ctx.source.sender.name, "seconds" to sec.toString()),
                            )
                        )
                    }
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(context.plugin.pluginMeta, slowmodeNode, "Set global chat slowmode interval", emptyList())

        val chatColorNode = Commands.literal("chatcolor")
            .requires { it.sender.hasPermission("obsidian.command.chatcolor") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("color", StringArgumentType.word()).suggests { _, b ->
                    listOf(
                        "reset",
                        "white",
                        "gray",
                        "aqua",
                        "green",
                        "yellow",
                        "gold",
                        "red",
                        "light_purple",
                        "#ff55aa"
                    )
                        .filter { it.startsWith(b.remaining, true) }
                        .forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val applied = chatService.setChatColor(player.uniqueId, StringArgumentType.getString(ctx, "color"))
                    if (applied == null) {
                        context.locale.send(player, "chat.chatcolor-reset", "target" to player.name)
                    } else {
                        context.locale.send(player, "chat.chatcolor-set", "target" to player.name, "color" to applied)
                    }
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("target", ArgumentTypes.player())
                        .requires {
                            it.sender.hasPermission("obsidian.command.chatcolor.others") || it.sender.hasPermission(
                                "obsidian.admin"
                            )
                        }
                        .executes { ctx ->
                            val target = ctx.getArgument("target", PlayerSelectorArgumentResolver::class.java)
                                .resolve(ctx.source).firstOrNull() ?: return@executes 0
                            val applied =
                                chatService.setChatColor(target.uniqueId, StringArgumentType.getString(ctx, "color"))
                            if (applied == null) {
                                context.locale.send(ctx.source.sender, "chat.chatcolor-reset", "target" to target.name)
                            } else {
                                context.locale.send(
                                    ctx.source.sender,
                                    "chat.chatcolor-set",
                                    "target" to target.name,
                                    "color" to applied
                                )
                            }
                            Command.SINGLE_SUCCESS
                        }
                )
            ).build()
        registrar.register(context.plugin.pluginMeta, chatColorNode, "Set persistent chat color", emptyList())

        val silenceNode = Commands.literal("silence")
            .requires { it.sender.hasPermission("obsidian.command.silence") || it.sender.hasPermission("obsidian.admin") }
            .executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val silenced = chatService.toggleSilence(player)
                val state = context.locale.raw(if (silenced) "general.state-on" else "general.state-off")
                context.locale.send(player, "chat.silence-toggled", "state" to state)
                Command.SINGLE_SUCCESS
            }.build()
        registrar.register(context.plugin.pluginMeta, silenceNode, "Toggle receiving public chat messages", emptyList())

        val chatDeleteNode = Commands.literal("chatdelete")
            .requires { it.sender.hasPermission("obsidian.chat.delete") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("id", StringArgumentType.word()).executes { ctx ->
                    val id = StringArgumentType.getString(ctx, "id")
                    if (chatService.deleteRecordedMessage(id)) {
                        context.locale.send(ctx.source.sender, "chat.delete-success")
                    } else {
                        context.locale.send(ctx.source.sender, "chat.delete-not-found")
                    }
                    Command.SINGLE_SUCCESS
                }
            ).build()
        registrar.register(
            context.plugin.pluginMeta,
            chatDeleteNode,
            "Delete a signed chat message for all players",
            emptyList()
        )
    }

    private fun registerStaffAndHelpOp(registrar: Commands) {
        val staffMsgNode = Commands.literal("staffmsg")
            .requires { it.sender.hasPermission("obsidian.staffchat") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                    val sender = ctx.source.sender
                    val msg = StringArgumentType.getString(ctx, "message")
                    val comp = TextFormatter.format(
                        chatService.staffChatFormat,
                        mapOf("sender" to sender.name, "message" to msg),
                    )
                    Bukkit.getConsoleSender().sendMessage(comp)
                    Bukkit.getOnlinePlayers()
                        .filter { it.hasPermission("obsidian.staffchat") || it.hasPermission("obsidian.admin") }
                        .forEach { it.sendMessage(comp) }
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(
            context.plugin.pluginMeta,
            staffMsgNode,
            "Send a message to staff chat",
            listOf("sc", "staffchat"),
        )

        val helpOpNode = Commands.literal("helpop")
            .requires { it.sender.hasPermission("obsidian.command.helpop") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                    val sender = ctx.source.sender
                    val msg = StringArgumentType.getString(ctx, "message")
                    val comp = TextFormatter.format(
                        chatService.helpopFormat,
                        mapOf("sender" to sender.name, "message" to msg),
                    )
                    Bukkit.getConsoleSender().sendMessage(comp)
                    Bukkit.getOnlinePlayers()
                        .filter { it.hasPermission("obsidian.staffchat") || it.hasPermission("obsidian.admin") }
                        .forEach { it.sendMessage(comp) }
                    context.locale.send(sender, "chat.helpop-sent")
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, helpOpNode, "Send a help request to online staff", emptyList())
    }

    private fun registerAnnouncementCommands(registrar: Commands) {
        val bcNode = Commands.literal("broadcast")
            .requires { it.sender.hasPermission("obsidian.command.broadcast") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                    val msg = StringArgumentType.getString(ctx, "message")
                    Bukkit.broadcast(TextFormatter.format(chatService.broadcastFormat, mapOf("message" to msg)))
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(
            context.plugin.pluginMeta,
            bcNode,
            "Broadcast an announcement",
            listOf("bc"),
        )

        val titleNode = Commands.literal("titlemsg")
            .requires { it.sender.hasPermission("obsidian.command.titlemsg") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    if ("all".startsWith(b.remaining, true)) b.suggest("all")
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.then(
                    Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                        val targetArg = StringArgumentType.getString(ctx, "target")
                        val rawMsg = StringArgumentType.getString(ctx, "message")
                        val parts = rawMsg.split("\\n", limit = 2)
                        val titleComp = TextFormatter.format(parts[0])
                        val subtitleComp = parts.getOrNull(1)?.let { TextFormatter.format(it) } ?: Component.empty()
                        val titleObj = Title.title(
                            titleComp,
                            subtitleComp,
                            Title.Times.times(
                                Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(500)
                            ),
                        )
                        resolveAudiences(targetArg).forEach { it.showTitle(titleObj) }
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, titleNode, "Send a title message to a player or all", emptyList())

        val actionBarNode = Commands.literal("actionbarmsg")
            .requires { it.sender.hasPermission("obsidian.command.actionbarmsg") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                    if ("all".startsWith(b.remaining, true)) b.suggest("all")
                    CommandHelper.suggestOnlinePlayers(ctx.source.sender, b.remaining).forEach(b::suggest)
                    b.buildFuture()
                }.then(
                    Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                        val targetArg = StringArgumentType.getString(ctx, "target")
                        val comp = TextFormatter.format(StringArgumentType.getString(ctx, "message"))
                        resolveAudiences(targetArg).forEach { it.sendActionBar(comp) }
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, actionBarNode, "Send an actionbar message", emptyList())
    }

    private fun registerCustomText(registrar: Commands) {
        val ctextNode = Commands.literal("ctext")
            .requires { it.sender.hasPermission("obsidian.command.ctext") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("section", StringArgumentType.word()).suggests { _, b ->
                    customTextService.getSectionNames().filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val section = StringArgumentType.getString(ctx, "section")
                    if (!customTextService.sendSection(ctx.source.sender, section, 1)) {
                        context.locale.send(ctx.source.sender, "chat.ctext-not-found", "name" to section)
                    }
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                        val section = StringArgumentType.getString(ctx, "section")
                        val page = IntegerArgumentType.getInteger(ctx, "page")
                        if (!customTextService.sendSection(ctx.source.sender, section, page)) {
                            context.locale.send(ctx.source.sender, "chat.ctext-not-found", "name" to section)
                        }
                        Command.SINGLE_SUCCESS
                    })
            ).build()
        registrar.register(context.plugin.pluginMeta, ctextNode, "Display paginated custom text sections", emptyList())
    }

    private fun resolveAudiences(targetArg: String): Collection<Player> {
        if (targetArg.equals("all", ignoreCase = true) || targetArg == "*") {
            return Bukkit.getOnlinePlayers()
        }
        return listOfNotNull(Bukkit.getPlayerExact(targetArg) ?: Bukkit.getPlayer(targetArg))
    }

    private fun registerMailAndIgnore(registrar: Commands) {
        // /mail <read|clear|send>
        val mailNode = Commands.literal("mail")
            .requires { it.sender.hasPermission("obsidian.command.mail") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("read")
                    .requires {
                        it.sender.hasPermission("obsidian.command.mail.read") || it.sender.hasPermission("obsidian.command.mail") || it.sender.hasPermission(
                            "obsidian.admin"
                        )
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val entries = mailService.readMail(player.uniqueId)
                        if (entries.isEmpty()) {
                            context.locale.send(player, "chat.mail-empty")
                        } else {
                            context.locale.send(player, "chat.mail-header", "count" to entries.size)
                            for (entry in entries) {
                                val badge = context.locale.raw(
                                    if (entry.read) "chat.mail-badge-read" else "chat.mail-badge-new"
                                )
                                context.locale.sendRaw(
                                    player,
                                    "chat.mail-entry",
                                    "badge" to badge,
                                    "sender" to entry.sender,
                                    "time" to mailService.formatTimestamp(entry.timestamp),
                                    "message" to entry.message,
                                )
                            }
                        }
                        Command.SINGLE_SUCCESS
                    })
            .then(
                Commands.literal("clear")
                    .requires {
                        it.sender.hasPermission("obsidian.command.mail.clear") || it.sender.hasPermission("obsidian.command.mail") || it.sender.hasPermission(
                            "obsidian.admin"
                        )
                    }
                    .executes { ctx ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                        val removed = mailService.clearMail(player.uniqueId)
                        context.locale.send(player, "chat.mail-cleared", "count" to removed)
                        Command.SINGLE_SUCCESS
                    })
            .then(
                Commands.literal("send")
                    .requires {
                        it.sender.hasPermission("obsidian.command.mail.send") || it.sender.hasPermission("obsidian.command.mail") || it.sender.hasPermission(
                            "obsidian.admin"
                        )
                    }
                    .then(Commands.argument("target", StringArgumentType.word()).suggests { _, b ->
                        Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(b.remaining, true) }
                            .forEach { b.suggest(it) }
                        b.buildFuture()
                    }.then(Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                        val sender = ctx.source.sender
                        val targetName = StringArgumentType.getString(ctx, "target")
                        val message = StringArgumentType.getString(ctx, "message")
                        val target = Bukkit.getPlayerExact(targetName) ?: Bukkit.getOfflinePlayerIfCached(targetName)
                        ?: run {
                            context.locale.send(sender, "general.player-not-found", "player" to targetName)
                            return@executes 0
                        }
                        mailService.sendMail(target.uniqueId, sender.name, message)
                        context.locale.send(sender, "chat.mail-sent", "target" to (target.name ?: targetName))
                        Command.SINGLE_SUCCESS
                    }))
            )
            .build()
        registrar.register(context.plugin.pluginMeta, mailNode, "Send and read player mail", emptyList())

        // /mailall <message>
        val mailAllNode = Commands.literal("mailall")
            .requires { it.sender.hasPermission("obsidian.command.mailall") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                val message = StringArgumentType.getString(ctx, "message")
                val count = mailService.sendMailAll(ctx.source.sender.name, message)
                context.locale.send(ctx.source.sender, "chat.mailall-sent", "count" to count)
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, mailAllNode, "Send mail to all known players", emptyList())

        // /ignore [player|list]
        val ignoreNode = Commands.literal("ignore")
            .requires { it.sender.hasPermission("obsidian.command.ignore") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.literal("list").executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val ignored = chatService.getIgnored(player.uniqueId)
                if (ignored.isEmpty()) {
                    context.locale.send(player, "chat.ignore-empty")
                } else {
                    val names = ignored.map { Bukkit.getOfflinePlayer(it).name ?: it.toString() }.joinToString(", ")
                    context.locale.send(player, "chat.ignore-list", "players" to names)
                }
                Command.SINGLE_SUCCESS
            })
            .then(Commands.argument("target", StringArgumentType.word()).suggests { _, b ->
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(b.remaining, true) }
                    .forEach { b.suggest(it) }
                b.buildFuture()
            }.executes { ctx ->
                val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                val targetName = StringArgumentType.getString(ctx, "target")
                val target = Bukkit.getPlayerExact(targetName) ?: Bukkit.getOfflinePlayerIfCached(targetName)
                ?: run {
                    context.locale.send(player, "general.player-not-found", "player" to targetName)
                    return@executes 0
                }
                if (target.uniqueId == player.uniqueId) {
                    context.locale.send(player, "chat.ignore-self")
                    return@executes 0
                }
                val added = chatService.toggleIgnore(player.uniqueId, target.uniqueId)
                val key = if (added) "chat.ignore-added" else "chat.ignore-removed"
                context.locale.send(player, key, "target" to (target.name ?: targetName))
                Command.SINGLE_SUCCESS
            }).build()
        registrar.register(context.plugin.pluginMeta, ignoreNode, "Ignore or unignore a player", emptyList())
    }

    private fun registerNotifications(registrar: Commands) {
        // /toast <target|all> <message>
        val toastNode = Commands.literal("toast")
            .requires { it.sender.hasPermission("obsidian.command.toast") || it.sender.hasPermission("obsidian.admin") }
            .then(Commands.argument("target", StringArgumentType.word()).suggests { _, b ->
                (listOf("all") + Bukkit.getOnlinePlayers().map { it.name }).filter { it.startsWith(b.remaining, true) }
                    .forEach(b::suggest)
                b.buildFuture()
            }.then(Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                val targetStr = StringArgumentType.getString(ctx, "target")
                val rawMsg = StringArgumentType.getString(ctx, "message")
                val targets = resolveAudiences(targetStr)
                if (targets.isEmpty()) {
                    context.locale.send(ctx.source.sender, "general.player-not-found", "player" to targetStr)
                    return@executes 0
                }

                var icon = org.bukkit.Material.PAPER
                var frame = "task"
                val tokens = rawMsg.split(" ")
                val cleanTokens = mutableListOf<String>()
                for (t in tokens) {
                    if (t.startsWith("-icon:")) {
                        icon =
                            org.bukkit.Material.matchMaterial(t.substring("-icon:".length)) ?: org.bukkit.Material.PAPER
                    } else if (t.startsWith("-t:")) {
                        frame = t.substring("-t:".length)
                    } else {
                        cleanTokens += t
                    }
                }
                val msg = cleanTokens.joinToString(" ")
                notificationService.sendToast(targets, icon, msg, frame)
                Command.SINGLE_SUCCESS
            })).build()
        registrar.register(context.plugin.pluginMeta, toastNode, "Send advancement toast notification", emptyList())

        // /bossbarmsg <target|all> <seconds> <message>
        val bossbarNode = Commands.literal("bossbarmsg")
            .requires { it.sender.hasPermission("obsidian.command.bossbarmsg") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("target", StringArgumentType.word()).suggests { _, b ->
                    (listOf("all") + Bukkit.getOnlinePlayers().map { it.name }).filter {
                        it.startsWith(
                            b.remaining,
                            true
                        )
                    }.forEach(b::suggest)
                    b.buildFuture()
                }
                    .then(
                        Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                            .then(
                                Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                                    val targetStr = StringArgumentType.getString(ctx, "target")
                                    val seconds =
                                        IntegerArgumentType.getInteger(ctx, "seconds")
                                    val message = StringArgumentType.getString(ctx, "message")
                                    val targets = resolveAudiences(targetStr)
                                    if (targets.isEmpty()) {
                                        context.locale.send(
                                            ctx.source.sender,
                                            "general.player-not-found",
                                            "player" to targetStr
                                        )
                                        return@executes 0
                                    }
                                    notificationService.sendBossBar(targets, message, seconds)
                                    Command.SINGLE_SUCCESS
                                }
                            ))).build()
        registrar.register(context.plugin.pluginMeta, bossbarNode, "Broadcast timed bossbar message", emptyList())

        // /sound <sound> [target|all] [volume] [pitch]
        val soundNode = Commands.literal("sound")
            .requires { it.sender.hasPermission("obsidian.command.sound") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("sound", StringArgumentType.word()).suggests { _, b ->
                    org.bukkit.Registry.SOUNDS.asSequence().map { it.key.key }
                        .filter { it.startsWith(b.remaining, true) }.take(40).forEach(b::suggest)
                    b.buildFuture()
                }.executes { ctx ->
                    val sound = StringArgumentType.getString(ctx, "sound")
                    val targets = (ctx.source.sender as? Player)?.let { listOf(it) } ?: Bukkit.getOnlinePlayers()
                    notificationService.playSound(targets, sound)
                    Command.SINGLE_SUCCESS
                }.then(
                    Commands.argument("target", StringArgumentType.word()).suggests { _, b ->
                        (listOf("all") + Bukkit.getOnlinePlayers().map { it.name }).filter {
                            it.startsWith(
                                b.remaining,
                                true
                            )
                        }.forEach(b::suggest)
                        b.buildFuture()
                    }.executes { ctx ->
                        val sound = StringArgumentType.getString(ctx, "sound")
                        val targetStr = StringArgumentType.getString(ctx, "target")
                        val targets = resolveAudiences(targetStr)
                        notificationService.playSound(targets, sound)
                        Command.SINGLE_SUCCESS
                    }.then(
                        Commands.argument(
                            "volume",
                            com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 10.0)
                        ).then(
                            Commands.argument(
                                "pitch",
                                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.1, 2.0)
                            ).executes { ctx ->
                                val sound = StringArgumentType.getString(ctx, "sound")
                                val targetStr = StringArgumentType.getString(ctx, "target")
                                val vol =
                                    com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "volume").toFloat()
                                val pitch =
                                    com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "pitch").toFloat()
                                val targets = resolveAudiences(targetStr)
                                notificationService.playSound(targets, sound, vol, pitch)
                                Command.SINGLE_SUCCESS
                            }
                        )))).build()
        registrar.register(context.plugin.pluginMeta, soundNode, "Play sound to target or server", emptyList())

        // /counter <start|stop> ...
        val counterNode = Commands.literal("counter")
            .requires { it.sender.hasPermission("obsidian.command.counter") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.literal("start").then(
                    Commands.argument("seconds", IntegerArgumentType.integer(1, 3600)).then(
                        Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                            val seconds = IntegerArgumentType.getInteger(ctx, "seconds")
                            val message = StringArgumentType.getString(ctx, "message")
                            val id = notificationService.startCountdown(Bukkit.getOnlinePlayers(), seconds, message)
                            context.locale.send(
                                ctx.source.sender,
                                "chat.counter-started",
                                "id" to id,
                                "seconds" to seconds
                            )
                            Command.SINGLE_SUCCESS
                        }
                    )
                ))
            .then(
                Commands.literal("stop").then(
                    Commands.argument("id", StringArgumentType.word()).executes { ctx ->
                        val id = StringArgumentType.getString(ctx, "id")
                        if (notificationService.stopCountdown(id)) {
                            context.locale.send(ctx.source.sender, "chat.counter-stopped", "id" to id)
                            Command.SINGLE_SUCCESS
                        } else {
                            context.locale.send(ctx.source.sender, "chat.counter-not-found", "id" to id)
                            0
                        }
                    }
                ))
            .build()
        registrar.register(context.plugin.pluginMeta, counterNode, "Start or stop a countdown bar", emptyList())
    }
}
