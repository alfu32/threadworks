package com.threadwork.mcp

import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.NodeId
import com.threadwork.core.model.TechnologyMetadata
import com.threadwork.core.model.VOID_LAYOUT_STRATEGY_ID
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThreadworkMcpServiceTest {
    private val repository = InMemoryDocumentRepository(newDocument("MCP test"))
    private val service = ThreadworkMcpService(
        gateway = SynchronizedMcpDocumentGateway(repository),
        context = ThreadworkMcpContext(
            applicationVersion = "test",
            technologies = { listOf(McpTechnologyDescriptor("c", "C Compiler", "c", "c-native")) },
            commands = { listOf(McpCommandDescriptor("file.save", "File: Save")) },
        ),
    )

    @Test
    fun `modern discovery and legacy initialization advertise the server`() {
        val discovery = service.handle(request("server/discover", 1, buildJsonObject { }))
        val discoveryResult = assertNotNull(discovery).getValue("result").jsonObject
        assertEquals("complete", discoveryResult.getValue("resultType").jsonPrimitive.content)
        assertTrue(discoveryResult.getValue("supportedVersions").toString().contains("2026-07-28"))
        assertEquals("threadwork", discoveryResult.getValue("_meta").jsonObject
            .getValue("io.modelcontextprotocol/serverInfo").jsonObject.getValue("name").jsonPrimitive.content)

        val initialize = service.handle(request("initialize", 2, buildJsonObject {
            put("protocolVersion", "2025-11-25")
        }))
        assertEquals("2025-11-25", assertNotNull(initialize).getValue("result").jsonObject
            .getValue("protocolVersion").jsonPrimitive.content)
    }

    @Test
    fun `agent can create update link and retrieve JSON slices`() {
        val root = repository.getDocument().rootNodeId.value
        val created = call("threadwork.create_entity", buildJsonObject {
            put("name", "Worker")
            put("kind", NodeKind.Processor.name)
            put("parentId", root)
            putJsonObject("attributes") {
                putJsonObject("metadata") { put("owner", "agent") }
            }
        })
        val workerId = created.getValue("entity").jsonObject.getValue("id").jsonPrimitive.content

        val updated = call("threadwork.update_entity", buildJsonObject {
            put("nodeId", workerId)
            putJsonObject("patch") {
                put("name", "Renamed Worker")
                putJsonObject("text") { put("declaration", "return 1") }
            }
        })
        assertEquals("Renamed Worker", updated.getValue("entity").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("return 1", repository.requireNode(com.threadwork.core.model.NodeId(workerId)).text.declaration)

        val link = call("threadwork.create_link", buildJsonObject {
            put("name", "self")
            put("sourceNodeId", workerId)
            put("sourcePortName", "out")
            put("targetNodeId", workerId)
            put("targetPortName", "in")
        })
        val linkId = link.getValue("entity").jsonObject.getValue("id").jsonPrimitive.content
        assertTrue(repository.requireNode(com.threadwork.core.model.NodeId(linkId)).isLink)

        val fragment = call("threadwork.get_fragment", buildJsonObject {
            put("nodeId", workerId)
            put("includeDescendants", true)
            put("includeLinks", true)
        })
        assertTrue(fragment.getValue("entities").toString().contains(workerId))
        assertTrue(fragment.getValue("entities").toString().contains(linkId))
    }

    @Test
    fun `created MCP entities inherit technology and layout from their parent`() {
        val root = repository.getDocument().rootNodeId.value
        val created = call("threadwork.create_entity", buildJsonObject {
            put("name", "Inherited worker")
            put("kind", NodeKind.Processor.name)
            put("parentId", root)
        })
        val id = created.getValue("entity").jsonObject.getValue("id").jsonPrimitive.content
        val entity = repository.requireNode(NodeId(id))

        assertEquals(TechnologyMetadata(), entity.technology)
        assertEquals(VOID_LAYOUT_STRATEGY_ID, entity.fileLayoutStrategyId)
    }

    @Test
    fun `agent guide is advertised and readable as markdown`() {
        val listed = service.handle(request("resources/list", 3, buildJsonObject { }))
            ?.getValue("result")?.jsonObject
            ?.getValue("resources")?.toString()
        assertTrue(listed.orEmpty().contains(ThreadworkMcpGuide.RESOURCE_URI))

        val read = service.handle(request("resources/read", 4, buildJsonObject {
            put("uri", ThreadworkMcpGuide.RESOURCE_URI)
        }))
        val contents = assertNotNull(read).getValue("result").jsonObject.getValue("contents").toString()
        assertTrue(contents.contains("Threadwork MCP Agent Guide"))
        assertTrue(contents.contains("Operation<T, E> = Success(T) | Failure(E)"))
        assertTrue(contents.contains("processing node should remain thin"))
        assertTrue(contents.contains("text/markdown"))
    }

    @Test
    fun `HTTP endpoint accepts modern routing headers`() {
        val controller = McpServerController(service, requestedPort = 0)
        try {
            val status = controller.start()
            assertTrue(status.running)
            val request = HttpRequest.newBuilder(URI(status.endpoint))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "server/discover")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{}}"""))
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("2026-07-28"))
        } finally {
            controller.stop()
        }
    }

    @Test
    fun `HTTP ping endpoint reports liveness and records access`() {
        val controller = McpServerController(service, requestedPort = 0)
        try {
            val status = controller.start()
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(status.pingEndpoint)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"status\":\"ok\""))
            assertTrue(controller.accessLogText().contains("GET /ping -> 200"))
        } finally {
            controller.stop()
        }
    }

    private fun call(name: String, params: JsonObject): JsonObject {
        val response = service.handle(request("tools/call", 10, buildJsonObject {
            put("name", name)
            put("arguments", params)
        }))
        val result = assertNotNull(response).getValue("result").jsonObject
        assertFalse(result["isError"]?.jsonPrimitive?.content == "true")
        return result.getValue("structuredContent").jsonObject
    }

    private fun request(method: String, id: Int, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }
}
