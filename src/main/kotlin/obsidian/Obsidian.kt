package obsidian

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.command.ObsidianCommandRegistrar
import obsidian.core.gui.MenuListener
import obsidian.integration.PlaceholderApiHook
import obsidian.integration.PlaceholderService
import obsidian.modules.automation.AutomationModule
import obsidian.modules.chat.ChatModule
import obsidian.modules.economy.EconomyModule
import obsidian.modules.home.HomeModule
import obsidian.modules.interaction.InteractionModule
import obsidian.modules.kit.KitModule
import obsidian.modules.mobfarm.MobFarmModule
import obsidian.modules.moderation.ModerationModule
import obsidian.modules.playtime.PlayTimeModule
import obsidian.modules.protect.ProtectModule
import obsidian.modules.proxy.ProxyModule
import obsidian.modules.stats.StatsModule
import obsidian.modules.teleport.TeleportModule
import obsidian.modules.utility.UtilityModule
import obsidian.modules.visual.VisualModule
import obsidian.modules.warp.WarpModule
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * Obsidian plugin entrypoint bootstrapping core services and Phase 1-4 feature modules.
 */
class ObsidianPlugin : JavaPlugin() {

    private var context: ObsidianContext? = null
    private var papiHook: PlaceholderApiHook? = null

    override fun onEnable() {
        val ctx = ObsidianContext(this)
        context = ctx
        Bukkit.getServicesManager().register(ObsidianContext::class.java, ctx, this, ServicePriority.Normal)

        val placeholderService = PlaceholderService(ctx)
        ctx.registerService(placeholderService)

        // Register global Phase 0 listeners
        val pm = Bukkit.getPluginManager()
        pm.registerEvents(ctx.users, this)
        pm.registerEvents(ctx.warmups, this)
        pm.registerEvents(ctx.commandAliases, this)
        pm.registerEvents(ctx.actionBar, this)
        pm.registerEvents(ctx.bossBar, this)
        pm.registerEvents(MenuListener(), this)

        ctx.actionBar.start()
        ctx.bossBar.start()

        // Register Phase 1-4 modules in dependency order
        val teleportModule = TeleportModule(ctx)
        val homeModule = HomeModule(ctx, teleportModule)
        val warpModule = WarpModule(ctx, teleportModule)
        val utilityModule = UtilityModule(ctx)
        val economyModule = EconomyModule(ctx)
        val kitModule = KitModule(ctx)
        val playTimeModule = PlayTimeModule(ctx)
        val moderationModule = ModerationModule(ctx)
        val chatModule = ChatModule(ctx)
        val visualModule = VisualModule(ctx)
        val interactionModule = InteractionModule(ctx)
        val automationModule = AutomationModule(ctx)
        val proxyModule = ProxyModule(ctx)
        val protectModule = ProtectModule(ctx)
        val statsModule = StatsModule(ctx)
        val mobFarmModule = MobFarmModule(ctx)

        ctx.modules.register(teleportModule)
        ctx.modules.register(homeModule)
        ctx.modules.register(warpModule)
        ctx.modules.register(utilityModule)
        ctx.modules.register(economyModule)
        ctx.modules.register(kitModule)
        ctx.modules.register(playTimeModule)
        ctx.modules.register(moderationModule)
        ctx.modules.register(chatModule)
        ctx.modules.register(visualModule)
        ctx.modules.register(interactionModule)
        ctx.modules.register(automationModule)
        ctx.modules.register(proxyModule)
        ctx.modules.register(protectModule)
        ctx.modules.register(statsModule)
        ctx.modules.register(mobFarmModule)

        ctx.modules.enableAll()
        ctx.mapMarkers.registerAndSync()

        if (placeholderService.isPapiEnabled()) {
            papiHook = PlaceholderApiHook(ctx, placeholderService).also {
                it.register()
                logger.info("Registered PlaceholderAPI expansion (%obsidian_*%).")
            }
        }

        // Register only the /obsidian (/obs) root command directly; all module commands are attached
        // as /obsidian <subcommand> and exposed as standalone aliases via CommandAliasService (CMI-style).
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            ctx.commandRegistry.clear()
            val interceptingRegistrar = ObsidianCommandRegistrar(registrar, ctx.commandRegistry)
            ctx.modules.registerAllCommands(interceptingRegistrar)

            val rootNode = ctx.commandRegistry.buildRootNode(CommandHelper.buildRootCommand(ctx))
            registrar.register(
                pluginMeta,
                rootNode,
                "Obsidian core management command",
                listOf("obs"),
            )
            ctx.commandAliases.initializeFromRegistry()
        }

        ctx.startAutoSave()
        logger.info("Obsidian ${pluginMeta.version} enabled (${ctx.modules.activeModules().size} modules active).")
    }

    override fun onDisable() {
        papiHook?.unregister()
        papiHook = null
        context?.shutdown()
        Bukkit.getServicesManager().unregisterAll(this)
        context = null
    }
}
