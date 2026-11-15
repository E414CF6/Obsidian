package obsidian.modules.economy

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import obsidian.core.ObsidianContext
import obsidian.core.config.YamlConfigFile
import obsidian.core.database.WorthRecord
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages item sell prices (`worth.yml` defaults + DB overrides) and `/worth` / `/sell`.
 */
class WorthService(
    private val context: ObsidianContext,
    private val economyService: EconomyService,
) {

    private val worthFile = YamlConfigFile(context.plugin, "worth.yml")
    private val prices = ConcurrentHashMap<Material, WorthRecord>()

    init {
        reload()
    }

    fun reload() {
        worthFile.reload()
        prices.clear()
        val useDefaults = context.config.raw.getBoolean("worth.use-defaults", true)
        if (useDefaults) {
            val sec = worthFile.yaml.getConfigurationSection("materials")
            sec?.getKeys(false)?.forEach { key ->
                val mat = Material.matchMaterial(key) ?: return@forEach
                val price = sec.getDouble(key, 0.0)
                if (price > 0.0) {
                    prices[mat] = WorthRecord(mat.name, price)
                }
            }
        }
        val dbRecords = runBlocking { context.economyRepository.findAllWorth() }
        for (rec in dbRecords) {
            val mat = Material.matchMaterial(rec.material) ?: continue
            if (rec.sellPrice <= 0.0) {
                prices.remove(mat)
            } else {
                prices[mat] = rec
            }
        }
    }

    fun getSellPrice(material: Material): Double? = prices[material]?.sellPrice

    fun setSellPrice(material: Material, price: Double) {
        val rounded = EconomyService.roundMoney(price)
        if (rounded <= 0.0) {
            prices.remove(material)
            context.scheduler.asyncScope.launch {
                context.economyRepository.deleteWorth(material.name)
            }
            return
        }
        val record = WorthRecord(material.name, rounded, null)
        prices[material] = record
        context.scheduler.asyncScope.launch {
            context.economyRepository.upsertWorth(record)
        }
    }

    fun sellHand(player: Player): SellResult {
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) return SellResult(0, 0.0)
        val unitPrice = getSellPrice(item.type) ?: return SellResult(0, 0.0)
        val count = item.amount
        val total = EconomyService.roundMoney(unitPrice * count)
        player.inventory.setItemInMainHand(null)
        economyService.deposit(player.uniqueId, total, player.name)
        return SellResult(count, total)
    }

    fun sellAll(player: Player): SellResult {
        var totalCount = 0
        var totalEarned = 0.0
        val contents = player.inventory.storageContents
        for (i in contents.indices) {
            val item: ItemStack = contents[i] ?: continue
            if (item.type.isAir) continue
            val unitPrice = getSellPrice(item.type) ?: continue
            totalCount += item.amount
            totalEarned += unitPrice * item.amount
            contents[i] = null
        }
        if (totalCount == 0) return SellResult(0, 0.0)
        player.inventory.storageContents = contents
        val rounded = EconomyService.roundMoney(totalEarned)
        economyService.deposit(player.uniqueId, rounded, player.name)
        return SellResult(totalCount, rounded)
    }

    data class SellResult(val itemCount: Int, val totalEarned: Double)
}
