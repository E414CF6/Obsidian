package obsidian.integration

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Method

/**
 * Reflection-safe soft integration with LuckPerms API (when installed) to resolve player prefixes,
 * suffixes, and primary groups without hard compile/runtime class coupling.
 */
class LuckPermsHook {

    private var cachedApi: Any? = null
    private var getPlayerAdapterMethod: Method? = null
    private var adapterGetMetaDataMethod: Method? = null
    private var adapterGetUserMethod: Method? = null
    private var getPrefixMethod: Method? = null
    private var getSuffixMethod: Method? = null
    private var getPrimaryGroupMethod: Method? = null

    fun isAvailable(): Boolean = Bukkit.getPluginManager().isPluginEnabled("LuckPerms")

    fun getPrefix(player: Player): String = resolveMeta(player)?.first ?: ""

    fun getSuffix(player: Player): String = resolveMeta(player)?.second ?: ""

    fun getPrimaryGroup(player: Player): String? {
        if (!isAvailable()) return null
        return runCatching {
            ensureInitialized()
            val adapter = getPlayerAdapter(player) ?: return null
            val user = adapterGetUserMethod?.invoke(adapter, player) ?: return null
            getPrimaryGroupMethod?.invoke(user) as? String
        }.getOrNull()
    }

    private fun resolveMeta(player: Player): Pair<String, String>? {
        if (!isAvailable()) return null
        return runCatching {
            ensureInitialized()
            val adapter = getPlayerAdapter(player) ?: return null
            val metaData = adapterGetMetaDataMethod?.invoke(adapter, player) ?: return null
            val prefix = (getPrefixMethod?.invoke(metaData) as? String) ?: ""
            val suffix = (getSuffixMethod?.invoke(metaData) as? String) ?: ""
            prefix to suffix
        }.getOrNull()
    }

    private fun getPlayerAdapter(player: Player): Any? {
        val api = cachedApi ?: return null
        return getPlayerAdapterMethod?.invoke(api, Player::class.java)
    }

    private fun ensureInitialized() {
        if (cachedApi != null) return
        val providerClass = Class.forName("net.luckperms.api.LuckPermsProvider")
        val api = providerClass.getMethod("get").invoke(null)
        val apiClass = Class.forName("net.luckperms.api.LuckPerms")
        val adapterClass = Class.forName("net.luckperms.api.platform.PlayerAdapter")
        val metaClass = Class.forName("net.luckperms.api.cacheddata.CachedMetaData")
        val userClass = Class.forName("net.luckperms.api.model.user.User")

        getPlayerAdapterMethod = apiClass.getMethod("getPlayerAdapter", Class::class.java)
        adapterGetMetaDataMethod = adapterClass.getMethod("getMetaData", Any::class.java)
        adapterGetUserMethod = adapterClass.getMethod("getUser", Any::class.java)
        getPrefixMethod = metaClass.getMethod("getPrefix")
        getSuffixMethod = metaClass.getMethod("getSuffix")
        getPrimaryGroupMethod = userClass.getMethod("getPrimaryGroup")
        cachedApi = api
    }
}
