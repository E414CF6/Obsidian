package obsidian.core.command

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.RemoteServerCommandEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.event.server.TabCompleteEvent
import obsidian.core.ObsidianContext
import java.io.File
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * CMI-style command alias manager (`Settings/Alias.yml` + `CustomAlias` equivalent).
 *
 * Instead of registering 180+ standalone commands directly in `plugin.yml` / Brigadier,
 * all Obsidian module commands are anchored under `/obsidian` (`/obs`), while standalone
 * commands (`/home`, `/tp`, `/bal`, etc.) and custom aliases (`aliases.yml`) are dynamically
 * registered into `CommandMap` and routed via preprocess/tab-complete handlers.
 */
class CommandAliasService(private val context: ObsidianContext) : Listener {

    private val commandAliasFile = File(context.plugin.dataFolder, "command-aliases.yml")
    private val customAliasFile = File(context.plugin.dataFolder, "aliases.yml")

    private val regularAliases = LinkedHashMap<String, CommandAliasEntry>()
    private val customAliases = LinkedHashMap<String, CommandAliasEntry>()
    private val disabledBaseSubcommands = ConcurrentHashMap.newKeySet<String>()
    private val registeredCommands = LinkedHashMap<String, ObsidianAliasCommand>()

    @Volatile
    private var combinedAliases: Map<String, CommandAliasEntry> = emptyMap()

    private val dispatchDepth = ThreadLocal.withInitial { 0 }

    private val syncCommandsMethod: Method? by lazy {
        runCatching {
            Bukkit.getServer().javaClass.getMethod("syncCommands")
        }.getOrNull()
    }

    /**
     * Called at the end of `LifecycleEvents.COMMANDS` after all module subcommands have been captured
     * into [ObsidianCommandRegistry].
     */
    fun initializeFromRegistry() {
        loadRegularAliases()
        loadCustomAliases()
        rebuildCache()
        reregisterCommandMapAliases(syncClients = false)
    }

    /**
     * Reloads both `command-aliases.yml` and `aliases.yml`, re-syncing dynamic `CommandMap`
     * registrations and online player command trees.
     */
    fun reload() {
        if (context.commandRegistry.allSubcommands().isEmpty()) {
            loadCustomAliases()
            rebuildCache()
            return
        }
        loadRegularAliases()
        loadCustomAliases()
        rebuildCache()
        reregisterCommandMapAliases(syncClients = true)
    }

    fun shutdown() {
        unregisterCommandMapAliases()
        registeredCommands.clear()
    }

    fun getRegularAliases(): Map<String, CommandAliasEntry> = synchronized(this) { regularAliases.toMap() }

    fun getCustomAliases(): Map<String, List<String>> = synchronized(this) {
        customAliases.mapValues { it.value.commands }
    }

    fun getAllAliases(): Map<String, CommandAliasEntry> = combinedAliases

    fun isBaseDisabled(subcommand: String): Boolean =
        disabledBaseSubcommands.contains(subcommand.trim().lowercase(Locale.ROOT))

    fun setCustomAlias(name: String, commands: List<String>) {
        val cleanName = name.trim().lowercase(Locale.ROOT).removePrefix("/")
        if (cleanName.isEmpty()) return
        // Prevent direct self-recursion just like CMI's AliasManager.addCustom
        if (commands.any { it.trim().removePrefix("/").equals(cleanName, ignoreCase = true) }) {
            context.plugin.logger.warning("Ignored recursive custom alias '/$cleanName'.")
            return
        }
        synchronized(this) {
            customAliases[cleanName] = CommandAliasEntry(
                alias = cleanName,
                commands = commands.toList(),
                enabled = true,
                type = CommandAliasType.CUSTOM,
                tabComplete = true,
            )
            rebuildCache()
        }
        context.scheduler.runAsync { saveCustomAliases() }
        reregisterCommandMapAliases(syncClients = true)
    }

    fun removeCustomAlias(name: String): Boolean {
        val cleanName = name.trim().lowercase(Locale.ROOT).removePrefix("/")
        val removed = synchronized(this) {
            val ex = customAliases.remove(cleanName) != null
            if (ex) rebuildCache()
            ex
        }
        if (removed) {
            context.scheduler.runAsync { saveCustomAliases() }
            reregisterCommandMapAliases(syncClients = true)
        }
        return removed
    }

