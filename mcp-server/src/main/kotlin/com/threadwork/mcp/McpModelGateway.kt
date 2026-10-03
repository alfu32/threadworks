package com.threadwork.mcp

import com.threadwork.core.model.ThreadworkDocument
import com.threadwork.storage.DocumentRepository

/**
 * Boundary between MCP transport code and the mutable desktop model.
 * Implementations decide how reads and writes are serialized with the host UI.
 */
interface McpDocumentGateway {
    fun <T> read(block: (ThreadworkDocument) -> T): T

    fun <T> mutate(label: String, block: (DocumentRepository) -> T): T
}

/** A synchronized gateway used by headless hosts and protocol tests. */
class SynchronizedMcpDocumentGateway(
    private val repository: DocumentRepository,
    private val afterMutation: (String) -> Unit = {},
) : McpDocumentGateway {
    private val lock = Any()

    override fun <T> read(block: (ThreadworkDocument) -> T): T = synchronized(lock) {
        block(repository.getDocument())
    }

    override fun <T> mutate(label: String, block: (DocumentRepository) -> T): T = synchronized(lock) {
        block(repository).also { afterMutation(label) }
    }
}
