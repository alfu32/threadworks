package com.threadwork.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class McpServerStatus(
    val running: Boolean,
    val host: String,
    val port: Int,
    val endpoint: String,
    val message: String = "",
) {
    val healthEndpoint: String get() = "http://$host:$port/health"
}

data class McpHealthResult(
    val alive: Boolean,
    val statusCode: Int? = null,
    val message: String = "",
)

class McpServerController(
    private val service: ThreadworkMcpService,
    private val host: String = "127.0.0.1",
    requestedPort: Int = 8765,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    private val statusRef = AtomicReference(McpServerStatus(false, host, requestedPort, endpoint(host, requestedPort)))
    private val listeners = mutableListOf<(McpServerStatus) -> Unit>()
    private val accessLogListeners = mutableListOf<(String) -> Unit>()
    private val accessLog = ArrayDeque<String>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build()
    private var server: HttpServer? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    private var port = requestedPort

    val status: McpServerStatus get() = statusRef.get()

    @Synchronized
    fun start(): McpServerStatus {
        if (server != null) return status
        return try {
            val created = HttpServer.create(InetSocketAddress(host, port), 0)
            created.createContext("/mcp") { exchange -> withAccessLog(exchange) { handleMcp(exchange) } }
            created.createContext("/health") { exchange -> withAccessLog(exchange) { handleHealth(exchange) } }
            created.createContext("/") { exchange -> withAccessLog(exchange) { respond(exchange, 404, "Not Found") } }
            executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "threadwork-mcp-http").apply { isDaemon = true }
            }
            created.executor = executor
            server = created
            created.start()
            port = created.address.port
            publish(McpServerStatus(true, host, port, endpoint(host, port), "MCP server running"))
            status
        } catch (error: IOException) {
            publish(McpServerStatus(false, host, port, endpoint(host, port), "MCP server failed: ${error.message}"))
            status
        }
    }

    @Synchronized
    fun stop(): McpServerStatus {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
        publish(McpServerStatus(false, host, port, endpoint(host, port), "MCP server stopped"))
        return status
    }

    @Synchronized
    fun restart(): McpServerStatus {
        stop()
        return start()
    }

    fun addStatusListener(listener: (McpServerStatus) -> Unit) {
        synchronized(listeners) { listeners += listener }
        listener(status)
    }

    fun removeStatusListener(listener: (McpServerStatus) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    fun addAccessLogListener(listener: (String) -> Unit) {
        synchronized(accessLogListeners) { accessLogListeners += listener }
        listener(accessLogText())
    }

    fun removeAccessLogListener(listener: (String) -> Unit) {
        synchronized(accessLogListeners) { accessLogListeners -= listener }
    }

    fun accessLogText(): String = synchronized(accessLog) { accessLog.joinToString("\n") }

    /** Poll the public health route without blocking the Swing event thread. */
    fun healthAsync(listener: (McpHealthResult) -> Unit) {
        if (!status.running) {
            listener(McpHealthResult(false, message = "MCP server is stopped"))
            return
        }
        val request = HttpRequest.newBuilder(URI(status.healthEndpoint))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .orTimeout(3, TimeUnit.SECONDS)
            .whenComplete { response, error ->
                if (error != null) {
                    listener(McpHealthResult(false, message = error.message ?: "MCP health check failed"))
                } else {
                    listener(
                        McpHealthResult(
                            alive = response.statusCode() == 200,
                            statusCode = response.statusCode(),
                            message = if (response.statusCode() == 200) {
                                "MCP server responded"
                            } else {
                                "MCP health check returned HTTP ${response.statusCode()}"
                            },
                        ),
                    )
                }
            }
    }

    private fun handleMcp(exchange: HttpExchange) {
        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            respond(exchange, 204, "")
            return
        }
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            exchange.responseHeaders.add("Allow", "POST, OPTIONS")
            respond(exchange, 405, "Method Not Allowed")
            return
        }
        val body = exchange.requestBody.use { input -> input.readNBytes(MAX_REQUEST_BYTES + 1) }
        if (body.size > MAX_REQUEST_BYTES) {
            respondJson(exchange, 413, "{\"error\":\"Request too large\"}")
            return
        }
        try {
            val parsed = json.parseToJsonElement(body.toString(StandardCharsets.UTF_8))
            validateModernHeaders(exchange, parsed)
            val response = when (parsed) {
                is JsonObject -> service.handle(parsed)
                is JsonArray -> JsonArray(parsed.mapNotNull { item -> service.handle(item.jsonObject) })
                else -> null
            }
            if (response == null || response is JsonArray && response.isEmpty()) {
                respond(exchange, 202, "")
            } else {
                respondJson(exchange, 200, json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), response))
            }
        } catch (error: IllegalArgumentException) {
            val message = error.message?.replace("\n", " ").orEmpty()
            respondJson(exchange, 400, rpcErrorResponse(null, -32602, message))
        } catch (error: Exception) {
            val message = error.message?.replace("\n", " ").orEmpty()
            respondJson(exchange, 400, rpcErrorResponse(null, -32700, message))
        }
    }

    private fun handleHealth(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            exchange.responseHeaders.add("Allow", "GET")
            respond(exchange, 405, "Method Not Allowed")
            return
        }
        respondJson(exchange, 200, json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), service.health()))
    }

    private fun validateModernHeaders(exchange: HttpExchange, parsed: kotlinx.serialization.json.JsonElement) {
        val request = parsed as? JsonObject ?: return
        val bodyMethod = (request["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val bodyParams = request["params"] as? JsonObject
        val metadata = bodyParams?.get("_meta") as? JsonObject
        val bodyVersion = (metadata?.get("io.modelcontextprotocol/protocolVersion") as? JsonPrimitive)?.contentOrNull
        val headerVersion = exchange.requestHeaders.getFirst("MCP-Protocol-Version")
        val modern = headerVersion == MODERN_PROTOCOL_VERSION || bodyVersion == MODERN_PROTOCOL_VERSION
        if (!modern) return
        require(headerVersion == MODERN_PROTOCOL_VERSION) {
            "Modern MCP requests must include MCP-Protocol-Version: $MODERN_PROTOCOL_VERSION"
        }
        require(exchange.requestHeaders.getFirst("Mcp-Method") == bodyMethod) {
            "Mcp-Method must match the JSON-RPC method"
        }
        val expectedName = when (bodyMethod) {
            "tools/call" -> (bodyParams?.get("name") as? JsonPrimitive)?.contentOrNull
            "resources/read" -> (bodyParams?.get("uri") as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        if (expectedName != null) {
            require(exchange.requestHeaders.getFirst("Mcp-Name") == expectedName) {
                "Mcp-Name must identify the requested tool or resource"
            }
        }
    }

    private fun respondJson(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        respond(exchange, status, body)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.setAttribute(RESPONSE_STATUS_ATTRIBUTE, status)
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private fun withAccessLog(exchange: HttpExchange, action: () -> Unit) {
        val startedAt = System.nanoTime()
        try {
            action()
        } finally {
            val status = exchange.getAttribute(RESPONSE_STATUS_ATTRIBUTE) as? Int ?: 500
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val line = "${Instant.now()} ${exchange.requestMethod} ${exchange.requestURI.path} -> $status ${elapsedMs}ms"
            val snapshot = synchronized(accessLog) {
                accessLog.addLast(line)
                while (accessLog.size > MAX_ACCESS_LOG_ENTRIES) accessLog.removeFirst()
                accessLog.joinToString("\n")
            }
            synchronized(accessLogListeners) { accessLogListeners.toList() }.forEach { listener ->
                runCatching { listener(snapshot) }
            }
        }
    }

    private fun publish(value: McpServerStatus) {
        statusRef.set(value)
        synchronized(listeners) { listeners.toList() }.forEach { listener ->
            runCatching { listener(value) }
        }
    }

    private fun endpoint(host: String, port: Int): String = "http://$host:$port/mcp"

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

    private fun rpcErrorResponse(id: String?, code: Int, message: String): String =
        "{\"jsonrpc\":\"2.0\",\"id\":${id ?: "null"},\"error\":{\"code\":$code,\"message\":\"${escapeJson(message)}\"}}"

    private companion object {
        const val MAX_REQUEST_BYTES = 10 * 1024 * 1024
        const val MAX_ACCESS_LOG_ENTRIES = 500
        const val MODERN_PROTOCOL_VERSION = "2026-07-28"
        const val RESPONSE_STATUS_ATTRIBUTE = "threadwork.mcp.responseStatus"
    }
}