    private fun loadRegularAliases() {
        val yaml = if (commandAliasFile.exists()) {
            YamlConfiguration.loadConfiguration(commandAliasFile)
        } else {
            YamlConfiguration()
        }

        yaml.options().setHeader(
            listOf(
                "Obsidian Command Alias Configuration (CMI-style)",
                "All Obsidian commands are registered under /obsidian (<subcommand>) (alias /obs).",
                "Standalone commands below (e.g. /home, /tp, /bal) are registered dynamically as aliases.",
                "Set 'enabled: false' for any alias to disable its standalone command and avoid plugin conflicts.",
                "Set 'tab: false' to disable tab completion for a standalone alias.",
                "Set 'base-disabled: true' to disable '/obsidian <subcommand>' while keeping the standalone alias.",
            )
        )

        val newRegular = LinkedHashMap<String, CommandAliasEntry>()
        val newDisabledBase = HashSet<String>()
        var modified = !commandAliasFile.exists()

        for (sub in context.commandRegistry.allSubcommands()) {
            // Primary standalone alias (e.g. /home -> /obsidian home $1-)
            val primaryPath = "aliases.${sub.name}"
            val primaryTarget = "obsidian ${sub.name} $1-"
            if (!yaml.isConfigurationSection(primaryPath)) {
                yaml.set("$primaryPath.command", primaryTarget)
                yaml.set("$primaryPath.enabled", true)
                yaml.set("$primaryPath.tab", true)
                yaml.set("$primaryPath.base-disabled", false)
                modified = true
            }
            val primaryEnabled = yaml.getBoolean("$primaryPath.enabled", true)
            val primaryTab = yaml.getBoolean("$primaryPath.tab", true)
            val primaryBaseDisabled = yaml.getBoolean("$primaryPath.base-disabled", false)
            val primaryCmd = yaml.getString("$primaryPath.command", primaryTarget) ?: primaryTarget

            if (primaryBaseDisabled) {
                newDisabledBase += sub.name
            }

            newRegular[sub.name] = CommandAliasEntry(
                alias = sub.name,
                commands = listOf(primaryCmd),
                enabled = primaryEnabled && context.config.registerStandaloneAliases,
                type = CommandAliasType.BASE,
                targetSubcommand = sub.name,
                tabComplete = primaryTab,
                baseDisabled = primaryBaseDisabled,
            )

            // Secondary aliases (e.g. /bal -> /obsidian balance $1-, /delhome -> /obsidian removehome $1-)
            for (secAlias in sub.aliases) {
                if (newRegular.containsKey(secAlias)) continue
                val secPath = "aliases.$secAlias"
                val secTarget = "obsidian ${sub.name} $1-"
                if (!yaml.isConfigurationSection(secPath)) {
                    yaml.set("$secPath.command", secTarget)
                    yaml.set("$secPath.enabled", true)
                    yaml.set("$secPath.tab", true)
                    modified = true
                }
                val secEnabled = yaml.getBoolean("$secPath.enabled", true)
                val secTab = yaml.getBoolean("$secPath.tab", true)
                val secCmd = yaml.getString("$secPath.command", secTarget) ?: secTarget

                newRegular[secAlias] = CommandAliasEntry(
                    alias = secAlias,
                    commands = listOf(secCmd),
                    enabled = secEnabled && context.config.registerStandaloneAliases,
                    type = CommandAliasType.SUBBASE,
                    targetSubcommand = sub.name,
                    tabComplete = secTab,
                )
            }
        }

        if (modified) {
            runCatching {
                commandAliasFile.parentFile?.mkdirs()
                yaml.save(commandAliasFile)
            }.onFailure { err ->
                context.plugin.logger.warning("Failed to save command-aliases.yml: ${err.message}")
            }
        }

        synchronized(this) {
            regularAliases.clear()
            regularAliases.putAll(newRegular)
        }
        disabledBaseSubcommands.clear()
        disabledBaseSubcommands.addAll(newDisabledBase)
    }

