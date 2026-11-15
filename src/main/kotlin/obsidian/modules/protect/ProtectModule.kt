package obsidian.modules.protect

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.ServicePriority
import java.util.*

/**
 * CoreProtect-inspired audit logging, inspection, lookup, rollback/restore, and purge module (`protect`).
 */
class ProtectModule(private val context: ObsidianContext) : ObsidianModule {

    override val id: String = "protect"
    override val description: String =
        "Block, container, entity & chat logging, inspection, lookup, rollback/restore, and purge"

    val repository = ProtectRepository(context.database)
    val queue = ProtectQueueService(context, repository)
    val service = ProtectService(context, repository, queue)
    val engine = ProtectRollbackEngine(context, service)
    private val listener = ProtectLoggingListener(context, service, engine)

    private val subcommands = listOf(
        "help", "inspect", "i", "lookup", "l", "page", "near",
        "rollback", "rb", "restore", "rs", "apply", "cancel", "undo",
        "purge", "status", "consumer", "tp", "teleport", "reload",
    )

    override fun onEnable() {
        context.registerService(service)
        Bukkit.getServicesManager().register(
            ProtectService::class.java,
            service,
            context.plugin,
            ServicePriority.Normal,
        )
        queue.start(service.config.consumerFlushMillis)
    }

    override fun onReload() {
        service.reloadConfig()
    }

    override fun onDisable() {
        service.shutdown()
    }

    override fun listeners(): List<Listener> = listOf(listener)

