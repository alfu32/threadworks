package com.threadwork.completion

import com.threadwork.compiler.api.CompilerCodeIntelligence
import com.threadwork.compiler.api.CompilerPlugin
import com.threadwork.core.classification.NodeStereotype
import com.threadwork.core.classification.stereotype
import com.threadwork.core.model.Node
import com.threadwork.core.model.NodeId
import com.threadwork.core.model.NodeTextSection
import com.threadwork.core.model.ThreadworkDocument

/** UTF-16 offsets, end exclusive, matching Kotlin strings and the editor buffer. */
data class CodeRange(val start: Int, val end: Int) {
    init { require(start >= 0 && end >= start) }
    operator fun contains(offset: Int): Boolean = offset >= start && offset < end
}

data class CodeLocation(val nodeId: NodeId, val section: NodeTextSection, val range: CodeRange)

sealed interface AnalysisResult<out T> {
    data object Unsupported : AnalysisResult<Nothing>
    data class Unavailable(val reason: String) : AnalysisResult<Nothing>
    data class Available<T>(val value: T) : AnalysisResult<T>
}

sealed interface CodeType {
    data object Unknown : CodeType
    data class Named(val name: String, val origin: TypeOrigin = TypeOrigin.Declared) : CodeType
    data class Ambiguous(val candidates: List<String>) : CodeType
}

enum class TypeOrigin { Declared, Inferred, Compiler }
enum class CodeMemberKind { Field, Method }
data class CodeMember(
    val name: String,
    val kind: CodeMemberKind,
    val type: CodeType = CodeType.Unknown,
    val signature: String = name,
    val documentation: String = "",
    val declaration: CodeLocation? = null,
)

/** Identity belongs to a declaration, not a spelling shared by unrelated variables. */
data class CodeSymbolId(val declaration: CodeLocation)
data class CodeReference(val symbol: CodeSymbolId, val location: CodeLocation)
data class CodeScope(val location: CodeLocation, val parent: CodeLocation?, val ownerType: String? = null)

data class CodeHoverInfo(
    val title: String,
    val body: String,
)

interface CodeAnalysis {
    fun scopes(): AnalysisResult<List<CodeScope>> = AnalysisResult.Unsupported
    fun declarations(): AnalysisResult<List<DeclarationSymbol>> = AnalysisResult.Unsupported
    fun completions(): AnalysisResult<List<CompletionSuggestion>> = AnalysisResult.Unsupported
    fun typeOf(expression: String, offset: Int): AnalysisResult<CodeType> = AnalysisResult.Unsupported
    fun members(expression: String, offset: Int): AnalysisResult<List<CodeMember>> = AnalysisResult.Unsupported
    fun definition(offset: Int): AnalysisResult<CodeLocation?> = AnalysisResult.Unsupported
    fun references(symbol: CodeSymbolId): AnalysisResult<List<CodeReference>> = AnalysisResult.Unsupported
    fun hover(offset: Int): AnalysisResult<CodeHoverInfo?> = AnalysisResult.Unsupported
}

data class CodeAnalysisContext(
    val document: ThreadworkDocument,
    val request: CompletionRequest,
    val compiler: CompilerCodeIntelligence,
)

fun interface CodeAnalysisProvider {
    fun analyze(context: CodeAnalysisContext): CodeAnalysis
}

/** Providers are ordered by authority; an empty answer is still an answer. */
class CompositeCodeAnalysis(private val providers: List<CodeAnalysis>) : CodeAnalysis {
    private fun <T> query(operation: (CodeAnalysis) -> AnalysisResult<T>): AnalysisResult<T> {
        var failure: AnalysisResult<T> = AnalysisResult.Unsupported
        for (provider in providers) {
            when (val result = operation(provider)) {
                is AnalysisResult.Available -> return result
                is AnalysisResult.Unavailable -> failure = result
                AnalysisResult.Unsupported -> Unit
            }
        }
        return failure
    }

    override fun declarations() = query { it.declarations() }
    override fun scopes() = query { it.scopes() }
    override fun completions() = query { it.completions() }
    override fun typeOf(expression: String, offset: Int) = query { it.typeOf(expression, offset) }
    override fun members(expression: String, offset: Int) = query { it.members(expression, offset) }
    override fun definition(offset: Int) = query { it.definition(offset) }
    override fun references(symbol: CodeSymbolId) = query { it.references(symbol) }
    override fun hover(offset: Int) = query { it.hover(offset) }
}

class LegacyCodeAnalysisProvider(private val service: NodeCompletionService) : CodeAnalysisProvider {
    override fun analyze(context: CodeAnalysisContext): CodeAnalysis = object : CodeAnalysis {
        override fun declarations() = AnalysisResult.Available(service.getDeclarationSymbols(context.request))
        override fun completions() = AnalysisResult.Available(service.getSuggestions(context.request))
    }
}

