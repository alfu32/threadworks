package com.threadwork.compiler.go

import com.threadwork.compiler.api.CompilerCodeIntelligence
import com.threadwork.compiler.api.CompilerCodeMember
import com.threadwork.compiler.api.CompilerCodeSymbol
import com.threadwork.compiler.api.CompilerCodeSymbolKind
import com.threadwork.compiler.api.CompilerOptions
import com.threadwork.compiler.api.CompilerTechnology
import com.threadwork.compiler.api.SingleFileLayoutStrategy
import com.threadwork.compiler.api.compilerArgumentName
import com.threadwork.compiler.api.defaultCodeIntelligence
import com.threadwork.compiler.generic.CompilerTemplateSet
import com.threadwork.compiler.generic.CompilerTemplateSetLoader
import com.threadwork.compiler.generic.TemplateSetCompiler
import com.threadwork.compiler.generic.compilerTemplateOverrides
import com.threadwork.core.classification.LinkClassifier
import com.threadwork.core.classification.LinkStereotype
import com.threadwork.core.classification.NodeStereotype
import com.threadwork.core.classification.stereotype
import com.threadwork.core.diagnostics.Diagnostic
import com.threadwork.core.diagnostics.DiagnosticSeverity
import com.threadwork.core.model.Node
import com.threadwork.core.model.NodeTextSection
import com.threadwork.core.model.ThreadworkDocument
import com.threadwork.core.model.effectiveTechnologyId
import com.threadwork.core.validation.DocumentValidator

/** Generates a self-contained Go application from a Threadwork topology. */
class GoCompiler : TemplateSetCompiler() {
    override val id: String = "go-compiler"
    override val displayName: String = "Go Compiler"
    override val supportedLanguageIds: Set<String> = setOf("go")
    override val supportedTechnologyIds: Set<String> = setOf("go")
    override val primitiveTypeIds: List<String> = listOf(
        "bool",
        "byte",
        "rune",
        "int",
        "int8",
        "int16",
        "int32",
        "int64",
        "uint",
        "uint8",
        "uint16",
        "uint32",
        "uint64",
        "uintptr",
        "float32",
        "float64",
        "complex64",
        "complex128",
        "string",
        "[]byte",
        "any",
    )
    override val providedTechnologies: List<CompilerTechnology> = listOf(CompilerTechnology("go", "go"))
    override val supportedLayoutStrategyIds: Set<String> = setOf(SingleFileLayoutStrategy.id)
    override val magicFileNames: Set<String> = TEMPLATES.staticFileNames

    override fun supports(document: ThreadworkDocument): Boolean = true

