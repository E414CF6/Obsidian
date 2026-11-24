package obsidian.modules.utility

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import obsidian.core.ObsidianContext
import obsidian.core.gui.MenuWindow
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Provides CMI-inspired flight, combat, and personal QoL systems:
 * - Temporary flight (`/tfly`) and temporary god mode (`/tgod`)
 * - Flight charge (`/flightcharge`, `/flyc`, `/autorecharge`)
 * - BossBar entity HP indicator (`HpBar`)
 * - Elytra glide boost
 * - Totem of Undying cooldown
 * - Per-player `/options` GUI backed by PDC
 */
class FlightAndCombatService(private val context: ObsidianContext) : Listener {

    companion object {
        private const val UNTIL_RELOG = -1L
        private const val TOTEM_COOLDOWN_MS = 30_000L
        private const val ELYTRA_BOOST_COOLDOWN_MS = 2_000L
    }

    private val chargeKey = NamespacedKey(context.plugin, "obsidian_flight_charge")
    private val autoRechargeKey = NamespacedKey(context.plugin, "obsidian_auto_recharge")
    private val hpBarOptKey = NamespacedKey(context.plugin, "obsidian_opt_hpbar")
    private val elytraOptKey = NamespacedKey(context.plugin, "obsidian_opt_elytra")

    // Value: epoch millis when it expires, or UNTIL_RELOG (-1L)
    private val tempFlyExpiry = ConcurrentHashMap<UUID, Long>()
    private val tempGodExpiry = ConcurrentHashMap<UUID, Long>()
    private val flightChargeActive = ConcurrentHashMap.newKeySet<UUID>()

    private data class ActiveHpBar(val bar: BossBar, var hideAtMillis: Long)

    private val activeHpBars = ConcurrentHashMap<UUID, ActiveHpBar>()
    private val totemCooldowns = ConcurrentHashMap<UUID, Long>()
    private val elytraCooldowns = ConcurrentHashMap<UUID, Long>()

    private var tickTask: ScheduledTask? = null

    fun start() {
        tickTask = context.scheduler.runAsyncTimer(1L, 1L) { tickEverySecond() }
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
        for ((uuid, active) in activeHpBars) {
            Bukkit.getPlayer(uuid)?.hideBossBar(active.bar)
        }
        activeHpBars.clear()
        tempFlyExpiry.clear()
        tempGodExpiry.clear()
        flightChargeActive.clear()
    }

    fun isTempGodActive(uuid: UUID): Boolean {
        val exp = tempGodExpiry[uuid] ?: return false
        return exp == UNTIL_RELOG || exp > System.currentTimeMillis()
    }

    fun setTempFly(target: Player, durationMillis: Long?) {
        when {
            durationMillis == null -> {
                tempFlyExpiry.remove(target.uniqueId)
                context.scheduler.runForEntity(target) {
                    if (target.isOnline) {
                        target.isFlying = false
                        target.allowFlight = false
                    }
                }
            }

            durationMillis == 0L -> {
                tempFlyExpiry[target.uniqueId] = UNTIL_RELOG
                context.scheduler.runForEntity(target) {
                    if (target.isOnline) target.allowFlight = true
                }
            }

            else -> {
                tempFlyExpiry[target.uniqueId] = System.currentTimeMillis() + durationMillis
                context.scheduler.runForEntity(target) {
                    if (target.isOnline) target.allowFlight = true
                }
            }
        }
    }

    fun getTempFlyRemainingMillis(uuid: UUID): Long? {
        val exp = tempFlyExpiry[uuid] ?: return null
        if (exp == UNTIL_RELOG) return UNTIL_RELOG
        val rem = exp - System.currentTimeMillis()
        return if (rem > 0) rem else null
    }

    fun setTempGod(target: Player, durationMillis: Long?) {
        when {
            durationMillis == null -> tempGodExpiry.remove(target.uniqueId)
            durationMillis == 0L -> tempGodExpiry[target.uniqueId] = UNTIL_RELOG
            else -> tempGodExpiry[target.uniqueId] = System.currentTimeMillis() + durationMillis
        }
    }

    fun getTempGodRemainingMillis(uuid: UUID): Long? {
        val exp = tempGodExpiry[uuid] ?: return null
        if (exp == UNTIL_RELOG) return UNTIL_RELOG
        val rem = exp - System.currentTimeMillis()
        return if (rem > 0) rem else null
    }

    fun getFlightCharge(player: Player): Double {
        return player.persistentDataContainer.get(chargeKey, PersistentDataType.DOUBLE) ?: 0.0
    }

    fun setFlightCharge(player: Player, amount: Double) {
        player.persistentDataContainer.set(chargeKey, PersistentDataType.DOUBLE, amount.coerceAtLeast(0.0))
    }

