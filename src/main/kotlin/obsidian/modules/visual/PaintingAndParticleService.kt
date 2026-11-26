package obsidian.modules.visual

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.key.Key
import obsidian.core.ObsidianContext
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Painting
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import kotlin.math.cos
import kotlin.math.sin

/**
 * Handles shift-right-click painting variant cycling and Folia-safe `/panimation` particle presets.
 */
class PaintingAndParticleService(private val context: ObsidianContext) : Listener {

    @Volatile
    var paintingCycleEnabled: Boolean = true
        private set

    init {
        reload()
    }

    fun reload() {
        paintingCycleEnabled = context.config.raw.getBoolean("painting.shift-cycle-enabled", true)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPaintingInteract(event: PlayerInteractEntityEvent) {
        if (!paintingCycleEnabled || event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!player.isSneaking) return
        val painting = event.rightClicked as? Painting ?: return
        if (!player.hasPermission("obsidian.painting.cycle") && !player.hasPermission("obsidian.admin")) return

        event.isCancelled = true
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.PAINTING_VARIANT)
        val allArts = registry.toList().sortedBy { it.key().value() }
        if (allArts.isEmpty()) return

        val current = painting.art
        val sameSizeArts = allArts.filter {
            it.blockWidth == current.blockWidth && it.blockHeight == current.blockHeight
        }
        val candidates = if (sameSizeArts.size > 1) sameSizeArts else allArts
        val idx = candidates.indexOfFirst { it.key() == current.key() }
        val next = candidates[(idx + 1) % candidates.size]
        if (painting.setArt(next, false) || painting.setArt(next, true)) {
            context.actionBar.pushTransientLocale(
                player,
                "visual.painting-cycled",
                "art" to next.key().value(),
            )
        }
    }

    fun playPreset(player: Player, preset: String, particleName: String?) {
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.PARTICLE_TYPE)
        val particle = particleName?.let { raw ->
            val key = if (raw.contains(':')) Key.key(raw.lowercase()) else Key.key("minecraft", raw.lowercase())
            registry.get(key)
        } ?: Particle.FLAME

        val origin = player.location.clone().add(0.0, 0.2, 0.0)
        val world = origin.world ?: return

        when (preset.lowercase()) {
            "burst" -> {
                context.scheduler.runAtLocation(origin) {
                    world.spawnParticle(particle, origin.clone().add(0.0, 1.0, 0.0), 40, 0.7, 0.7, 0.7, 0.05)
                }
            }

            "circle" -> {
                for (step in 0 until 16) {
                    val delay = (step / 2L) + 1L
                    val angle = (2.0 * Math.PI * step) / 16.0
                    val loc = origin.clone().add(cos(angle) * 1.4, 0.8, sin(angle) * 1.4)
                    context.scheduler.runForEntityDelayed(player, delay) {
                        world.spawnParticle(particle, loc, 2, 0.0, 0.0, 0.0, 0.0)
                    }
                }
            }

            "helix" -> {
                for (step in 0 until 24) {
                    val delay = step.toLong() + 1L
                    val angle = step * 0.6
                    val yOffset = step * 0.1
                    val locA = origin.clone().add(cos(angle) * 1.1, yOffset, sin(angle) * 1.1)
                    val locB = origin.clone().add(cos(angle + Math.PI) * 1.1, yOffset, sin(angle + Math.PI) * 1.1)
                    context.scheduler.runForEntityDelayed(player, delay) {
                        world.spawnParticle(particle, locA, 1, 0.0, 0.0, 0.0, 0.0)
                        world.spawnParticle(particle, locB, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }
            }

            else -> {
                // ring
                context.scheduler.runAtLocation(origin) {
                    for (i in 0 until 24) {
                        val angle = (2.0 * Math.PI * i) / 24.0
                        val pt: Location = origin.clone().add(cos(angle) * 1.8, 0.5, sin(angle) * 1.8)
                        world.spawnParticle(particle, pt, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }
            }
        }
    }
}
