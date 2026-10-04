package com.threadwork.mcp

/** Shared Markdown guide for agents working through the Threadwork MCP surface. */
object ThreadworkMcpGuide {
    const val RESOURCE_URI = "threadwork://agent-guide"

    fun markdown(): String = ThreadworkMcpGuide::class.java
        .getResourceAsStream("/threadwork-mcp-agent-guide.md")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("Threadwork MCP agent guide resource is missing")
}
