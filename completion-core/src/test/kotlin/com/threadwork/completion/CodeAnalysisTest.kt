package com.threadwork.completion

import com.threadwork.compiler.api.CompilerCodeIntelligence
import com.threadwork.compiler.api.CompilerTypeFieldInfo
import com.threadwork.compiler.api.CompilerTypeInformation
import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.NodeTextSection
import com.threadwork.core.model.TechnologyMetadata
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files

class CodeAnalysisTest {
    private val parser = TreeSitterAnalysisProvider()

    @Test
    fun `external interface indexes provide reusable type and member metadata`() {
        val directory = Files.createTempDirectory("threadwork-index")
        try {
            val index = ExternalCodeIndex(
                languageId = "java",
                technologyId = "jdk",
                symbols = listOf(ExternalCodeSymbol("Files", "Type")),
                types = listOf(ExternalCodeType("Files", documentation = "File operations", fields = listOf(ExternalCodeField("separator", "String")))),
            )
            val json = kotlinx.serialization.json.Json.encodeToString(ExternalCodeIndex.serializer(), index)
            Files.writeString(directory.resolve("java-jdk.json"), json)
            Files.writeString(directory.resolve("ignored.json"), "not json")

            val intelligence = ExternalCodeIndexCatalog.fromDirectory(directory).intelligence("java", "jdk")
            assertEquals(listOf("Files"), intelligence.symbols.map { it.name })
            assertEquals(listOf("separator"), intelligence.types.single().fields.map { it.name })
            assertEquals("File operations", intelligence.types.single().documentation)
        } finally {
            Files.deleteIfExists(directory.resolve("java-jdk.json"))
            Files.deleteIfExists(directory.resolve("ignored.json"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `same-scope library globals are available to dependent source`() {
        val repository = InMemoryDocumentRepository(newDocument("library-globals"))
        val root = repository.getDocument().rootNodeId
        repository.updateNodeTechnology(root, TechnologyMetadata(languageId = "c", technologyId = "native-c"))
        val library = repository.createNode(root, "bean_lib", NodeKind.Processor)
        val worker = repository.createNode(root, "worker", NodeKind.Processor)
        repository.updateNodeText(library.id, repository.requireNode(library.id).text.copy(
            declaration = "bean bstate = (bean){0, 0};\nint set_bstate(long long id) { bstate.id = id; }",
        ))
        val source = "bstate.<caret>"
        val cursor = source.indexOf("<caret>")
        val code = source.replace("<caret>", "")
        repository.updateNodeText(worker.id, repository.requireNode(worker.id).text.copy(declaration = code))
        val request = CompletionRequest(worker.id, NodeTextSection.Declaration, "c", "native-c", cursor, code, "", "")

        val analysis = parser.analyze(CodeAnalysisContext(
            repository.getDocument(),
            request,
            CompilerCodeIntelligence(types = listOf(
                CompilerTypeInformation("bean", "c", "", fields = listOf(
                    CompilerTypeFieldInfo("id", "number", false),
                    CompilerTypeFieldInfo("timestamp", "number", false),
                )),
            )),
        ))
        val type = assertIs<AnalysisResult.Available<CodeType>>(analysis.typeOf("bstate", cursor)).value
        assertEquals("bean", assertIs<CodeType.Named>(type).name)
        val members = assertIs<AnalysisResult.Available<List<CodeMember>>>(analysis.members("bstate", cursor)).value
        assertEquals(listOf("id", "timestamp"), members.map { it.name })
    }

    private fun context(language: String, code: String, compiler: CompilerCodeIntelligence = CompilerCodeIntelligence()): CodeAnalysisContext {
        val repository = InMemoryDocumentRepository(newDocument("analysis"))
        val root = repository.getDocument().rootNodeId
        repository.updateNodeTechnology(root, TechnologyMetadata(languageId = language))
        val node = repository.createNode(root, "worker", NodeKind.Processor)
        val cursor = code.indexOf("<caret>").let { if (it < 0) code.length else it }
        val source = code.replace("<caret>", "")
        repository.updateNodeText(node.id, node.text.copy(declaration = source))
        return CodeAnalysisContext(repository.getDocument(), CompletionRequest(node.id, NodeTextSection.Declaration, language, "", cursor, source, "", ""), compiler)
    }

    @Test
    fun `each bundled grammar resolves typed receiver fields including kotlin`() {
        val cases = mapOf(
            "c" to "struct Packet { int id; };\nPacket packet;\npacket.<caret>",
            "javascript" to "class Packet { id = 1; }\nconst packet = new Packet();\npacket.<caret>",
            "php" to "class Packet { public int ${'$'}id; }\n${'$'}packet = new Packet();\n${'$'}packet-><caret>",
            "python" to "class Packet:\n    id: int\npacket: Packet = Packet()\npacket.<caret>",
            "java" to "class Packet { int id; }\nclass Worker { void run() { Packet packet; packet.<caret> } }",
            "go" to "package main\ntype Packet struct { id int }\nfunc run() { var packet Packet; packet.<caret> }",
            "kotlin" to "class Packet { val id: Int = 1 }\nval packet: Packet = Packet()\npacket.<caret>",
        )
        val failures = cases.mapNotNull { (language, source) -> runCatching {
            val ctx = context(language, source)
            val receiver = if (language == "php") "${'$'}packet" else "packet"
            val analysis = parser.analyze(ctx)
            val type = assertIs<AnalysisResult.Available<CodeType>>(analysis.typeOf(receiver, ctx.request.cursorOffset), language)
            assertEquals("Packet", assertIs<CodeType.Named>(type.value, "$language: ${parser.syntax(language, ctx.request.fullText)}").name, language)
            val members = assertIs<AnalysisResult.Available<List<CodeMember>>>(analysis.members(receiver, ctx.request.cursorOffset))
            assertTrue(members.value.any { it.name == "id" && it.kind == CodeMemberKind.Field }, "$language: ${parser.syntax(language, ctx.request.fullText)}\nMembers: ${members.value}")
        }.exceptionOrNull()?.message }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `type fields from compiler support local pointers and chained member access`() {
        val compiler = CompilerCodeIntelligence(types = listOf(
            CompilerTypeInformation("Packet", "c", "", fields = listOf(CompilerTypeFieldInfo("header", "Header", false))),
            CompilerTypeInformation("Header", "c", "", fields = listOf(CompilerTypeFieldInfo("count", "int", false))),
        ))
        val ctx = context("c", "Packet *packet;\npacket->header.<caret>", compiler)
        val result = assertIs<AnalysisResult.Available<List<CodeMember>>>(parser.analyze(ctx).members("packet->header", ctx.request.cursorOffset))
        assertEquals(listOf("count"), result.value.map { it.name })
    }

    @Test
    fun `java method return types support chained completions`() {
        val ctx = context("java", "class Header { int count; } class Packet { Header next() { return null; } } class Worker { void run() { Packet packet; packet.next().<caret> } }")
        val result = assertIs<AnalysisResult.Available<List<CodeMember>>>(parser.analyze(ctx).members("packet.next()", ctx.request.cursorOffset))
        assertEquals(listOf("count"), result.value.map { it.name })
    }

    @Test
    fun `shadowing binds usages to the closest declaration and excludes comments and strings`() {
        val ctx = context("javascript", "let value = 1;\n{ let value = 'inner'; value; }\n// value\nconst text = 'value';\nvalue;<caret>")
        val analysis = parser.analyze(ctx)
        val outerUse = ctx.request.fullText.lastIndexOf("value;")
        val innerUse = ctx.request.fullText.indexOf("value; }")
        val outer = assertIs<AnalysisResult.Available<CodeLocation?>>(analysis.definition(outerUse)).value
        val inner = assertIs<AnalysisResult.Available<CodeLocation?>>(analysis.definition(innerUse)).value
        assertNotNull(outer)
        assertNotNull(inner)
        assertTrue(outer != inner)
        val refs = assertIs<AnalysisResult.Available<List<CodeReference>>>(analysis.references(CodeSymbolId(outer))).value
        assertEquals(listOf(outerUse), refs.map { it.location.range.start })
    }

    @Test
    fun `unicode source offsets remain editor utf16 offsets`() {
        val ctx = context("javascript", "// accented é and emoji 😀\nconst packet = 1;\npacket;<caret>")
        val result = assertIs<AnalysisResult.Available<CodeLocation?>>(parser.analyze(ctx).definition(ctx.request.fullText.lastIndexOf("packet")))
        assertEquals(ctx.request.fullText.indexOf("packet"), result.value?.range?.start)
    }

    @Test
    fun `fallback operates per capability and never overrides an empty or unknown answer`() {
        val primary = object : CodeAnalysis {
            override fun members(expression: String, offset: Int) = AnalysisResult.Available(emptyList<CodeMember>())
            override fun typeOf(expression: String, offset: Int) = AnalysisResult.Available(CodeType.Unknown)
        }
        val backup = object : CodeAnalysis {
            override fun declarations() = AnalysisResult.Available(emptyList<DeclarationSymbol>())
            override fun members(expression: String, offset: Int) = error("Empty result must not fall back")
            override fun typeOf(expression: String, offset: Int) = error("Unknown type must not guess")
        }
        val combined = CompositeCodeAnalysis(listOf(primary, backup))
        assertEquals(AnalysisResult.Available(emptyList()), combined.declarations())
        assertEquals(AnalysisResult.Available(emptyList()), combined.members("x", 0))
        assertEquals(AnalysisResult.Available(CodeType.Unknown), combined.typeOf("x", 0))
    }

    @Test
    fun `unsupported languages retain legacy declaration and completion providers`() {
        val ctx = context("cpp", "class Packet {};\nvoid run() {}<caret>")
        val service = ModelAwareCompletionService({ ctx.document })
        assertTrue(service.getDeclarationSymbols(ctx.request).any { it.name == "Packet" })
        assertTrue(service.getSuggestions(ctx.request).any { it.label == "Packet" })
    }

    @Test
    fun `member edits replace only suffix after pointer access and method call`() {
        for ((source, receiver, prefix) in listOf(
            Triple("packet->hea", "packet", "hea"), Triple("packet.next().co", "packet.next()", "co"), Triple("packet?.he", "packet", "he"),
        )) {
            val access = assertNotNull(MemberAccess.at(source, source.length))
            assertEquals(receiver, access.receiver)
            assertEquals(prefix, access.prefix)
            assertEquals(prefix, source.substring(access.memberStart))
        }
    }
}
