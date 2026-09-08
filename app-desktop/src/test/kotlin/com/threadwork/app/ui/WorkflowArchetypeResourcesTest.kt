package com.threadwork.app.ui

import com.threadwork.storage.KotlinxJsonDocumentStore
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.NodePort
import com.threadwork.core.model.PortDirection
import com.threadwork.core.model.TypeDefinition
import com.threadwork.core.model.TypeFieldDefinition
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowArchetypeResourcesTest {
    @Test
    fun `selected topology archetype retains internal links and their types`() {
        val repository = InMemoryDocumentRepository(newDocument("Archetype Test"))
        val root = repository.getDocument().rootNodeId
        val packet = repository.createNode(root, "Packet", NodeKind.Type)
        repository.updateNodeTypeDefinition(
            packet.id,
            TypeDefinition(mutableListOf(TypeFieldDefinition("id", "string"))),
        )
        val source = repository.createNode(root, "source", NodeKind.Processor)
        val target = repository.createNode(root, "target", NodeKind.Processor)
        val omitted = repository.createNode(root, "omitted", NodeKind.Processor)
        repository.addPort(source.id, NodePort("out", "out", PortDirection.Output))
        repository.addPort(target.id, NodePort("in", "in", PortDirection.Input))
        val link = repository.createLink(root, "packet", source.id, "out", target.id, "in")
        repository.updateLinkData(link.id, requireNotNull(link.link).copy(typeDefinitionId = packet.id.value))

        val snapshot = archetypeSnapshot(repository.getDocument(), linkedSetOf(source.id, target.id))

        assertTrue(snapshot.nodes.containsKey(source.id))
        assertTrue(snapshot.nodes.containsKey(target.id))
        assertTrue(snapshot.nodes.containsKey(packet.id))
        assertTrue(snapshot.nodes.containsKey(link.id))
        assertTrue(snapshot.nodes.containsKey(omitted.id).not())
        assertEquals(source.id, snapshot.nodes.getValue(link.id).link?.sourceNodeId)
        assertEquals(target.id, snapshot.nodes.getValue(link.id).link?.targetNodeId)
        assertEquals(packet.id.value, snapshot.nodes.getValue(link.id).link?.typeDefinitionId)
    }

    @Test
    fun `bundled workflow archetypes are grouped valid documents`() {
        val store = KotlinxJsonDocumentStore()
        val workflowResources = listOf(
            "/workflow-archetypes/integration/request-response.orch",
            "/workflow-archetypes/quality/validation-pipeline.orch",
        )

        workflowResources.forEach { path ->
            val source = requireNotNull(javaClass.getResourceAsStream(path))
                .bufferedReader()
                .use { it.readText() }
            val document = store.loadText(source)
            assertTrue(document.nodes.values.any { it.isLink })
            assertTrue(document.nodes.values.filterNot { it.isLink }.all { it.text.declaration.isBlank() })
            assertTrue(
                document.nodes.values
                    .filter { it.id != document.rootNodeId && !it.isLink }
                    .all { it.text.specification.isNotBlank() },
            )
        }

        val shutdownResources = listOf(
            "/workflow-archetypes/runtime/shutdown-signal-c.orch" to "threadwork_runner__get_shutdown_signal",
            "/workflow-archetypes/runtime/shutdown-signal-php.orch" to "getShutdownSignal",
            "/workflow-archetypes/runtime/shutdown-signal-javascript.orch" to "getShutdownSignal",
            "/workflow-archetypes/runtime/shutdown-signal-python.orch" to "get_shutdown_signal",
            "/workflow-archetypes/runtime/shutdown-signal-go.orch" to "GetShutdownSignal",
        )
        shutdownResources.forEach { (path, signalProbe) ->
            val document = store.loadText(requireNotNull(javaClass.getResourceAsStream(path))
                .bufferedReader().use { it.readText() })
            val probe = document.nodes.values.single { it.name == "probe_shutdown_signal" }
            assertTrue(probe.text.declaration.contains(signalProbe))
            assertTrue(probe.text.declaration.contains("shutdown", ignoreCase = true))
            assertTrue(probe.text.specification.contains("orderly network shutdown"))
        }

        val starterLanguages = listOf("c", "php", "javascript", "python", "go")
        val starterResources = starterLanguages.flatMap { language ->
            listOf("http-server", "file-queue", "config-reader", "http-reader", "http-writer")
                .map { archetype -> "/workflow-archetypes/$language/$archetype.orch" }
        }
        starterResources.forEach { path ->
            val document = store.loadText(requireNotNull(javaClass.getResourceAsStream(path))
                .bufferedReader().use { it.readText() })
            assertTrue(document.nodes.values.filter { it.id != document.rootNodeId }.all {
                it.text.specification.isNotBlank()
            })
        }
        starterLanguages.forEach { language ->
            val document = store.loadText(requireNotNull(javaClass.getResourceAsStream(
                "/workflow-archetypes/$language/http-server.orch",
            )).bufferedReader().use { it.readText() })
            val server = document.nodes.values.single {
                it.id != document.rootNodeId && it.name == "http_server"
            }
            assertEquals(
                mapOf("request" to PortDirection.Output, "response" to PortDirection.Input),
                server.ports.associate { it.name to it.direction },
            )
            assertTrue(server.ports.all { it.dataType == "HttpExchange" })
            assertTrue(server.text.specification.contains("exactly these two data ports"))
            assertTrue(server.text.specification.contains("`id`, `request_text`, and `response_text`"))
            assertTrue(server.text.specification.contains("SIGTERM"))
            assertTrue(document.nodes.values.any { it.name == "http_request_registry_lib" })

            val fileQueue = starterNode(store, language, "file-queue", "file_queue")
            assertEquals(
                mapOf(
                    "config" to PortDirection.Input,
                    "read" to PortDirection.Output,
                    "response" to PortDirection.Input,
                ),
                fileQueue.ports.associate { it.name to it.direction },
            )
            assertTrue(fileQueue.text.specification.contains("`in`, `out`, and `err`"))
            assertTrue(fileQueue.text.specification.contains("`id`, `filename`, and `content`"))

            val configReader = starterNode(store, language, "config-reader", "config_reader_lib")
            assertTrue(configReader.text.specification.contains("caller-supplied file path"))

            val httpReader = starterNode(store, language, "http-reader", "http_reader")
            assertEquals(
                mapOf("config" to PortDirection.Input, "result" to PortDirection.Output),
                httpReader.ports.associate { it.name to it.direction },
            )
            assertTrue(httpReader.text.specification.contains("`method`, `url`, `params`, and `headers`"))

            val httpWriter = starterNode(store, language, "http-writer", "http_writer")
            assertEquals(
                mapOf("config" to PortDirection.Input, "request" to PortDirection.Input),
                httpWriter.ports.associate { it.name to it.direction },
            )
            assertTrue(httpWriter.text.specification.contains("`method`, `url`, and `headers`"))
        }

        val catalogRows = requireNotNull(javaClass.getResourceAsStream("/workflow-archetypes/catalog.tsv"))
            .bufferedReader()
            .useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith('#') }.toList() }
        assertEquals(workflowResources.size + shutdownResources.size + starterResources.size, catalogRows.size)
        assertTrue(catalogRows.all { row -> row.substringAfterLast('\t').count { it == '/' } == 1 })
    }

    private fun starterNode(
        store: KotlinxJsonDocumentStore,
        language: String,
        resource: String,
        name: String,
    ) = store.loadText(requireNotNull(javaClass.getResourceAsStream(
        "/workflow-archetypes/$language/$resource.orch",
    )).bufferedReader().use { it.readText() }).let { document ->
        document.nodes.values.single { it.id != document.rootNodeId && it.name == name }
    }

    @Test
    fun `user archetypes are discovered from immediate folders beside bundled archetypes`() {
        val store = KotlinxJsonDocumentStore()
        val userFolder = createTempDirectory("threadwork-user-archetypes")
        val customGroup = Files.createDirectories(userFolder.resolve("custom-flows"))
        val source = requireNotNull(
            javaClass.getResourceAsStream("/workflow-archetypes/integration/request-response.orch"),
        ).bufferedReader().use { it.readText() }
        Files.writeString(customGroup.resolve("custom-request.orch"), source)

        val archetypes = loadWorkflowArchetypes(store, userFolder)

        assertEquals(33, archetypes.size)
        assertEquals(32, archetypes.count { !it.id.startsWith("user:") })
        val custom = archetypes.single { it.id == "user:custom-flows/custom-request.orch" }
        assertEquals("custom-flows", custom.group)
        assertEquals("Request Response Template", custom.label)
        assertTrue(custom.description.startsWith("Coordinates one request"))
    }

    @Test
    fun `archetype filenames are safe without changing model names`() {
        assertEquals("Order-processing-v2", archetypeFileStem("Order processing / v2"))
        assertEquals("archetype", archetypeFileStem(" / "))
    }
}