    private fun loadCustomAliases() {
        if (!customAliasFile.exists()) {
            createDefaultCustomAliases()
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(customAliasFile)
        val sec = yaml.getConfigurationSection("aliases")
        val loaded = LinkedHashMap<String, CommandAliasEntry>()
        if (sec != null) {
            for (rawKey in sec.getKeys(false)) {
                val key = rawKey.trim().lowercase(Locale.ROOT).removePrefix("/")
                if (key.isEmpty()) continue
                val entry = when {
                    sec.isConfigurationSection(rawKey) -> {
                        val sub = sec.getConfigurationSection(rawKey)!!
                        val cmds = when {
                            sub.isList("cmds") -> sub.getStringList("cmds")
                            sub.isList("Cmds") -> sub.getStringList("Cmds")
                            sub.isString("cmd") -> listOfNotNull(sub.getString("cmd"))
                            else -> emptyList()
                        }
                        if (cmds.isEmpty()) null
                        else CommandAliasEntry(
                            alias = key,
                            commands = cmds,
                            enabled = sub.getBoolean("enabled", true),
                            type = CommandAliasType.CUSTOM,
                            tabComplete = sub.getBoolean("tab", true),
                            requiresPerm = sub.getBoolean("perm", false),
                            exact = sub.getBoolean("exact-match", false),
                            addTabs = sub.getBoolean("add-tabs", false),
                            customTabs = sub.getStringList("custom-tabs"),
                        )
                    }

                    sec.isList(rawKey) -> {
                        val lines = sec.getStringList(rawKey)
                        if (lines.isEmpty()) null
                        else CommandAliasEntry(alias = key, commands = lines, type = CommandAliasType.CUSTOM)
                    }

                    sec.isString(rawKey) -> {
                        val line = sec.getString(rawKey)
                        if (line.isNullOrBlank()) null
                        else CommandAliasEntry(alias = key, commands = listOf(line), type = CommandAliasType.CUSTOM)
                    }

                    else -> null
                }
                if (entry != null) {
                    if (entry.commands.any { it.trim().removePrefix("/").equals(key, ignoreCase = true) }) {
                        context.plugin.logger.warning("Ignored recursive custom alias '/$key'.")
                    } else {
                        loaded[key] = entry
                    }
                }
            }
        }

        synchronized(this) {
            customAliases.clear()
            customAliases.putAll(loaded)
        }
    }

    private fun createDefaultCustomAliases() {
        val defaults = linkedMapOf(
            "discord" to listOf("msg: <blue><bold>Discord:</bold></blue> <aqua>https://discord.gg/example</aqua>"),
            "rules" to listOf("player: ctext rules"),
        )
        val yaml = YamlConfiguration()
        for ((k, v) in defaults) {
            yaml.set("aliases.$k", v)
        }
        runCatching {
            customAliasFile.parentFile?.mkdirs()
            yaml.save(customAliasFile)
        }
        synchronized(this) {
            customAliases.clear()
            for ((k, v) in defaults) {
                customAliases[k] = CommandAliasEntry(alias = k, commands = v, type = CommandAliasType.CUSTOM)
            }
        }
    }

    private fun saveCustomAliases() {
        val snapshot = synchronized(this) { customAliases.toMap() }
        val yaml = YamlConfiguration()
        for ((key, entry) in snapshot) {
            if (!entry.requiresPerm && !entry.exact && !entry.addTabs && entry.tabComplete && entry.customTabs.isEmpty() && entry.enabled) {
                yaml.set("aliases.$key", entry.commands)
            } else {
                yaml.set("aliases.$key.cmds", entry.commands)
                yaml.set("aliases.$key.enabled", entry.enabled)
                if (entry.requiresPerm) yaml.set("aliases.$key.perm", true)
                if (!entry.tabComplete) yaml.set("aliases.$key.tab", false)
                if (entry.exact) yaml.set("aliases.$key.exact-match", true)
                if (entry.addTabs) yaml.set("aliases.$key.add-tabs", true)
                if (entry.customTabs.isNotEmpty()) yaml.set("aliases.$key.custom-tabs", entry.customTabs)
            }
        }
        runCatching {
            customAliasFile.parentFile?.mkdirs()
            yaml.save(customAliasFile)
        }
    }

    private fun rebuildCache() {
        synchronized(this) {
            val merged = LinkedHashMap<String, CommandAliasEntry>(regularAliases.size + customAliases.size)
            merged.putAll(regularAliases)
            merged.putAll(customAliases)
            combinedAliases = merged.toMap()
        }
    }

    @Synchronized
    private fun reregisterCommandMapAliases(syncClients: Boolean) {
        val commandMap = runCatching { Bukkit.getCommandMap() }.getOrNull() ?: return
        unregisterCommandMapAliases()

        // Group enabled aliases by their first word (`baseCommand`) so each root command label
        // is registered at most once in `CommandMap`.
        val baseEntries = LinkedHashMap<String, CommandAliasEntry>()
        for (entry in combinedAliases.values) {
            if (!entry.enabled) continue
            val base = entry.baseCommand
            if (base.isEmpty() || base == "obsidian" || base == "obs") continue
            // Prefer BASE/SUBBASE metadata when a custom multi-word alias shares the same base word
            val existing = baseEntries[base]
            if (existing == null || (existing.type == CommandAliasType.CUSTOM && entry.type != CommandAliasType.CUSTOM)) {
                baseEntries[base] = entry
            }
        }

        for ((base, entry) in baseEntries) {
            val desc = entry.targetSubcommand?.let {
                context.commandRegistry.getSubcommand(it)?.description
            }.orEmpty().ifEmpty { "Obsidian command alias" }
            val proxy = ObsidianAliasCommand(base, this, desc)
            commandMap.register("obsidian", proxy)
            registeredCommands[base] = proxy
        }

        if (syncClients) {
            syncServerCommands()
        }
    }

    @Synchronized
    private fun unregisterCommandMapAliases() {
        if (registeredCommands.isEmpty()) return
        val commandMap = runCatching { Bukkit.getCommandMap() }.getOrNull() ?: return
        val known: MutableMap<String, Command> = commandMap.knownCommands

        for ((base, cmd) in registeredCommands) {
            if (known[base] === cmd) {
                known.remove(base)
            }
            val namespaced = "obsidian:$base"
            if (known[namespaced] === cmd) {
                known.remove(namespaced)
            }
            cmd.unregister(commandMap)
        }
        registeredCommands.clear()
    }

    private fun syncServerCommands() {
        runCatching {
            syncCommandsMethod?.invoke(Bukkit.getServer())
        }
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
        } else {
            context.scheduler.runGlobal {
                Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
            }
        }
    }

