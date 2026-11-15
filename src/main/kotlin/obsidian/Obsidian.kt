package obsidian

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import obsidian.core.ObsidianContext
import obsidian.core.command.CommandHelper
import obsidian.core.gui.MenuListener
import obsidian.integration.PlaceholderApiHook
import obsidian.integration.PlaceholderService
import obsidian.modules.automation.AutomationModule
import obsidian.modules.chat.ChatModule
import obsidian.modules.economy.EconomyModule
import obsidian.modules.home.HomeModule
import obsidian.modules.interaction.InteractionModule
import obsidian.modules.kit.KitModule
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
        pm.registerEvents(MenuListener(), this)

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

        ctx.modules.enableAll()

        if (placeholderService.isPapiEnabled()) {
            papiHook = PlaceholderApiHook(ctx, placeholderService).also {
                it.register()
                logger.info("Registered PlaceholderAPI expansion (%obsidian_*%).")
            }
        }

        // Register Brigadier commands via Paper LifecycleEvents
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            CommandHelper.registerRootCommand(registrar, ctx)
            ctx.modules.registerAllCommands(registrar)
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
