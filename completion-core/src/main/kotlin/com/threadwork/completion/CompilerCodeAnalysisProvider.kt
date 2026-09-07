package com.threadwork.completion

import com.threadwork.compiler.api.CompilerCodeMember

/** Adapts compiler-owned runtime and model symbols without inventing lexical/type resolution. */
class CompilerCodeAnalysisProvider : CodeAnalysisProvider {
    override fun analyze(context: CodeAnalysisContext): CodeAnalysis = object : CodeAnalysis {
        override fun typeOf(expression: String, offset: Int): AnalysisResult<CodeType> {
            val symbol = context.compiler.symbols.firstOrNull { it.name == expression }
                ?: return AnalysisResult.Unsupported
            return AnalysisResult.Available(symbol.typeName.takeIf(String::isNotBlank)
                ?.let { CodeType.Named(it, TypeOrigin.Compiler) } ?: CodeType.Unknown)
        }

        override fun members(expression: String, offset: Int): AnalysisResult<List<CodeMember>> {
            val symbol = context.compiler.symbols.firstOrNull { it.name == expression }
                ?: return AnalysisResult.Unsupported
            return AnalysisResult.Available(symbol.members.map { it.toCodeMember(symbol.name) })
        }
    }
}

internal fun CompilerCodeMember.toCodeMember(receiver: String): CodeMember {
    val local = name.removePrefix("$receiver.").removePrefix("$receiver->").removePrefix("$receiver::")
    return CodeMember(local.substringBefore('('), if (isMethod) CodeMemberKind.Method else CodeMemberKind.Field,
        typeName.takeIf(String::isNotBlank)?.let { CodeType.Named(it, TypeOrigin.Compiler) } ?: CodeType.Unknown,
        local, documentation)
}