    fun isAutoRecharge(player: Player): Boolean {
        return (player.persistentDataContainer.get(autoRechargeKey, PersistentDataType.BYTE)
            ?: 0.toByte()) == 1.toByte()
    }

    fun setAutoRecharge(player: Player, enabled: Boolean) {
        player.persistentDataContainer.set(autoRechargeKey, PersistentDataType.BYTE, if (enabled) 1 else 0)
    }

    fun toggleFlightChargeMode(player: Player): Boolean {
        return if (flightChargeActive.remove(player.uniqueId)) {
            context.scheduler.runForEntity(player) {
                player.isFlying = false
                player.allowFlight = false
            }
            false
        } else {
            if (getFlightCharge(player) <= 0.0) return false
            flightChargeActive.add(player.uniqueId)
            context.scheduler.runForEntity(player) {
                player.allowFlight = true
            }
            true
        }
    }

    fun isFlightChargeMode(uuid: UUID): Boolean = flightChargeActive.contains(uuid)

    fun isHpBarEnabled(player: Player): Boolean {
        val raw = player.persistentDataContainer.get(hpBarOptKey, PersistentDataType.BYTE)
        return raw == null || raw == 1.toByte()
    }

    fun setHpBarEnabled(player: Player, enabled: Boolean) {
        player.persistentDataContainer.set(hpBarOptKey, PersistentDataType.BYTE, if (enabled) 1 else 0)
    }

    fun isElytraBoostEnabled(player: Player): Boolean {
        val raw = player.persistentDataContainer.get(elytraOptKey, PersistentDataType.BYTE)
        return raw == null || raw == 1.toByte()
    }

    fun setElytraBoostEnabled(player: Player, enabled: Boolean) {
        player.persistentDataContainer.set(elytraOptKey, PersistentDataType.BYTE, if (enabled) 1 else 0)
    }

    fun openOptionsGui(player: Player) {
        val window = MenuWindow(context.locale.component("utility.options-title"), 3)
        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            val m = itemMeta
            m.displayName(Component.text(" "))
            itemMeta = m
        }
        window.fillBorder(filler)

        fun toggleItem(material: Material, name: String, enabled: Boolean): ItemStack {
            return ItemStack(material).apply {
                val m = itemMeta
                val status = context.locale.raw(if (enabled) "general.state-on" else "general.state-off")
                m.displayName(
                    context.locale.component(
                        "utility.options-item-name",
                        mapOf("name" to name, "state" to status),
                    )
                )
                m.lore(listOf(context.locale.component("utility.options-click-toggle")))
                itemMeta = m
            }
        }

        window.set(
            10,
            toggleItem(Material.REDSTONE, context.locale.raw("utility.options-hp-bar"), isHpBarEnabled(player))
        ) {
            setHpBarEnabled(player, !isHpBarEnabled(player))
            openOptionsGui(player)
        }
        window.set(
            12,
            toggleItem(
                Material.ELYTRA,
                context.locale.raw("utility.options-elytra-boost"),
                isElytraBoostEnabled(player)
            )
        ) {
            setElytraBoostEnabled(player, !isElytraBoostEnabled(player))
            openOptionsGui(player)
        }
        window.set(
            14,
            toggleItem(Material.FEATHER, context.locale.raw("utility.options-auto-recharge"), isAutoRecharge(player))
        ) {
            setAutoRecharge(player, !isAutoRecharge(player))
            openOptionsGui(player)
        }
        window.set(16, toggleItem(Material.PAPER, "Toggle Private Messages", true)) {
            player.closeInventory()
            player.performCommand("msgtoggle")
        }

