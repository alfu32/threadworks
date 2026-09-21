package com.threadwork.core.analysis

import com.threadwork.core.classification.LinkClassifier
import com.threadwork.core.classification.LinkStereotype
import com.threadwork.core.classification.NodeClassifier
import com.threadwork.core.classification.NodeStereotype
import com.threadwork.core.model.Node
import com.threadwork.core.model.NodeId
import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.ThreadworkDocument
import com.threadwork.core.model.fullyQualifiedName
import com.threadwork.core.model.linkNodes

enum class AnalysisSeverity {
    INFO,
    NOTICE,
    WARNING,
    PROBLEM,
}

enum class AnalysisActionKind {
    INSPECT,
    NAVIGATE,
    LAYOUT,
    MODEL,
}

data class AnalysisIndicator(
    val id: String,
    val label: String,
    val value: String,
    val description: String = "",
    val affectedNodes: List<NodeId> = emptyList(),
)

data class AnalysisAction(
    val id: String,
    val title: String,
    val description: String,
    val kind: AnalysisActionKind = AnalysisActionKind.INSPECT,
    val affectedNodes: List<NodeId> = emptyList(),
    val affectedLinks: List<NodeId> = emptyList(),
)

data class AnalysisFinding(
    val id: String,
    val severity: AnalysisSeverity,
    val category: String,
    val title: String,
    val description: String,
    val affectedNodes: List<NodeId> = emptyList(),
    val affectedLinks: List<NodeId> = emptyList(),
    val evidence: List<AnalysisIndicator> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val actions: List<AnalysisAction> = emptyList(),
)

data class AnalysisSection(
    val id: String,
    val title: String,
    val summary: String,
    val indicators: List<AnalysisIndicator> = emptyList(),
    val findings: List<AnalysisFinding> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val actions: List<AnalysisAction> = emptyList(),
)

data class AnalysisThresholds(
    val highFanOut: Int = 8,
    val highDependencyFanIn: Int = 8,
    val deepDependencyReachability: Int = 6,
)

data class DirectedEdge(
    val linkId: NodeId,
    val source: NodeId,
    val target: NodeId,
)

/**
 * A sparse Boolean adjacency matrix. The row/column order is the shared order
 * from [GraphSnapshot.nodeIds], so P, E, D and U can be compared safely.
 */
data class SparseBooleanGraph(
    val nodeIds: List<NodeId>,
    private val outgoingRows: Map<NodeId, Set<NodeId>>,
    private val incomingRows: Map<NodeId, Set<NodeId>>,
    private val linkIdsByPair: Map<Pair<NodeId, NodeId>, List<NodeId>>,
) {
    val edgeCount: Int get() = outgoingRows.values.sumOf(Set<NodeId>::size)

    fun outgoing(nodeId: NodeId): Set<NodeId> = outgoingRows[nodeId].orEmpty()

    fun incoming(nodeId: NodeId): Set<NodeId> = incomingRows[nodeId].orEmpty()

    fun outDegree(nodeId: NodeId): Int = outgoing(nodeId).size

    fun inDegree(nodeId: NodeId): Int = incoming(nodeId).size

    fun contains(source: NodeId, target: NodeId): Boolean = target in outgoing(source)

    fun edgePairs(): Set<Pair<NodeId, NodeId>> = outgoingRows.flatMapTo(linkedSetOf()) { (source, targets) ->
        targets.map { target -> source to target }
    }

    fun linkIds(source: NodeId, target: NodeId): List<NodeId> =
        linkIdsByPair[source to target].orEmpty()

    companion object {
        fun empty(nodeIds: List<NodeId>): SparseBooleanGraph = SparseBooleanGraph(
            nodeIds = nodeIds,
            outgoingRows = nodeIds.associateWith { emptySet() },
            incomingRows = nodeIds.associateWith { emptySet() },
            linkIdsByPair = emptyMap(),
        )

        fun from(nodeIds: List<NodeId>, edges: Collection<DirectedEdge>): SparseBooleanGraph {
            val outgoing = nodeIds.associateWith { linkedSetOf<NodeId>() }.toMutableMap()
            val incoming = nodeIds.associateWith { linkedSetOf<NodeId>() }.toMutableMap()
            val linkIds = linkedMapOf<Pair<NodeId, NodeId>, MutableList<NodeId>>()
            edges.forEach { edge ->
                if (edge.source !in outgoing || edge.target !in outgoing) return@forEach
                outgoing.getValue(edge.source) += edge.target
                incoming.getValue(edge.target) += edge.source
                linkIds.getOrPut(edge.source to edge.target, ::mutableListOf) += edge.linkId
            }
            return SparseBooleanGraph(
                nodeIds = nodeIds,
                outgoingRows = outgoing.mapValues { it.value.toSet() },
                incomingRows = incoming.mapValues { it.value.toSet() },
                linkIdsByPair = linkIds.mapValues { it.value.toList() },
            )
        }
    }
}

