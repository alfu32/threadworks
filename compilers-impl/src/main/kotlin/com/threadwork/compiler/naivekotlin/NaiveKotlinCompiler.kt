package com.threadwork.compiler.naivekotlin

import com.threadwork.compiler.api.CompilerOptions
import com.threadwork.compiler.api.CompilerTechnology
import com.threadwork.compiler.api.CompilerCodeIntelligence
import com.threadwork.compiler.api.CompilerCodeMember
import com.threadwork.compiler.api.CompilerCodeSymbol
import com.threadwork.compiler.api.CompilerCodeSymbolKind
import com.threadwork.compiler.api.defaultCodeIntelligence
import com.threadwork.compiler.generic.CompilerTemplateSet
import com.threadwork.compiler.generic.CompilerTemplateSetLoader
import com.threadwork.compiler.generic.TemplateSetCompiler
import com.threadwork.core.diagnostics.Diagnostic
import com.threadwork.core.diagnostics.DiagnosticSeverity
import com.threadwork.core.classification.LinkClassifier
import com.threadwork.core.classification.LinkStereotype
import com.threadwork.core.model.ThreadworkDocument
import com.threadwork.core.model.Node
import com.threadwork.core.model.effectiveTechnologyId
import com.threadwork.core.validation.DocumentValidator

class NaiveKotlinCompiler : TemplateSetCompiler() {
    override val id: String = "naive-kotlin"
    override val displayName: String = "Naive Kotlin/JVM Compiler"
    override val supportedLanguageIds: Set<String> = setOf("kotlin")
    override val supportedTechnologyIds: Set<String> = setOf("kotlin-jvm")
    override val primitiveTypeIds: List<String> = listOf(
        "Boolean",
        "Byte",
        "UByte",
        "Short",
        "UShort",
        "Int",
        "UInt",
        "Long",
        "ULong",
        "Float",
        "Double",
        "Char",
        "String",
        "ByteArray",
        "IntArray",
        "LongArray",
        "FloatArray",
        "DoubleArray",
        "BooleanArray",
        "Array<Any>",
        "Any",
        "Unit",
    )
    override val providedTechnologies: List<CompilerTechnology> = listOf(CompilerTechnology("kotlin", "kotlin-jvm"))
    override val magicFileNames: Set<String> = TEMPLATES.staticFileNames

    override fun supports(document: ThreadworkDocument): Boolean = true

    override fun validate(document: ThreadworkDocument): List<Diagnostic> =
        DocumentValidator.validate(document) + document.nodes.values
            .filter { it.isLink && document.effectiveTechnologyId(it.id) == "kotlin-jvm" }
            .filter { LinkClassifier.classify(document, it) == LinkStereotype.RunnableCapability }
            .map {
                Diagnostic(
                    DiagnosticSeverity.Error,
                    "Kotlin/JVM has no configured runtime compiler for a run capability; use src or a toolchain adapter.",
                    it.id,
                    sourcePluginId = id,
                )
            }

    override fun codeIntelligence(document: ThreadworkDocument, node: Node): CompilerCodeIntelligence {
        val defaults = defaultCodeIntelligence(document, node)
        val runtimeSymbols = listOf(
            runtimeSymbol("ThreadworkRunner", "Kotlin application execution state", RUNNER_METHODS),
            runtimeSymbol("generated.threadworkRunner", "global Kotlin application runner", RUNNER_METHODS),
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
            documentation = "Kotlin/JVM Threadwork runtime helper. JVM shutdown hooks do not expose a portable OS signal number, so the getter returns zero.",
            members = members,
        )

    private companion object {
        val RUNNER_METHODS = listOf(
            CompilerCodeMember("shutdownRequest()", "Unit", "Request shutdown."),
            CompilerCodeMember("getShutdownSignal()", "Int", "Return zero; JVM hooks do not expose a portable signal number."),
            CompilerCodeMember("isRunning()", "Boolean", "Return the requested running state."),
            CompilerCodeMember("recordTransit()", "Unit", "Record a completed modeled transport."),
            CompilerCodeMember("beginShutdownDrain(idleTicks: UInt)", "Unit", "Begin bounded idle-window draining."),
            CompilerCodeMember("hasRecentTransit()", "Boolean", "Return whether transit or the drain window remains."),
            CompilerCodeMember("installShutdownHook()", "Unit", "Install the JVM shutdown hook."),
        )
        val TEMPLATES = CompilerTemplateSetLoader.load("/compiler-templates/kotlin/compiler.properties")
    }
}
