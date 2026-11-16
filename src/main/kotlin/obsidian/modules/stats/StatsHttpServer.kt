package obsidian.modules.stats

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * Embedded lightweight HTTP REST server exposing `/health`, `/api/stats`, `/api/overview`,
 * and `/api/players/{id}` with RSA-SHA256 request verification and optional `x-api-key` validation.
 */
class StatsHttpServer(
    private val aggregator: StatsAggregatorService,
    private val rsaAuth: RsaAuthService,
    private val logger: Logger? = null,
) {

    @Volatile
    var host: String = "0.0.0.0"

    @Volatile
    var port: Int = 4000

    @Volatile
    var workerThreads: Int = 4

    @Volatile
    var corsOrigin: String = "*"

    @Volatile
    var requireAuth: Boolean = true

    @Volatile
    var apiKey: String = ""

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var startedAtMillis: Long = 0L

    val isRunning: Boolean
        get() = server != null

    val boundPort: Int
        get() = server?.address?.port ?: -1

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        return try {
            val http = HttpServer.create(InetSocketAddress(host, port), 0)
            val threadIdx = AtomicInteger(1)
            val pool = Executors.newFixedThreadPool(workerThreads.coerceAtLeast(2)) { r ->
                Thread(r, "Obsidian-Stats-HTTP-${threadIdx.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }

            http.createContext("/health") { exchange ->
                handle(exchange, authenticate = false) {
                    val body = mapOf(
                        "ok" to true,
                        "plugin" to "Obsidian",
                        "module" to "stats",
                        "uptimeMs" to (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L),
                    )
                    sendJson(exchange, 200, body)
                }
            }

            http.createContext("/api/stats") { exchange ->
                handle(exchange, authenticate = true) {
                    val queryParams = parseQueryParams(exchange.requestURI.rawQuery)
                    val force = queryParams["refresh"]?.equals("true", ignoreCase = true) == true
                    val q = queryParams["q"]
                    val result = aggregator.queryPlayers(q, forceRefresh = force)
                    sendJson(exchange, 200, result)
                }
            }

            http.createContext("/api/overview") { exchange ->
                handle(exchange, authenticate = true) {
                    val queryParams = parseQueryParams(exchange.requestURI.rawQuery)
                    val force = queryParams["refresh"]?.equals("true", ignoreCase = true) == true
                    val snapshot = aggregator.getSnapshot(forceRefresh = force)
                    sendJson(exchange, 200, snapshot.overview)
                }
            }

            http.createContext("/api/players/") { exchange ->
                handle(exchange, authenticate = true) {
                    val path = exchange.requestURI.path
                    val rawId = path.removePrefix("/api/players/").trim().trimEnd('/')
                    val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
                    val queryParams = parseQueryParams(exchange.requestURI.rawQuery)
                    val force = queryParams["refresh"]?.equals("true", ignoreCase = true) == true
                    val player = aggregator.findPlayer(id, forceRefresh = force)
                    if (player == null) {
                        sendJson(exchange, 404, mapOf("error" to "Player not found", "id" to id))
                    } else {
                        val snapshot = aggregator.getSnapshot(forceRefresh = false)
                        sendJson(
                            exchange,
                            200,
                            PlayerDetailResponse(
                                player = player,
                                queriedAt = snapshot.overview.queriedAt,
                                sourceMode = snapshot.overview.sourceMode,
                                meta = snapshot.meta,
                            ),
                        )
                    }
                }
            }

            http.executor = pool
            http.start()
            startedAtMillis = System.currentTimeMillis()
            server = http
            executor = pool
            logger?.info("Stats HTTP API server listening on http://$host:${http.address.port}")
            true
        } catch (ex: IOException) {
            logger?.warning("Failed to bind Stats HTTP server on $host:$port (${ex.message})")
            false
        }
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handle(
        exchange: HttpExchange,
        authenticate: Boolean,
        block: () -> Unit,
    ) {
        try {
            addCorsHeaders(exchange)
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(204, -1L)
                return
            }
            if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
                sendJson(exchange, 405, mapOf("error" to "Method Not Allowed"))
                return
            }
            if (authenticate && !isAuthorized(exchange)) {
                sendJson(exchange, 401, mapOf("error" to "Unauthorized: missing or invalid signature/api-key"))
                return
            }
            block()
        } catch (ex: Throwable) {
            logger?.warning("Stats HTTP error on ${exchange.requestURI}: ${ex.message}")
            runCatching {
                sendJson(exchange, 500, mapOf("error" to (ex.message ?: "Internal Server Error")))
            }
        } finally {
            exchange.close()
        }
    }

    private fun isAuthorized(exchange: HttpExchange): Boolean {
        val configuredKey = apiKey.trim()
        if (configuredKey.isNotEmpty()) {
            val providedKey = exchange.requestHeaders.getFirst("x-api-key")?.trim()
            if (providedKey != configuredKey) return false
        }
        if (!requireAuth) return true

        val sig = exchange.requestHeaders.getFirst("x-api-signature")
        val ts = exchange.requestHeaders.getFirst("x-api-timestamp")
        return rsaAuth.verifySignedRequest(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            timestampHeader = ts,
            signatureHeader = sig,
        )
    }

    private fun addCorsHeaders(exchange: HttpExchange) {
        val headers = exchange.responseHeaders
        headers.set("Access-Control-Allow-Origin", corsOrigin.ifBlank { "*" })
        headers.set("Access-Control-Allow-Methods", "GET, OPTIONS")
        headers.set("Access-Control-Allow-Headers", "Content-Type, x-api-key, x-api-signature, x-api-timestamp")
    }

    private fun sendJson(exchange: HttpExchange, status: Int, payload: Any) {
        val json = StatsJson.GSON.toJson(payload)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { os ->
            os.write(bytes)
            os.flush()
        }
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            val key = URLDecoder.decode(if (idx >= 0) pair.substring(0, idx) else pair, StandardCharsets.UTF_8)
            val value = if (idx >= 0) URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8) else ""
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }
}
