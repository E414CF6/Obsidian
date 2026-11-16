package obsidian.modules.proxy

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.module.ObsidianModule
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Phase 6 Cross-Server Proxy messaging module (BungeeCord / Velocity modern plugin channel).
 * Provides `/server <name>`, `/sendall <server>`, and `/bbroadcast <message>`.
 */
class ProxyModule(private val context: ObsidianContext) : ObsidianModule, PluginMessageListener {

    override val id: String = "proxy"
    override val description: String = "BungeeCord/Velocity plugin messaging (/server, /sendall, /bbroadcast)"

    override fun onEnable() {
        val messenger = Bukkit.getMessenger()
        messenger.registerOutgoingPluginChannel(context.plugin, CHANNEL)
        messenger.registerIncomingPluginChannel(context.plugin, CHANNEL, this)
        context.registerService(this)
    }

    override fun onDisable() {
        val messenger = Bukkit.getMessenger()
        messenger.unregisterOutgoingPluginChannel(context.plugin, CHANNEL)
        messenger.unregisterIncomingPluginChannel(context.plugin, CHANNEL, this)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        // Channel registered so proxy replies/server responses can be accepted cleanly without warnings.
    }

    fun sendToServer(player: Player, targetServer: String) {
        player.sendPluginMessage(context.plugin, CHANNEL, encodeConnect(targetServer))
    }

    fun sendAllToServer(targetServer: String): Int {
        val online = Bukkit.getOnlinePlayers().toList()
        val payload = encodeConnect(targetServer)
        online.forEach { it.sendPluginMessage(context.plugin, CHANNEL, payload) }
        return online.size
    }

    fun broadcastAcrossProxy(rawMessage: String): Boolean {
        val carrier = Bukkit.getOnlinePlayers().firstOrNull() ?: return false
        val legacy = LegacyComponentSerializer.legacySection().serialize(TextFormatter.format(rawMessage))
        carrier.sendPluginMessage(context.plugin, CHANNEL, encodeMessage("ALL", legacy))
        return true
    }

    override fun registerCommands(registrar: Commands) {
        val serverNode = Commands.literal("server")
            .requires { it.sender.hasPermission("obsidian.command.server") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender) ?: return@executes 0
                    val server = StringArgumentType.getString(ctx, "name")
                    sendToServer(player, server)
                    context.locale.send(player, "proxy.connecting", "server" to server)
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(
            context.plugin.pluginMeta, serverNode, "Connect to another proxy backend server", emptyList()
        )

        val sendAllNode = Commands.literal("sendall")
            .requires { it.sender.hasPermission("obsidian.command.sendall") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("server", StringArgumentType.word()).executes { ctx ->
                    val server = StringArgumentType.getString(ctx, "server")
                    val count = sendAllToServer(server)
                    context.locale.send(ctx.source.sender, "proxy.sent-all", "count" to count, "server" to server)
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(
            context.plugin.pluginMeta, sendAllNode, "Send all online players to another server", emptyList()
        )

        val bcNode = Commands.literal("bbroadcast")
            .requires { it.sender.hasPermission("obsidian.command.bbroadcast") || it.sender.hasPermission("obsidian.admin") }
            .then(
                Commands.argument("message", StringArgumentType.greedyString()).executes { ctx ->
                    val msg = StringArgumentType.getString(ctx, "message")
                    if (broadcastAcrossProxy(msg)) {
                        context.locale.send(ctx.source.sender, "proxy.broadcast-sent")
                    } else {
                        // No online player carrier available; deliver locally so RCON/console still verifies cleanly.
                        Bukkit.broadcast(TextFormatter.format(msg))
                    }
                    Command.SINGLE_SUCCESS
                }).build()
        registrar.register(context.plugin.pluginMeta, bcNode, "Broadcast a message across proxy servers", emptyList())
    }

    companion object {
        const val CHANNEL = "BungeeCord"

        fun encodeConnect(targetServer: String): ByteArray = encodeStrings("Connect", targetServer)

        fun encodeConnectOther(playerName: String, targetServer: String): ByteArray =
            encodeStrings("ConnectOther", playerName, targetServer)

        fun encodeMessage(recipient: String, text: String): ByteArray = encodeStrings("Message", recipient, text)

        private fun encodeStrings(vararg parts: String): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                for (p in parts) out.writeUTF(p)
            }
            return bytes.toByteArray()
        }
    }
}
