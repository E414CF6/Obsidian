package obsidian.modules.utility

import org.bukkit.GameMode
import org.bukkit.WeatherType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable

/**
 * Pure parsing, scaling, and item-repair helpers for [UtilityModule].
 */
object UtilityHelpers {

    fun parseGameMode(input: String): GameMode? = when (input.lowercase()) {
        "0", "s", "survival" -> GameMode.SURVIVAL
        "1", "c", "creative" -> GameMode.CREATIVE
        "2", "a", "adventure" -> GameMode.ADVENTURE
        "3", "sp", "spectator" -> GameMode.SPECTATOR
        else -> null
    }

    /**
     * Converts CMI-style `0..10` speed (default `1.0`) to Bukkit's `0.0..1.0` scale.
     */
    fun scaleSpeedToBukkit(speed: Double, isFlying: Boolean): Float {
        val clamped = speed.coerceIn(0.0, 10.0)
        val defaultSpeed = if (isFlying) 0.1 else 0.2
        val result = if (clamped <= 1.0) {
            clamped * defaultSpeed
        } else {
            defaultSpeed + ((clamped - 1.0) / 9.0) * (1.0 - defaultSpeed)
        }
        return result.toFloat().coerceIn(0.0f, 1.0f)
    }

    fun parsePersonalTime(input: String): Long? = when (input.lowercase()) {
        "day" -> 1000L
        "noon" -> 6000L
        "sunset", "dusk" -> 12000L
        "night" -> 13000L
        "midnight" -> 18000L
        "sunrise", "dawn" -> 23000L
        else -> input.toLongOrNull()
    }

    fun parsePersonalWeather(input: String): WeatherType? = when (input.lowercase()) {
        "sun", "clear", "sunny" -> WeatherType.CLEAR
        "rain", "storm", "downfall" -> WeatherType.DOWNFALL
        else -> null
    }

    fun isRepairable(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        val meta = item.itemMeta as? Damageable ?: return false
        return meta.hasDamage() && meta.damage > 0
    }

    fun repairItem(item: ItemStack?): Boolean {
        if (!isRepairable(item) || item == null) return false
        val meta = item.itemMeta as? Damageable ?: return false
        meta.damage = 0
        item.itemMeta = meta
        return true
    }
}