data class GraphSnapshot(
    val nodeIds: List<NodeId>,
    val nodesById: Map<NodeId, Node>,
    val nodeLabels: Map<NodeId, String>,
    val principal: SparseBooleanGraph,
    val error: SparseBooleanGraph,
    val dependency: SparseBooleanGraph,
    val structural: SparseBooleanGraph,
    val principalLinks: List<NodeId>,
    val errorLinks: List<NodeId>,
    val dependencyLinks: List<NodeId>,
) {
    companion object {
        fun from(document: ThreadworkDocument): GraphSnapshot {
            val nodes = document.nodes.values
                .filterNot { it.isLink }
                .sortedWith(compareBy<Node> { document.fullyQualifiedName(it.id) }.thenBy { it.id.value })
            val nodeIds = nodes.map(Node::id)
            val nodeSet = nodeIds.toSet()
            val principalEdges = mutableListOf<DirectedEdge>()
            val errorEdges = mutableListOf<DirectedEdge>()
            val dependencyEdges = mutableListOf<DirectedEdge>()

            document.linkNodes()
                .sortedBy { it.id.value }
                .forEach { linkNode ->
                    val link = linkNode.link ?: return@forEach
                    if (link.sourceNodeId !in nodeSet || link.targetNodeId !in nodeSet) return@forEach
                    val edge = DirectedEdge(linkNode.id, link.sourceNodeId, link.targetNodeId)
                    when (LinkClassifier.classify(document, linkNode)) {
                        LinkStereotype.ErrorPipe -> errorEdges += edge
                        LinkStereotype.UsageImport,
                        LinkStereotype.DependencyInjection,
                        LinkStereotype.TypeUsage -> dependencyEdges += edge.copy(
                            source = link.targetNodeId,
                            target = link.sourceNodeId,
                        )
                        LinkStereotype.Transport -> principalEdges += edge
                        LinkStereotype.SourceCapability,
                        LinkStereotype.RunnableCapability -> Unit
                    }
                }

            val principal = SparseBooleanGraph.from(nodeIds, principalEdges)
            val error = SparseBooleanGraph.from(nodeIds, errorEdges)
            val dependency = SparseBooleanGraph.from(nodeIds, dependencyEdges)
            val structuralEdges = (principalEdges + errorEdges + dependencyEdges)
                .distinctBy { it.source to it.target }
            return GraphSnapshot(
                nodeIds = nodeIds,
                nodesById = nodes.associateBy(Node::id),
                nodeLabels = nodes.associate { node ->
                    node.id to document.fullyQualifiedName(node.id).ifBlank { node.name }
                },
                principal = principal,
                error = error,
                dependency = dependency,
                structural = SparseBooleanGraph.from(nodeIds, structuralEdges),
                principalLinks = principalEdges.map(DirectedEdge::linkId),
                errorLinks = errorEdges.map(DirectedEdge::linkId),
                dependencyLinks = dependencyEdges.map(DirectedEdge::linkId),
            )
        }
    }
}

object NetworkGraphAlgorithms {
    fun reachableFrom(graph: SparseBooleanGraph, start: NodeId): Set<NodeId> {
        if (start !in graph.nodeIds) return emptySet()
        val visited = linkedSetOf<NodeId>()
        val pending = ArrayDeque<NodeId>()
        pending += start
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            graph.outgoing(current).forEach { successor ->
                if (successor !in visited) pending += successor
            }
        }
        return visited
    }

    fun shortestPath(graph: SparseBooleanGraph, start: NodeId, target: NodeId): List<NodeId> {
        if (start !in graph.nodeIds || target !in graph.nodeIds) return emptyList()
        val previous = mutableMapOf<NodeId, NodeId?>()
        val pending = ArrayDeque<NodeId>()
        pending += start
        previous[start] = null
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current == target) break
            graph.outgoing(current).forEach { successor ->
                if (successor !in previous) {
                    previous[successor] = current
                    pending += successor
                }
            }
        }
        if (target !in previous) return emptyList()
        val path = mutableListOf<NodeId>()
        var current: NodeId? = target
        while (current != null) {
            path += current
            current = previous[current]
        }
        return path.asReversed()
    }

    fun weakComponents(graph: SparseBooleanGraph): List<Set<NodeId>> {
        val remaining = graph.nodeIds.toMutableSet()
        val components = mutableListOf<Set<NodeId>>()
        while (remaining.isNotEmpty()) {
            val root = remaining.first()
            val component = linkedSetOf<NodeId>()
            val pending = ArrayDeque<NodeId>()
            pending += root
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!component.add(current)) continue
                remaining.remove(current)
                (graph.outgoing(current) + graph.incoming(current)).forEach { adjacent ->
                    if (adjacent !in component) pending += adjacent
                }
            }
            components += component
        }
        return components.sortedWith(compareByDescending<Set<NodeId>> { it.size }.thenBy { it.firstOrNull()?.value.orEmpty() })
    }

    /** Iterative Kosaraju traversal avoids recursion depth failures on large models. */
    fun stronglyConnectedComponents(graph: SparseBooleanGraph): List<Set<NodeId>> {
        val visited = mutableSetOf<NodeId>()
        val order = mutableListOf<NodeId>()
        graph.nodeIds.forEach { start ->
            if (start in visited) return@forEach
            val stack = mutableListOf<Pair<NodeId, Boolean>>()
            stack += start to false
            while (stack.isNotEmpty()) {
                val (current, expanded) = stack.removeLast()
                if (expanded) {
                    order += current
                    continue
                }
                if (!visited.add(current)) continue
                stack += current to true
                graph.outgoing(current).toList().asReversed().forEach { successor ->
                    if (successor !in visited) stack += successor to false
                }
            }
        }

        val assigned = mutableSetOf<NodeId>()
        val components = mutableListOf<Set<NodeId>>()
        order.asReversed().forEach { start ->
            if (!assigned.add(start)) return@forEach
            val component = linkedSetOf(start)
            val pending = ArrayDeque<NodeId>()
            pending += start
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                graph.incoming(current).forEach { predecessor ->
                    if (assigned.add(predecessor)) {
                        component += predecessor
                        pending += predecessor
                    }
                }
            }
            components += component
        }
        return components.sortedWith(compareByDescending<Set<NodeId>> { it.size }.thenBy { it.firstOrNull()?.value.orEmpty() })
    }

    fun cyclicComponents(graph: SparseBooleanGraph): List<Set<NodeId>> =
        stronglyConnectedComponents(graph).filter { component ->
            component.size > 1 || component.any { graph.contains(it, it) }
        }

    fun topologicalOrder(graph: SparseBooleanGraph): List<NodeId>? {
        val indegree = graph.nodeIds.associateWith(graph::inDegree).toMutableMap()
        val pending = ArrayDeque<NodeId>()
        graph.nodeIds.filter { indegree.getValue(it) == 0 }.forEach(pending::addLast)
        val order = mutableListOf<NodeId>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            order += current
            graph.outgoing(current).forEach { successor ->
                val next = indegree.getValue(successor) - 1
                indegree[successor] = next
                if (next == 0) pending += successor
            }
        }
        return order.takeIf { it.size == graph.nodeIds.size }
    }

    fun density(graph: SparseBooleanGraph): Double {
        val possible = graph.nodeIds.size.toDouble().let { it * it }
        return if (possible == 0.0) 0.0 else graph.edgeCount / possible
    }
}

