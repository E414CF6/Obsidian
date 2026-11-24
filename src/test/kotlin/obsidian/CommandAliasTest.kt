package obsidian

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.configuration.PluginMeta
import obsidian.core.command.AliasMatcher
import obsidian.core.command.CommandAliasEntry
import obsidian.core.command.CommandAliasType
import obsidian.core.command.ObsidianCommandRegistrar
import obsidian.core.command.ObsidianCommandRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class CommandAliasTest {

    @Test
    fun `AliasMatcher resolves regular, subbase, multi-word, disabled, and wildcard aliases`() {
        val aliases = linkedMapOf(
            "home" to CommandAliasEntry(
                alias = "home",
                commands = listOf("obsidian home $1-"),
                enabled = true,
                type = CommandAliasType.BASE,
                targetSubcommand = "home",
            ),
            "bal" to CommandAliasEntry(
                alias = "bal",
                commands = listOf("obsidian balance $1-"),
                enabled = true,
                type = CommandAliasType.SUBBASE,
                targetSubcommand = "balance",
            ),
            "tp" to CommandAliasEntry(
                alias = "tp",
                commands = listOf("obsidian tp $1-"),
                enabled = false,
                type = CommandAliasType.BASE,
                targetSubcommand = "tp",
            ),
            "kit" to CommandAliasEntry(
                alias = "kit",
                commands = listOf("obsidian kit $1-"),
                enabled = true,
                type = CommandAliasType.CUSTOM,
            ),
            "kit vip" to CommandAliasEntry(
                alias = "kit vip",
                commands = listOf("msg: VIP kit info"),
                enabled = true,
                type = CommandAliasType.CUSTOM,
            ),
            "ca ?" to CommandAliasEntry(
                alias = "ca ?",
                commands = listOf("msg: Help page"),
                enabled = true,
                type = CommandAliasType.CUSTOM,
            ),
            "ping" to CommandAliasEntry(
                alias = "ping",
                commands = listOf("msg: Pong"),
                enabled = true,
                exact = true,
                type = CommandAliasType.CUSTOM,
            ),
        )

        assertEquals("home", AliasMatcher.findBestMatch("home", aliases)?.alias)
        assertEquals("home", AliasMatcher.findBestMatch("home mybase", aliases)?.alias)
        assertEquals("bal", AliasMatcher.findBestMatch("bal Steve", aliases)?.alias)
        assertNull(AliasMatcher.findBestMatch("tp Steve", aliases))
        assertEquals("kit vip", AliasMatcher.findBestMatch("kit vip", aliases)?.alias)
        assertEquals("kit", AliasMatcher.findBestMatch("kit starter", aliases)?.alias)
        assertEquals("ca ?", AliasMatcher.findBestMatch("ca invalid arg", aliases)?.alias)
        assertEquals("ping", AliasMatcher.findBestMatch("ping", aliases)?.alias)
        assertNull(AliasMatcher.findBestMatch("ping extra", aliases))
    }

    @Test
    fun `AliasMatcher expands positional arguments and trims empty trailing placeholders`() {
        val baseEntry = CommandAliasEntry(
            alias = "home",
            commands = listOf("obsidian home $1-"),
            type = CommandAliasType.BASE,
            targetSubcommand = "home",
        )
        assertEquals(listOf("obsidian home"), AliasMatcher.expandCommands(baseEntry, "home", "Alex"))
        assertEquals(listOf("obsidian home base1"), AliasMatcher.expandCommands(baseEntry, "home base1", "Alex"))

        val customEntry = CommandAliasEntry(
            alias = "h",
            commands = listOf("obsidian heal $1", "msg: {player} healed $1 ($2-)"),
            type = CommandAliasType.CUSTOM,
        )
        assertEquals(
            listOf("obsidian heal Steve", "msg: Alex healed Steve (now please)"),
            AliasMatcher.expandCommands(customEntry, "h Steve now please", "Alex"),
        )
        assertFalse(AliasMatcher.isSpecializedAlias(listOf("obsidian home base1")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("msg: Hello")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("obsidian home", "obsidian heal")))
    }

    @Test
    fun `ObsidianCommandRegistrar captures subcommands under root and merges colliding info literals`() {
        val registry = ObsidianCommandRegistry()
        var delegateRegisterCalls = 0

        val fakeDelegate = Proxy.newProxyInstance(
            Commands::class.java.classLoader,
            arrayOf(Commands::class.java),
        ) { _, method, _ ->
            if (method.name.startsWith("register")) {
                delegateRegisterCalls++
                emptySet<String>()
            } else {
                null
            }
        } as Commands

        val fakeMeta = Proxy.newProxyInstance(
            PluginMeta::class.java.classLoader,
            arrayOf(PluginMeta::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "Obsidian"
                "getVersion" -> "1.0"
                else -> null
            }
        } as PluginMeta

        val intercepting = ObsidianCommandRegistrar(fakeDelegate, registry)

        val homeNode = Commands.literal("home")
            .then(Commands.argument("name", StringArgumentType.word()).executes { Command.SINGLE_SUCCESS })
            .executes { Command.SINGLE_SUCCESS }
            .build()
        intercepting.register(fakeMeta, homeNode, "Teleport to home", listOf("homes", "h"))

        val infoModuleNode = Commands.literal("info")
            .requires { it.sender.hasPermission("obsidian.command.info") }
            .then(Commands.argument("target", StringArgumentType.word()).executes { Command.SINGLE_SUCCESS })
            .build()
        intercepting.register(fakeMeta, infoModuleNode, "View player info", emptyList())

        assertEquals(0, delegateRegisterCalls, "Subcommands must not be registered directly on Paper root")
        assertEquals(2, registry.allSubcommands().size)
        assertEquals(listOf("homes", "h"), registry.getSubcommand("home")?.aliases)

        val rootBuilder = Commands.literal("obsidian")
            .then(Commands.literal("info").executes { Command.SINGLE_SUCCESS })
        val rootNode = registry.buildRootNode(rootBuilder)

        assertNotNull(rootNode.getChild("home"))
        val mergedInfo = rootNode.getChild("info")
        assertNotNull(mergedInfo)
        assertNotNull(mergedInfo.command, "Root /obsidian info handler must remain intact")
        assertNotNull(mergedInfo.getChild("target"), "Module /obsidian info <target> child must be merged")

        // Verify localDispatcher parses both `/obsidian home` and `/obsidian info <target>`
        assertNotNull(registry.localDispatcher.root.getChild("obsidian").getChild("home"))
        assertNotNull(registry.localDispatcher.root.getChild("obs").getChild("home"))
    }
}
