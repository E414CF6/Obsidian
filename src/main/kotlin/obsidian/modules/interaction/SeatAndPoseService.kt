package obsidian.modules.interaction

import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles player seating (`/sit`), mounting (`/ride`, `/shakeitoff`), glowing (`/glow`),
 * and Paper 26.3 attribute scaling (`/scale`).
 */
class SeatAndPoseService(private val context: ObsidianContext) : Listener {

    private val seatKey = NamespacedKey(context.plugin, "seat_owner")
    private val seatsByPlayer = ConcurrentHashMap<UUID, UUID>()

    fun toggleSit(player: Player): Boolean {
        if (seatsByPlayer.containsKey(player.uniqueId)) {
            standUp(player.uniqueId)
            return false
        }
        val spawnLoc: Location = player.location.clone().subtract(0.0, 1.72, 0.0)
        val seat = player.world.spawn(spawnLoc, ArmorStand::class.java) { stand ->
            stand.isVisible = false
            stand.setGravity(false)
            stand.isInvulnerable = true
            stand.isMarker = true
            stand.isSmall = true
            stand.isPersistent = false
            stand.persistentDataContainer.set(seatKey, PersistentDataType.STRING, player.uniqueId.toString())
        }
        seatsByPlayer[player.uniqueId] = seat.uniqueId
        seat.addPassenger(player)
        return true
    }

    fun standUp(playerUuid: UUID) {
        val seatUuid = seatsByPlayer.remove(playerUuid) ?: return
        val entity = Bukkit.getEntity(seatUuid) ?: return
        context.scheduler.runForEntity(entity) { entity.remove() }
    }

    fun isSeated(playerUuid: UUID): Boolean = seatsByPlayer.containsKey(playerUuid)

    fun rideTarget(rider: Player, target: Entity): Boolean {
        if (target.uniqueId == rider.uniqueId) return false
        if (rider.vehicle != null) rider.leaveVehicle()
        return target.addPassenger(rider)
    }

    fun shakeOffPassengers(carrier: Player): Int {
        val passengers = carrier.passengers.toList()
        if (passengers.isEmpty()) return 0
        carrier.eject()
        return passengers.size
    }

    fun setScale(player: Player, scale: Double): Double? {
        val attr = player.getAttribute(Attribute.SCALE) ?: return null
        val clamped = scale.coerceIn(0.25, 4.0)
        attr.baseValue = clamped
        return clamped
    }

    fun shutdownAll() {
        val keys = seatsByPlayer.keys.toList()
        keys.forEach { standUp(it) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDismount(event: EntityDismountEvent) {
        val player = event.entity as? Player ?: return
        val dismounted = event.dismounted
        val owner = dismounted.persistentDataContainer.get(seatKey, PersistentDataType.STRING) ?: return
        if (owner == player.uniqueId.toString()) {
            seatsByPlayer.remove(player.uniqueId)
            context.scheduler.runForEntity(dismounted) {
                dismounted.remove()
            }
            // Gently nudge player back above the seat block
            context.scheduler.runForEntityDelayed(player, 1L) {
                if (player.isOnline && !player.isDead) {
                    val dest = player.location.clone().add(0.0, 0.5, 0.0)
                    player.teleportAsync(dest)
                }
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        standUp(event.player.uniqueId)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        standUp(event.player.uniqueId)
    }
}