data class AnalysisReport(
    val sections: List<AnalysisSection>,
    val snapshot: GraphSnapshot,
    val thresholds: AnalysisThresholds,
)

object NetworkAnalysisEngine {
    fun analyze(
        document: ThreadworkDocument,
        thresholds: AnalysisThresholds = AnalysisThresholds(),
    ): AnalysisReport {
        val snapshot = GraphSnapshot.from(document)
        val context = Context(document, snapshot, thresholds)
        val sections = listOf(
            overview(context),
            principal(context),
            error(context),
            dependencies(context),
            workflows(context),
            crossLayer(context),
            structuralQuality(context),
            availableActions(context),
        )
        return AnalysisReport(sections, snapshot, thresholds)
    }

    private data class Context(
        val document: ThreadworkDocument,
        val snapshot: GraphSnapshot,
        val thresholds: AnalysisThresholds,
    ) {
        val principalCycles by lazy { NetworkGraphAlgorithms.cyclicComponents(snapshot.principal) }
        val errorCycles by lazy { NetworkGraphAlgorithms.cyclicComponents(snapshot.error) }
        val dependencyCycles by lazy { NetworkGraphAlgorithms.cyclicComponents(snapshot.dependency) }
        val workflows by lazy { NetworkGraphAlgorithms.weakComponents(snapshot.principal) }
        val workflowByNode by lazy {
            workflows.flatMapIndexed { index, nodes -> nodes.map { it to index } }.toMap()
        }
        val processingNodes by lazy {
            snapshot.nodeIds.filter { nodeId ->
                val node = snapshot.nodesById.getValue(nodeId)
                nodeId != document.rootNodeId && !isLibrary(node, document) &&
                    node.kind !in setOf(NodeKind.Type, NodeKind.Note) &&
                    (node.kind == NodeKind.Processor || node.kind == NodeKind.Group ||
                        snapshot.principal.inDegree(nodeId) > 0 || snapshot.principal.outDegree(nodeId) > 0)
            }
        }
        val libraryNodes by lazy {
            snapshot.nodeIds.filter { isLibrary(snapshot.nodesById.getValue(it), document) }
        }
        val isolatedNodes by lazy {
            snapshot.nodeIds.filter { snapshot.structural.inDegree(it) == 0 && snapshot.structural.outDegree(it) == 0 }
        }
    }

    private fun overview(context: Context): AnalysisSection {
        val s = context.snapshot
        val indicators = listOf(
            indicator("nodes.total", "Nodes", s.nodeIds.size),
            indicator("nodes.processing", "Processing candidates", context.processingNodes.size),
            indicator("nodes.libraries", "Libraries", context.libraryNodes.size),
            indicator("links.principal", "Principal data links", s.principal.edgeCount),
            indicator("links.error", "Error links", s.error.edgeCount),
            indicator("links.dependencies", "Dependency links", s.dependency.edgeCount),
            indicator("workflows", "Principal workflows", context.workflows.size),
            indicator("nodes.isolated", "Structurally isolated", context.isolatedNodes.size, affectedNodes = context.isolatedNodes),
            indicator("cycles.principal", "Principal cycles", context.principalCycles.size),
            indicator("cycles.error", "Error cycles", context.errorCycles.size),
            indicator("cycles.dependencies", "Dependency cycles", context.dependencyCycles.size),
            indicator("error.sinks", "Error sinks", errorSinks(s.error).size),
            indicator(
                "principal.selfLinks",
                "Principal self-links",
                selfLinkedNodes(s.principal).size,
                affectedNodes = selfLinkedNodes(s.principal),
            ),
            indicator("graph.structuralDensity", "Structural density", percentage(NetworkGraphAlgorithms.density(s.structural))),
        )
        val findings = buildList {
            if (context.isolatedNodes.isNotEmpty()) {
                add(finding(
                    id = "overview.isolated",
                    severity = AnalysisSeverity.NOTICE,
                    category = "structure",
                    title = "Some entities are structurally isolated",
                    description = "These entities do not participate in principal data flow, error flow, or dependency links.",
                    nodes = context.isolatedNodes,
                    recommendation = "Confirm whether they are intentionally standalone documentation, types, or unfinished work.",
                ))
            }
            if (context.principalCycles.isNotEmpty()) {
                add(finding(
                    id = "overview.principalCycles",
                    severity = AnalysisSeverity.INFO,
                    category = "principal flow",
                    title = "Principal feedback regions exist",
                    description = "Cycles are valid in Threadwork and often indicate feedback, state, or a memory loop.",
                    nodes = context.principalCycles.flattenDistinct(),
                    recommendation = "Inspect the cycle boundaries and confirm that the feedback semantics are intentional.",
                ))
            }
            if (context.dependencyCycles.isNotEmpty()) {
                add(finding(
                    id = "overview.dependencyCycles",
                    severity = AnalysisSeverity.WARNING,
                    category = "dependencies",
                    title = "Dependency cycles need review",
                    description = "A dependency cycle makes library initialization and ownership harder to reason about.",
                    nodes = context.dependencyCycles.flattenDistinct(),
                    recommendation = "Inspect the cycle before changing topology; cycles may be deliberate but should be explicit.",
                ))
            }
        }
        return AnalysisSection(
            id = "overview",
            title = "Overview",
            summary = "A factual inventory of the model and the three independent network layers.",
            indicators = indicators,
            findings = findings,
            recommendations = listOf(
                "Use the layer-specific chapters to distinguish runtime data flow from errors and library dependencies.",
                "Treat isolated entities and cycles as review prompts, not automatic errors.",
            ),
        )
    }

