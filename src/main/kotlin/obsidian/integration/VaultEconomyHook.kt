package obsidian.integration

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import obsidian.modules.economy.EconomyService
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority

/**
 * Vault [Economy] provider backed by [EconomyService].
 */
@Suppress("OVERRIDE_DEPRECATION")
class VaultEconomyHook(
    private val plugin: Plugin,
    private val economyService: EconomyService,
) : Economy {

    fun register() {
        Bukkit.getServicesManager().register(Economy::class.java, this, plugin, ServicePriority.High)
    }

    fun unregister() {
        Bukkit.getServicesManager().unregister(Economy::class.java, this)
    }

    override fun isEnabled(): Boolean = plugin.isEnabled
    override fun getName(): String = "ObsidianEconomy"
    override fun hasBankSupport(): Boolean = false
    override fun fractionalDigits(): Int = 2
    override fun format(amount: Double): String = economyService.formatMoney(amount)
    override fun currencyNamePlural(): String = economyService.currencyNamePlural
    override fun currencyNameSingular(): String = economyService.currencyName

    override fun hasAccount(playerName: String): Boolean = true
    override fun hasAccount(player: OfflinePlayer): Boolean = true
    override fun hasAccount(playerName: String, worldName: String): Boolean = true
    override fun hasAccount(player: OfflinePlayer, worldName: String): Boolean = true

    override fun getBalance(playerName: String): Double =
        getBalance(Bukkit.getOfflinePlayer(playerName))

    override fun getBalance(player: OfflinePlayer): Double =
        economyService.getBalance(player.uniqueId)

    override fun getBalance(playerName: String, world: String): Double = getBalance(playerName)
    override fun getBalance(player: OfflinePlayer, world: String): Double = getBalance(player)

    override fun has(playerName: String, amount: Double): Boolean =
        has(Bukkit.getOfflinePlayer(playerName), amount)

    override fun has(player: OfflinePlayer, amount: Double): Boolean =
        economyService.has(player.uniqueId, amount)

    override fun has(playerName: String, worldName: String, amount: Double): Boolean = has(playerName, amount)
    override fun has(player: OfflinePlayer, worldName: String, amount: Double): Boolean = has(player, amount)

    override fun withdrawPlayer(playerName: String, amount: Double): EconomyResponse =
        withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount)

    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse {
        if (amount < 0) return EconomyResponse(
            0.0,
            getBalance(player),
            EconomyResponse.ResponseType.FAILURE,
            "Cannot withdraw negative amount"
        )
        val ok = economyService.withdraw(player.uniqueId, amount, player.name)
        val bal = economyService.getBalance(player.uniqueId)
        return if (ok) {
            EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, null)
        } else {
            EconomyResponse(0.0, bal, EconomyResponse.ResponseType.FAILURE, "Insufficient funds")
        }
    }

    override fun withdrawPlayer(playerName: String, worldName: String, amount: Double): EconomyResponse =
        withdrawPlayer(playerName, amount)

    override fun withdrawPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse =
        withdrawPlayer(player, amount)

    override fun depositPlayer(playerName: String, amount: Double): EconomyResponse =
        depositPlayer(Bukkit.getOfflinePlayer(playerName), amount)

    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse {
        if (amount < 0) return EconomyResponse(
            0.0,
            getBalance(player),
            EconomyResponse.ResponseType.FAILURE,
            "Cannot deposit negative amount"
        )
        val ok = economyService.deposit(player.uniqueId, amount, player.name)
        val bal = economyService.getBalance(player.uniqueId)
        return if (ok) {
            EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, null)
        } else {
            EconomyResponse(0.0, bal, EconomyResponse.ResponseType.FAILURE, "Deposit failed")
        }
    }

    override fun depositPlayer(playerName: String, worldName: String, amount: Double): EconomyResponse =
        depositPlayer(playerName, amount)

    override fun depositPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse =
        depositPlayer(player, amount)

    override fun createPlayerAccount(playerName: String): Boolean = true
    override fun createPlayerAccount(player: OfflinePlayer): Boolean = true
    override fun createPlayerAccount(playerName: String, worldName: String): Boolean = true
    override fun createPlayerAccount(player: OfflinePlayer, worldName: String): Boolean = true

    private fun notSupported(): EconomyResponse =
        EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported")

    override fun createBank(name: String, player: String): EconomyResponse = notSupported()
    override fun createBank(name: String, player: OfflinePlayer): EconomyResponse = notSupported()
    override fun deleteBank(name: String): EconomyResponse = notSupported()
    override fun bankBalance(name: String): EconomyResponse = notSupported()
    override fun bankHas(name: String, amount: Double): EconomyResponse = notSupported()
    override fun bankWithdraw(name: String, amount: Double): EconomyResponse = notSupported()
    override fun bankDeposit(name: String, amount: Double): EconomyResponse = notSupported()
    override fun isBankOwner(name: String, playerName: String): EconomyResponse = notSupported()
    override fun isBankOwner(name: String, player: OfflinePlayer): EconomyResponse = notSupported()
    override fun isBankMember(name: String, playerName: String): EconomyResponse = notSupported()
    override fun isBankMember(name: String, player: OfflinePlayer): EconomyResponse = notSupported()
    override fun getBanks(): MutableList<String> = mutableListOf()
}
