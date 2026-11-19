package obsidian.modules.utility

import org.bukkit.*
import org.bukkit.inventory.*
import org.bukkit.inventory.meta.Damageable
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure parsing, scaling, and item-repair helpers for [UtilityModule].
 */
object UtilityHelpers {

    data class CondenseRule(val input: Material, val inputCount: Int, val output: Material, val outputCount: Int)

    private val condenseCache = ConcurrentHashMap<Material, CondenseRule?>()
    private val uncondenseCache = ConcurrentHashMap<Material, CondenseRule?>()
    private val NO_RULE = CondenseRule(Material.AIR, 0, Material.AIR, 0)

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

    fun parseColor(input: String): Color? {
        val trimmed = input.trim()
        if (trimmed.startsWith("#")) {
            val hex = trimmed.substring(1)
            val rgb = hex.toIntOrNull(16) ?: return null
            if (hex.length != 6) return null
            return Color.fromRGB(rgb)
        }
        return when (trimmed.lowercase()) {
            "white" -> Color.WHITE
            "silver", "lightgray", "light_gray" -> Color.SILVER
            "gray", "grey" -> Color.GRAY
            "black" -> Color.BLACK
            "red" -> Color.RED
            "maroon", "darkred", "dark_red" -> Color.MAROON
            "yellow" -> Color.YELLOW
            "olive" -> Color.OLIVE
            "lime", "green" -> Color.LIME
            "darkgreen", "dark_green" -> Color.GREEN
            "aqua", "cyan" -> Color.AQUA
            "teal", "darkaqua", "dark_aqua" -> Color.TEAL
            "blue" -> Color.BLUE
            "navy", "darkblue", "dark_blue" -> Color.NAVY
            "fuchsia", "magenta", "pink" -> Color.FUCHSIA
            "purple" -> Color.PURPLE
            "orange" -> Color.ORANGE
            else -> null
        }
    }

    fun condenseInventory(inventory: PlayerInventory, handOnly: Boolean): Pair<Int, Int> {
        val candidates = if (handOnly) {
            val held = inventory.itemInMainHand
            if (held.type.isAir) return 0 to 0
            listOf(held.type)
        } else {
            inventory.storageContents.filterNotNull().filter { !it.type.isAir }.map { it.type }.distinct()
        }

        var totalInput = 0
        var totalOutput = 0
        for (material in candidates) {
            val rule = findCondenseRule(material) ?: continue
            var available = 0
            for (slot in inventory.storageContents) {
                if (slot != null && slot.type == material && !slot.hasItemMeta()) {
                    available += slot.amount
                }
            }
            val craftTimes = available / rule.inputCount
            if (craftTimes <= 0) continue

            val toRemove = craftTimes * rule.inputCount
            val toAdd = craftTimes * rule.outputCount
            removePlainMaterial(inventory, material, toRemove)
            val leftovers = inventory.addItem(ItemStack(rule.output, toAdd))
            for (leftover in leftovers.values) {
                inventory.holder?.let { holder ->
                    if (holder is org.bukkit.entity.Player) {
                        holder.world.dropItemNaturally(holder.location, leftover)
                    }
                }
            }
            totalInput += toRemove
            totalOutput += toAdd
        }
        return totalInput to totalOutput
    }

    fun uncondenseInventory(inventory: PlayerInventory, handOnly: Boolean): Pair<Int, Int> {
        val candidates = if (handOnly) {
            val held = inventory.itemInMainHand
            if (held.type.isAir) return 0 to 0
            listOf(held.type)
        } else {
            inventory.storageContents.filterNotNull().filter { !it.type.isAir }.map { it.type }.distinct()
        }

        var totalBlocks = 0
        var totalItems = 0
        for (material in candidates) {
            val rule = findUncondenseRule(material) ?: continue
            var available = 0
            for (slot in inventory.storageContents) {
                if (slot != null && slot.type == material && !slot.hasItemMeta()) {
                    available += slot.amount
                }
            }
            val craftTimes = available / rule.inputCount
            if (craftTimes <= 0) continue

            val toRemove = craftTimes * rule.inputCount
            val toAdd = craftTimes * rule.outputCount
            removePlainMaterial(inventory, material, toRemove)
            val leftovers = inventory.addItem(ItemStack(rule.output, toAdd))
            for (leftover in leftovers.values) {
                inventory.holder?.let { holder ->
                    if (holder is org.bukkit.entity.Player) {
                        holder.world.dropItemNaturally(holder.location, leftover)
                    }
                }
            }
            totalBlocks += toRemove
            totalItems += toAdd
        }
        return totalBlocks to totalItems
    }