    private fun principal(context: Context): AnalysisSection {
        val graph = context.snapshot.principal
        val generators = graph.nodeIds.filter { graph.inDegree(it) == 0 && graph.outDegree(it) > 0 }
        val sinks = graph.nodeIds.filter { graph.inDegree(it) > 0 && graph.outDegree(it) == 0 }
        val transformers = graph.nodeIds.filter { graph.inDegree(it) == 1 && graph.outDegree(it) == 1 }
        val fanOut = graph.nodeIds.filter { graph.outDegree(it) > 1 }
        val merges = graph.nodeIds.filter { graph.inDegree(it) > 1 }
        val routers = graph.nodeIds.filter { graph.inDegree(it) > 0 && graph.outDegree(it) > 1 }
        val highFanOut = graph.nodeIds.filter { graph.outDegree(it) >= context.thresholds.highFanOut }
        val mostReachable = graph.nodeIds.maxByOrNull { NetworkGraphAlgorithms.reachableFrom(graph, it).size }
        val indicators = listOf(
            indicator("principal.edges", "Directed data edges", graph.edgeCount),
            indicator("principal.generators", "Generator candidates", generators.size, affectedNodes = generators),
            indicator("principal.sinks", "Sink candidates", sinks.size, affectedNodes = sinks),
            indicator("principal.transformers", "Transformer candidates", transformers.size, affectedNodes = transformers),
            indicator("principal.fanOut", "Fan-out candidates", fanOut.size, affectedNodes = fanOut),
            indicator("principal.merges", "Merge candidates", merges.size, affectedNodes = merges),
            indicator("principal.routers", "Router candidates", routers.size, affectedNodes = routers),
            indicator("principal.maxReachability", "Largest downstream reachability", mostReachable?.let { NetworkGraphAlgorithms.reachableFrom(graph, it).size } ?: 0, affectedNodes = listOfNotNull(mostReachable)),
        )
        val findings = buildList {
            if (context.principalCycles.isNotEmpty()) {
                add(finding(
                    id = "principal.cycles",
                    severity = AnalysisSeverity.INFO,
                    category = "feedback",
                    title = "Principal data contains feedback cycles",
                    description = "Strongly connected components identify regions where information can circulate instead of following a one-way pipeline.",
                    nodes = context.principalCycles.flattenDistinct(),
                    recommendation = "Inspect whether each cycle is a deliberate feedback or state mechanism and document its convergence behavior.",
                ))
            }
            if (highFanOut.isNotEmpty()) {
                add(finding(
                    id = "principal.highFanOut",
                    severity = AnalysisSeverity.NOTICE,
                    category = "branching",
                    title = "Some nodes have high principal fan-out",
                    description = "High fan-out can be an intentional router, but it also increases coupling and makes downstream effects harder to trace.",
                    nodes = highFanOut,
                    recommendation = "Review the routing responsibility and consider grouping related destinations behind an explicit component.",
                    evidence = highFanOut.map { node -> indicator("fanout.${node.value}", nodeLabel(context, node), graph.outDegree(node)) },
                ))
            }
        }
        return AnalysisSection(
            id = "principal",
            title = "Principal Data Flow",
            summary = "Directed data-flow roles and reachability candidates derived from the principal layer only.",
            indicators = indicators,
            findings = findings,
            recommendations = listOf(
                "Generator, sink, transformer, merge, and router labels are structural candidates, not semantic declarations.",
                "Use downstream reachability to inspect the effect of a node before making topology changes.",
            ),
        )
    }

