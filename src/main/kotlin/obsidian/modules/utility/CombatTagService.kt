package obsidian.modules.utility

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * CMI-inspired PvP/PvE combat tagging, anti-combat-log enforcement, and player/mob head drops
 * (`PlayerCombatManager` / `HeadDropListener` equivalent).
 */
class CombatTagService(private val context: ObsidianContext) : Listener {

    private val combatExpiry = ConcurrentHashMap<UUID, Long>()
    private var tickTask: ScheduledTask? = null

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var pvpTagSeconds: Int = 15
        private set

    @Volatile
    var pveTagSeconds: Int = 0
        private set

    @Volatile
    var killOnLogout: Boolean = true
        private set

    @Volatile
    var disableFlyInCombat: Boolean = true
        private set

    @Volatile
    var blockedCommands: Set<String> = emptySet()
        private set

    @Volatile
    var playerHeadDropChance: Double = 100.0
        private set

    @Volatile
    var mobHeadDropChance: Double = 0.0
        private set

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        enabled = raw.getBoolean("combat.enabled", true)
        pvpTagSeconds = raw.getInt("combat.pvp-tag-seconds", 15).coerceAtLeast(0)
        pveTagSeconds = raw.getInt("combat.pve-tag-seconds", 0).coerceAtLeast(0)
        killOnLogout = raw.getBoolean("combat.kill-on-logout", true)
        disableFlyInCombat = raw.getBoolean("combat.disable-fly", true)
        blockedCommands = raw.getStringList("combat.blocked-commands")
            .map { it.trim().lowercase(Locale.ROOT).removePrefix("/") }
            .filter { it.isNotEmpty() }
            .toSet()
        playerHeadDropChance = raw.getDouble("combat.head-drops.player-chance", 100.0).coerceIn(0.0, 100.0)
        mobHeadDropChance = raw.getDouble("combat.head-drops.mob-chance", 0.0).coerceIn(0.0, 100.0)
    }

    fun start() {
        tickTask = context.scheduler.runAsyncTimer(1L, 1L) { tickActionBars() }
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
        combatExpiry.clear()
    }

    fun isInCombat(uuid: UUID): Boolean {
        val exp = combatExpiry[uuid] ?: return false
        if (System.currentTimeMillis() >= exp) {
            combatExpiry.remove(uuid)
            return false
        }
        return true
    }

    fun getRemainingSeconds(uuid: UUID): Int {
        val exp = combatExpiry[uuid] ?: return 0
        val rem = exp - System.currentTimeMillis()
        if (rem <= 0L) {
            combatExpiry.remove(uuid)
            return 0
        }
        return ((rem + 999L) / 1000L).toInt()
    }

    fun tagPlayer(player: Player, durationSeconds: Int) {
        if (durationSeconds <= 0) return
        if (player.hasPermission("obsidian.combat.bypass") || player.hasPermission("obsidian.admin")) return
        val wasInCombat = isInCombat(player.uniqueId)
        combatExpiry[player.uniqueId] = System.currentTimeMillis() + durationSeconds * 1000L
        if (!wasInCombat) {
            context.locale.send(player, "utility.combat-entered", "seconds" to durationSeconds)
        }
        if (disableFlyInCombat && (player.allowFlight || player.isFlying)) {
            context.scheduler.runForEntity(player) {
                if (player.isOnline) {
                    player.isFlying = false
                    player.allowFlight = false
                }
            }
        }
    }

    fun clearCombat(uuid: UUID) {
        combatExpiry.remove(uuid)
    }

    private fun tickActionBars() {
        if (combatExpiry.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((uuid, exp) in combatExpiry.entries) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                combatExpiry.remove(uuid)
                continue
            }
            val rem = exp - now
            if (rem <= 0L) {
                combatExpiry.remove(uuid)
                context.scheduler.runForEntity(player) {
                    if (player.isOnline) context.locale.send(player, "utility.combat-expired")
                }
            } else {
                val sec = ((rem + 999L) / 1000L).toInt()
                context.scheduler.runForEntity(player) {
                    if (player.isOnline) {
                        context.locale.sendActionBar(player, "utility.combat-actionbar", "seconds" to sec)
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (!enabled) return
        val victim = event.entity as? Player
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }

        if (victim != null && attacker != null && victim.uniqueId != attacker.uniqueId) {
            if (pvpTagSeconds > 0) {
                tagPlayer(victim, pvpTagSeconds)
                tagPlayer(attacker, pvpTagSeconds)
            }
        } else if (pveTagSeconds > 0) {
            if (victim != null) tagPlayer(victim, pveTagSeconds)
            if (attacker != null && event.entity !is Player) tagPlayer(attacker, pveTagSeconds)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!enabled || blockedCommands.isEmpty()) return
        val player = event.player
        if (!isInCombat(player.uniqueId)) return
        if (player.hasPermission("obsidian.combat.bypass") || player.hasPermission("obsidian.admin")) return

        val raw = event.message.trim().removePrefix("/").trimStart()
        val tokens = raw.split(Regex("\\s+"), limit = 3)
        if (tokens.isEmpty()) return
        val first = tokens[0].substringAfter(':').lowercase(Locale.ROOT)
        val cmdName = if ((first == "obsidian" || first == "obs") && tokens.size >= 2) {
            tokens[1].lowercase(Locale.ROOT)
        } else {
            first
        }

        if (blockedCommands.contains(cmdName)) {
            event.isCancelled = true
            context.locale.send(
                player,
                "utility.combat-command-blocked",
                "seconds" to getRemainingSeconds(player.uniqueId),
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (!enabled || !killOnLogout) {
            combatExpiry.remove(player.uniqueId)
            return
        }
        if (!isInCombat(player.uniqueId)) return
        combatExpiry.remove(player.uniqueId)
        if (player.hasPermission("obsidian.combat.bypass") || player.hasPermission("obsidian.admin")) return

        player.health = 0.0
        Bukkit.broadcast(
            context.locale.component("utility.combat-logged", mapOf("player" to player.name))
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        combatExpiry.remove(victim.uniqueId)
        val killer = victim.killer ?: return
        if (killer.uniqueId == victim.uniqueId) return
        if (playerHeadDropChance <= 0.0) return
        if (playerHeadDropChance < 100.0 && Random.nextDouble(0.0, 100.0) > playerHeadDropChance) return

        val skull = createPlayerSkull(victim.name, victim)
        event.drops += skull
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is Player) return
        entity.killer ?: return
        if (mobHeadDropChance <= 0.0) return
        if (mobHeadDropChance < 100.0 && Random.nextDouble(0.0, 100.0) > mobHeadDropChance) return

        val head = createMobHead(entity.type, 1) ?: return
        event.drops += head
    }

    companion object {
        fun createPlayerSkull(name: String, onlineTarget: Player? = null): ItemStack {
            return ItemStack(Material.PLAYER_HEAD, 1).apply {
                val meta = itemMeta as SkullMeta
                if (onlineTarget != null) {
                    meta.owningPlayer = onlineTarget
                } else {
                    @Suppress("DEPRECATION")
                    meta.owner = name
                }
                meta.displayName(Component.text("${name}'s Head"))
                itemMeta = meta
            }
        }

        fun createMobHead(type: EntityType, amount: Int = 1): ItemStack? {
            val mat = when (type) {
                EntityType.SKELETON -> Material.SKELETON_SKULL
                EntityType.WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL
                EntityType.ZOMBIE -> Material.ZOMBIE_HEAD
                EntityType.CREEPER -> Material.CREEPER_HEAD
                EntityType.ENDER_DRAGON -> Material.DRAGON_HEAD
                EntityType.PIGLIN -> Material.PIGLIN_HEAD
                EntityType.PLAYER -> Material.PLAYER_HEAD
                else -> return null
            }
            return ItemStack(mat, amount.coerceIn(1, 64))
        }
    }
}