    override fun registerCommands(registrar: Commands) {
        val builder: (String) -> LiteralArgumentBuilder<CommandSourceStack> = { name ->
            Commands.literal(name)
                .requires {
                    it.sender.hasPermission("obsidian.protect.co") || it.sender.hasPermission("obsidian.admin")
                }
                .executes { ctx ->
                    sendHelp(ctx.source.sender, emptyList())
                    Command.SINGLE_SUCCESS
                }
                .then(
                    Commands.argument("args", StringArgumentType.greedyString())
                        .suggests { ctx, sb ->
                            val input = sb.remainingLowerCase
                            val parts = input.split(" ")
                            if (parts.size <= 1) {
                                subcommands.filter { it.startsWith(parts.firstOrNull().orEmpty()) }
                                    .forEach(sb::suggest)
                            } else {
                                suggestFilterTokens(ctx.source.sender, parts.last())
                                    .forEach { suggestion ->
                                        val prefix = input.substringBeforeLast(' ', "")
                                        sb.suggest(if (prefix.isEmpty()) suggestion else "$prefix $suggestion")
                                    }
                            }
                            sb.buildFuture()
                        }
                        .executes { ctx ->
                            val raw = StringArgumentType.getString(ctx, "args")
                            dispatchCommand(
                                ctx.source.sender,
                                raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
                            Command.SINGLE_SUCCESS
                        }
                )
        }

        CommandHelper.registerWithOptionalAliases(
            registrar = registrar,
            context = context,
            nodeBuilder = builder,
            primaryName = "co",
            description = "Obsidian Protect lookup, inspect, rollback, restore, and purge",
            aliases = listOf("core", "coreprotect"),
        )
    }

    fun dispatchCommand(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sendHelp(sender, emptyList())
            return
        }
        val sub = args[0].lowercase(Locale.ROOT)
        val tail = args.drop(1)

        when (sub) {
            "help", "?" -> sendHelp(sender, tail)

            "inspect", "i", "inspector" -> {
                if (!checkPerm(sender, "obsidian.protect.inspect")) return
                val player = CommandHelper.requirePlayer(context, sender) ?: return
                val modeArg = tail.firstOrNull()?.lowercase(Locale.ROOT)
                val targetState = when (modeArg) {
                    "on", "enable", "true" -> true
                    "off", "disable", "false" -> false
                    else -> !service.isInspecting(player)
                }
                service.setInspecting(player, targetState)
                context.locale.send(
                    player,
                    if (targetState) "protect.inspector-on" else "protect.inspector-off",
                )
            }

            "lookup", "l" -> {
                if (!checkPerm(sender, "obsidian.protect.lookup")) return
                if (tail.size == 1 && tail[0].toIntOrNull() != null) {
                    engine.showPage(sender, tail[0].toInt())
                    return
                }
                val parsed = parseForSender(sender, tail, defaultRadius = service.config.defaultRadius) ?: return
                engine.performLookup(sender, parsed)
            }

            "page" -> {
                if (!checkPerm(sender, "obsidian.protect.lookup")) return
                val page = tail.firstOrNull()?.toIntOrNull()
                if (page == null || page < 1) {
                    sender.sendMessage(TextFormatter.format("<red>사용법: /co page <번호></red>"))
                    return
                }
                engine.showPage(sender, page)
            }

            "near" -> {
                if (!checkPerm(sender, "obsidian.protect.lookup")) return
                val radius = tail.firstOrNull()?.toIntOrNull()
                    ?.coerceIn(1, service.config.maxRadius)
                    ?: 5
                val parsed = parseForSender(sender, listOf("r:$radius"), defaultRadius = radius) ?: return
                engine.performLookup(sender, parsed)
            }

            "rollback", "rb", "ro" -> {
                if (!checkPerm(sender, "obsidian.protect.rollback")) return
                if (tail.isEmpty()) {
                    sender.sendMessage(TextFormatter.format("<red>사용법: /co rollback u:<대상> t:<시간> [r:<반경>] [a:<행동>] [#preview]</red>"))
                    return
                }
                val parsed = parseForSender(sender, tail, defaultRadius = service.config.defaultRadius) ?: return
                if (parsed.sinceMillis == null && parsed.untilMillis == null && parsed.users.isEmpty() && !parsed.useSelection) {
                    context.locale.send(sender, "protect.rollback-missing-filter")
                    return
                }
                engine.performRollbackOrRestore(sender, parsed, restoreMode = false)
            }

            "restore", "rs", "re" -> {
                if (!checkPerm(sender, "obsidian.protect.restore")) return
                if (tail.isEmpty()) {
                    sender.sendMessage(TextFormatter.format("<red>사용법: /co restore u:<대상> t:<시간> [r:<반경>] [a:<행동>] [#preview]</red>"))
                    return
                }
                val parsed = parseForSender(sender, tail, defaultRadius = service.config.defaultRadius) ?: return
                if (parsed.sinceMillis == null && parsed.untilMillis == null && parsed.users.isEmpty() && !parsed.useSelection) {
                    context.locale.send(sender, "protect.rollback-missing-filter")
                    return
                }
                engine.performRollbackOrRestore(sender, parsed, restoreMode = true)
            }

            "apply" -> {
                if (!checkPerm(sender, "obsidian.protect.rollback")) return
                val player = CommandHelper.requirePlayer(context, sender) ?: return
                engine.applyPreviewSession(player)
            }

            "cancel" -> {
                if (!checkPerm(sender, "obsidian.protect.rollback")) return
                val player = CommandHelper.requirePlayer(context, sender) ?: return
                engine.cancelPreview(player)
            }

            "undo" -> {
                if (!checkPerm(sender, "obsidian.protect.rollback")) return
                engine.performUndo(sender)
            }

            "purge" -> {
                if (!checkPerm(sender, "obsidian.protect.purge")) return
                val parsed = parseForSender(sender, tail, defaultRadius = null) ?: return
                val cutoff = parsed.sinceMillis
                if (cutoff == null) {
                    sender.sendMessage(TextFormatter.format("<red>사용법: /co purge t:<보존기간> [r:<반경|월드>] (예: /co purge t:30d)</red>"))
                    return
                }
                val vacuum = tail.any { it.equals("#optimize", true) || it.equals("#vacuum", true) }
                CommandHelper.dispatchAsync(context, sender, {
                    queue.flushSync()
                    repository.purgeBefore(
                        cutoffMillis = cutoff,
                        world = parsed.world,
                        centerX = parsed.centerX,
                        centerY = parsed.centerY,
                        centerZ = parsed.centerZ,
                        radius = parsed.radius,
                        vacuumSqlite = vacuum,
                    )
                }) { res ->
                    context.locale.send(
                        sender,
                        "protect.purge-done",
                        "total" to res.total,
                        "blocks" to res.deletedBlocks,
                        "containers" to res.deletedContainers,
                        "entities" to res.deletedEntities,
                        "messages" to res.deletedMessages,
                    )
                }
            }

            "status", "stats", "version" -> {
                if (!checkPerm(sender, "obsidian.protect.status")) return
                CommandHelper.dispatchAsync(context, sender, {
                    repository.tableCounts()
                }) { counts ->
                    val state = if (queue.paused.get()) "PAUSED" else "ACTIVE"
                    context.locale.send(
                        sender,
                        "protect.status",
                        "state" to state,
                        "pending" to queue.pendingCount(),
                        "flushed" to queue.totalFlushed(),
                        "blocks" to counts.blocks,
                        "containers" to counts.containers,
                        "entities" to counts.entities,
                        "messages" to counts.messages,
                    )
                }
            }

            "consumer" -> {
                if (!checkPerm(sender, "obsidian.protect.consumer")) return
                when (tail.firstOrNull()?.lowercase(Locale.ROOT) ?: "status") {
                    "pause" -> {
                        queue.paused.set(true)
                        context.locale.send(sender, "protect.consumer-state", "state" to "PAUSED")
                    }

                    "resume", "unpause" -> {
                        queue.paused.set(false)
                        context.locale.send(sender, "protect.consumer-state", "state" to "ACTIVE")
                    }

                    "flush" -> {
                        CommandHelper.dispatchAsync(context, sender, {
                            queue.flushSync()
                        }) { flushed ->
                            context.locale.send(sender, "protect.consumer-flushed", "count" to flushed)
                        }
                    }

                    else -> {
                        val state = if (queue.paused.get()) "PAUSED" else "ACTIVE"
                        sender.sendMessage(
                            context.locale.prefix().append(
                                TextFormatter.format("<gray>Consumer: <aqua>$state</aqua> | Pending: <white>${queue.pendingBreakdown()}</white> | Total Flushed: <white>${queue.totalFlushed()}</white></gray>")
                            )
                        )
                    }
                }
            }

            "tp", "teleport" -> {
                if (!checkPerm(sender, "obsidian.protect.teleport")) return
                val player = CommandHelper.requirePlayer(context, sender) ?: return
                if (tail.size < 4) {
                    sender.sendMessage(TextFormatter.format("<red>사용법: /co tp <월드> <x> <y> <z></red>"))
                    return
                }
                val world = Bukkit.getWorld(tail[0])
                val x = tail[1].toDoubleOrNull()
                val y = tail[2].toDoubleOrNull()
                val z = tail[3].toDoubleOrNull()
                if (world == null || x == null || y == null || z == null) {
                    sender.sendMessage(TextFormatter.format("<red>올바르지 않은 월드 또는 좌표입니다.</red>"))
                    return
                }
                player.teleportAsync(Location(world, x + 0.5, y, z + 0.5))
                context.locale.send(
                    player,
                    "protect.teleported",
                    "world" to world.name,
                    "x" to x.toInt(),
                    "y" to y.toInt(),
                    "z" to z.toInt()
                )
            }

            "reload" -> {
                if (!checkPerm(sender, "obsidian.protect.reload")) return
                context.reloadAll()
                context.locale.send(sender, "general.reloaded")
            }

            else -> {
                sender.sendMessage(
                    context.locale.prefix().append(
                        TextFormatter.format("<red>알 수 없는 /co 하위 명령어: <white>$sub</white>. <gray>/co help</gray> 를 확인하세요.</red>")
                    )
                )
            }
        }
    }