    private fun error(context: Context): AnalysisSection {
        val graph = context.snapshot.error
        val sources = graph.nodeIds.filter { graph.inDegree(it) == 0 && graph.outDegree(it) > 0 }
        val sinks = errorSinks(graph)
        val collectors = graph.nodeIds.filter { graph.inDegree(it) > 1 }
        val uncovered = context.processingNodes.filter { graph.outDegree(it) == 0 }
        val noSinkSources = sources.filter { source ->
            NetworkGraphAlgorithms.reachableFrom(graph, source).none { it in sinks }
        }
        val covered = context.processingNodes.size - uncovered.size
        val coverage = if (context.processingNodes.isEmpty()) 0.0 else covered.toDouble() / context.processingNodes.size
        val indicators = listOf(
            indicator("error.edges", "Directed error edges", graph.edgeCount),
            indicator("error.sources", "Error sources", sources.size, affectedNodes = sources),
            indicator("error.sinks", "Error sinks", sinks.size, affectedNodes = sinks),
            indicator("error.collectors", "Error collectors", collectors.size, affectedNodes = collectors),
            indicator("error.processingCoverage", "Processing nodes with an error route", percentage(coverage), affectedNodes = context.processingNodes.filter { it !in uncovered }),
            indicator("error.uncoveredProcessing", "Processing nodes without an error route", uncovered.size, affectedNodes = uncovered),
        )
        val findings = buildList {
            if (uncovered.isNotEmpty()) {
                add(finding(
                    id = "error.uncoveredProcessing",
                    severity = AnalysisSeverity.WARNING,
                    category = "coverage",
                    title = "Processing candidates have no outgoing error route",
                    description = "The model does not show where failures from these processing candidates are routed.",
                    nodes = uncovered,
                    recommendation = "Add or document an error path where the runtime contract requires one; missing routing is a review observation, not a validation failure.",
                ))
            }
            if (context.errorCycles.isNotEmpty()) {
                val cycleNodes = context.errorCycles.flattenDistinct()
                add(finding(
                    id = "error.cycles",
                    severity = AnalysisSeverity.NOTICE,
                    category = "termination",
                    title = "Error flow contains cycles",
                    description = "Errors can circulate through these strongly connected regions.",
                    nodes = cycleNodes,
                    recommendation = "Verify retry, backoff, and termination behavior for each error cycle.",
                ))
            }
            if (noSinkSources.isNotEmpty()) {
                add(finding(
                    id = "error.noSink",
                    severity = AnalysisSeverity.WARNING,
                    category = "termination",
                    title = "Some error sources cannot reach an error sink",
                    description = "These source candidates have an error path, but the path does not reach a node with no outgoing error edge.",
                    nodes = noSinkSources,
                    recommendation = "Inspect whether the error path intentionally hands control to another service or whether a terminal handler is missing.",
                ))
            }
        }
        return AnalysisSection(
            id = "error",
            title = "Error Flow",
            summary = "Independent analysis of error topology, coverage, collection, and termination candidates.",
            indicators = indicators,
            findings = findings,
            recommendations = listOf(
                "Separate normal data routing from error routing when reviewing coverage.",
                "A shared error collector may be useful, but it is also a coupling point worth inspecting.",
            ),
        )
    }

    private fun dependencies(context: Context): AnalysisSection {
        val graph = context.snapshot.dependency
        val libraries = context.libraryNodes
        val unused = libraries.filter { graph.inDegree(it) == 0 }
        val foundational = libraries.filter { graph.outDegree(it) == 0 }
        val shared = libraries.filter { graph.inDegree(it) > 1 }
        val deep = graph.nodeIds.filter { NetworkGraphAlgorithms.reachableFrom(graph, it).size > context.thresholds.deepDependencyReachability }
        val processingToProcessing = graph.edgePairs().filter { (source, target) ->
            source in context.processingNodes && target in context.processingNodes
        }
        val indicators = listOf(
            indicator("dependency.edges", "Dependency edges", graph.edgeCount),
            indicator("dependency.libraries", "Library candidates", libraries.size, affectedNodes = libraries),
            indicator("dependency.unused", "Unused library candidates", unused.size, affectedNodes = unused),
            indicator("dependency.shared", "Shared library candidates", shared.size, affectedNodes = shared),
            indicator("dependency.foundational", "Foundational library candidates", foundational.size, affectedNodes = foundational),
            indicator("dependency.deep", "Nodes with deep dependency reachability", deep.size, affectedNodes = deep),
            indicator("dependency.processingToProcessing", "Processing-to-processing dependency edges", processingToProcessing.size),
        )
        val findings = buildList {
            if (unused.isNotEmpty()) {
                add(finding(
                    id = "dependency.unused",
                    severity = AnalysisSeverity.NOTICE,
                    category = "libraries",
                    title = "Some library candidates have no dependants",
                    description = "These entities look like libraries but are not targets of a dependency link.",
                    nodes = unused,
                    recommendation = "Remove stale library nodes or add the explicit capability link that makes their use intentional.",
                ))
            }
            if (context.dependencyCycles.isNotEmpty()) {
                add(finding(
                    id = "dependency.cycles",
                    severity = AnalysisSeverity.WARNING,
                    category = "cycles",
                    title = "Dependencies contain cycles",
                    description = "The dependency graph cannot be fully ordered while these strongly connected components remain.",
                    nodes = context.dependencyCycles.flattenDistinct(),
                    recommendation = "Inspect the cycle and decide whether the shared responsibility belongs in a lower-level library.",
                ))
            }
            if (shared.isNotEmpty()) {
                add(finding(
                    id = "dependency.shared",
                    severity = AnalysisSeverity.INFO,
                    category = "coupling",
                    title = "Libraries are shared by multiple dependants",
                    description = "Shared libraries are useful reuse points and potential change-amplification points.",
                    nodes = shared,
                    recommendation = "Review the library contract and keep it stable if multiple workflows depend on it.",
                ))
            }
            if (processingToProcessing.isNotEmpty()) {
                val nodes = processingToProcessing.flatMap { it.toList() }.distinct()
                add(finding(
                    id = "dependency.processingToProcessing",
                    severity = AnalysisSeverity.NOTICE,
                    category = "architecture",
                    title = "Processing nodes depend directly on other processing nodes",
                    description = "The dependency layer contains runtime-looking node pairs, which may be intentional but can blur data flow and capability flow.",
                    nodes = nodes,
                    recommendation = "Confirm that these are capability dependencies rather than principal data links modeled with the wrong interaction.",
                ))
            }
        }
        return AnalysisSection(
            id = "dependencies",
            title = "Dependencies",
            summary = "Direct and transitive dependency structure, library reuse, ordering, and coupling candidates.",
            indicators = indicators,
            findings = findings,
            recommendations = listOf(
                "Dependency direction is dependent -> dependency; nodes with no outgoing dependency are foundational candidates.",
                "Keep capability links separate from principal data links so both the compiler and this report retain their meaning.",
            ),
        )
    }