    fun canUseAlias(sender: CommandSender, aliasLabel: String): Boolean {
        val key = aliasLabel.trim().lowercase(Locale.ROOT)
        val entry = combinedAliases[key] ?: return true
        if (!entry.enabled) return false

        if (entry.type == CommandAliasType.CUSTOM) {
            if (!entry.requiresPerm) return true
            return sender.hasPermission("obsidian.customalias.${entry.permissionKey}") ||
                sender.hasPermission("obsidian.admin")
        }

        val subName = entry.targetSubcommand ?: return true
        val sub = context.commandRegistry.getSubcommand(subName) ?: return true
        return sub.node.canUse(CommandSourceResolver.resolve(sender))
    }

    fun executeFromCommandMap(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        val cleanLabel = commandLabel.substringAfter(':').trim().lowercase(Locale.ROOT)
        val rawCommand = if (args.isEmpty()) cleanLabel else "$cleanLabel ${args.joinToString(" ")}"
        val entry = AliasMatcher.findBestMatch(rawCommand, combinedAliases) ?: return false
        if (!entry.enabled) return false

        if (entry.type == CommandAliasType.CUSTOM && entry.requiresPerm &&
            !sender.hasPermission("obsidian.customalias.${entry.permissionKey}") &&
            !sender.hasPermission("obsidian.admin")
        ) {
            context.locale.send(sender, "general.no-permission")
            return true
        }

        val depth = dispatchDepth.get()
        if (depth >= MAX_ALIAS_DISPATCH_DEPTH) {
            context.plugin.logger.warning("Prevented recursive alias dispatch for '/$rawCommand'.")
            return false
        }

        val expanded = AliasMatcher.expandCommands(entry, rawCommand, sender.name)
        if (expanded.isEmpty()) return true

        dispatchDepth.set(depth + 1)
        return try {
            if (!AliasMatcher.isSpecializedAlias(expanded)) {
                val cmdLine = expanded.first().removePrefix("/").trim()
                if (cmdLine.isEmpty()) true else Bukkit.dispatchCommand(sender, cmdLine)
            } else {
                val remainder = rawCommand.removePrefix(entry.alias).trim()
                SpecializedCommandExecutor.executeAll(
                    context,
                    expanded,
                    sender as? Player,
                    mapOf("args" to remainder, "player" to sender.name),
                )
                true
            }
        } finally {
            dispatchDepth.set(depth)
        }
    }

