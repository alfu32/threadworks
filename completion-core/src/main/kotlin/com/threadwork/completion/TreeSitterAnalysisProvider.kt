package com.threadwork.completion

import com.threadwork.core.model.NodeTextSection
import com.threadwork.core.model.NodeId
import com.threadwork.core.model.effectiveTextLanguageId
import com.threadwork.core.model.effectiveTechnologyId
import com.threadwork.core.model.effectiveLayoutStrategyId
import com.threadwork.core.classification.NodeStereotype
import com.threadwork.core.classification.stereotype
import org.treesitter.TSInputEncoding
import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterC
import org.treesitter.TreeSitterGo
import org.treesitter.TreeSitterJava
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterPhp
import org.treesitter.TreeSitterPython

/** Native trees are closed after extraction; only bounded, immutable JVM snapshots are cached. */
class TreeSitterAnalysisProvider : CodeAnalysisProvider {
    private val cache = object : LinkedHashMap<Pair<String, String>, SyntaxNode>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, SyntaxNode>?) = size > 32
    }
    private val languages = mutableMapOf<String, TSLanguage>()
    private val unavailable = mutableMapOf<String, String>()

    @Synchronized
    internal fun syntax(language: String, source: String): SyntaxNode {
        val key = language to source
        cache[key]?.let { return it }
        val grammar = languages.getOrPut(language) {
            when (language) {
                "c" -> TreeSitterC()
                "javascript" -> TreeSitterJavascript()
                "php" -> TreeSitterPhp()
                "kotlin" -> TreeSitterKotlin()
                "python" -> TreeSitterPython()
                "java" -> TreeSitterJava()
                "go" -> TreeSitterGo()
                else -> error("Unsupported grammar: $language")
            }
        }
        val prefix = if (language == "php" && !source.trimStart().startsWith("<?")) "<?php\n" else ""
        return TSParser().use { parser ->
            check(parser.setLanguage(grammar)) { "Incompatible Tree-sitter grammar: $language" }
            // Supply actual UTF-16 bytes; JNI's String API uses modified UTF-8.
            val bytes = (prefix + source).toByteArray(Charsets.UTF_16LE)
            parser.parse(ByteArray(8192), null, { buffer, offset, _ ->
                val count = minOf(buffer.size, bytes.size - offset).coerceAtLeast(0)
                if (count > 0) bytes.copyInto(buffer, 0, offset, offset + count)
                count
            }, TSInputEncoding.TSInputEncodingUTF16LE).use { tree ->
                fun copy(node: TSNode, depth: Int): SyntaxNode {
                    check(depth < 256) { "Source nesting exceeds analysis limit" }
                    val range = CodeRange(
                        (node.startByte / 2 - prefix.length).coerceIn(0, source.length),
                        (node.endByte / 2 - prefix.length).coerceIn(0, source.length),
                    )
                    val children = (0 until node.childCount).mapNotNull { index ->
                        node.getChild(index).takeIf { it.isNamed }?.let {
                            node.getFieldNameForChild(index).orEmpty() to copy(it, depth + 1)
                        }
                    }
                    return SyntaxNode(node.type, range, children)
                }
                copy(tree.rootNode, 0).also { if (source.length <= 100_000) cache[key] = it }
            }
        }
    }

    override fun analyze(context: CodeAnalysisContext): CodeAnalysis {
        val language = analysisLanguage(context.request.languageId)
        if (language !in supportedLanguages) return object : CodeAnalysis {}
        // Parsing is deferred until a capability is queried.
        return object : CodeAnalysis {
            private val delegate: CodeAnalysis by lazy {
                val failure = synchronized(this@TreeSitterAnalysisProvider) { unavailable[language] }
                if (failure != null) return@lazy failed(failure)
                try {
                    createAnalysis(context, language)
                } catch (error: LinkageError) {
                    val reason = error.message ?: "Native parser unavailable"
                    synchronized(this@TreeSitterAnalysisProvider) { unavailable[language] = reason }
                    failed(reason)
                } catch (error: Exception) {
                    failed(error.message ?: "Source analysis failed")
                }
            }
            override fun declarations() = delegate.declarations()
            override fun scopes() = delegate.scopes()
            override fun completions() = delegate.completions()
            override fun typeOf(expression: String, offset: Int) = delegate.typeOf(expression, offset)
            override fun members(expression: String, offset: Int) = delegate.members(expression, offset)
            override fun definition(offset: Int) = delegate.definition(offset)
            override fun references(symbol: CodeSymbolId) = delegate.references(symbol)
            override fun hover(offset: Int) = delegate.hover(offset)
        }
    }

    private fun createAnalysis(context: CodeAnalysisContext, language: String): CodeAnalysis {
        val request = context.request
        val document = context.document
        val node = document.nodes.getValue(request.nodeId)
        val prose = request.textSection in setOf(NodeTextSection.Specification, NodeTextSection.AiInstructions)
        val section = if (prose) NodeTextSection.Declaration else request.textSection
        val source = if (prose) node.text.declaration else request.fullText
        val active = SourceSymbols(node.id, node.name, section, language, source, syntax(language, source))
        // Treat the same-language/compiler scope as the project's source index. In a
        // generated single-file layout this corresponds to the declarations that the
        // compiler emits alongside the active function, without parsing those bodies
        // on every keystroke.
        val companions = document.nodes.values
            .asSequence()
            .filter { candidate ->
                candidate.id != node.id &&
                    !candidate.isLink &&
                    candidate.text.declaration.isNotBlank() &&
                    analysisLanguage(document.effectiveTextLanguageId(candidate.id, NodeTextSection.Declaration)) == language &&
                    (candidate.stereotype(document) == NodeStereotype.ServiceLibrary ||
                        (document.effectiveTechnologyId(candidate.id) == document.effectiveTechnologyId(node.id) &&
                            document.effectiveLayoutStrategyId(candidate.id) == document.effectiveLayoutStrategyId(node.id)))
            }
            .map { candidate ->
                SourceSymbols(
                    candidate.id,
                    candidate.name,
                    NodeTextSection.Declaration,
                    language,
                    candidate.text.declaration,
                    syntax(language, candidate.text.declaration),
                )
            }
            .toList()
        // Runtime units have no editor coordinates. Keep them separate from node sources.
        val runtime = context.sourceProvider().filter { analysisLanguage(it.languageId) == language }.map { unit ->
            SourceSymbols(
                NodeId("compiler-source:${unit.id}"), unit.id, NodeTextSection.Declaration,
                language, unit.content, syntax(language, unit.content),
            )
        }
        return ParsedCodeAnalysis(context, active, companions, if (prose) source.length else request.cursorOffset, runtime)
    }

    private fun failed(reason: String) = object : CodeAnalysis {
        override fun scopes() = AnalysisResult.Unavailable(reason)
        override fun declarations() = AnalysisResult.Unavailable(reason)
        override fun completions() = AnalysisResult.Unavailable(reason)
        override fun typeOf(expression: String, offset: Int) = AnalysisResult.Unavailable(reason)
        override fun members(expression: String, offset: Int) = AnalysisResult.Unavailable(reason)
        override fun definition(offset: Int) = AnalysisResult.Unavailable(reason)
        override fun references(symbol: CodeSymbolId) = AnalysisResult.Unavailable(reason)
        override fun hover(offset: Int) = AnalysisResult.Unavailable(reason)
    }

    companion object {
        private val supportedLanguages = setOf("c", "javascript", "php", "kotlin", "python", "java", "go")
    }
}

internal fun analysisLanguage(id: String): String = when (val normalized = id.trim().lowercase()) {
    "js", "node", "nodejs", "quickjs", "01-javascript" -> "javascript"
    "kt", "kts", "kotlin-jvm", "kotlin-script" -> "kotlin"
    "py" -> "python"
    "golang" -> "go"
    else -> normalized
}

internal data class SyntaxNode(val kind: String, val range: CodeRange, val children: List<Pair<String, SyntaxNode>>) {
    fun field(name: String): SyntaxNode? = children.firstOrNull { it.first == name }?.second
    fun nodes(): List<SyntaxNode> = children.map { it.second }
    fun descendants(): Sequence<SyntaxNode> = sequence {
        yield(this@SyntaxNode)
        for (child in nodes()) yieldAll(child.descendants())
    }
}