    private fun workflows(context: Context): AnalysisSection {
        val graph = context.snapshot.principal
        val indicators = listOf(
            indicator("workflows.count", "Weakly connected principal components", context.workflows.size),
            indicator("workflows.largest", "Largest workflow size", context.workflows.maxOfOrNull(Set<NodeId>::size) ?: 0),
            indicator("workflows.withFeedback", "Workflows with feedback", context.workflows.count { nodes -> nodes.any { node -> context.principalCycles.any { node in it } } }),
            indicator("workflows.withoutGenerator", "Workflows without generator candidate", context.workflows.count { nodes -> nodes.size > 1 && nodes.none { graph.inDegree(it) == 0 && graph.outDegree(it) > 0 } }),
            indicator("workflows.withoutSink", "Workflows without sink candidate", context.workflows.count { nodes -> nodes.size > 1 && nodes.none { graph.inDegree(it) > 0 && graph.outDegree(it) == 0 } }),
        )
        val findings = buildList {
            context.workflows.forEachIndexed { index, nodes ->
                if (nodes.size <= 1) return@forEachIndexed
                val hasGenerator = nodes.any { graph.inDegree(it) == 0 && graph.outDegree(it) > 0 }
                val hasSink = nodes.any { graph.inDegree(it) > 0 && graph.outDegree(it) == 0 }
                if (!hasGenerator || !hasSink) {
                    add(finding(
                        id = "workflow.shape.$index",
                        severity = AnalysisSeverity.NOTICE,
                        category = "workflow",
                        title = "Workflow ${index + 1} has an incomplete boundary shape",
                        description = "The workflow has ${nodes.size} connected entities but ${if (hasGenerator) "a generator" else "no generator"} and ${if (hasSink) "a sink" else "no sink"} candidate.",
                        nodes = nodes.toList(),
                        recommendation = "Inspect the workflow boundary and decide whether inputs, outputs, or feedback are represented explicitly.",
                    ))
                }
            }
        }
        val workflowSummaries = context.workflows.mapIndexed { index, nodes ->
            val generators = nodes.count { graph.inDegree(it) == 0 && graph.outDegree(it) > 0 }
            val sinks = nodes.count { graph.inDegree(it) > 0 && graph.outDegree(it) == 0 }
            "Workflow ${index + 1}: ${nodes.size} entities, $generators generator candidates, $sinks sink candidates"
        }
        return AnalysisSection(
            id = "workflows",
            title = "Subsystems / Workflows",
            summary = "Principal-data weak components provide conservative workflow candidates without collapsing the other layers.",
            indicators = indicators + workflowSummaries.mapIndexed { index, value ->
                AnalysisIndicator("workflow.$index", "Workflow ${index + 1}", value, affectedNodes = context.workflows[index].toList())
            },
            findings = findings,
            recommendations = listOf(
                "Use workflow candidates as inspection groups; hierarchy and composite boundaries remain the authoritative structure.",
                "A component with no generator or sink may be a feedback service, a partial subsystem, or an unfinished topology.",
            ),
        )
    }

