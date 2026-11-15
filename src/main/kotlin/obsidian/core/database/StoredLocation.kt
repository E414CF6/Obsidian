package obsidian.core.database

import org.bukkit.Bukkit
import org.bukkit.Location

/**
 * World-name-backed immutable location snapshot safe for async storage and unloaded-world resilience.
 */
data class StoredLocation(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    fun toBukkitLocation(): Location? {
        val bukkitWorld = Bukkit.getWorld(world) ?: return null
        return Location(bukkitWorld, x, y, z, yaw, pitch)
    }

    fun serialize(): String = "$world;$x;$y;$z;$yaw;$pitch"

    companion object {
        fun fromBukkit(location: Location): StoredLocation? {
            val w = location.world?.name ?: return null
            return StoredLocation(w, location.x, location.y, location.z, location.yaw, location.pitch)
        }

        fun deserialize(raw: String?): StoredLocation? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(';')
            if (parts.size < 4) return null
            val x = parts[1].toDoubleOrNull() ?: return null
            val y = parts[2].toDoubleOrNull() ?: return null
            val z = parts[3].toDoubleOrNull() ?: return null
            val yaw = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
            val pitch = parts.getOrNull(5)?.toFloatOrNull() ?: 0f
            return StoredLocation(parts[0], x, y, z, yaw, pitch)
        }
    }
}
