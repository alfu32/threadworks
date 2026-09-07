package com.threadwork.completion

import com.threadwork.core.model.NodeId
import com.threadwork.core.model.NodeTextSection

internal data class SourceBinding(
    val name: String,
    val kind: DeclarationSymbolKind,
    val location: CodeLocation,
    val scope: CodeRange,
    val typeName: String,
    val initializer: String,
    val header: String,
    val ownerType: String? = null,
) {
    val callable: Boolean get() = kind == DeclarationSymbolKind.Function
    val isType: Boolean get() = kind in setOf(DeclarationSymbolKind.Class, DeclarationSymbolKind.Struct, DeclarationSymbolKind.Interface, DeclarationSymbolKind.TypeAlias, DeclarationSymbolKind.Enum)
}

internal class SourceSymbols(
    val nodeId: NodeId,
    val nodeName: String,
    val section: NodeTextSection,
    val language: String,
    val source: String,
    val root: SyntaxNode,
) {
    val bindings = mutableListOf<SourceBinding>()
    val identifiers = mutableListOf<SyntaxNode>()
    private val whole = CodeRange(0, source.length)
    val scopes = mutableListOf(CodeScope(CodeLocation(nodeId, section, whole), null))

    init { visit(root, whole, null, null) }

    fun text(node: SyntaxNode?): String = node?.let { source.substring(it.range.start, it.range.end) }.orEmpty()

    private fun identifier(node: SyntaxNode?): SyntaxNode? {
        node ?: return null
        if (node.kind in identifierKinds) return node
        return identifier(node.field("declarator") ?: node.field("name"))
            ?: node.nodes().firstOrNull { it.kind in identifierKinds }
    }

    private fun type(node: SyntaxNode?): String = text(node).trim().removePrefix(":").trim()

    private fun add(
        name: SyntaxNode?,
        declaration: SyntaxNode,
        scope: CodeRange,
        kind: DeclarationSymbolKind,
        typeName: String = "",
        initializer: SyntaxNode? = null,
        ownerType: String? = null,
    ) {
        name ?: return
        val value = text(name).trim()
        if (value.isEmpty() || bindings.any { it.location.range == name.range }) return
        val bodyStart = declaration.field("body")?.range?.start ?: declaration.range.end
        bindings += SourceBinding(value, kind, CodeLocation(nodeId, section, name.range), scope, typeName,
            text(initializer), source.substring(declaration.range.start, bodyStart).trim().lineSequence().first(), ownerType)
    }

    private fun visit(node: SyntaxNode, scope: CodeRange, ownerType: String?, parent: SyntaxNode?) {
        if (node.kind.contains("comment") || node.kind in setOf("string", "string_literal", "interpreted_string_literal", "character_literal")) return
        if (node.kind in identifierKinds) {
            identifiers += node
            return
        }
        var childScope = scope
        var childOwner = ownerType

        when (node.kind) {
            "class_declaration", "class_definition", "interface_declaration", "struct_specifier", "enum_declaration", "type_spec" -> {
                val name = identifier(node.field("name")) ?: node.nodes().firstOrNull { it.kind in identifierKinds }
                val kind = when (node.kind) {
                    "struct_specifier" -> DeclarationSymbolKind.Struct
                    "interface_declaration" -> DeclarationSymbolKind.Interface
                    "enum_declaration" -> DeclarationSymbolKind.Enum
                    "type_spec" -> DeclarationSymbolKind.TypeAlias
                    else -> DeclarationSymbolKind.Class
                }
                add(name, node, scope, kind, text(name))
                childScope = node.range
                childOwner = text(name).ifBlank { ownerType }
            }
            "function_definition", "function_declaration", "method_declaration", "method_definition" -> {
                val declarator = node.field("declarator")
                val name = identifier(node.field("name") ?: declarator)
                    ?: node.nodes().firstOrNull { it.kind in identifierKinds }
                val returnType = node.field("return_type") ?: node.field("type") ?: node.field("result")
                    ?: node.nodes().firstOrNull { it.kind in setOf("user_type", "nullable_type") }
                add(name, node, scope, DeclarationSymbolKind.Function, type(returnType), ownerType = ownerType)
                childScope = node.range
                childOwner = null
            }
            "compound_statement", "statement_block", "block", "for_statement", "for_in_statement", "catch_clause", "lambda_literal", "arrow_function" -> {
                // Python/PHP blocks do not introduce lexical variable scopes.
                if (language !in setOf("python", "php")) childScope = node.range
            }
            "declaration", "field_declaration", "type_definition" -> {
                val typeNode = node.field("type")
                val declarators = node.children.filter { it.first == "declarator" }.map { it.second }
                if (language == "go" && node.kind == "field_declaration") {
                    node.children.filter { it.first == "name" }.forEach { (_, name) ->
                        add(name, node, scope, DeclarationSymbolKind.Variable, type(typeNode), ownerType = ownerType)
                    }
                }
                declarators.forEach { declarator ->
                    val name = identifier(declarator)
                    val function = declarator.descendants().any { it.kind == "function_declarator" }
                    val typeName = type(typeNode) + if (declarator.descendants().any { it.kind == "pointer_declarator" }) "*" else ""
                    add(name, node, scope,
                        if (node.kind == "type_definition") DeclarationSymbolKind.TypeAlias else if (function) DeclarationSymbolKind.Function else DeclarationSymbolKind.Variable,
                        typeName, declarator.field("value"), ownerType)
                }
            }
            "variable_declarator" -> {
                add(identifier(node.field("name")), node, scope, DeclarationSymbolKind.Variable,
                    type(parent?.field("type")), node.field("value"), ownerType)
            }
            "field_definition" -> add(identifier(node.field("property")), node, scope,
                DeclarationSymbolKind.Variable, initializer = node.field("value"), ownerType = ownerType)
            "parameter_declaration", "formal_parameter", "simple_parameter", "parameter", "class_parameter", "typed_parameter", "typed_default_parameter" -> {
                val name = identifier(node.field("name") ?: node.field("declarator"))
                    ?: node.nodes().firstOrNull { it.kind in identifierKinds }
                val typeNode = node.field("type") ?: node.nodes().firstOrNull { it.kind in setOf("user_type", "nullable_type") }
                add(name, node, scope, DeclarationSymbolKind.Variable, type(typeNode), node.field("value"), ownerType)
            }
            "property_declaration" -> {
                // Kotlin declares name/type inside variable_declaration; PHP uses property_element.
                node.nodes().filter { it.kind in setOf("variable_declaration", "property_element") }.forEach { variable ->
                    val name = identifier(variable.field("name")) ?: variable.nodes().firstOrNull { it.kind in identifierKinds }
                    val typeNode = node.field("type") ?: variable.field("type")
                        ?: variable.nodes().firstOrNull { it.kind in setOf("user_type", "nullable_type") }
                    val initializer = node.field("value") ?: node.nodes().lastOrNull { it.range.start > variable.range.end }
                    add(name, node, scope, DeclarationSymbolKind.Variable, type(typeNode), initializer, ownerType)
                }
            }
            "assignment", "assignment_expression", "short_var_declaration", "var_spec" -> {
                val left = node.field("left") ?: node.field("name")
                val right = node.field("right") ?: node.field("value")
                val names = if (left?.kind == "expression_list") left.nodes() else listOfNotNull(left)
                val values = if (right?.kind == "expression_list") right.nodes() else listOfNotNull(right)
                names.filter { it.kind in identifierKinds }.forEachIndexed { index, name ->
                    val existing = bindings.any { it.name == text(name) && it.scope == scope }
                    if (!existing) add(name, node, scope, DeclarationSymbolKind.Variable, type(node.field("type")), values.getOrNull(index), ownerType)
                }
            }
        }
        if (childScope != scope) scopes += CodeScope(CodeLocation(nodeId, section, childScope), CodeLocation(nodeId, section, scope), ownerType ?: childOwner)
        node.nodes().forEach { visit(it, childScope, childOwner, node) }
    }

    fun visible(offset: Int): List<SourceBinding> = bindings.filter {
        it.ownerType == null && offset >= it.scope.start && offset <= it.scope.end &&
            (it.isType || it.callable || it.location.range.start <= offset)
    }.sortedWith(compareBy<SourceBinding> { it.scope.end - it.scope.start }.thenByDescending { it.location.range.start })
        .distinctBy { it.name }

    fun declarationSymbols(): List<DeclarationSymbol> = bindings.map {
        DeclarationSymbol(it.name, it.kind, it.header, language, nodeId, nodeName, it.location.range.start, it.location.range.end)
    }

    companion object {
        val identifierKinds = setOf("identifier", "type_identifier", "field_identifier", "property_identifier", "simple_identifier", "variable_name", "name")
    }
}
