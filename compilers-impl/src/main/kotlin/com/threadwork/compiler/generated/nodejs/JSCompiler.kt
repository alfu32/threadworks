package com.threadwork.compiler.generated.nodejs

import com.threadwork.compiler.api.CompilerOptions
import com.threadwork.compiler.api.CompilerTechnology
import com.threadwork.compiler.api.CompilerTypeConstructor
import com.threadwork.compiler.api.CompilerCodeIntelligence
import com.threadwork.compiler.api.CompilerCodeMember
import com.threadwork.compiler.api.CompilerCodeSymbol
import com.threadwork.compiler.api.CompilerCodeSymbolKind
import com.threadwork.compiler.api.defaultCodeIntelligence
import com.threadwork.compiler.generic.CompilerTemplateSet
import com.threadwork.compiler.generic.CompilerTemplateSetLoader
import com.threadwork.compiler.generic.TemplateSetCompiler
import com.threadwork.core.diagnostics.Diagnostic
import com.threadwork.core.model.Node
import com.threadwork.core.model.ThreadworkDocument
import com.threadwork.core.validation.DocumentValidator

class JSCompiler : TemplateSetCompiler() {
    override val id: String = "nodejs-compiler"
    override val displayName: String = "Node.js CommonJS Compiler"
    override val supportedLanguageIds: Set<String> = setOf("javascript")
    override val supportedTechnologyIds: Set<String> = setOf("nodejs")
    override val primitiveTypeIds: List<String> = listOf(
        "boolean",
        "number",
        "bigint",
        "string",
        "symbol",
        "object",
        "undefined",
        "null",
        "Array",
        "Uint8Array",
    )
    override val typeConstructors: List<CompilerTypeConstructor> = listOf(
        CompilerTypeConstructor("array", "Array", listOf("item"), "Array<{0}>"),
        CompilerTypeConstructor("map", "Map", listOf("key", "value"), "Map<{0}, {1}>"),
    )
    override val providedTechnologies: List<CompilerTechnology> = listOf(CompilerTechnology("javascript", "nodejs"))
    override val magicFileNames: Set<String> = TEMPLATES.staticFileNames

    override fun supports(document: ThreadworkDocument): Boolean = true

    override fun validate(document: ThreadworkDocument): List<Diagnostic> =
        DocumentValidator.validate(document, primitiveTypeIds, typeConstructors.map { it.id })

    override fun codeIntelligence(document: ThreadworkDocument, node: Node): CompilerCodeIntelligence {
        val defaults = defaultCodeIntelligence(document, node)
        val runtimeSymbols = listOf(
            runtimeSymbol("ThreadworkRunner", "Node.js application execution state", RUNNER_METHODS),
            runtimeSymbol("threadworkRunner", "global Node.js application runner", RUNNER_METHODS),
        )
        return defaults.copy(
            symbols = (defaults.symbols + runtimeSymbols).distinctBy { it.name to it.kind },
        )
    }

    override fun templatesFor(document: ThreadworkDocument, options: CompilerOptions): CompilerTemplateSet =
        TEMPLATES

    private fun runtimeSymbol(
        name: String,
        detail: String,
        members: List<CompilerCodeMember> = emptyList(),
    ): CompilerCodeSymbol =
        CompilerCodeSymbol(
            name = name,
            kind = CompilerCodeSymbolKind.RuntimeSymbol,
            detail = detail,
            documentation = "Node.js Threadwork runtime helper.",
            members = members,
        )

    private companion object {
        val RUNNER_METHODS = listOf(
            CompilerCodeMember("shutdownRequest()", "void", "Request shutdown."),
            CompilerCodeMember("getShutdownSignal()", "number", "Return the last SIGINT or SIGTERM number, or zero."),
            CompilerCodeMember("isRunning()", "boolean", "Return the requested running state."),
            CompilerCodeMember("recordTransit()", "void", "Record a completed modeled transport."),
            CompilerCodeMember("beginShutdownDrain(idleTicks)", "void", "Begin bounded idle-window draining."),
            CompilerCodeMember("hasRecentTransit()", "boolean", "Return whether transit or the drain window remains."),
            CompilerCodeMember("installShutdownHandlers()", "void", "Install SIGINT and SIGTERM handlers."),
        )
        val TEMPLATES = CompilerTemplateSetLoader.load("/compiler-templates/nodejs/compiler.properties")
    }
}
