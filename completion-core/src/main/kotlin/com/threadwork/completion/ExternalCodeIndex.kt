package com.threadwork.completion

import com.threadwork.compiler.api.CompilerCodeIntelligence
import com.threadwork.compiler.api.CompilerCodeMember
import com.threadwork.compiler.api.CompilerCodeSymbol
import com.threadwork.compiler.api.CompilerCodeSymbolKind
import com.threadwork.compiler.api.CompilerTypeFieldInfo
import com.threadwork.compiler.api.CompilerTypeInformation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Interface-only API catalog. Bodies are deliberately absent from this format. */
@Serializable
data class ExternalCodeIndex(
    val languageId: String,
    val technologyId: String = "",
    val symbols: List<ExternalCodeSymbol> = emptyList(),
    val types: List<ExternalCodeType> = emptyList(),
)

@Serializable
data class ExternalCodeSymbol(
    val name: String,
    val kind: String = "GeneratedFunction",
    val typeName: String = "",
    val detail: String = "",
    val documentation: String = "",
    val members: List<ExternalCodeMember> = emptyList(),
)

@Serializable
data class ExternalCodeMember(
    val name: String,
    val typeName: String = "",
    val detail: String = "",
    val documentation: String = "",
    val isMethod: Boolean = false,
)

@Serializable
data class ExternalCodeType(
    val name: String,
    val declaration: String = "",
    val documentation: String = "",
    val fields: List<ExternalCodeField> = emptyList(),
)

@Serializable
data class ExternalCodeField(
    val name: String,
    val typeName: String,
    val isReference: Boolean = false,
)

fun ExternalCodeIndex.toCompilerIntelligence(): CompilerCodeIntelligence = CompilerCodeIntelligence(
    symbols = symbols.map { symbol ->
        CompilerCodeSymbol(
            name = symbol.name,
            kind = runCatching { CompilerCodeSymbolKind.valueOf(symbol.kind) }
                .getOrDefault(CompilerCodeSymbolKind.GeneratedFunction),
            typeName = symbol.typeName,
            detail = symbol.detail,
            documentation = symbol.documentation,
            members = symbol.members.map { member ->
                CompilerCodeMember(member.name, member.detail, member.documentation, member.typeName, member.isMethod)
            },
        )
    },
    types = types.map { type ->
        CompilerTypeInformation(
            name = type.name,
            languageId = languageId,
            declaration = type.declaration,
            documentation = type.documentation,
            fields = type.fields.map { field -> CompilerTypeFieldInfo(field.name, field.typeName, field.isReference) },
        )
    },
)

/** Cached catalog loaded from the user's `.threadworks/indexes` directory. */
class ExternalCodeIndexCatalog private constructor(private val indexes: List<ExternalCodeIndex>) {
    fun intelligence(languageId: String, technologyId: String): CompilerCodeIntelligence {
        val language = analysisLanguage(languageId)
        return indexes.filter { index ->
            analysisLanguage(index.languageId) == language &&
                (index.technologyId.isBlank() || technologyId.isBlank() || index.technologyId == technologyId)
        }.fold(CompilerCodeIntelligence()) { result, index ->
            val next = index.toCompilerIntelligence()
            CompilerCodeIntelligence(
                symbols = (result.symbols + next.symbols).distinctBy { it.name to it.kind },
                types = (result.types + next.types).distinctBy { it.name },
            )
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun empty() = ExternalCodeIndexCatalog(emptyList())

        fun fromDirectory(directory: Path): ExternalCodeIndexCatalog {
            if (!Files.isDirectory(directory)) return empty()
            val loaded = Files.list(directory).use { paths ->
                paths.iterator().asSequence()
                    .filter { path -> path.isRegularFile() && path.extension.equals("json", true) }
                    .mapNotNull { path -> runCatching { json.decodeFromString<ExternalCodeIndex>(Files.readString(path)) }.getOrNull() }
                    .toList()
            }
            return ExternalCodeIndexCatalog(loaded)
        }

        fun fromUserDirectory(): ExternalCodeIndexCatalog =
            fromDirectory(Path.of(System.getProperty("user.home"), ".threadworks", "indexes"))
    }
}