    fun tabCompleteAlias(
        sender: CommandSender,
        aliasLabel: String,
        args: Array<out String>,
        location: Location?,
    ): List<String> {
        val cleanLabel = aliasLabel.substringAfter(':').trim().lowercase(Locale.ROOT)
        val argPart = args.joinToString(" ")
        val rawBuffer = if (args.isEmpty()) cleanLabel else "$cleanLabel $argPart"
        val entry = AliasMatcher.findBestMatch(rawBuffer, combinedAliases)
            ?: combinedAliases[cleanLabel]
            ?: return emptyList()

        if (!entry.enabled || !entry.tabComplete) return emptyList()

        if (entry.type == CommandAliasType.CUSTOM) {
            if (entry.customTabs.isNotEmpty()) {
                val idx = (args.size - 1).coerceAtLeast(0)
                val prefix = args.lastOrNull().orEmpty()
                val rawChoices = entry.customTabs.getOrNull(idx)
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                return rawChoices.filter { it.startsWith(prefix, ignoreCase = true) }
            }
            val clean = entry.cleanCommand.removePrefix("/").trim()
            if (!AliasMatcher.isSpecializedAlias(entry.commands) &&
                (clean.startsWith("obsidian ", ignoreCase = true) || clean.startsWith("obs ", ignoreCase = true))
            ) {
                val expandedForTab = if (args.isEmpty()) clean else "$clean $argPart"
                return suggestViaBrigadier(sender, expandedForTab, location)
            }
            return emptyList()
        }

        val subTarget = entry.targetSubcommand ?: return emptyList()
        val expanded = if (args.isEmpty()) "obsidian $subTarget " else "obsidian $subTarget $argPart"
        return suggestViaBrigadier(sender, expanded, location)
    }

