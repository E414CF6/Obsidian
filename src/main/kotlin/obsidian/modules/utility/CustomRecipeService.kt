package obsidian.modules.utility

import obsidian.core.ObsidianContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CustomRecipeSummary(
    val id: String,
    val type: String,
    val result: Material,
    val amount: Int,
)

/**
 * Loads and registers custom Shaped, Shapeless, and Furnace recipes from `plugins/Obsidian/recipes.yml`.
 */
class CustomRecipeService(private val context: ObsidianContext) {

    private val recipeFile = File(context.plugin.dataFolder, "recipes.yml")
    private val registeredKeys = ConcurrentHashMap.newKeySet<NamespacedKey>()

    init {
        reload()
    }

    fun reload() {
        unregisterAll()
        if (!recipeFile.exists()) {
            createDefaultRecipes()
        }
        loadAndRegister()
    }

    fun unregisterAll() {
        for (key in registeredKeys) {
            runCatching { Bukkit.removeRecipe(key) }
        }
        registeredKeys.clear()
    }

    private fun createDefaultRecipes() {
        val yaml = YamlConfiguration()
        yaml.set("recipes.saddle.type", "shaped")
        yaml.set("recipes.saddle.shape", listOf("LLL", "LIL", "I I"))
        yaml.set("recipes.saddle.ingredients.L", "LEATHER")
        yaml.set("recipes.saddle.ingredients.I", "IRON_INGOT")
        yaml.set("recipes.saddle.result", "SADDLE")
        yaml.set("recipes.saddle.amount", 1)

        yaml.set("recipes.name_tag.type", "shapeless")
        yaml.set("recipes.name_tag.ingredients", listOf("PAPER", "STRING", "IRON_NUGGET"))
        yaml.set("recipes.name_tag.result", "NAME_TAG")
        yaml.set("recipes.name_tag.amount", 1)

        runCatching {
            recipeFile.parentFile?.mkdirs()
            yaml.save(recipeFile)
        }
    }

    private fun loadAndRegister() {
        if (!recipeFile.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(recipeFile)
        val sec = yaml.getConfigurationSection("recipes") ?: return

        for (id in sec.getKeys(false)) {
            val node = sec.getConfigurationSection(id) ?: continue
            val type = node.getString("type", "shaped")?.lowercase() ?: "shaped"
            val resultMat = Material.matchMaterial(node.getString("result", "") ?: "") ?: continue
            val amount = node.getInt("amount", 1).coerceIn(1, resultMat.maxStackSize)
            val resultItem = ItemStack(resultMat, amount)
            val nsKey = NamespacedKey(context.plugin, "custom_${id.lowercase()}")

            try {
                val recipe: Recipe? = when (type) {
                    "shaped" -> {
                        val shape = node.getStringList("shape")
                        if (shape.isEmpty()) null
                        else {
                            val shaped = ShapedRecipe(nsKey, resultItem)
                            shaped.shape(*shape.toTypedArray())
                            val ingSec = node.getConfigurationSection("ingredients")
                            if (ingSec != null) {
                                for (ch in ingSec.getKeys(false)) {
                                    val matName = ingSec.getString(ch) ?: continue
                                    val mat = Material.matchMaterial(matName) ?: continue
                                    shaped.setIngredient(ch[0], mat)
                                }
                            }
                            shaped
                        }
                    }

                    "shapeless" -> {
                        val ingList = node.getStringList("ingredients")
                        if (ingList.isEmpty()) null
                        else {
                            val shapeless = ShapelessRecipe(nsKey, resultItem)
                            for (itemStr in ingList) {
                                val mat = Material.matchMaterial(itemStr) ?: continue
                                shapeless.addIngredient(mat)
                            }
                            shapeless
                        }
                    }

                    "furnace", "smelt" -> {
                        val inputStr = node.getString("input", "") ?: ""
                        val inputMat = Material.matchMaterial(inputStr) ?: continue
                        val exp = node.getDouble("experience", 0.5).toFloat()
                        val cookingTime = node.getInt("cooking-time", 200)
                        FurnaceRecipe(nsKey, resultItem, inputMat, exp, cookingTime)
                    }

                    else -> null
                }

                if (recipe != null) {
                    Bukkit.addRecipe(recipe)
                    registeredKeys.add(nsKey)
                }
            } catch (ex: Exception) {
                context.plugin.logger.warning("Failed to register custom recipe '$id': ${ex.message}")
            }
        }
    }

    fun listRecipes(): List<CustomRecipeSummary> {
        if (!recipeFile.exists()) return emptyList()
        val yaml = YamlConfiguration.loadConfiguration(recipeFile)
        val sec = yaml.getConfigurationSection("recipes") ?: return emptyList()
        val list = mutableListOf<CustomRecipeSummary>()
        for (id in sec.getKeys(false)) {
            val node = sec.getConfigurationSection(id) ?: continue
            val type = node.getString("type", "shaped") ?: "shaped"
            val resultMat = Material.matchMaterial(node.getString("result", "") ?: "") ?: Material.AIR
            val amount = node.getInt("amount", 1)
            list += CustomRecipeSummary(id, type, resultMat, amount)
        }
        return list
    }

    fun getRegisteredRecipe(id: String): Recipe? {
        val nsKey = NamespacedKey(context.plugin, "custom_${id.lowercase()}")
        return Bukkit.getRecipe(nsKey)
    }

    fun deleteRecipe(id: String): Boolean {
        if (!recipeFile.exists()) return false
        val yaml = YamlConfiguration.loadConfiguration(recipeFile)
        if (!yaml.contains("recipes.$id")) return false
        yaml.set("recipes.$id", null)
        runCatching { yaml.save(recipeFile) }
        val nsKey = NamespacedKey(context.plugin, "custom_${id.lowercase()}")
        Bukkit.removeRecipe(nsKey)
        registeredKeys.remove(nsKey)
        return true
    }
}
