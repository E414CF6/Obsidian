package obsidian.modules.automation

import obsidian.core.ObsidianContext
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Filters noisy or sensitive server console lines matching configured regex patterns
 * via Log4j2 reflection (`console-filter.patterns`).
 */
class ConsoleFilterService(private val context: ObsidianContext) {

    @Volatile
    var enabled: Boolean = true
        private set

    private val compiledPatterns = CopyOnWriteArrayList<Regex>()

    @Volatile
    private var attachedFilter: Any? = null

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        enabled = raw.getBoolean("console-filter.enabled", true)
        compiledPatterns.clear()
        for (pattern in raw.getStringList("console-filter.patterns")) {
            if (pattern.isBlank()) continue
            runCatching { Regex(pattern) }
                .onSuccess { compiledPatterns.add(it) }
                .onFailure {
                    context.plugin.logger.warning("Invalid console-filter regex '$pattern': ${it.message}")
                }
        }
    }

    fun start() {
        if (attachedFilter != null) return
        attachLog4jFilter()
    }

    fun shutdown() {
        detachLog4jFilter()
    }

    fun shouldFilter(message: String?): Boolean {
        if (!enabled || message.isNullOrEmpty() || compiledPatterns.isEmpty()) return false
        return matchesAny(message, compiledPatterns)
    }

    private fun attachLog4jFilter() {
        runCatching {
            val logManagerClass = Class.forName("org.apache.logging.log4j.LogManager")
            val coreLoggerClass = Class.forName("org.apache.logging.log4j.core.Logger")
            val filterClass = Class.forName("org.apache.logging.log4j.core.Filter")
            val resultClass = Class.forName("org.apache.logging.log4j.core.Filter\$Result")
            val stateClass = Class.forName("org.apache.logging.log4j.core.LifeCycle\$State")

            val rootLogger = logManagerClass.getMethod("getRootLogger").invoke(null) ?: return
            if (!coreLoggerClass.isInstance(rootLogger)) return

            val deny = java.lang.Enum.valueOf(resultClass.asSubclass(Enum::class.java), "DENY")
            val neutral = java.lang.Enum.valueOf(resultClass.asSubclass(Enum::class.java), "NEUTRAL")
            val startedState = java.lang.Enum.valueOf(stateClass.asSubclass(Enum::class.java), "STARTED")

            val proxy = Proxy.newProxyInstance(
                filterClass.classLoader,
                arrayOf(filterClass),
            ) { _, method, args ->
                when (method.name) {
                    "getOnMismatch", "getOnMatch" -> neutral
                    "getState" -> startedState
                    "isStarted" -> true
                    "isStopped" -> false
                    "start", "stop", "initialize" -> null
                    "filter" -> {
                        val text = extractMessageText(args)
                        if (shouldFilter(text)) deny else neutral
                    }
                    "equals" -> false
                    "hashCode" -> System.identityHashCode(this)
                    "toString" -> "ObsidianConsoleFilter"
                    else -> null
                }
            }

            coreLoggerClass.getMethod("addFilter", filterClass).invoke(rootLogger, proxy)
            attachedFilter = proxy
        }.onFailure { err ->
            context.plugin.logger.fine("ConsoleFilter Log4j hook skipped: ${err.message}")
        }
    }

    private fun detachLog4jFilter() {
        val filter = attachedFilter ?: return
        attachedFilter = null
        runCatching {
            val logManagerClass = Class.forName("org.apache.logging.log4j.LogManager")
            val coreLoggerClass = Class.forName("org.apache.logging.log4j.core.Logger")
            val filterClass = Class.forName("org.apache.logging.log4j.core.Filter")
            val rootLogger = logManagerClass.getMethod("getRootLogger").invoke(null) ?: return
            if (!coreLoggerClass.isInstance(rootLogger)) return
            val removeMethod = coreLoggerClass.methods.firstOrNull {
                it.name == "removeFilter" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(filterClass)
            }
            removeMethod?.invoke(rootLogger, filter)
        }
    }

    private fun extractMessageText(args: Array<out Any?>?): String? {
        if (args.isNullOrEmpty()) return null
        if (args.size == 1 && args[0] != null) {
            val event = args[0]!!
            val msgObj = runCatching { event.javaClass.getMethod("getMessage").invoke(event) }.getOrNull()
            return runCatching {
                msgObj?.javaClass?.getMethod("getFormattedMessage")?.invoke(msgObj)?.toString()
            }.getOrNull() ?: msgObj?.toString()
        }
        // Overloads like filter(Logger, Level, Marker, Message/String/Object, ...)
        val msgArg = args.getOrNull(3) ?: return null
        return runCatching {
            msgArg.javaClass.getMethod("getFormattedMessage").invoke(msgArg)?.toString()
        }.getOrNull() ?: msgArg.toString()
    }

    companion object {
        fun matchesAny(message: String, patterns: List<Regex>): Boolean =
            patterns.any { it.containsMatchIn(message) }
    }
}
