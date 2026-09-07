package com.threadwork.completion

import com.threadwork.compiler.api.CompilerCodeSymbolKind

internal class ParsedCodeAnalysis(
    private val context: CodeAnalysisContext,
    private val active: SourceSymbols,
    private val companions: List<SourceSymbols>,
    private val cursor: Int,
) : CodeAnalysis {
    private val allBindings = (listOf(active) + companions).flatMap { it.bindings }
    private val compiler = context.compiler

    override fun declarations() = AnalysisResult.Available(active.declarationSymbols())
    override fun scopes() = AnalysisResult.Available(active.scopes.toList())

    override fun completions(): AnalysisResult<List<CompletionSuggestion>> {
        val prefix = context.request.prefix
        // Other project units participate in type/member resolution, but their
        // top-level locals must not leak into the active editor's completion list.
        val visible = active.visible(cursor)
        return AnalysisResult.Available(visible.distinctBy { it.name }.filter { prefix.isBlank() || it.name.startsWith(prefix, true) }.map {
            CompletionSuggestion(it.name, it.name, CompletionSuggestionKind.UserSymbol,
                "${it.kind.name.lowercase()} declared in ${if (it.location.nodeId == active.nodeId) "this node" else "dependency library"}" +
                    it.typeName.takeIf(String::isNotBlank)?.let { name -> ": $name" }.orEmpty(), it.header)
        })
    }

    private fun binding(name: String, offset: Int, source: SourceSymbols = active): SourceBinding? =
        source.visible(offset).firstOrNull { it.name == name }
            ?: companions.flatMap { unit -> unit.bindings.filter { it.scope == CodeRange(0, unit.source.length) && it.ownerType == null } }
                .filter { it.name == name }.singleOrNull()

    private fun bindingType(binding: SourceBinding, depth: Int): CodeType {
        if (binding.typeName.isNotBlank()) return CodeType.Named(binding.typeName)
        if (binding.initializer.isBlank() || depth >= 12) return CodeType.Unknown
        val source = (listOf(active) + companions).first { it.nodeId == binding.location.nodeId }
        return infer(binding.initializer, binding.location.range.start, depth + 1, source).let {
            if (it is CodeType.Named) it.copy(origin = TypeOrigin.Inferred) else it
        }
    }

    override fun typeOf(expression: String, offset: Int) = AnalysisResult.Available(infer(expression, offset))

    private fun infer(expression: String, offset: Int, depth: Int = 0, source: SourceSymbols = active): CodeType {
        if (depth > 12) return CodeType.Unknown
        val expr = expression.trim()
        if (expr in setOf("this", "${'$'}this", "self")) {
            val owner = source.scopes.filter { offset >= it.location.range.start && offset <= it.location.range.end && it.ownerType != null }
                .minByOrNull { it.location.range.end - it.location.range.start }?.ownerType
            return owner?.let { CodeType.Named(it) } ?: CodeType.Unknown
        }
        if (expr.startsWith("new ")) return CodeType.Named(expr.removePrefix("new ").substringBefore('(').trim(), TypeOrigin.Inferred)
        if (expr.firstOrNull() in listOf('\'', '"')) return CodeType.Named("string", TypeOrigin.Inferred)
        if (expr == "true" || expr == "false" || expr == "True" || expr == "False") return CodeType.Named("boolean", TypeOrigin.Inferred)
        if (expr.toDoubleOrNull() != null) return CodeType.Named("number", TypeOrigin.Inferred)
        // Resolve qualified receivers recursively, including zero-argument method calls.
        val member = MemberAccess.at(expr, expr.length)
        if (member != null && member.prefix.isNotEmpty()) {
            return membersOf(member.receiver, offset, depth + 1, source).firstOrNull { it.name == member.prefix }?.type ?: CodeType.Unknown
        }
        val callStart = expr.indexOf('(')
        if (callStart > 0 && expr.endsWith(')')) {
            val callee = expr.substring(0, callStart).trim()
            val callMember = MemberAccess.at(callee, callee.length)
            if (callMember != null) return membersOf(callMember.receiver, offset, depth + 1, source)
                .filter { it.name == callMember.prefix && it.kind == CodeMemberKind.Method }.singleOrNull()?.type ?: CodeType.Unknown
            val target = binding(callee, offset, source)
            if (target?.isType == true || compiler.types.any { it.name == callee }) return CodeType.Named(callee, TypeOrigin.Inferred)
            if (target?.callable == true) return bindingType(target, depth + 1)
            return CodeType.Unknown
        }
        // Go composite literals and C explicitly typed compound literals.
        val literalType = expr.substringBefore('{').trim().removePrefix("&").removeSurrounding("(", ")")
        if ('{' in expr && (allBindings.any { it.isType && it.name == literalType } || compiler.types.any { it.name == literalType })) {
            return CodeType.Named(literalType, TypeOrigin.Inferred)
        }
        binding(expr, offset, source)?.let { return bindingType(it, depth + 1) }
        val modeled = compiler.symbols.firstOrNull { it.name == expr }
        return modeled?.typeName?.takeIf(String::isNotBlank)?.let { CodeType.Named(it, TypeOrigin.Compiler) } ?: CodeType.Unknown
    }

    override fun members(expression: String, offset: Int): AnalysisResult<List<CodeMember>> =
        AnalysisResult.Available(membersOf(expression, offset))

    private fun membersOf(expression: String, offset: Int, depth: Int = 0, source: SourceSymbols = active): List<CodeMember> {
        if (depth > 12) return emptyList()
        val local = binding(expression.trim(), offset, source)
        val modeled = if (local == null) compiler.symbols.firstOrNull { it.name == expression.trim() } else null
        if (modeled != null && modeled.kind in setOf(CompilerCodeSymbolKind.InputBuffer, CompilerCodeSymbolKind.OutputBuffer, CompilerCodeSymbolKind.ServiceInstance, CompilerCodeSymbolKind.RuntimeSymbol)) {
            // A transport buffer's typeName may describe its payload, not the buffer itself.
            return modeled.members.map { it.toCodeMember(modeled.name) }
        }
        val type = infer(expression, offset, depth + 1, source) as? CodeType.Named ?: return emptyList()
        val name = normalizeType(type.name)
        val aliases = allBindings.filter { it.name == name && it.kind == DeclarationSymbolKind.TypeAlias && it.typeName != name }
        val target = aliases.singleOrNull()?.typeName?.let(::normalizeType) ?: name
        val members = allBindings.filter { it.ownerType == target }.map {
            CodeMember(it.name.removePrefix("$"), if (it.callable) CodeMemberKind.Method else CodeMemberKind.Field,
                bindingType(it, depth + 1), it.header, declaration = it.location)
        }
        val fields = compiler.types.firstOrNull { it.name == target }?.fields.orEmpty().map {
            CodeMember(it.name, CodeMemberKind.Field, CodeType.Named(it.typeName + if (it.isReference) "*" else "", TypeOrigin.Compiler))
        }
        val methods = compiler.symbols.filter { it.kind == CompilerCodeSymbolKind.Type && it.name == target }.flatMap { it.members }.map {
            it.toCodeMember(target)
        }
        return (members + fields + methods).distinctBy { it.name to it.signature }
    }

    override fun definition(offset: Int): AnalysisResult<CodeLocation?> {
        active.bindings.firstOrNull { offset in it.location.range }?.let { return AnalysisResult.Available(it.location) }
        val identifier = active.identifiers.filter { offset in it.range }.minByOrNull { it.range.end - it.range.start }
            ?: return AnalysisResult.Available(null)
        val access = MemberAccess.at(active.source, identifier.range.end)
        val location = if (access != null) {
            membersOf(access.receiver, offset).filter { it.name == access.prefix }.singleOrNull()?.declaration
        } else binding(active.text(identifier), offset)?.location
        return AnalysisResult.Available(location)
    }

    override fun references(symbol: CodeSymbolId): AnalysisResult<List<CodeReference>> = AnalysisResult.Available(
        active.identifiers.distinctBy { it.range }.mapNotNull { identifier ->
            val location = CodeLocation(active.nodeId, active.section, identifier.range)
            if (location == symbol.declaration) return@mapNotNull null
            if ((definition(identifier.range.start) as? AnalysisResult.Available)?.value == symbol.declaration) CodeReference(symbol, location) else null
        },
    )

    private fun normalizeType(name: String): String = name.trim().removePrefix("const ").removePrefix("struct ")
        .removePrefix("class ").trim().trimEnd('*', '&', '?', ' ').removePrefix("*").trim()
}
