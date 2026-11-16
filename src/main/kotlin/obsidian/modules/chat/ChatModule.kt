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
    val chatService = ChatService(context, filterService)
    val tabListService = TabListService(context)
    val deathMessageService = DeathMessageService(context)
    val customTextService = CustomTextService(context)

    override fun onEnable() {
        context.registerService(chatService)
        val interval = tabListService.updateIntervalSeconds
        context.scheduler.runGlobalTimer(interval, interval) {
            tabListService.updateAll()
        }
    }

    override fun onReload() {
        filterService.reload()
        chatService.reload()
        tabListService.reload()
        deathMessageService.reload()
        customTextService.reload()
        tabListService.updateAll()
    }

    override fun listeners(): List<Listener> = listOf(chatService, tabListService, deathMessageService)

    override fun registerCommands(registrar: Commands) {
        registerPrivateMessaging(registrar)
        registerNickCommands(registrar)
        registerChatControlCommands(registrar)
        registerStaffAndHelpOp(registrar)
        registerAnnouncementCommands(registrar)
        registerCustomText(registrar)
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
            if (context.config.registerStandaloneAliases) listOf("tell", "whisper", "w", "pm") else emptyList(),
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
            if (context.config.registerStandaloneAliases) listOf("r") else emptyList(),
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
            if (context.config.registerStandaloneAliases) listOf("cc") else emptyList(),
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
            if (context.config.registerStandaloneAliases) listOf("sc", "staffchat") else emptyList(),
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
            if (context.config.registerStandaloneAliases) listOf("bc") else emptyList(),
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
}
