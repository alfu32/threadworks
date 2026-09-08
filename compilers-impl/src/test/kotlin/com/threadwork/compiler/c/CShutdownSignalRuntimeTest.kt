package com.threadwork.compiler.c

import com.threadwork.compiler.api.CompilerOptions
import com.threadwork.compiler.api.SingleFileLayoutStrategy
import com.threadwork.core.model.TechnologyMetadata
import com.threadwork.core.model.NodeKind
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CShutdownSignalRuntimeTest {
    @Test
    fun `SIGTERM is recorded for model shutdown logic instead of stopping the runner in the handler`() {
        val repository = InMemoryDocumentRepository(newDocument("signal-runtime"))
        val root = repository.getDocument().rootNodeId
        repository.updateNodeTechnology(root, TechnologyMetadata("c", "c-native", "c-compiler"))
        repository.updateNodeFileLayoutStrategy(root, SingleFileLayoutStrategy.id)
        repository.createNode(root, "probe_shutdown_signal", NodeKind.Processor)

        val source = requireNotNull(CCompiler().compile(repository.getDocument(), CompilerOptions()).generatedProject)
            .files.first { it.content.contains("threadwork_shutdown_signal_handler") }.content

        val handler = source.substringAfter("static void threadwork_shutdown_signal_handler")
            .substringBefore("threadwork_error_t threadwork_runner__init")
        assertTrue(handler.contains("shutdown_signal = signal_number;"))
        assertFalse(handler.contains("running = 0;"))
    }
}