    private fun removePlainMaterial(inventory: PlayerInventory, material: Material, amount: Int) {
        var remaining = amount
        val contents = inventory.storageContents
        for (i in contents.indices) {
            val item = contents[i] ?: continue
            if (item.type != material || item.hasItemMeta()) continue
            if (item.amount <= remaining) {
                remaining -= item.amount
                inventory.setItem(i, null)
            } else {
                item.amount -= remaining
                inventory.setItem(i, item)
                remaining = 0
            }
            if (remaining <= 0) break
        }
    }

    private fun findCondenseRule(material: Material): CondenseRule? {
        val cached = condenseCache[material]
        if (cached != null) return if (cached === NO_RULE) null else cached

        var best: CondenseRule? = null
        val iter = Bukkit.recipeIterator()
        while (iter.hasNext()) {
            val recipe = iter.next()
            val rule = extractSingleIngredientRule(recipe) ?: continue
            if (rule.input != material) continue
            if (rule.inputCount != 4 && rule.inputCount != 9) continue
            if (rule.inputCount < rule.outputCount) continue
            // Ensure reversible (can uncondense back to input)
            if (!hasReverseRecipe(rule.output, material)) continue
            if (best == null || rule.inputCount > best.inputCount) {
                best = rule
            }
            if (rule.inputCount == 9) break
        }

        condenseCache[material] = best ?: NO_RULE
        return best
    }

    private fun findUncondenseRule(material: Material): CondenseRule? {
        val cached = uncondenseCache[material]
        if (cached != null) return if (cached === NO_RULE) null else cached

        var best: CondenseRule? = null
        val iter = Bukkit.recipeIterator()
        while (iter.hasNext()) {
            val recipe = iter.next()
            val rule = extractSingleIngredientRule(recipe) ?: continue
            if (rule.input != material || rule.inputCount != 1) continue
            if (rule.outputCount != 4 && rule.outputCount != 9) continue
            if (!hasCondenseRecipe(rule.output, material, rule.outputCount)) continue
            best = rule
            break
        }

        uncondenseCache[material] = best ?: NO_RULE
        return best
    }

    private fun hasReverseRecipe(blockMaterial: Material, targetIngredient: Material): Boolean {
        val iter = Bukkit.recipeIterator()
        while (iter.hasNext()) {
            val rule = extractSingleIngredientRule(iter.next()) ?: continue
            if (rule.input == blockMaterial && rule.inputCount == 1 && rule.output == targetIngredient) {
                return true
            }
        }
        return false
    }

    private fun hasCondenseRecipe(ingredient: Material, blockMaterial: Material, count: Int): Boolean {
        val iter = Bukkit.recipeIterator()
        while (iter.hasNext()) {
            val rule = extractSingleIngredientRule(iter.next()) ?: continue
            if (rule.input == ingredient && rule.inputCount == count && rule.output == blockMaterial) {
                return true
            }
        }
        return false
    }

    private fun extractSingleIngredientRule(recipe: Recipe): CondenseRule? {
        val result = recipe.result
        if (result.type.isAir) return null
        val choices: List<RecipeChoice?> = when (recipe) {
            is ShapedRecipe -> {
                val list = mutableListOf<RecipeChoice?>()
                for (row in recipe.shape) {
                    for (ch in row) {
                        if (ch != ' ') list += recipe.choiceMap[ch]
                    }
                }
                list
            }

            is ShapelessRecipe -> recipe.choiceList
            else -> return null
        }
        val nonNull = choices.filterNotNull()
        if (nonNull.isEmpty()) return null
        val firstChoice = nonNull.first() as? RecipeChoice.MaterialChoice ?: return null
        if (firstChoice.choices.size != 1) return null
        val mat = firstChoice.choices.first()
        for (choice in nonNull) {
            val mc = choice as? RecipeChoice.MaterialChoice ?: return null
            if (mc.choices.size != 1 || mc.choices.first() != mat) return null
        }
        return CondenseRule(mat, nonNull.size, result.type, result.amount)
    }
}
