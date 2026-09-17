package com.threadwork.compiler

import com.threadwork.compiler.c.CCompiler
import com.threadwork.compiler.filesystem.FilesystemCompiler
import com.threadwork.compiler.generated.nodejs.JSCompiler
import com.threadwork.compiler.go.GoCompiler
import com.threadwork.compiler.naivekotlin.NaiveKotlinCompiler
import com.threadwork.compiler.php.PhpCompiler
import com.threadwork.compiler.quickjs.QuickJsCompiler
import com.threadwork.core.model.BuiltInTypeIds
import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.TechnologyMetadata
import com.threadwork.core.model.TypeDefinition
import com.threadwork.core.model.TypeFieldDefinition
import com.threadwork.core.model.TypeExpression
import com.threadwork.core.model.TypeQualifiers
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompilerPrimitiveTypeTest {
    @Test
    fun `each built in source compiler publishes native primitive spellings`() {
        assertTrue("unsigned long long" in CCompiler().primitiveTypeIds)
        assertTrue("char *" in CCompiler().primitiveTypeIds)
        assertTrue("float64" in GoCompiler().primitiveTypeIds)
        assertTrue("[]byte" in GoCompiler().primitiveTypeIds)
        assertTrue("bool" in PhpCompiler().primitiveTypeIds)
        assertTrue("mixed" in PhpCompiler().primitiveTypeIds)
        assertTrue("boolean" in JSCompiler().primitiveTypeIds)
        assertTrue("Uint8Array" in JSCompiler().primitiveTypeIds)
        assertTrue("boolean" in QuickJsCompiler().primitiveTypeIds)
        assertTrue("Int" in NaiveKotlinCompiler().primitiveTypeIds)
        assertTrue("ByteArray" in NaiveKotlinCompiler().primitiveTypeIds)
    }

    @Test
    fun `multi tech compiler retains shared Threadwork primitive vocabulary`() {
        assertEquals(BuiltInTypeIds.all, FilesystemCompiler().primitiveTypeIds)
    }

    @Test
    fun `C type generation preserves native primitive declarations`() {
        val repository = InMemoryDocumentRepository(newDocument("native-c-types"))
        val rootId = repository.getDocument().rootNodeId
        repository.updateNodeTechnology(rootId, TechnologyMetadata(languageId = "c", technologyId = "c-native"))
        val type = repository.createNode(rootId, "Counter", NodeKind.Type)
        repository.updateNodeTypeDefinition(
            type.id,
            TypeDefinition(mutableListOf(TypeFieldDefinition("value", "unsigned long long"))),
        )
        val source = assertNotNull(CCompiler().compile(repository.getDocument()).generatedProject)
            .files
            .single()
            .content
        assertTrue(source.contains("unsigned long long value;"))
        assertTrue(!source.contains("struct unsigned long long value;"))
        assertEquals(
            "unsigned long long",
            assertNotNull(CCompiler().typeInformation(repository.getDocument(), type, "unsigned long long")).name,
        )
    }

    @Test
    fun `composed types render with active compiler spelling`() {
        val repository = InMemoryDocumentRepository(newDocument("composed types"))
        val root = repository.getDocument().rootNodeId
        val type = repository.createNode(root, "Envelope", NodeKind.Type)
        val kotlinExpression = TypeExpression.constructed(
            "map",
            TypeExpression.named("String"),
            TypeExpression.constructed("array", TypeExpression.named(type.id.value)),
        )
        val goExpression = TypeExpression.constructed(
            "map",
            TypeExpression.named("string"),
            TypeExpression.constructed("array", TypeExpression.named(type.id.value)),
        )
        assertEquals("Map<String, Array<Envelope>>", NaiveKotlinCompiler().renderTypeExpression(repository.getDocument(), kotlinExpression))
        assertEquals("map[string][]Envelope", GoCompiler().renderTypeExpression(repository.getDocument(), goExpression))
    }

    @Test
    fun `collection type nodes render through their selected generic references`() {
        val repository = InMemoryDocumentRepository(newDocument("collection types"))
        val root = repository.getDocument().rootNodeId
        val values = repository.createNode(root, "Values", NodeKind.Type)
        repository.updateNodeTypeDefinition(
            values.id,
            TypeDefinition(
                qualifier = TypeQualifiers.Array,
                genericTypeIds = mutableListOf("string"),
            ),
        )

        assertEquals(
            "[]string",
            GoCompiler().renderTypeExpression(
                repository.getDocument(),
                TypeExpression.named(values.id.value),
            ),
        )
    }
}