    private fun parseForSender(sender: CommandSender, args: List<String>, defaultRadius: Int?): ProtectQuery? {
        val player = sender as? Player
        val loc = player?.location
        val parsed = ProtectCommandParser.parseFilterArgs(
            tokens = args,
            defaultWorld = loc?.world?.name,
            centerX = loc?.blockX,
            centerY = loc?.blockY,
            centerZ = loc?.blockZ,
            defaultRadius = defaultRadius,
            maxRadius = service.config.maxRadius,
        ).getOrElse { err ->
            sender.sendMessage(
                context.locale.prefix()
                    .append(TextFormatter.format("<red>${TextFormatter.escape(err.message ?: "Invalid arguments")}</red>"))
            )
            return null
        }

        return engine.attachSelectionIfRequested(sender, parsed).getOrElse { err ->
            sender.sendMessage(
                context.locale.prefix()
                    .append(TextFormatter.format("<red>${TextFormatter.escape(err.message ?: "Selection error")}</red>"))
            )
            return null
        }
    }

    private fun checkPerm(sender: CommandSender, permission: String): Boolean =
        CommandHelper.hasPermission(context, sender, permission)

    private fun suggestFilterTokens(sender: CommandSender, currentToken: String): List<String> {
        val lower = currentToken.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("u:") -> {
                val typed = currentToken.substring(2)
                CommandHelper.suggestOnlinePlayers(sender, typed).map { "u:$it" }
            }

            lower.startsWith("t:") -> listOf("t:5m", "t:30m", "t:1h", "t:6h", "t:1d", "t:7d", "t:30d")
                .filter { it.startsWith(lower) }

            lower.startsWith("r:") -> listOf("r:5", "r:10", "r:25", "r:50", "r:#global", "r:#selection")
                .filter { it.startsWith(lower) }

            lower.startsWith("a:") -> listOf(
                "a:block", "a:+block", "a:-block", "a:container", "a:+container", "a:-container",
                "a:click", "a:kill", "a:spawn", "a:chat", "a:command", "a:session", "a:sign",
            ).filter { it.startsWith(lower) }

            else -> listOf("u:", "t:", "r:", "a:", "i:", "e:", "f:", "#preview", "#count", "#global", "#selection")
                .filter { it.startsWith(lower) }
        }
    }

    private fun sendHelp(sender: CommandSender, args: List<String>) {
        val topic = args.firstOrNull()?.lowercase(Locale.ROOT)
        sender.sendMessage(TextFormatter.format("<dark_gray>-----</dark_gray> <gradient:#7c3aed:#06b6d4><bold>Obsidian Protect Help</bold></gradient> <dark_gray>-----</dark_gray>"))
        if (topic == "params") {
            listOf(
                "<aqua>u:<유저></aqua> <gray>- 대상 플레이어/액터 필터 (#tnt, #creeper 등)</gray>",
                "<aqua>t:<시간></aqua> <gray>- 기간 필터 (예: 30m, 2h, 1d12h, 1d-2h)</gray>",
                "<aqua>r:<반경></aqua> <gray>- 반경/월드 필터 (숫자, 월드명, #global, #selection)</gray>",
                "<aqua>a:<행동></aqua> <gray>- block, +block, -block, container, click, kill, spawn, chat, command, session, sign</gray>",
                "<aqua>i:<재질> / e:<제외재질></aqua> <gray>- 포함/제외 블록·아이템 필터</gray>",
                "<aqua>f:<키워드></aqua> <gray>- 채팅/명령/표지판 문자열 검색</gray>",
                "<aqua>#preview / #count</aqua> <gray>- 미리보기 또는 건수만 조회</gray>",
            ).forEach { sender.sendMessage(TextFormatter.format(it)) }
            return
        }
        listOf(
            "<aqua>/co inspect</aqua> <gray>(/co i) - 블록·상자 조사 모드 토글</gray>",
            "<aqua>/co lookup <조건></aqua> <gray>(/co l) - 변경 이력 조회</gray>",
            "<aqua>/co near [반경]</aqua> <gray>- 주변 이력 빠른 조회</gray>",
            "<aqua>/co page <번호></aqua> <gray>- 조회 결과 페이지 이동</gray>",
            "<aqua>/co rollback <조건></aqua> <gray>(/co rb) - 지정 시점 상태로 되돌리기</gray>",
            "<aqua>/co restore <조건></aqua> <gray>(/co rs) - 롤백된 영역 다시 복구</gray>",
            "<aqua>/co apply / cancel / undo</aqua> <gray>- #preview 적용/취소 또는 직전 작업 되돌리기</gray>",
            "<aqua>/co purge t:<기간></aqua> <gray>- 오래된 기록 정리</gray>",
            "<aqua>/co status / consumer</aqua> <gray>- DB 통계 및 비동기 큐 제어</gray>",
            "<gray>매개변수 상세: <aqua>/co help params</aqua></gray>",
        ).forEach { sender.sendMessage(TextFormatter.format(it)) }
    }
}