    private fun crossLayer(context: Context): AnalysisSection {
        val p = context.snapshot.principal
        val e = context.snapshot.error
        val d = context.snapshot.dependency
        val samePairPrincipalError = p.edgePairs().intersect(e.edgePairs())
        val errorOnly = e.edgePairs().filterNot(p.edgePairs()::contains)
        val crossWorkflowDependencies = d.edgePairs().filter { (source, target) ->
            source in context.processingNodes && target in context.processingNodes &&
                context.workflowByNode[source] != context.workflowByNode[target]
        }
        val crossWorkflowErrors = e.edgePairs().filter { (source, target) ->
            context.workflowByNode[source] != null && context.workflowByNode[target] != null &&
                context.workflowByNode[source] != context.workflowByNode[target]
        }
        val sharedLibraries = context.libraryNodes.filter { d.inDegree(it) > 1 }
        val indicators = listOf(
            indicator("crossLayer.samePair", "Pairs carrying principal and error links", samePairPrincipalError.size),
            indicator("crossLayer.errorOnly", "Error-only directed pairs", errorOnly.size),
            indicator("crossLayer.sharedLibraries", "Libraries shared across dependants", sharedLibraries.size, affectedNodes = sharedLibraries),
            indicator("crossLayer.crossWorkflowDependencies", "Cross-workflow processing dependencies", crossWorkflowDependencies.size),
            indicator("crossLayer.crossWorkflowErrors", "Cross-workflow error links", crossWorkflowErrors.size),
        )
        val findings = buildList {
            if (crossWorkflowDependencies.isNotEmpty()) {
                add(finding(
                    id = "crossLayer.dependencies",
                    severity = AnalysisSeverity.NOTICE,
                    category = "cross-workflow",
                    title = "Processing dependencies cross workflow boundaries",
                    description = "These dependency pairs connect entities that belong to different principal-data components.",
                    nodes = crossWorkflowDependencies.flatMap { it.toList() }.distinct(),
                    recommendation = "Confirm the cross-workflow capability contract and consider an explicit shared service boundary if appropriate.",
                ))
            }
            if (crossWorkflowErrors.isNotEmpty()) {
                add(finding(
                    id = "crossLayer.errors",
                    severity = AnalysisSeverity.INFO,
                    category = "cross-workflow",
                    title = "Error flow crosses workflow boundaries",
                    description = "Errors from one principal component can reach another component.",
                    nodes = crossWorkflowErrors.flatMap { it.toList() }.distinct(),
                    recommendation = "Review ownership and observability at the receiving workflow boundary.",
                ))
            }
            if (samePairPrincipalError.isNotEmpty()) {
                add(finding(
                    id = "crossLayer.samePair",
                    severity = AnalysisSeverity.INFO,
                    category = "layers",
                    title = "Some node pairs carry both data and error relationships",
                    description = "The separate layers remain distinct, but these endpoint pairs have both normal and failure paths.",
                    nodes = samePairPrincipalError.flatMap { it.toList() }.distinct(),
                    recommendation = "Keep the port and interaction names explicit so normal and error traffic remain distinguishable.",
                ))
            }
        }
        return AnalysisSection(
            id = "crossLayer",
            title = "Cross-Layer Analysis",
            summary = "Comparisons between principal data, error, and dependency layers without merging their semantics.",
            indicators = indicators,
            findings = findings,
            recommendations = listOf(
                "Use this chapter to find coupling that is invisible when each layer is inspected in isolation.",
                "Dependencies without principal communication are descriptive evidence, not proof of a design defect.",
            ),
        )
    }

    private fun structuralQuality(context: Context): AnalysisSection {
        val p = context.snapshot.principal
        val d = context.snapshot.dependency
        val errorSinks = errorSinks(context.snapshot.error)
        val findings = buildList {
            val isolated = context.isolatedNodes
            if (isolated.isNotEmpty()) add(finding(
                id = "quality.isolated",
                severity = AnalysisSeverity.NOTICE,
                category = "quality",
                title = "Completely isolated entities",
                description = "No P, E, or D relationship touches these entities.",
                nodes = isolated,
                recommendation = "Confirm whether these are notes, types, placeholders, or entities waiting to be connected.",
            ))
            val highFanOut = p.nodeIds.filter { p.outDegree(it) >= context.thresholds.highFanOut }
            if (highFanOut.isNotEmpty()) add(finding(
                id = "quality.highFanOut",
                severity = AnalysisSeverity.WARNING,
                category = "coupling",
                title = "Extremely high principal fan-out",
                description = "These nodes have at least ${context.thresholds.highFanOut} principal destinations.",
                nodes = highFanOut,
                recommendation = "Inspect whether the node is a deliberate router or is carrying too many responsibilities.",
            ))
            val highDependencyFanIn = context.libraryNodes.filter { d.inDegree(it) >= context.thresholds.highDependencyFanIn }
            if (highDependencyFanIn.isNotEmpty()) add(finding(
                id = "quality.highDependencyFanIn",
                severity = AnalysisSeverity.WARNING,
                category = "coupling",
                title = "Extremely high dependency fan-in",
                description = "These libraries have at least ${context.thresholds.highDependencyFanIn} dependants.",
                nodes = highDependencyFanIn,
                recommendation = "Treat the library contract as a high-impact change point and review its ownership.",
            ))
            val uncovered = context.processingNodes.filter { context.snapshot.error.outDegree(it) == 0 }
            if (uncovered.isNotEmpty()) add(finding(
                id = "quality.noErrorRoute",
                severity = AnalysisSeverity.WARNING,
                category = "resilience",
                title = "Processing candidates without error routing",
                description = "No outgoing error relationship is represented for these nodes.",
                nodes = uncovered,
                recommendation = "Add an error route or document why the node cannot emit failures.",
            ))
            if (context.dependencyCycles.isNotEmpty()) add(finding(
                id = "quality.dependencyCycle",
                severity = AnalysisSeverity.WARNING,
                category = "ordering",
                title = "Dependency cycle",
                description = "Dependency SCCs prevent a simple initialization order.",
                nodes = context.dependencyCycles.flattenDistinct(),
                recommendation = "Inspect and break the cycle only if the capability contract allows it.",
            ))
            val errorCyclesWithoutSink = context.errorCycles.filter { cycle ->
                cycle.none { node -> NetworkGraphAlgorithms.reachableFrom(context.snapshot.error, node).any { it in errorSinks } }
            }
            if (errorCyclesWithoutSink.isNotEmpty()) add(finding(
                id = "quality.errorCycleNoSink",
                severity = AnalysisSeverity.PROBLEM,
                category = "termination",
                title = "Error cycle has no reachable sink",
                description = "An error cycle does not reach a node with no outgoing error edge.",
                nodes = errorCyclesWithoutSink.flattenDistinct(),
                recommendation = "Inspect retry and shutdown behavior before relying on this error path.",
            ))
            context.principalCycles.takeIf { it.isNotEmpty() }?.let { cycles ->
                add(finding(
                    id = "quality.selfLinksAndCycles",
                    severity = AnalysisSeverity.INFO,
                    category = "feedback",
                    title = "Feedback and self-link regions",
                    description = "Self-links and multi-node cycles may implement memory or feedback and are not invalid by themselves.",
                    nodes = cycles.flattenDistinct(),
                    recommendation = "Document convergence and packet lifetime for intentional feedback paths.",
                ))
            }
        }
        return AnalysisSection(
            id = "quality",
            title = "Structural Quality",
            summary = "Threshold-based review prompts. Thresholds are explicit and configurable rather than hidden in rendering code.",
            indicators = listOf(
                indicator("threshold.highFanOut", "High fan-out threshold", context.thresholds.highFanOut),
                indicator("threshold.highDependencyFanIn", "High dependency fan-in threshold", context.thresholds.highDependencyFanIn),
                indicator("threshold.deepDependency", "Deep dependency reachability threshold", context.thresholds.deepDependencyReachability),
                indicator("quality.findings", "Quality findings", findings.size),
            ),
            findings = findings,
            recommendations = listOf(
                "Review warnings and problems first, then use notices and information findings to guide cleanup.",
                "No quality finding changes the model automatically; edits remain explicit and undoable.",
            ),
        )
    }