    private fun suggestViaBrigadier(
        sender: CommandSender,
        expandedCommand: String,
        location: Location?,
    ): List<String> {
        val dispatcher = context.commandRegistry.localDispatcher
        val sourceStack = CommandSourceResolver.resolve(sender, location)
        val parseResults = dispatcher.parse(expandedCommand, sourceStack)
        val suggestions = runCatching {
            dispatcher.getCompletionSuggestions(parseResults).get()
        }.getOrNull() ?: return emptyList()

        val lastWordStart = expandedCommand.lastIndexOf(' ') + 1
        return suggestions.list.mapNotNull { suggestion ->
            val start = suggestion.range.start.coerceIn(0, expandedCommand.length)
            val applied = expandedCommand.substring(0, start) + suggestion.text
            if (applied.length >= lastWordStart) {
                applied.substring(lastWordStart)
            } else {
                suggestion.text
            }
        }.distinct()
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPlayerCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        val raw = normalizeCommandInput(event.message) ?: return

        // Block `/obsidian <subcommand>` when `base-disabled: true` in command-aliases.yml
        val disabledSub = extractDisabledBaseSubcommand(raw)
        if (disabledSub != null) {
            event.isCancelled = true
            context.locale.send(event.player, "general.command-disabled", "command" to disabledSub)
            return
        }

        val entry = AliasMatcher.findBestMatch(raw, combinedAliases) ?: return
        if (!entry.enabled) return

        if (entry.type == CommandAliasType.CUSTOM && entry.requiresPerm &&
            !event.player.hasPermission("obsidian.customalias.${entry.permissionKey}") &&
            !event.player.hasPermission("obsidian.admin")
        ) {
            event.isCancelled = true
            context.locale.send(event.player, "general.no-permission")
            return
        }

        val expanded = AliasMatcher.expandCommands(entry, raw, event.player.name)
        if (expanded.isEmpty()) return

        if (!AliasMatcher.isSpecializedAlias(expanded)) {
            val targetCmd = expanded.first().removePrefix("/").trim()
            if (targetCmd.isNotEmpty()) {
                event.message = "/$targetCmd"
            }
        } else {
            event.isCancelled = true
            val remainder = raw.removePrefix(entry.alias).trim()
            SpecializedCommandExecutor.executeAll(
                context,
                expanded,
                event.player,
                mapOf("args" to remainder, "player" to event.player.name),
            )
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) {
        val raw = normalizeCommandInput(event.command) ?: return
        val disabledSub = extractDisabledBaseSubcommand(raw)
        if (disabledSub != null) {
            event.isCancelled = true
            context.locale.send(event.sender, "general.command-disabled", "command" to disabledSub)
            return
        }

        val entry = AliasMatcher.findBestMatch(raw, combinedAliases) ?: return
        if (!entry.enabled) return

        val expanded = AliasMatcher.expandCommands(entry, raw, event.sender.name)
        if (expanded.isEmpty()) return

        if (!AliasMatcher.isSpecializedAlias(expanded)) {
            val targetCmd = expanded.first().removePrefix("/").trim()
            if (targetCmd.isNotEmpty()) {
                event.command = targetCmd
            }
        } else {
            event.isCancelled = true
            val remainder = raw.removePrefix(entry.alias).trim()
            SpecializedCommandExecutor.executeAll(
                context,
                expanded,
                null,
                mapOf("args" to remainder, "player" to event.sender.name),
            )
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onRemoteServerCommand(event: RemoteServerCommandEvent) {
        val raw = normalizeCommandInput(event.command) ?: return
        val disabledSub = extractDisabledBaseSubcommand(raw)
        if (disabledSub != null) {
            event.isCancelled = true
            context.locale.send(event.sender, "general.command-disabled", "command" to disabledSub)
            return
        }

        val entry = AliasMatcher.findBestMatch(raw, combinedAliases) ?: return
        if (!entry.enabled) return

        val expanded = AliasMatcher.expandCommands(entry, raw, event.sender.name)
        if (expanded.isEmpty()) return

        if (!AliasMatcher.isSpecializedAlias(expanded)) {
            val targetCmd = expanded.first().removePrefix("/").trim()
            if (targetCmd.isNotEmpty()) {
                event.command = targetCmd
            }
        } else {
            event.isCancelled = true
            val remainder = raw.removePrefix(entry.alias).trim()
            SpecializedCommandExecutor.executeAll(
                context,
                expanded,
                null,
                mapOf("args" to remainder, "player" to event.sender.name),
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTabComplete(event: TabCompleteEvent) {
        val rawBuffer = event.buffer.removePrefix("/")
        if (rawBuffer.isBlank()) return

        // Suggest multi-word custom alias segments (modeled after CMI's AliasTabCompListener)
        val parts = rawBuffer.split(' ')
        val tokenIndex = parts.size - 1
        val currentPrefix = parts.lastOrNull().orEmpty()
        val lowerPrefix = rawBuffer.lowercase(Locale.ROOT)
        val customCandidates = ArrayList<String>()
        for (entry in combinedAliases.values) {
            if (!entry.enabled || entry.type != CommandAliasType.CUSTOM || !entry.tabComplete) continue
            if (entry.requiresPerm &&
                !event.sender.hasPermission("obsidian.customalias.${entry.permissionKey}") &&
                !event.sender.hasPermission("obsidian.admin")
            ) {
                continue
            }
            if (entry.alias.lowercase(Locale.ROOT).startsWith(lowerPrefix)) {
                val aliasTokens = entry.alias.split(' ')
                if (aliasTokens.size > tokenIndex && tokenIndex > 0) {
                    val candidate = aliasTokens[tokenIndex]
                    if (candidate.startsWith(currentPrefix, ignoreCase = true)) {
                        customCandidates += candidate
                    }
                }
            }
        }
        if (customCandidates.isNotEmpty()) {
            val merged = LinkedHashSet(event.completions)
            merged.addAll(customCandidates)
            event.completions = merged.toList()
        }
    }

    private fun normalizeCommandInput(input: String): String? {
        var trimmed = input.trim()
        if (trimmed.startsWith("/:")) trimmed = trimmed.substring(2)
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1)
        trimmed = trimmed.trimStart()
        if (trimmed.isEmpty()) return null

        val firstSpace = trimmed.indexOf(' ')
        val firstToken = if (firstSpace < 0) trimmed else trimmed.substring(0, firstSpace)
        val cleanFirst = if (firstToken.startsWith("obsidian:", ignoreCase = true)) {
            firstToken.substring("obsidian:".length)
        } else {
            firstToken
        }
        return if (firstSpace < 0) cleanFirst else cleanFirst + trimmed.substring(firstSpace)
    }

    private fun extractDisabledBaseSubcommand(rawCommand: String): String? {
        val tokens = rawCommand.split(Regex("\\s+"), limit = 3)
        if (tokens.size < 2) return null
        val root = tokens[0].lowercase(Locale.ROOT)
        if (root != "obsidian" && root != "obs") return null
        val sub = tokens[1].lowercase(Locale.ROOT)
        return if (isBaseDisabled(sub)) sub else null
    }

    companion object {
        private const val MAX_ALIAS_DISPATCH_DEPTH = 4
    }
}
