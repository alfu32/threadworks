package com.threadwork.mcp

import com.threadwork.core.diagnostics.Diagnostic
import com.threadwork.core.model.LinkData
import com.threadwork.core.model.ModelUser
import com.threadwork.core.model.Node
import com.threadwork.core.model.NodeId
import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.NodeLayout
import com.threadwork.core.model.NodePort
import com.threadwork.core.model.NodeText
import com.threadwork.core.model.PortDirection
import com.threadwork.core.model.ProjectStatus
import com.threadwork.core.model.Revision
import com.threadwork.core.model.TechnologyMetadata
import com.threadwork.core.model.ThreadworkDocument
import com.threadwork.core.model.TypeDefinition
import com.threadwork.core.model.fullyQualifiedName
import com.threadwork.core.model.getElementById
import com.threadwork.core.model.projectName
import com.threadwork.core.validation.DocumentValidator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

data class McpTechnologyDescriptor(
    val compilerId: String,
    val compilerName: String,
    val languageId: String,
    val technologyId: String,
)

data class McpCommandDescriptor(
    val id: String,
    val title: String,
    val description: String = "",
)

data class ThreadworkMcpContext(
    val applicationName: String = "Threadwork",
    val applicationVersion: String,
    val technologies: () -> List<McpTechnologyDescriptor> = { emptyList() },
    val commands: () -> List<McpCommandDescriptor> = { emptyList() },
    val executeCommand: (String) -> Boolean = { false },
)

/**
 * JSON-RPC/MCP application layer. It contains no Swing or HTTP code and can
 * therefore be used by desktop, packaged, and headless hosts alike.
 */