class ModelAwareCompletionService(
    private val documentProvider: () -> ThreadworkDocument,
    private val compilerProvider: (ThreadworkDocument, Node) -> CompilerPlugin? = { _, _ -> null },
    technologyProviders: List<TechnologyCompletionProvider> = listOf(FlowTemplateCompletionProvider()),
    declarationSymbolIndex: DocumentDeclarationSymbolIndex = DocumentDeclarationSymbolIndex(),
    private val analysisProviders: List<CodeAnalysisProvider> = listOf(TreeSitterAnalysisProvider()),
    private val externalIndexCatalog: ExternalCodeIndexCatalog = ExternalCodeIndexCatalog.fromUserDirectory(),
) : NodeCompletionService {
    private val legacy = LegacyNodeCompletionService(documentProvider, compilerProvider, technologyProviders, declarationSymbolIndex)
    private val fallback = LegacyCodeAnalysisProvider(legacy)
    private val compilerAnalysis = CompilerCodeAnalysisProvider()

    override fun analysis(request: CompletionRequest): CodeAnalysis {
        val document = documentProvider()
        val node = document.nodes[request.nodeId] ?: return object : CodeAnalysis {}
        val compilerIntelligence = compilerProvider(document, node)?.codeIntelligence(document, node) ?: CompilerCodeIntelligence()
        val external = externalIndexCatalog.intelligence(request.languageId, request.technologyId)
        val context = CodeAnalysisContext(
            document,
            request,
            CompilerCodeIntelligence(
                symbols = (compilerIntelligence.symbols + external.symbols).distinctBy { it.name to it.kind },
                types = (compilerIntelligence.types + external.types).distinctBy { it.name },
            ),
        )
        val advanced = if (node.stereotype(document) == NodeStereotype.CompilerTemplate) emptyList() else analysisProviders.map { it.analyze(context) }
        return CompositeCodeAnalysis(advanced + compilerAnalysis.analyze(context) + fallback.analyze(context))
    }

    override fun getDeclarationSymbols(request: CompletionRequest): List<DeclarationSymbol> =
        (analysis(request).declarations() as? AnalysisResult.Available)?.value.orEmpty()

    override fun getSuggestions(request: CompletionRequest): List<CompletionSuggestion> {
        val analysis = analysis(request)
        val access = MemberAccess.at(request.fullText, request.cursorOffset)
        if (access != null) {
            val result = analysis.members(access.receiver, request.cursorOffset)
            if (result is AnalysisResult.Available) {
                return result.value.filter { it.name.startsWith(access.prefix, ignoreCase = true) }.map { member ->
                    CompletionSuggestion(
                        label = member.name,
                        insertText = if (member.kind == CodeMemberKind.Method) "${member.name}()" else member.name,
                        kind = CompletionSuggestionKind.TypeMember,
                        detail = member.signature + when (val type = member.type) {
                            is CodeType.Named -> ": ${type.name}"
                            else -> ""
                        },
                        documentation = member.documentation,
                        replacementRange = CodeRange(access.memberStart, request.cursorOffset),
                    )
                }.distinctBy { it.insertText }.sortedBy { it.label }
            }
        }
        val primary = (analysis.completions() as? AnalysisResult.Available)?.value.orEmpty()
        // Model/compiler names remain additive; source-local names come from the selected analyzer.
        val modeled = legacy.getSuggestions(request).filter { it.kind != CompletionSuggestionKind.UserSymbol }
        return (modeled + primary).distinctBy { it.insertText }
    }
}

internal data class MemberAccess(val receiver: String, val prefix: String, val memberStart: Int) {
    companion object {
        fun at(source: String, offset: Int): MemberAccess? {
            if (offset !in 0..source.length) return null
            var start = offset
            while (start > 0 && (source[start - 1].isLetterOrDigit() || source[start - 1] == '_')) start--
            val before = source.substring(0, start).trimEnd()
            val operator = listOf("->", "?.", "::", ".").firstOrNull(before::endsWith) ?: return null
            val receiverEnd = before.length - operator.length
            var receiverStart = receiverEnd
            var depth = 0
            while (receiverStart > 0) {
                val c = source[receiverStart - 1]
                if (c == ')' || c == ']') depth++
                if (c == '(' || c == '[') {
                    if (depth == 0) break
                    depth--
                }
                if (depth == 0 && !(c.isLetterOrDigit() || c in "_$.-?>:()[]")) break
                receiverStart--
            }
            val receiver = source.substring(receiverStart, receiverEnd).trim()
            return receiver.takeIf { it.isNotEmpty() }?.let { MemberAccess(it, source.substring(start, offset), start) }
        }
    }
}
