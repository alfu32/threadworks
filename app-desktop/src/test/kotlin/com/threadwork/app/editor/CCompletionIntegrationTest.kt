package com.threadwork.app.editor

import com.threadwork.compiler.c.CCompiler
import com.threadwork.compiler.php.PhpCompiler
import com.threadwork.compiler.quickjs.QuickJsCompiler
import com.threadwork.compiler.naivekotlin.NaiveKotlinCompiler
import com.threadwork.completion.CompletionRequest
import com.threadwork.completion.AnalysisResult
import com.threadwork.completion.CodeHoverInfo
import com.threadwork.completion.CodeLocation
import com.threadwork.completion.DeclarationSymbolOrigin
import com.threadwork.completion.ModelAwareCompletionService
import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.NodeTextSection
import com.threadwork.core.model.TechnologyMetadata
import com.threadwork.core.model.LinkInteractionKinds
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CCompletionIntegrationTest {
    @Test
    fun `shared runtime indexing preserves object oriented runner completions`() {
        val compilers = listOf(PhpCompiler(), QuickJsCompiler(), NaiveKotlinCompiler())
        for (compiler in compilers) {
            val technology = compiler.providedTechnologies.first()
            val repository = InMemoryDocumentRepository(newDocument("runtime-intelligence"))
            val root = repository.getDocument().rootNodeId
            repository.updateNodeTechnology(root, TechnologyMetadata(
                languageId = technology.languageId, technologyId = technology.technologyId, compilerId = compiler.id,
            ))
            val worker = repository.createNode(root, "worker", NodeKind.Processor)
            val service = ModelAwareCompletionService(repository::getDocument, { _, _ -> compiler })
            val source = if (compiler is PhpCompiler) "threadwork_runner()->" else "threadworkRunner."
            val request = CompletionRequest(worker.id, NodeTextSection.Declaration, technology.languageId,
                technology.technologyId, source.length, source, source, "")
            val labels = service.getSuggestions(request).map { it.label }
            assertTrue("shutdownRequest" in labels, "${compiler.id}: $labels")
            assertTrue("getShutdownSignal" in labels, "${compiler.id}: $labels")
            assertTrue("isRunning" in labels, "${compiler.id}: $labels")
        }
    }

    @Test
    fun `runtime source supplies completions fields and signatures without editor locations`() {
        val repository = InMemoryDocumentRepository(newDocument("runtime-intelligence"))
        val root = repository.getDocument().rootNodeId
        repository.updateNodeTechnology(root, TechnologyMetadata(languageId = "c", technologyId = "c-native", compilerId = "c-compiler"))
        val worker = repository.createNode(root, "worker", NodeKind.Processor)
        val compiler = CCompiler()
        val service = ModelAwareCompletionService(repository::getDocument, { _, _ -> compiler })
        fun request(source: String, prefix: String = "") = CompletionRequest(
            worker.id, NodeTextSection.Declaration, "c", "c-native", source.length, source, source.substringAfterLast('\n'), prefix,
        )

        val suggestions = service.getSuggestions(request("threadwork_", "threadwork_"))
        for (name in listOf("threadwork_runner", "threadwork_context", "threadwork_runner_t", "threadwork_runner__init",
            "threadwork_runner__destroy", "threadwork_runner__install_shutdown_signal_handlers", "threadwork_runner__record_transit",
            "threadwork_runner__begin_shutdown_drain", "threadwork_runner__has_recent_transit", "threadwork_buffer_push")) {
            assertTrue(suggestions.any { it.label == name }, "Missing $name: ${suggestions.map { it.label }}")
        }
        assertTrue(service.getSuggestions(request("THREADWORK_", "THREADWORK_")).any { it.label == "THREADWORK_OK" })
        val declarations = assertIs<AnalysisResult.Available<List<com.threadwork.completion.DeclarationSymbol>>>(
            service.analysis(request("threadwork_", "threadwork_")).declarations(),
        ).value
        assertTrue(declarations.any { it.name == "threadwork_runner" && it.origin == DeclarationSymbolOrigin.Runtime })
        for (source in listOf("threadwork_runner.", "threadwork_runner_t *runner;\nrunner->")) {
            val fields = service.getSuggestions(request(source)).map { it.label }
            assertTrue("running" in fields, fields.toString())
            assertTrue("shutdown_signal" in fields, fields.toString())
            assertTrue("transit" in fields, fields.toString())
        }
        val source = "threadwork_runner__get_shutdown_signal(&threadwork_runner, &signal);"
        val analysis = service.analysis(request(source))
        val hover = assertIs<AnalysisResult.Available<CodeHoverInfo?>>(analysis.hover(5)).value
        assertNotNull(hover)
        assertTrue(hover.title.contains("threadwork_error_t threadwork_runner__get_shutdown_signal("), hover.title)
        assertTrue(hover.title.contains("threadwork_runner_t *this"), hover.title)
        assertTrue(hover.body.contains("@brief"), hover.body)
        assertNull(assertIs<AnalysisResult.Available<CodeLocation?>>(analysis.definition(5)).value)
        assertFalse(suggestions.any { it.label == "recent_transit" || it.label == "this" })
    }

    @Test
    fun `runtime override edits invalidate runtime completion index`() {
        val repository = InMemoryDocumentRepository(newDocument("runtime-override"))
        val root = repository.getDocument().rootNodeId
        repository.updateNodeTechnology(root, TechnologyMetadata(languageId = "c", technologyId = "c-native", compilerId = "c-compiler"))
        val worker = repository.createNode(root, "worker", NodeKind.Processor)
        val override = repository.createNode(root, "@RuntimeSupport", NodeKind.Processor)
        val service = ModelAwareCompletionService(repository::getDocument, { _, _ -> CCompiler() })
        fun labels(): List<String> = service.getSuggestions(CompletionRequest(
            worker.id, NodeTextSection.Declaration, "c", "c-native", 10, "threadwork_", "threadwork_", "threadwork_",
        )).map { it.label }
        repository.updateNodeText(override.id, override.text.copy(declaration = "int threadwork_custom_one(void) { return 1; }"))
        assertTrue("threadwork_custom_one" in labels())
        repository.updateNodeText(override.id, override.text.copy(declaration = "int threadwork_custom_two(void) { return 2; }"))
        assertTrue("threadwork_custom_two" in labels())
        assertFalse("threadwork_custom_one" in labels())
    }

    @Test
    fun `composite completion exposes direct child C lifecycle functions`() {
        val repository = InMemoryDocumentRepository(newDocument("ticker"))
        val root = repository.getDocument().rootNodeId
        repository.updateNodeTechnology(
            root,
            TechnologyMetadata(languageId = "c", technologyId = "c-native", compilerId = "c-compiler"),
        )
        repository.createNode(root, "read_time", NodeKind.Processor)
        repository.createNode(root, "write_file", NodeKind.Processor)

        val labels = ModelAwareCompletionService(
            documentProvider = repository::getDocument,
            compilerProvider = { _, _ -> CCompiler() },
        ).getSuggestions(
            CompletionRequest(
                nodeId = root,
                textSection = NodeTextSection.Declaration,
                languageId = "c",
                technologyId = "c-native",
                cursorOffset = 0,
                fullText = "",
                currentLine = "",
                prefix = "",
            ),
        ).map { it.label }

        assertTrue(labels.any { it.startsWith("tw_init_read_time_") })
        assertTrue(labels.any { it.startsWith("tw_run_read_time_") })
        assertTrue(labels.any { it.startsWith("tw_init_write_file_") })
        assertTrue(labels.any { it.startsWith("tw_run_write_file_") })
    }

    @Test
    fun `library dependency links default to dependency injection and expose functions to libraries`() {
        val repository = InMemoryDocumentRepository(newDocument("ticker"))
        val root = repository.getDocument().rootNodeId
        repository.updateNodeTechnology(
            root,
            TechnologyMetadata(languageId = "c", technologyId = "c-native", compilerId = "c-compiler"),
        )
        val provider = repository.createNode(root, "lib_math", NodeKind.Processor)
        repository.updateNodeText(
            provider.id,
            repository.requireNode(provider.id).text.copy(
                declaration = "int maximum(int left, int right) { return left > right ? left : right; }",
            ),
        )
        val consumer = repository.createNode(root, "lib_metrics", NodeKind.Processor)
        val dependency = repository.createLink(root, "math_service", provider.id, "out", consumer.id, "in")
        val worker = repository.createNode(root, "calculate_metrics", NodeKind.Processor)
        val reverseDependency = repository.createLink(root, "metrics_input", worker.id, "out", consumer.id, "in")

        assertEquals(LinkInteractionKinds.Library, dependency.link?.interactionKind)
        assertEquals(LinkInteractionKinds.Library, reverseDependency.link?.interactionKind)
        assertTrue(
            CCompiler().codeIntelligence(repository.getDocument(), consumer).symbols.any {
                it.name == "math_service__maximum"
            },
        )
    }

    @Test
    fun `C completion preserves snake case link names end to end`() {
        val repository = InMemoryDocumentRepository(newDocument("ticker"))
        val root = repository.getDocument().rootNodeId
        repository.updateNodeTechnology(
            root,
            TechnologyMetadata(languageId = "c", technologyId = "c-native", compilerId = "c-compiler"),
        )
        val reader = repository.createNode(root, "read_time", NodeKind.Processor)
        val worker = repository.createNode(root, "gen_uuid", NodeKind.Processor)
        val writer = repository.createNode(root, "gen_text", NodeKind.Processor)
        repository.createLink(root, "ip_time", reader.id, "out", worker.id, "in")
        repository.createLink(root, "ip_uuid", worker.id, "out", writer.id, "in")

        val service = ModelAwareCompletionService(
            documentProvider = repository::getDocument,
            compilerProvider = { _, _ -> CCompiler() },
        )
        val labels = service.getSuggestions(
            CompletionRequest(
                nodeId = worker.id,
                textSection = NodeTextSection.Declaration,
                languageId = "c",
                technologyId = "c-native",
                cursorOffset = 0,
                fullText = "",
                currentLine = "",
                prefix = "",
            ),
        ).mapTo(linkedSetOf()) { it.label }

        assertTrue("ip_time" in labels)
        assertTrue("ip_uuid" in labels)
        assertTrue("pop(ip_time, &item)" in labels)
        assertTrue("push(ip_uuid, &item)" in labels)
        assertFalse("ipTime" in labels)
        assertFalse("ipUuid" in labels)
    }
}