    override fun validate(document: ThreadworkDocument): List<Diagnostic> =
        DocumentValidator.validate(document) + document.nodes.values
            .filter { it.isLink && document.effectiveTechnologyId(it.id) == "go" }
            .filter { LinkClassifier.classify(document, it) == LinkStereotype.RunnableCapability }
            .map {
                Diagnostic(
                    DiagnosticSeverity.Error,
                    "Go has no configured runtime compiler for a run capability; use a source capability or an explicit evaluator adapter.",
                    it.id,
                    sourcePluginId = id,
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

    override fun codeIntelligence(document: ThreadworkDocument, node: Node): CompilerCodeIntelligence {
        val defaults = defaultCodeIntelligence(document, node)
        val goSymbols = defaults.symbols
            .filterNot { it.kind == CompilerCodeSymbolKind.ServiceInstance }
            .map { symbol ->
                if (symbol.kind != CompilerCodeSymbolKind.SourceCapability) return@map symbol
                symbol.copy(
                    members = symbol.members.map { member ->
                        member.copy(name = member.name.replace(".getSource(", ".GetSource("))
                    },
                )
            }
        return defaults.copy(
            symbols = (
                goSymbols + listOf(
                    runtimeSymbol("ThreadworkRunner", "Go application execution state", RUNNER_METHODS),
                    runtimeSymbol("threadworkRunner", "global Go application runner", RUNNER_METHODS),
                    runtimeSymbol("push", "func push[T any](buffer *ThreadworkBuffer, value T)"),
                    runtimeSymbol("pop", "func pop[T any](buffer *ThreadworkBuffer, out *T) bool"),
                )
                ).distinctBy { it.name to it.kind },
        )
    }

    override fun generatedEntitySymbols(document: ThreadworkDocument, node: Node): List<CompilerCodeSymbol> {
        if (node.isLink || node.stereotype(document) == NodeStereotype.ServiceLibrary) return emptyList()
        val symbol = indexedNodeSymbol(document, node)
        return listOf(
            generatedFunction("init_$symbol", "generated child initialization function", node),
            generatedFunction("run_$symbol", "generated child execution function", node),
        )
    }

    override fun generatedFunctionHeader(
        document: ThreadworkDocument,
        node: Node,
        section: NodeTextSection,
    ): String {
        if (node.isLink || node.stereotype(document) == NodeStereotype.ServiceLibrary) return ""
        val functionPrefix = when (section) {
            NodeTextSection.Declaration -> "run"
            NodeTextSection.Instantiation -> "init"
            else -> return ""
        }
        val arguments = mutableListOf("context *ThreadworkContext")
        node.incomingLinks.mapNotNull(document.nodes::get).forEach { linkNode ->
            when (LinkClassifier.classify(document, linkNode)) {
                LinkStereotype.UsageImport,
                LinkStereotype.DependencyInjection -> Unit
                LinkStereotype.SourceCapability -> {
                    val typeName = dependencySymbol(document, linkNode)
                        .replaceFirstChar(Char::uppercase) + "Capability"
                    arguments += "${compilerArgumentName(linkNode.name)} *$typeName"
                }
                LinkStereotype.RunnableCapability -> arguments += "${compilerArgumentName(linkNode.name)} any"
                else -> arguments += "${compilerArgumentName(linkNode.name)} *ThreadworkBuffer"
            }
        }
        node.outgoingLinks.mapNotNull(document.nodes::get)
            .filterNot { LinkClassifier.isCapability(document, it) }
            .forEach { arguments += "${compilerArgumentName(it.name)} *ThreadworkBuffer" }
        return "func ${functionPrefix}_${indexedNodeSymbol(document, node)}(${arguments.joinToString(", ")}) error {"
    }

    private fun generatedFunction(name: String, detail: String, node: Node): CompilerCodeSymbol =
        CompilerCodeSymbol(
            name = name,
            kind = CompilerCodeSymbolKind.GeneratedFunction,
            detail = detail,
            documentation = "Generated Go function for direct child '${node.name}'.",
            originNodeId = node.id,
        )

    private fun runtimeSymbol(
        name: String,
        detail: String,
        members: List<CompilerCodeMember> = emptyList(),
    ): CompilerCodeSymbol = CompilerCodeSymbol(
        name = name,
        kind = CompilerCodeSymbolKind.RuntimeSymbol,
        detail = detail,
        documentation = "Go Threadwork runtime helper.",
        members = members,
    )

    private fun indexedNodeSymbol(document: ThreadworkDocument, node: Node): String {
        val nodes = document.nodes.values.filterNot(Node::isLink).sortedBy { it.id.value }
        val index = nodes.indexOfFirst { it.id == node.id }.takeIf { it >= 0 }?.plus(1) ?: 1
        return "${safeIdentifier(node.name).lowercase()}_$index"
    }

    private fun dependencySymbol(document: ThreadworkDocument, linkNode: Node): String {
        val link = linkNode.link ?: return safeIdentifier(linkNode.name)
        val source = document.nodes[link.sourceNodeId]
        val dependencies = source?.outgoingLinks.orEmpty()
            .mapNotNull(document.nodes::get)
            .filter { candidate ->
                LinkClassifier.classify(document, candidate) in setOf(
                    LinkStereotype.UsageImport,
                    LinkStereotype.DependencyInjection,
                    LinkStereotype.SourceCapability,
                    LinkStereotype.RunnableCapability,
                )
            }
        val index = dependencies.indexOfFirst { it.id == linkNode.id }.takeIf { it >= 0 }?.plus(1) ?: 1
        return "${safeIdentifier(linkNode.name)}$index"
    }

    private fun safeIdentifier(value: String): String {
        val candidate = value.trim().replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').ifBlank { "node" }
        return if (candidate.first().isDigit()) "_$candidate" else candidate
    }

    private companion object {
        val RUNNER_METHODS = listOf(
            CompilerCodeMember("ShutdownRequest()", "void", "Request shutdown."),
            CompilerCodeMember("GetShutdownSignal()", "int", "Return the last SIGINT or SIGTERM number, or zero."),
            CompilerCodeMember("IsRunning()", "bool", "Return the requested running state."),
            CompilerCodeMember("RecordTransit()", "void", "Record a completed modeled transport."),
            CompilerCodeMember("BeginShutdownDrain(idleTicks uint32)", "void", "Begin bounded idle-window draining."),
            CompilerCodeMember("HasRecentTransit()", "bool", "Return whether transit or the drain window remains."),
            CompilerCodeMember("InstallShutdownSignalHandlers()", "void", "Install SIGINT and SIGTERM handlers."),
            CompilerCodeMember("StopShutdownSignalHandlers()", "void", "Release installed signal handlers."),
        )
        val TEMPLATES = CompilerTemplateSetLoader.load("/compiler-templates/go/compiler.properties")
    }
}
