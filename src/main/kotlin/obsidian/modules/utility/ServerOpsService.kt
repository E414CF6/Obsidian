package obsidian.modules.utility

import obsidian.core.ObsidianContext
import obsidian.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerListPingEvent
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import kotlin.math.*

/**
 * Handles server-level operational utilities: dynamic MOTD, max-player cap override,
 * recipe lookup formatting, and recursive-descent math evaluation (`/solve`).
 */
class ServerOpsService(private val context: ObsidianContext) : Listener {

    @Volatile
    var motdOverride: String? = null
        private set

    @Volatile
    var maxPlayersOverride: Int? = null
        private set

    @Volatile
    var donateLines: List<String> = emptyList()
        private set

    init {
        reload()
    }

    fun reload() {
        val raw = context.config.raw
        motdOverride = raw.getString("server-ops.motd")?.takeIf { it.isNotBlank() }
        val maxP = raw.getInt("server-ops.max-players", -1)
        maxPlayersOverride = if (maxP > 0) maxP else null
        donateLines = raw.getStringList("server-ops.donate-lines")
    }

    fun setMotd(motd: String?) {
        motdOverride = motd?.takeIf { it.isNotBlank() }
        context.config.raw.set("server-ops.motd", motdOverride ?: "")
    }

    fun setMaxPlayers(maxPlayers: Int?) {
        maxPlayersOverride = maxPlayers?.takeIf { it > 0 }
        context.config.raw.set("server-ops.max-players", maxPlayersOverride ?: -1)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onServerPing(event: ServerListPingEvent) {
        motdOverride?.let { event.motd(TextFormatter.format(it)) }
        maxPlayersOverride?.let { event.maxPlayers = it }
    }

    fun describeRecipes(stack: ItemStack): List<String> {
        val recipes = Bukkit.getRecipesFor(stack)
        if (recipes.isEmpty()) return emptyList()
        return recipes.take(5).mapIndexed { index, recipe ->
            val num = index + 1
            when (recipe) {
                is ShapedRecipe -> {
                    val map = recipe.choiceMap.entries
                        .filter { it.value != null }
                        .joinToString(", ") { "${it.key}=${it.value?.itemStack?.type?.name ?: "?"}" }
                    "#$num [Shaped] ${recipe.shape.joinToString("/")} ($map)"
                }

                is ShapelessRecipe -> {
                    val items = recipe.choiceList.joinToString(" + ") { it.itemStack.type.name }
                    "#$num [Shapeless] $items"
                }

                is CookingRecipe<*> -> {
                    "#$num [${recipe.javaClass.simpleName}] ${recipe.inputChoice.itemStack.type.name} (${recipe.cookingTime}t)"
                }

                else -> "#$num [${recipe.javaClass.simpleName}] -> ${recipe.result.type.name} x${recipe.result.amount}"
            }
        }
    }

    companion object {
        /**
         * Evaluates a mathematical expression supporting `+ - * / % ^`, parentheses, unary signs,
         * constants (`pi`, `e`), and functions (`sqrt`, `abs`, `sin`, `cos`, `tan`, `log`, `round`, `floor`, `ceil`).
         */
        fun solveMathExpression(input: String): Double {
            val expr = input.trim()
            require(expr.isNotEmpty()) { "Empty expression" }
            val parser = MathParser(expr)
            val result = parser.parseExpression()
            parser.expectEnd()
            require(!result.isNaN() && !result.isInfinite()) { "Invalid numeric result" }
            return result
        }
    }

    private class MathParser(private val s: String) {
        private var pos = 0

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun expectEnd() {
            skipWs()
            if (pos < s.length) throw IllegalArgumentException("Unexpected character '${s[pos]}' at position $pos")
        }

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWs()
                if (pos >= s.length) break
                value = when (s[pos]) {
                    '+' -> {
                        pos++; value + parseTerm()
                    }

                    '-' -> {
                        pos++; value - parseTerm()
                    }

                    else -> break
                }
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parsePower()
            while (true) {
                skipWs()
                if (pos >= s.length) break
                value = when (s[pos]) {
                    '*' -> {
                        pos++; value * parsePower()
                    }

                    '/' -> {
                        pos++
                        val d = parsePower()
                        if (d == 0.0) throw IllegalArgumentException("Division by zero")
                        value / d
                    }

                    '%' -> {
                        pos++; value % parsePower()
                    }

                    else -> break
                }
            }
            return value
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            skipWs()
            return if (pos < s.length && s[pos] == '^') {
                pos++
                base.pow(parsePower())
            } else {
                base
            }
        }

        private fun parseUnary(): Double {
            skipWs()
            if (pos < s.length && s[pos] == '+') {
                pos++
                return parseUnary()
            }
            if (pos < s.length && s[pos] == '-') {
                pos++
                return -parseUnary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (pos >= s.length) throw IllegalArgumentException("Unexpected end of expression")
            if (s[pos] == '(') {
                pos++
                val v = parseExpression()
                skipWs()
                if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("Missing closing parenthesis")
                pos++
                return v
            }
            if (s[pos].isDigit() || s[pos] == '.') {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                return s.substring(start, pos).toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid number near index $start")
            }
            if (s[pos].isLetter()) {
                val start = pos
                while (pos < s.length && s[pos].isLetterOrDigit()) pos++
                val name = s.substring(start, pos).lowercase()
                skipWs()
                if (pos < s.length && s[pos] == '(') {
                    pos++
                    val arg = parseExpression()
                    skipWs()
                    if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("Missing ')' after $name(")
                    pos++
                    return when (name) {
                        "sqrt" -> sqrt(arg)
                        "abs" -> abs(arg)
                        "sin" -> sin(arg)
                        "cos" -> cos(arg)
                        "tan" -> tan(arg)
                        "log", "ln" -> ln(arg)
                        "log10" -> log10(arg)
                        "round" -> round(arg)
                        "floor" -> floor(arg)
                        "ceil" -> ceil(arg)
                        else -> throw IllegalArgumentException("Unknown function '$name'")
                    }
                }
                return when (name) {
                    "pi" -> PI
                    "e" -> E
                    else -> throw IllegalArgumentException("Unknown constant '$name'")
                }
            }
            throw IllegalArgumentException("Unexpected character '${s[pos]}'")
        }
    }
}