    private fun availableActions(context: Context): AnalysisSection {
        val actions = linkedMapOf<String, AnalysisAction>()
        listOf(
            "all.processing" to ("Select processing candidates" to context.processingNodes),
            "all.libraries" to ("Select library candidates" to context.libraryNodes),
            "all.isolated" to ("Select isolated entities" to context.isolatedNodes),
            "all.cycles" to ("Select all cyclic regions" to context.principalCycles.flattenDistinct()),
        ).forEach { (id, value) ->
            if (value.second.isNotEmpty()) {
                actions[id] = AnalysisAction(id, value.first, "Select the affected entities in the designer.", affectedNodes = value.second)
            }
        }
        context.workflows.forEachIndexed { index, nodes ->
            if (nodes.size > 1) {
                actions["workflow.$index"] = AnalysisAction(
                    id = "workflow.$index",
                    title = "Select workflow ${index + 1}",
                    description = "Select the entities in this principal-data component.",
                    affectedNodes = nodes.toList(),
                )
            }
        }
        return AnalysisSection(
            id = "actions",
            title = "Available Actions",
            summary = "Safe, read-only inspection actions are available now. Layout and model mutations remain explicit future actions with preview and undo.",
            actions = actions.values.toList(),
            recommendations = listOf(
                "Select a finding or action to inspect the affected entities in the existing designer.",
                "Use the current editor commands for changes so the normal history and undo/redo path remains authoritative.",
            ),
        )
    }

    private fun finding(
        id: String,
        severity: AnalysisSeverity,
        category: String,
        title: String,
        description: String,
        nodes: Collection<NodeId> = emptyList(),
        links: Collection<NodeId> = emptyList(),
        evidence: List<AnalysisIndicator> = emptyList(),
        recommendation: String? = null,
    ): AnalysisFinding {
        val nodeList = nodes.distinct()
        val linkList = links.distinct()
        val actions = listOfNotNull(
            (nodeList + linkList).takeIf { it.isNotEmpty() }?.let {
                AnalysisAction(
                    id = "$id.inspect",
                    title = "Select affected entities",
                    description = "Select the entities involved in this finding in the designer.",
                    affectedNodes = nodeList,
                    affectedLinks = linkList,
                )
            },
        )
        return AnalysisFinding(
            id = id,
            severity = severity,
            category = category,
            title = title,
            description = description,
            affectedNodes = nodeList,
            affectedLinks = linkList,
            evidence = evidence,
            recommendations = listOfNotNull(recommendation),
            actions = actions,
        )
    }

    private fun indicator(
        id: String,
        label: String,
        value: Any,
        description: String = "",
        affectedNodes: List<NodeId> = emptyList(),
    ): AnalysisIndicator = AnalysisIndicator(id, label, value.toString(), description, affectedNodes)

    private fun nodeLabel(context: Context, nodeId: NodeId): String =
        context.document.fullyQualifiedName(nodeId).ifBlank { context.snapshot.nodesById[nodeId]?.name ?: nodeId.value }

    private fun isLibrary(node: Node, document: ThreadworkDocument): Boolean =
        NodeClassifier.classify(document, node) == NodeStereotype.ServiceLibrary ||
            node.name.trim().lowercase().let { it.startsWith("lib_") || it.startsWith("library_") }

    private fun errorSinks(graph: SparseBooleanGraph): List<NodeId> =
        graph.nodeIds.filter { graph.inDegree(it) > 0 && graph.outDegree(it) == 0 }

    private fun selfLinkedNodes(graph: SparseBooleanGraph): List<NodeId> =
        graph.nodeIds.filter { graph.contains(it, it) }

    private fun percentage(value: Double): String = "%.1f%%".format(value * 100.0)

    private fun List<Set<NodeId>>.flattenDistinct(): List<NodeId> = flatten().distinct()
}
