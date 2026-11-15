package obsidian.modules.economy

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import obsidian.core.ObsidianContext
import obsidian.core.database.EconomyRecord
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * Thread-safe multi-currency-ready economy engine with SQLite/MySQL persistence and money cheques.
 */
class EconomyService(private val context: ObsidianContext) : Listener {

    var currencySymbol: String = "$"
        private set
    var currencyName: String = "Dollar"
        private set
    var currencyNamePlural: String = "Dollars"
        private set
    var startingBalance: Double = 100.0
        private set
    var minBalance: Double = 0.0
        private set
    var maxBalance: Double = 1_000_000_000_000.0
        private set
    var minPayAmount: Double = 0.01
        private set

    private var decimalFormat: DecimalFormat = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))
    private val balances = ConcurrentHashMap<UUID, CachedAccount>()
    private val chequeAmountKey = NamespacedKey(context.plugin, "cheque_amount")
    private val chequeCreatorKey = NamespacedKey(context.plugin, "cheque_creator")

    private data class CachedAccount(
        @Volatile var username: String,
        @Volatile var balance: Double,
        @Volatile var dirty: Boolean = false,
    )

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        currencySymbol = raw.getString("economy.currency-symbol", "$") ?: "$"
        currencyName = raw.getString("economy.currency-name", "Dollar") ?: "Dollar"
        currencyNamePlural = raw.getString("economy.currency-name-plural", "Dollars") ?: "Dollars"
        startingBalance = raw.getDouble("economy.starting-balance", 100.0)
        minBalance = raw.getDouble("economy.min-balance", 0.0)
        maxBalance = raw.getDouble("economy.max-balance", 1_000_000_000_000.0)
        minPayAmount = raw.getDouble("economy.min-pay-amount", 0.01)
        val pattern = raw.getString("economy.format-pattern", "#,##0.##") ?: "#,##0.##"
        decimalFormat = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
    }

    fun getBalance(uuid: UUID): Double {
        balances[uuid]?.let { return it.balance }
        val loaded = runBlocking { context.economyRepository.findByUuid(uuid) }
        if (loaded != null) {
            balances[uuid] = CachedAccount(loaded.username, loaded.balance, dirty = false)
            return loaded.balance
        }
        val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)
        val created = CachedAccount(name, roundMoney(startingBalance), dirty = true)
        balances[uuid] = created
        persistSingleAsync(uuid, created)
        return created.balance
    }

    fun has(uuid: UUID, amount: Double): Boolean {
        if (amount < 0.0) return false
        return getBalance(uuid) + 1e-6 >= amount
    }

    fun setBalance(uuid: UUID, amount: Double, usernameHint: String? = null): Double {
        val clamped = roundMoney(amount.coerceIn(minBalance, maxBalance))
        val account = getOrCreateAccount(uuid, usernameHint)
        synchronized(account) {
            account.balance = clamped
            account.dirty = true
        }
        persistSingleAsync(uuid, account)
        return clamped
    }

    fun deposit(uuid: UUID, amount: Double, usernameHint: String? = null): Boolean {
        if (amount < 0.0 || !amount.isFinite()) return false
        val account = getOrCreateAccount(uuid, usernameHint)
        synchronized(account) {
            val next = roundMoney((account.balance + amount).coerceIn(minBalance, maxBalance))
            account.balance = next
            account.dirty = true
        }
        persistSingleAsync(uuid, account)
        return true
    }

    fun withdraw(uuid: UUID, amount: Double, usernameHint: String? = null): Boolean {
        if (amount < 0.0 || !amount.isFinite()) return false
        val account = getOrCreateAccount(uuid, usernameHint)
        synchronized(account) {
            if (account.balance - amount < minBalance - 1e-6) return false
            account.balance = roundMoney((account.balance - amount).coerceAtLeast(minBalance))
            account.dirty = true
        }
        persistSingleAsync(uuid, account)
        return true
    }

    fun transfer(fromUuid: UUID, fromName: String, toUuid: UUID, toName: String, amount: Double): Boolean {
        if (amount <= 0.0 || !amount.isFinite() || fromUuid == toUuid) return false
        val first = if (fromUuid < toUuid) fromUuid to fromName else toUuid to toName
        val second = if (fromUuid < toUuid) toUuid to toName else fromUuid to fromName
        val acc1 = getOrCreateAccount(first.first, first.second)
        val acc2 = getOrCreateAccount(second.first, second.second)
        synchronized(acc1) {
            synchronized(acc2) {
                val senderAcc = if (fromUuid == first.first) acc1 else acc2
                val receiverAcc = if (fromUuid == first.first) acc2 else acc1
                if (senderAcc.balance - amount < minBalance - 1e-6) return false
                senderAcc.balance = roundMoney((senderAcc.balance - amount).coerceAtLeast(minBalance))
                senderAcc.dirty = true
                receiverAcc.balance = roundMoney((receiverAcc.balance + amount).coerceIn(minBalance, maxBalance))
                receiverAcc.dirty = true
            }
        }
        persistSingleAsync(fromUuid, getOrCreateAccount(fromUuid, fromName))
        persistSingleAsync(toUuid, getOrCreateAccount(toUuid, toName))
        return true
    }

    suspend fun getTopBalances(limit: Int): List<EconomyRecord> {
        flushDirty()
        return context.economyRepository.topBalances(limit)
    }

    suspend fun flushDirty(): Int {
        val snapshot = mutableListOf<Pair<UUID, CachedAccount>>()
        val records = mutableListOf<EconomyRecord>()
        val now = System.currentTimeMillis()
        for ((uuid, acc) in balances) {
            synchronized(acc) {
                if (acc.dirty) {
                    snapshot += uuid to acc
                    records += EconomyRecord(uuid, acc.username, acc.balance, now)
                }
            }
        }
        if (records.isEmpty()) return 0
        context.economyRepository.upsertAll(records)
        snapshot.forEach { (_, acc) -> acc.dirty = false }
        return records.size
    }

    fun flushAllBlocking() {
        runBlocking { flushDirty() }
    }

    fun formatMoney(amount: Double): String = "$currencySymbol${decimalFormat.format(roundMoney(amount))}"

    fun createChequeItem(creatorName: String, amount: Double): ItemStack {
        val rounded = roundMoney(amount)
        val item = ItemStack(Material.PAPER, 1)
        val meta = item.itemMeta
        meta.displayName(TextFormatter.format("<gold><bold>Bank Cheque</bold></gold> <gray>(${formatMoney(rounded)})</gray>"))
        meta.lore(
            listOf(
                TextFormatter.format("<gray>Value:</gray> <gold>${formatMoney(rounded)}</gold>"),
                TextFormatter.format("<gray>Issued by:</gray> <white>$creatorName</white>"),
                TextFormatter.format("<yellow>Right-click to redeem</yellow>"),
            )
        )
        meta.persistentDataContainer.set(chequeAmountKey, PersistentDataType.DOUBLE, rounded)
        meta.persistentDataContainer.set(chequeCreatorKey, PersistentDataType.STRING, creatorName)
        item.itemMeta = meta
        return item
    }

    fun readChequeAmount(item: ItemStack?): Double? {
        if (item == null || item.type != Material.PAPER) return null
        val pdc = item.itemMeta?.persistentDataContainer ?: return null
        return pdc.get(chequeAmountKey, PersistentDataType.DOUBLE)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        context.scheduler.asyncScope.launch {
            val loaded = context.economyRepository.findByUuid(player.uniqueId)
            if (loaded != null) {
                val acc = balances.computeIfAbsent(player.uniqueId) {
                    CachedAccount(player.name, loaded.balance, dirty = false)
                }
                if (acc.username != player.name) {
                    acc.username = player.name
                    acc.dirty = true
                }
            } else {
                val acc = balances.computeIfAbsent(player.uniqueId) {
                    CachedAccount(player.name, roundMoney(startingBalance), dirty = true)
                }
                persistSingleAsync(player.uniqueId, acc)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onChequeUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (!event.action.isRightClick) return
        val item = event.item ?: return
        val amount = readChequeAmount(item) ?: return
        event.isCancelled = true
        val player = event.player
        if (!amount.isFinite() || amount <= 0.0) {
            context.locale.send(player, "economy.cheque-invalid")
            return
        }
        if (item.amount > 1) {
            item.amount -= 1
        } else {
            player.inventory.setItemInMainHand(null)
        }
        deposit(player.uniqueId, amount, player.name)
        context.locale.send(player, "economy.cheque-redeemed", "amount" to formatMoney(amount))
    }

    private fun getOrCreateAccount(uuid: UUID, usernameHint: String?): CachedAccount {
        balances[uuid]?.let { existing ->
            if (!usernameHint.isNullOrBlank() && existing.username != usernameHint) {
                existing.username = usernameHint
                existing.dirty = true
            }
            return existing
        }
        val loaded = runBlocking { context.economyRepository.findByUuid(uuid) }
        val resolvedName =
            usernameHint ?: loaded?.username ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)
        val acc = CachedAccount(
            username = resolvedName,
            balance = loaded?.balance ?: roundMoney(startingBalance),
            dirty = loaded == null,
        )
        return balances.putIfAbsent(uuid, acc) ?: acc
    }

    private fun persistSingleAsync(uuid: UUID, account: CachedAccount) {
        val snapshot = EconomyRecord(uuid, account.username, account.balance, System.currentTimeMillis())
        context.scheduler.asyncScope.launch {
            context.economyRepository.upsertAll(listOf(snapshot))
            account.dirty = false
        }
    }

    companion object {
        fun roundMoney(value: Double): Double = (value * 100.0).roundToLong() / 100.0

        /**
         * Parses money amounts such as `500`, `1.5k`, `2m`, `1b`.
         */
        fun parseAmount(raw: String): Double? {
            val cleaned = raw.trim().lowercase().replace(",", "")
            if (cleaned.isEmpty()) return null
            val (numPart, multiplier) = when {
                cleaned.endsWith("k") -> cleaned.dropLast(1) to 1_000.0
                cleaned.endsWith("m") -> cleaned.dropLast(1) to 1_000_000.0
                cleaned.endsWith("b") -> cleaned.dropLast(1) to 1_000_000_000.0
                else -> cleaned to 1.0
            }
            val parsed = numPart.toDoubleOrNull() ?: return null
            if (!parsed.isFinite() || parsed <= 0.0) return null
            return roundMoney(parsed * multiplier)
        }
    }
}
