package com.threadwork.compiler.quickjs

import com.threadwork.compiler.api.CompilerOptions
import com.threadwork.compiler.api.CompilerTechnology
import com.threadwork.compiler.api.CompilerCodeIntelligence
import com.threadwork.compiler.api.CompilerCodeMember
import com.threadwork.compiler.api.CompilerCodeSymbol
import com.threadwork.compiler.api.CompilerCodeSymbolKind
import com.threadwork.compiler.api.SingleFileLayoutStrategy
import com.threadwork.compiler.api.defaultCodeIntelligence
import com.threadwork.compiler.generic.CompilerTemplateSet
import com.threadwork.compiler.generic.CompilerTemplateSetLoader
import com.threadwork.compiler.generic.TemplateSetCompiler
import com.threadwork.compiler.generic.compilerTemplateOverrides
import com.threadwork.core.diagnostics.Diagnostic
import com.threadwork.core.model.Node
import com.threadwork.core.model.ThreadworkDocument
import com.threadwork.core.model.BuiltInTypeIds
import com.threadwork.core.validation.DocumentValidator

/** Generates portable JavaScript intended for the QuickJS command-line runtime. */
class QuickJsCompiler : TemplateSetCompiler() {
    override val id: String = "quickjs-compiler"
    override val displayName: String = "QuickJS JavaScript Compiler"
    override val supportedLanguageIds: Set<String> = setOf("javascript")
    override val supportedTechnologyIds: Set<String> = setOf("quickjs")
    override val primitiveTypeIds: List<String> = listOf(
        BuiltInTypeIds.Boolean,
        BuiltInTypeIds.Number,
        BuiltInTypeIds.Date,
        BuiltInTypeIds.String,
        BuiltInTypeIds.Array,
    )
    override val providedTechnologies: List<CompilerTechnology> = listOf(
        CompilerTechnology("javascript", "quickjs"),
    )
    override val supportedLayoutStrategyIds: Set<String> = setOf(SingleFileLayoutStrategy.id)
    override val magicFileNames: Set<String> = TEMPLATES.staticFileNames

    override fun supports(document: ThreadworkDocument): Boolean = true

    override fun validate(document: ThreadworkDocument): List<Diagnostic> =
        DocumentValidator.validate(document)

    override fun codeIntelligence(document: ThreadworkDocument, node: Node): CompilerCodeIntelligence {
        val defaults = defaultCodeIntelligence(document, node)
        val runtimeSymbols = listOf(
            runtimeSymbol("createRuntimeContext", "create QuickJS execution context"),
            runtimeSymbol("ThreadworkRunner", "QuickJS application execution state", RUNNER_METHODS),
            runtimeSymbol("threadworkRunner", "global QuickJS application runner", RUNNER_METHODS),
        )
        return defaults.copy(
            symbols = (defaults.symbols + runtimeSymbols).distinctBy { it.name to it.kind },
        )
    }

    override fun templatesFor(document: ThreadworkDocument, options: CompilerOptions): CompilerTemplateSet {
        val overrides = compilerTemplateOverrides(document)
        return TEMPLATES.overlay(
            CompilerTemplateSet(
                templates = overrides.templates,
                projectFiles = overrides.projectFiles,
            ),
        )
    }

    private fun runtimeSymbol(
        name: String,
        detail: String,
        members: List<CompilerCodeMember> = emptyList(),
    ): CompilerCodeSymbol =
        CompilerCodeSymbol(
            name = name,
            kind = CompilerCodeSymbolKind.RuntimeSymbol,
            detail = detail,
            documentation = if (name == "threadworkRunner" || name == "ThreadworkRunner") {
                "QuickJS does not expose OS signal numbers to this generated script, so getShutdownSignal() returns zero."
            } else {
                "QuickJS Threadwork runtime helper."
            },
            members = members,
        )

    private companion object {
        val RUNNER_METHODS = listOf(
            CompilerCodeMember("shutdownRequest()", "void", "Request shutdown."),
            CompilerCodeMember("getShutdownSignal()", "number", "Return zero; QuickJS does not expose OS signal numbers."),
            CompilerCodeMember("isRunning()", "boolean", "Return the requested running state."),
            CompilerCodeMember("recordTransit()", "void", "Record a completed modeled transport."),
            CompilerCodeMember("beginShutdownDrain(idleTicks)", "void", "Begin bounded idle-window draining."),
            CompilerCodeMember("hasRecentTransit()", "boolean", "Return whether transit or the drain window remains."),
        )
        val TEMPLATES = CompilerTemplateSetLoader.load("/compiler-templates/quickjs/compiler.properties")
    }
}