        window.open(player)
    }

    private fun tickEverySecond() {
        val now = System.currentTimeMillis()

        // Expire tfly
        for ((uuid, exp) in tempFlyExpiry.entries) {
            if (exp != UNTIL_RELOG && now >= exp) {
                tempFlyExpiry.remove(uuid)
                val player = Bukkit.getPlayer(uuid) ?: continue
                context.scheduler.runForEntity(player) {
                    if (player.isOnline) {
                        player.isFlying = false
                        player.allowFlight = false
                        context.locale.send(player, "utility.tfly-expired")
                    }
                }
            }
        }

        // Expire tgod
        for ((uuid, exp) in tempGodExpiry.entries) {
            if (exp != UNTIL_RELOG && now >= exp) {
                tempGodExpiry.remove(uuid)
                val player = Bukkit.getPlayer(uuid) ?: continue
                context.scheduler.runForEntity(player) {
                    if (player.isOnline) {
                        context.locale.send(player, "utility.tgod-expired")
                    }
                }
            }
        }

        // Drain flight charge
        for (uuid in flightChargeActive.toList()) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                flightChargeActive.remove(uuid)
                continue
            }
            if (!player.isFlying) continue
            val current = getFlightCharge(player)
            val next = max(0.0, current - 1.0)
            setFlightCharge(player, next)
            if (next <= 0.0) {
                flightChargeActive.remove(uuid)
                context.scheduler.runForEntity(player) {
                    player.isFlying = false
                    player.allowFlight = false
                    context.locale.send(player, "utility.flightcharge-empty")
                }
            } else if (next <= 10.0) {
                context.scheduler.runForEntity(player) {
                    context.locale.sendActionBar(player, "utility.flightcharge-low", "seconds" to next.toInt())
                }
            }
        }

        // Hide expired HP bars
        for ((uuid, active) in activeHpBars.entries) {
            if (now >= active.hideAtMillis) {
                activeHpBars.remove(uuid)
                val player = Bukkit.getPlayer(uuid) ?: continue
                context.scheduler.runForEntity(player) {
                    if (player.isOnline) player.hideBossBar(active.bar)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageEntity(event: EntityDamageByEntityEvent) {
        val target = event.entity as? LivingEntity ?: return
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return
        if (attacker == target) return
        if (!attacker.hasPermission("obsidian.bossbar.hpbar") && !attacker.hasPermission("obsidian.admin")) return
        if (!isHpBarEnabled(attacker)) return

        val maxHp = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val remaining = max(0.0, target.health - event.finalDamage)
        val ratio = (remaining / maxHp).toFloat().coerceIn(0.0f, 1.0f)

        val color = when {
            ratio > 0.5f -> BossBar.Color.GREEN
            ratio > 0.25f -> BossBar.Color.YELLOW
            else -> BossBar.Color.RED
        }
        val title = TextFormatter.format(
            "<white>${target.name}</white> <gray>(</gray><red>${"%.1f".format(remaining)}</red><gray>/</gray><green>${
                "%.1f".format(
                    maxHp
                )
            }</green><gray>)</gray>"
        )

        val existing = activeHpBars[attacker.uniqueId]
        if (existing != null) {
            existing.bar.name(title)
            existing.bar.progress(ratio)
            existing.bar.color(color)
            existing.hideAtMillis = System.currentTimeMillis() + 4_000L
        } else {
            val bar = BossBar.bossBar(title, ratio, color, BossBar.Overlay.PROGRESS)
            activeHpBars[attacker.uniqueId] = ActiveHpBar(bar, System.currentTimeMillis() + 4_000L)
            attacker.showBossBar(bar)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onElytraSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return
        val player = event.player
        if (!player.isGliding) return
        tryElytraBoost(player)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onElytraFeatherClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR) return
        val player = event.player
        if (!player.isGliding) return
        if (player.inventory.itemInMainHand.type != Material.FEATHER) return
        tryElytraBoost(player)
    }

    private fun tryElytraBoost(player: Player) {
        if (!player.hasPermission("obsidian.elytra.boost") && !player.hasPermission("obsidian.admin")) return
        if (!isElytraBoostEnabled(player)) return
        val now = System.currentTimeMillis()
        val readyAt = elytraCooldowns[player.uniqueId] ?: 0L
        if (now < readyAt) return
        elytraCooldowns[player.uniqueId] = now + ELYTRA_BOOST_COOLDOWN_MS

        val dir = player.location.direction.normalize().multiply(1.35)
        player.velocity = dir
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTotemResurrect(event: EntityResurrectEvent) {
        val player = event.entity as? Player ?: return
        if (player.hasPermission("obsidian.totem.bypass") || player.hasPermission("obsidian.admin")) return
        val now = System.currentTimeMillis()
        val readyAt = totemCooldowns[player.uniqueId] ?: 0L
        if (now < readyAt) {
            event.isCancelled = true
            val remSec = ((readyAt - now) / 1000L) + 1L
            context.locale.sendActionBar(player, "utility.totem-cooldown", "seconds" to remSec)
            return
        }
        totemCooldowns[player.uniqueId] = now + TOTEM_COOLDOWN_MS
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        if (tempFlyExpiry[uuid] == UNTIL_RELOG) {
            tempFlyExpiry.remove(uuid)
            event.player.isFlying = false
            event.player.allowFlight = false
        }
        if (tempGodExpiry[uuid] == UNTIL_RELOG) {
            tempGodExpiry.remove(uuid)
        }
        flightChargeActive.remove(uuid)
        activeHpBars.remove(uuid)?.let { event.player.hideBossBar(it.bar) }
    }
}