class ThreadworkMcpService(
    private val gateway: McpDocumentGateway,
    private val context: ThreadworkMcpContext,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
) {
    private val toolDefinitions = toolDefinitions()

    fun handle(request: JsonObject): JsonObject? {
        val id = request["id"]
        val method = request.string("method")
            ?: return rpcError(id, -32600, "Request method is required")
        val params = request.objectValue("params") ?: buildJsonObject { }

        return when (method) {
            "server/discover" -> responseOrNull(id, discoverResult())
            "initialize" -> responseOrNull(id, initializeResult(params))
            "notifications/initialized" -> null
            "ping" -> responseOrNull(id, buildJsonObject { })
            "tools/list" -> responseOrNull(id, buildJsonObject {
                putJsonArray("tools") { toolDefinitions.forEach(::add) }
            })
            "tools/call" -> responseOrNull(id, callTool(params))
            "resources/list" -> responseOrNull(id, resourcesList())
            "resources/read" -> responseOrNull(id, resourcesRead(params))
            "prompts/list" -> responseOrNull(id, promptsList())
            "prompts/get" -> responseOrNull(id, promptGet(params))
            else -> rpcError(id, -32601, "Unknown MCP method '$method'")
        }
    }

    fun health(): JsonObject = buildJsonObject {
        put("name", context.applicationName)
        put("version", context.applicationVersion)
        put("protocol", "MCP Streamable HTTP")
        put("status", "ok")
    }

    private fun initializeResult(params: JsonObject): JsonObject {
        val requestedVersion = params.string("protocolVersion")
        val supportedVersions = listOf("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        val selectedVersion = requestedVersion
            ?.takeIf { it in supportedVersions }
            ?: "2025-11-25"
        return buildJsonObject {
            put("protocolVersion", selectedVersion)
            put("capabilities", capabilities())
            putJsonObject("serverInfo") {
                put("name", "threadwork")
                put("title", context.applicationName)
                put("version", context.applicationVersion)
            }
            put("instructions", instructions())
        }
    }

    private fun discoverResult(): JsonObject = buildJsonObject {
        put("resultType", "complete")
        putJsonArray("supportedVersions") { add(JsonPrimitive("2026-07-28")) }
        put("capabilities", capabilities())
        putJsonObject("_meta") {
            putJsonObject("io.modelcontextprotocol/serverInfo") {
                put("name", "threadwork")
                put("version", context.applicationVersion)
            }
        }
        put("instructions", instructions())
        put("ttlMs", 0)
        put("cacheScope", "private")
    }

    private fun capabilities(): JsonObject = buildJsonObject {
        putJsonObject("tools") { }
        putJsonObject("resources") { put("listChanged", false) }
        putJsonObject("prompts") { put("listChanged", false) }
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = params.string("name")
            ?: return toolError("Tool name is required")
        val arguments = params.objectValue("arguments") ?: buildJsonObject { }
        return try {
            toolSuccess(name, executeTool(name, arguments))
        } catch (error: IllegalArgumentException) {
            toolError(error.message ?: "Invalid tool arguments")
        } catch (error: IllegalStateException) {
            toolError(error.message ?: "Model operation failed")
        } catch (error: Exception) {
            toolError(error.message ?: "Unexpected Threadwork MCP error")
        }
    }

    private fun executeTool(name: String, args: JsonObject): JsonObject = when (name) {
        "threadwork.get_design" -> gateway.read { document ->
            buildJsonObject {
                put("projectName", document.projectName())
                put("document", encode(document))
            }
        }
        "threadwork.get_fragment" -> gateway.read { document ->
            fragment(document, args.requiredString("nodeId"), args.boolean("includeDescendants", true), args.boolean("includeLinks", true))
        }
        "threadwork.get_entity" -> gateway.read { document -> entity(document, args.requiredString("nodeId")) }
        "threadwork.list_entities" -> gateway.read { document -> listEntities(document, args) }
        "threadwork.get_metadata" -> gateway.read { document -> getMetadata(document, args) }
        "threadwork.list_users" -> gateway.read { document ->
            buildJsonObject { putJsonArray("users") { document.users.forEach { add(encode(it)) } } }
        }
        "threadwork.list_technologies" -> buildJsonObject {
            putJsonArray("technologies") {
                context.technologies().distinctBy { listOf(it.compilerId, it.languageId, it.technologyId) }
                    .sortedWith(compareBy({ it.compilerId }, { it.languageId }, { it.technologyId }))
                    .forEach { technology ->
                        add(buildJsonObject {
                            put("compilerId", technology.compilerId)
                            put("compilerName", technology.compilerName)
                            put("languageId", technology.languageId)
                            put("technologyId", technology.technologyId)
                        })
                    }
            }
        }
        "threadwork.get_command_catalog" -> commandCatalog()
        "threadwork.execute_command" -> {
            val commandId = args.requiredString("commandId")
            require(context.executeCommand(commandId)) { "Unknown or disabled command '$commandId'" }
            buildJsonObject { put("commandId", commandId); put("executed", true) }
        }
        "threadwork.create_entity" -> gateway.mutate("MCP create entity") { repository ->
            val parentId = args.string("parentId")?.takeIf(String::isNotBlank)?.let(::NodeId)
                ?: repository.getDocument().rootNodeId
            val node = repository.createNode(
                parentId = parentId,
                name = args.requiredString("name"),
                kind = enumOrDefault(args.string("kind").orEmpty(), NodeKind.Processor),
            )
            applyEntityPatch(repository, node.id, args.objectValue("attributes") ?: buildJsonObject { })
            resultEntity(repository.getDocument(), node.id)
        }
        "threadwork.update_entity" -> gateway.mutate("MCP update entity") { repository ->
            val id = NodeId(args.requiredString("nodeId"))
            applyEntityPatch(repository, id, args.objectValue("patch") ?: args)
            resultEntity(repository.getDocument(), id)
        }
        "threadwork.delete_entity" -> gateway.mutate("MCP delete entity") { repository ->
            val id = NodeId(args.requiredString("nodeId"))
            repository.deleteNode(id)
            buildJsonObject { put("nodeId", id.value); put("deleted", true) }
        }
        "threadwork.move_entity" -> gateway.mutate("MCP move entity") { repository ->
            val id = NodeId(args.requiredString("nodeId"))
            val parentId = args.string("parentId")?.takeIf(String::isNotBlank)?.let(::NodeId)
            repository.moveNode(id, parentId)
            resultEntity(repository.getDocument(), id)
        }
        "threadwork.create_link" -> gateway.mutate("MCP create link") { repository ->
            val link = repository.createLink(
                parentId = args.string("parentId")?.takeIf(String::isNotBlank)?.let(::NodeId),
                name = args.requiredString("name"),
                sourceNodeId = NodeId(args.requiredString("sourceNodeId")),
                sourcePortName = args.requiredString("sourcePortName"),
                targetNodeId = NodeId(args.requiredString("targetNodeId")),
                targetPortName = args.requiredString("targetPortName"),
            )
            args.objectValue("link")?.let { patchLink(repository, link.id, it) }
            resultEntity(repository.getDocument(), link.id)
        }
        "threadwork.update_link" -> gateway.mutate("MCP update link") { repository ->
            val id = NodeId(args.requiredString("nodeId"))
            require(repository.requireNode(id).isLink) { "Entity '$id' is not a link" }
            patchLink(repository, id, args.objectValue("patch") ?: args)
            resultEntity(repository.getDocument(), id)
        }
        "threadwork.set_document_metadata" -> gateway.mutate("MCP set document metadata") { repository ->
            repository.updateDocumentMetadata(args.objectValue("metadata")?.stringMap() ?: error("'metadata' is required"))
            gateway.read { document -> buildJsonObject { put("metadata", stringMap(document.metadata)) } }
        }
        "threadwork.register_user" -> gateway.mutate("MCP register user") { repository ->
            val userElement = args["user"] ?: error("user is required")
            repository.registerUser(decode(ModelUser.serializer(), userElement))
            gateway.read { document -> buildJsonObject { putJsonArray("users") { document.users.forEach { add(encode(it)) } } } }
        }
        "threadwork.validate_design" -> gateway.read { document ->
            val diagnostics = DocumentValidator.validate(document)
            buildJsonObject {
                put("valid", diagnostics.none { it.severity.name == "Error" })
                putJsonArray("diagnostics") { diagnostics.forEach { add(encode(it)) } }
            }
        }
        "threadwork.analyze_network" -> gateway.read { document -> networkSummary(document) }
        else -> error("Unknown MCP tool '$name'")
    }

    private fun applyEntityPatch(repository: com.threadwork.storage.DocumentRepository, id: NodeId, patch: JsonObject) {
        repository.requireNode(id)
        patch.string("name")?.let { repository.renameNode(id, it) }
        patch.string("nameDetail")?.let { repository.updateNodeNameDetail(id, it) }
        patch.string("kind")?.let { repository.updateNodeKind(id, enumOrDefault(it, NodeKind.Processor)) }
        if (patch.containsKey("parentId")) {
            repository.moveNode(id, patch.string("parentId")?.takeIf(String::isNotBlank)?.let(::NodeId))
        }
        patch["layout"]?.let { repository.updateNodeLayout(id, decode(NodeLayout.serializer(), it)) }
        patch["text"]?.let { repository.updateNodeText(id, decodeNodeText(it)) }
        patch["technology"]?.let { repository.updateNodeTechnology(id, decode(TechnologyMetadata.serializer(), it)) }
        patch.string("fileLayoutStrategyId")?.let { repository.updateNodeFileLayoutStrategy(id, it) }
        if (patch.containsKey("binaryContent")) {
            repository.updateNodeBinaryContent(id, patch.string("binaryContent")?.let(Base64.getDecoder()::decode))
        }
        if (patch.containsKey("responsible")) repository.updateNodeResponsible(id, patch.string("responsible"))
        if (patch.containsKey("assignee")) repository.updateNodeAssignee(id, patch.string("assignee"))
        patch.string("status")?.let { repository.updateNodeStatus(id, enumOrNull<ProjectStatus>(it)) }
        if (patch.containsKey("status") && patch["status"] == JsonNull) repository.updateNodeStatus(id, null)
        patch["metadata"]?.let { repository.updateNodeMetadata(id, it.jsonObject.stringMap()) }
        patch["pluginData"]?.let { repository.updateNodePluginData(id, it.jsonObject.mapValues { (_, value) -> value.jsonObject }) }
        patch["ports"]?.let { repository.updateNodePorts(id, decode(ListSerializer(NodePort.serializer()), it)) }
        patch["typeDefinition"]?.let { repository.updateNodeTypeDefinition(id, decode(TypeDefinition.serializer(), it)) }
        patch["link"]?.let { patchLink(repository, id, it.jsonObject) }
        patch["revision"]?.let { repository.updateNodeRevision(id, decode(Revision.serializer(), it)) }
        patch["diagnostics"]?.let { repository.updateNodeDiagnostics(id, decode(ListSerializer(Diagnostic.serializer()), it)) }
    }

    private fun patchLink(repository: com.threadwork.storage.DocumentRepository, id: NodeId, patch: JsonObject) {
        val current = repository.requireNode(id).link ?: error("Entity '$id' has no link data")
        repository.updateLinkData(id, current.copy(
            sourceNodeId = patch.string("sourceNodeId")?.let(::NodeId) ?: current.sourceNodeId,
            sourcePortName = patch.string("sourcePortName") ?: current.sourcePortName,
            targetNodeId = patch.string("targetNodeId")?.let(::NodeId) ?: current.targetNodeId,
            targetPortName = patch.string("targetPortName") ?: current.targetPortName,
            transportKind = patch.string("transportKind") ?: current.transportKind,
            typeName = patch.string("typeName") ?: current.typeName,
            payloadDefinition = patch.string("payloadDefinition") ?: current.payloadDefinition,
            typeDefinitionId = patch.string("typeDefinitionId") ?: current.typeDefinitionId,
            compositeBoundaryIds = patch["compositeBoundaryIds"]?.let { it.stringList().map(::NodeId).toMutableList() }
                ?: current.compositeBoundaryIds,
            interactionKind = patch.string("interactionKind") ?: current.interactionKind,
            typeExpression = patch["typeExpression"]?.let { decode(com.threadwork.core.model.TypeExpression.serializer(), it) }
                ?: current.typeExpression,
        ))
    }

    private fun decodeNodeText(value: JsonElement): NodeText {
        val fields = value.jsonObject.toMutableMap()
        fun alias(source: String, target: String) {
            if (target !in fields) fields[source]?.let { fields[target] = it }
            fields.remove(source)
        }
        alias("instantiation", "initialization")
        alias("instantiationLanguageId", "initializationLanguageId")
        alias("declaration", "source")
        alias("declarationLanguageId", "sourceLanguageId")
        return decode(NodeText.serializer(), JsonObject(fields))
    }

    private fun listEntities(document: ThreadworkDocument, args: JsonObject): JsonObject {
        val parentId = args.string("parentId")?.takeIf(String::isNotBlank)?.let(::NodeId)
        val kind = args.string("kind")?.let { enumOrNull<NodeKind>(it) }
        val recursive = args.boolean("recursive", true)
        val ids: Iterable<NodeId> = if (parentId == null) {
            document.nodes.keys
        } else if (recursive) {
            descendants(document, parentId)
        } else {
            document.nodes[parentId]?.children.orEmpty()
        }
        return buildJsonObject {
            putJsonArray("entities") {
                ids.mapNotNull(document.nodes::get)
                    .filter { kind == null || it.kind == kind }
                    .sortedBy { document.fullyQualifiedName(it.id) }
                    .forEach { add(entitySummary(document, it)) }
            }
        }
    }

    private fun getMetadata(document: ThreadworkDocument, args: JsonObject): JsonObject {
        val nodeId = args.string("nodeId")?.takeIf(String::isNotBlank)
        return if (nodeId == null) {
            buildJsonObject { put("scope", "document"); put("metadata", stringMap(document.metadata)) }
        } else {
            val node = document.getElementById(nodeId) ?: error("Entity '$nodeId' does not exist")
            buildJsonObject { put("scope", "entity"); put("nodeId", nodeId); put("metadata", stringMap(node.metadata)) }
        }
    }

    private fun fragment(document: ThreadworkDocument, nodeIdValue: String, includeDescendants: Boolean, includeLinks: Boolean): JsonObject {
        val nodeId = NodeId(nodeIdValue)
        require(document.nodes.containsKey(nodeId)) { "Entity '$nodeIdValue' does not exist" }
        val ids = linkedSetOf(nodeId)
        if (includeDescendants) ids += descendants(document, nodeId)
        if (includeLinks) {
            val relatedLinks = document.nodes.values.filter { link ->
                link.isLink && link.link?.let { it.sourceNodeId in ids || it.targetNodeId in ids } == true
            }
            ids += relatedLinks.map(Node::id)
        }
        return buildJsonObject {
            put("rootNodeId", nodeIdValue)
            putJsonArray("entities") { ids.mapNotNull(document.nodes::get).forEach { add(encode(it)) } }
        }
    }

    private fun entity(document: ThreadworkDocument, nodeIdValue: String): JsonObject {
        val node = document.getElementById(nodeIdValue) ?: error("Entity '$nodeIdValue' does not exist")
        return buildJsonObject {
            put("entity", encode(node))
            put("fullyQualifiedName", document.fullyQualifiedName(node.id))
            put("projectName", document.projectName())
        }
    }

    private fun resultEntity(document: ThreadworkDocument, nodeId: NodeId): JsonObject = entity(document, nodeId.value)

    private fun entitySummary(document: ThreadworkDocument, node: Node): JsonObject = buildJsonObject {
        put("id", node.id.value)
        put("name", node.name)
        put("kind", node.kind.name)
        put("parentId", node.parentId?.value ?: "")
        put("fullyQualifiedName", document.fullyQualifiedName(node.id))
        put("isComposite", node.isComposite)
        put("status", node.status?.name ?: "")
        put("technologyId", node.technology.technologyId)
    }

    private fun networkSummary(document: ThreadworkDocument): JsonObject {
        val entities = document.nodes.values.count { !it.isLink }
        val links = document.nodes.values.count(Node::isLink)
        val types = document.nodes.values.count { it.kind == NodeKind.Type }
        val composites = document.nodes.values.count { it.isComposite && !it.isLink }
        val ports = document.nodes.values.sumOf { it.ports.size }
        return buildJsonObject {
            put("entities", entities)
            put("links", links)
            put("types", types)
            put("composites", composites)
            put("ports", ports)
            put("users", document.users.size)
            put("projectName", document.projectName())
        }
    }

    private fun commandCatalog(): JsonObject = buildJsonObject {
        putJsonArray("commands") {
            context.commands().sortedBy(McpCommandDescriptor::id).forEach { command ->
                add(buildJsonObject {
                    put("id", command.id)
                    put("title", command.title)
                    put("description", command.description)
                })
            }
        }
    }

    private fun resourcesList(): JsonObject = buildJsonObject {
        putJsonArray("resources") {
            add(resource("threadwork://instructions", "Threadwork instructions", "text/plain"))
            add(resource(ThreadworkMcpGuide.RESOURCE_URI, "Threadwork MCP agent guide", "text/markdown"))
            add(resource("threadwork://technologies", "Available compiler technologies", "application/json"))
            add(resource("threadwork://commands", "Desktop command catalog", "application/json"))
            add(resource("threadwork://design", "Current Threadwork design", "application/json"))
        }
    }

    private fun resourcesRead(params: JsonObject): JsonObject {
        val uri = params.string("uri") ?: error("Resource URI is required")
        val text = when (uri) {
            "threadwork://instructions" -> instructions()
            ThreadworkMcpGuide.RESOURCE_URI -> ThreadworkMcpGuide.markdown()
            "threadwork://technologies" -> json.encodeToString(executeTool("threadwork.list_technologies", buildJsonObject { }))
            "threadwork://commands" -> json.encodeToString(commandCatalog())
            "threadwork://design" -> json.encodeToString(gateway.read { encode(it) })
            else -> error("Unknown resource URI '$uri'")
        }
        return buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject { put("uri", uri); put("mimeType", resourceMimeType(uri)); put("text", text) })
            }
        }
    }

    private fun resourceMimeType(uri: String): String = when (uri) {
        "threadwork://instructions" -> "text/plain"
        ThreadworkMcpGuide.RESOURCE_URI -> "text/markdown"
        else -> "application/json"
    }

    private fun promptsList(): JsonObject = buildJsonObject {
        putJsonArray("prompts") {
            add(buildJsonObject {
                put("name", "threadwork.inspect_design")
                put("title", "Inspect the current design")
                put("description", "Read the current design and summarize its structure, technologies, and validation state.")
            })
            add(buildJsonObject {
                put("name", "threadwork.edit_entity")
                put("title", "Edit a Threadwork entity")
                put("description", "Inspect an entity before applying a focused model edit.")
            })
        }
    }

    private fun promptGet(params: JsonObject): JsonObject {
        val name = params.string("name") ?: error("Prompt name is required")
        return when (name) {
            "threadwork.inspect_design" -> buildJsonObject {
                put("description", "Inspect the open Threadwork design")
                putJsonArray("messages") { add(userMessage("Use threadwork.get_design, threadwork.validate_design, and threadwork.analyze_network before proposing changes.")) }
            }
            "threadwork.edit_entity" -> buildJsonObject {
                put("description", "Inspect and edit a Threadwork entity")
                putJsonArray("messages") { add(userMessage("Use threadwork.get_entity first, then apply the smallest threadwork.update_entity patch needed.")) }
            }
            else -> error("Unknown prompt '$name'")
        }
    }

    private fun instructions(): String = buildString {
        appendLine("Threadwork is a desktop topology-first IDE and compiler framework.")
        appendLine("It models projects as entities, processing nodes, types, libraries, composites, and directed links.")
        appendLine("The open document is the source of truth. Read before editing, use entity IDs rather than names, and use the repository tools for mutations so parent/child and link references remain synchronized.")
        appendLine("threadwork.get_design returns the complete JSON model. threadwork.get_fragment returns a JSON slice for one entity and optionally its descendants and related links.")
        appendLine("Use threadwork.list_technologies before assigning compiler technology. Use threadwork.validate_design after structural changes.")
        appendLine("New entities inherit parent technology and file layout: omit technology and fileLayoutStrategyId unless an explicit override is intended; the stored layout sentinel is 'none'.")
        appendLine("Read ${ThreadworkMcpGuide.RESOURCE_URI} first: it is the machine-facing Threadwork design manifesto plus the complete workflow, mutation semantics, topology conventions, C/library rules, layout guidance, and persistence behavior.")
        appendLine("The desktop command catalog is available through threadwork.get_command_catalog and commands can be invoked with threadwork.execute_command when the command is enabled.")
        appendLine("Available technologies:")
        context.technologies().distinctBy { listOf(it.compilerId, it.languageId, it.technologyId) }
            .sortedWith(compareBy({ it.compilerId }, { it.languageId }, { it.technologyId }))
            .forEach { appendLine("- ${it.compilerId}: ${it.languageId}/${it.technologyId} (${it.compilerName})") }
    }

    private fun toolSuccess(name: String, data: JsonObject): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", json.encodeToString(data)) })
        })
        put("structuredContent", data)
        put("isError", false)
    }

    private fun toolError(message: String): JsonObject = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", message) }) })
        put("isError", true)
    }

    private fun responseOrNull(id: JsonElement?, result: JsonObject): JsonObject? {
        if (id == null) return null
        val annotated = buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            if (!result.containsKey("resultType")) put("resultType", "complete")
            if (!result.containsKey("_meta")) {
                putJsonObject("_meta") {
                    putJsonObject("io.modelcontextprotocol/serverInfo") {
                        put("name", "threadwork")
                        put("version", context.applicationVersion)
                    }
                }
            }
            if (result.keys.any { it == "tools" || it == "resources" || it == "prompts" || it == "contents" }) {
                if (!result.containsKey("ttlMs")) put("ttlMs", 0)
                if (!result.containsKey("cacheScope")) put("cacheScope", "private")
            }
        }
        return buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", annotated) }
    }

    private fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject? {
        if (id == null) return null
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
    }

    private fun resource(uri: String, name: String, mimeType: String): JsonObject = buildJsonObject {
        put("uri", uri)
        put("name", name)
        put("mimeType", mimeType)
    }

    private fun toolDefinitions(): List<JsonObject> = listOf(
        tool("threadwork.get_design", "Get the complete open design as JSON.", emptySchema()),
        tool("threadwork.get_fragment", "Get a JSON fragment for an entity and optionally its descendants and links.", schema(mapOf(
            "nodeId" to stringProperty("Entity ID", required = true),
            "includeDescendants" to booleanProperty("Include descendants", default = true),
            "includeLinks" to booleanProperty("Include related links", default = true),
        ), listOf("nodeId"))),
        tool("threadwork.get_entity", "Get one entity, its full attributes, and its qualified name.", schema(mapOf("nodeId" to stringProperty("Entity ID", true)), listOf("nodeId"))),
        tool("threadwork.list_entities", "List entity summaries, optionally below a parent and filtered by kind.", schema(mapOf(
            "parentId" to stringProperty("Parent entity ID"),
            "kind" to stringProperty("Entity kind", enum = NodeKind.entries.map(NodeKind::name)),
            "recursive" to booleanProperty("Include descendants", default = true),
        ))),
        tool("threadwork.get_metadata", "Get document or entity metadata.", schema(mapOf("nodeId" to stringProperty("Entity ID")))),
        tool("threadwork.list_users", "List users stored in the open model.", emptySchema()),
        tool("threadwork.list_technologies", "List compiler technologies available in this app instance.", emptySchema()),
        tool("threadwork.get_command_catalog", "List desktop commands available to the agent.", emptySchema()),
        tool("threadwork.execute_command", "Execute an enabled desktop command by ID.", schema(mapOf("commandId" to stringProperty("Command ID", true)), listOf("commandId"))),
        tool("threadwork.create_entity", "Create an entity under a parent; new entities inherit the parent's technology and file layout unless explicitly overridden.", schema(mapOf(
            "name" to stringProperty("Entity name", true),
            "kind" to stringProperty("Entity kind", enum = NodeKind.entries.map(NodeKind::name), default = "Processor"),
            "parentId" to stringProperty("Parent entity ID"),
            "attributes" to objectProperty("Initial entity patch"),
        ), listOf("name"))),
        tool("threadwork.update_entity", "Patch every editable entity attribute, including text, ports, type definitions, links, metadata, and plugin data.", schema(mapOf(
            "nodeId" to stringProperty("Entity ID", true),
            "patch" to objectProperty("Entity attributes to change", true),
        ), listOf("nodeId", "patch"))),
        tool("threadwork.delete_entity", "Delete an entity and repository-managed descendants/links.", schema(mapOf("nodeId" to stringProperty("Entity ID", true)), listOf("nodeId"))),
        tool("threadwork.move_entity", "Move an entity to another parent or to the document root.", schema(mapOf(
            "nodeId" to stringProperty("Entity ID", true),
            "parentId" to stringProperty("New parent entity ID"),
        ), listOf("nodeId"))),
        tool("threadwork.create_link", "Create a directed link, including self-links, between two entities.", schema(mapOf(
            "name" to stringProperty("Link name", true),
            "sourceNodeId" to stringProperty("Source entity ID", true),
            "sourcePortName" to stringProperty("Source port", true),
            "targetNodeId" to stringProperty("Target entity ID", true),
            "targetPortName" to stringProperty("Target port", true),
            "parentId" to stringProperty("Requested parent ID"),
            "link" to objectProperty("Additional LinkData fields"),
        ), listOf("name", "sourceNodeId", "sourcePortName", "targetNodeId", "targetPortName"))),
        tool("threadwork.update_link", "Update LinkData for an existing link.", schema(mapOf(
            "nodeId" to stringProperty("Link entity ID", true),
            "patch" to objectProperty("LinkData fields", true),
        ), listOf("nodeId", "patch"))),
        tool("threadwork.set_document_metadata", "Replace document metadata with the supplied string map.", schema(mapOf("metadata" to objectProperty("String metadata map", true)), listOf("metadata"))),
        tool("threadwork.register_user", "Store or refresh a model user, including provider fields, role, avatar, and refresh timestamp.", schema(mapOf("user" to objectProperty("ModelUser", true)), listOf("user"))),
        tool("threadwork.validate_design", "Validate the current design and return diagnostics.", emptySchema()),
        tool("threadwork.analyze_network", "Return quantitative counts for the current network.", emptySchema()),
    )

    private fun tool(name: String, description: String, schema: JsonObject): JsonObject = buildJsonObject {
        put("name", name)
        put("title", name.removePrefix("threadwork.").replace('_', ' '))
        put("description", description)
        put("inputSchema", schema)
        putJsonObject("annotations") {
            put("readOnlyHint", name in READ_ONLY_TOOLS)
            put("destructiveHint", name in DESTRUCTIVE_TOOLS)
            put("idempotentHint", name in READ_ONLY_TOOLS || name == "threadwork.update_entity" || name == "threadwork.update_link")
            put("openWorldHint", false)
        }
    }

    private fun emptySchema(): JsonObject = schema(emptyMap())

    private fun schema(properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (key, value) -> put(key, value) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", true)
    }

    private fun stringProperty(
        description: String,
        required: Boolean = false,
        enum: List<String> = emptyList(),
        default: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum.isNotEmpty()) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
        if (default != null) put("default", default)
    }

    private fun booleanProperty(description: String, default: Boolean): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
        put("default", default)
    }

    private fun objectProperty(description: String, required: Boolean = false): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", true)
    }

    private fun userMessage(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonObject("content") { put("type", "text"); put("text", text) }
    }

    private fun descendants(document: ThreadworkDocument, rootId: NodeId): Set<NodeId> {
        val result = linkedSetOf<NodeId>()
        val pending = ArrayDeque<NodeId>()
        pending.addAll(document.nodes[rootId]?.children.orEmpty())
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!result.add(id)) continue
            pending.addAll(document.nodes[id]?.children.orEmpty())
        }
        return result
    }

    private fun encode(value: Any): JsonElement = when (value) {
        is ThreadworkDocument -> json.encodeToJsonElement(ThreadworkDocument.serializer(), value)
        is Node -> json.encodeToJsonElement(Node.serializer(), value)
        is ModelUser -> json.encodeToJsonElement(ModelUser.serializer(), value)
        is Diagnostic -> json.encodeToJsonElement(Diagnostic.serializer(), value)
        else -> error("Cannot encode ${value::class.simpleName}")
    }

    private fun <T> decode(serializer: KSerializer<T>, value: JsonElement): T = json.decodeFromJsonElement(serializer, value)

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        value.trim().takeIf(String::isNotBlank)?.let { raw ->
            enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: error("Unknown ${T::class.simpleName} '$value'")
        }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
        enumOrNull<T>(value) ?: fallback

    private fun JsonObject.string(key: String): String? = this[key]?.let { element ->
        (element as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.requiredString(key: String): String = string(key)?.takeIf(String::isNotBlank)
        ?: error("'$key' is required")

    private fun JsonObject.boolean(key: String, default: Boolean): Boolean = this[key]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: default

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.stringMap(): Map<String, String> = entries.associate { (key, value) ->
        key to ((value as? JsonPrimitive)?.contentOrNull ?: value.toString())
    }

    private fun stringMap(values: Map<String, String>): JsonObject = buildJsonObject {
        values.toSortedMap().forEach { (key, value) -> put(key, value) }
    }

    private fun JsonElement.stringList(): List<String> = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: error("Expected a JSON string array")

    private companion object {
        val READ_ONLY_TOOLS = setOf(
            "threadwork.get_design",
            "threadwork.get_fragment",
            "threadwork.get_entity",
            "threadwork.list_entities",
            "threadwork.get_metadata",
            "threadwork.list_users",
            "threadwork.list_technologies",
            "threadwork.get_command_catalog",
            "threadwork.validate_design",
            "threadwork.analyze_network",
        )
        val DESTRUCTIVE_TOOLS = setOf("threadwork.delete_entity")
    }
}
