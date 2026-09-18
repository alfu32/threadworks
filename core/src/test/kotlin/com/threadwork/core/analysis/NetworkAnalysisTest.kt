package com.threadwork.core.analysis

import com.threadwork.core.model.LinkData
import com.threadwork.core.model.LinkInteractionKinds
import com.threadwork.core.model.Node
import com.threadwork.core.model.NodeId
import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.ThreadworkDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkAnalysisTest {
    @Test
    fun `snapshot preserves separate directed layers and self links`() {
        val root = node("root", "project", NodeKind.Group)
        val source = node("source", "source")
        val worker = node("worker", "worker")
        val sink = node("sink", "sink")
        val error = node("error", "err_log")
        val library = node("library", "lib_common")
        val principal = link("p1", source.id, worker.id)
        val feedback = link("p2", worker.id, worker.id)
        val output = link("p3", worker.id, sink.id)
        val errorLink = link("e1", worker.id, error.id, sourcePort = "error")
        val dependency = link(
            "d1",
            worker.id,
            library.id,
            transportKind = "usage",
        )
        val snapshot = GraphSnapshot.from(document(root, source, worker, sink, error, library, principal, feedback, output, errorLink, dependency))

        assertEquals(6, snapshot.nodeIds.size)
        assertEquals(3, snapshot.principal.edgeCount)
        assertEquals(1, snapshot.error.edgeCount)
        assertEquals(1, snapshot.dependency.edgeCount)
        assertTrue(snapshot.principal.contains(worker.id, worker.id))
        assertFalse(snapshot.error.contains(worker.id, worker.id))
        assertTrue(snapshot.dependency.contains(library.id, worker.id))
        assertEquals(5, snapshot.structural.edgeCount)
        assertTrue(snapshot.principal.nodeIds == snapshot.error.nodeIds)
        assertTrue(snapshot.error.nodeIds == snapshot.dependency.nodeIds)
    }

    @Test
    fun `directed algorithms cover reachability cycles components and topological order`() {
        val ids = listOf("a", "b", "c", "d", "isolated").map(::NodeId)
        val graph = SparseBooleanGraph.from(
            ids,
            listOf(
                DirectedEdge(NodeId("ab"), ids[0], ids[1]),
                DirectedEdge(NodeId("bc"), ids[1], ids[2]),
                DirectedEdge(NodeId("cc"), ids[2], ids[2]),
                DirectedEdge(NodeId("cd"), ids[2], ids[3]),
            ),
        )

        assertEquals(setOf(ids[0], ids[1], ids[2], ids[3]), NetworkGraphAlgorithms.reachableFrom(graph, ids[0]))
        assertEquals(listOf(ids[0], ids[1], ids[2], ids[3]), NetworkGraphAlgorithms.shortestPath(graph, ids[0], ids[3]))
        assertTrue(NetworkGraphAlgorithms.cyclicComponents(graph).contains(setOf(ids[2])))
        assertTrue(NetworkGraphAlgorithms.weakComponents(graph).contains(setOf(ids[4])))
        assertTrue(NetworkGraphAlgorithms.topologicalOrder(graph) == null)
        val dag = SparseBooleanGraph.from(
            ids,
            listOf(
                DirectedEdge(NodeId("ab"), ids[0], ids[1]),
                DirectedEdge(NodeId("bc"), ids[1], ids[2]),
                DirectedEdge(NodeId("cd"), ids[2], ids[3]),
            ),
        )
        val order = requireNotNull(NetworkGraphAlgorithms.topologicalOrder(dag))
        assertEquals(ids.toSet(), order.toSet())
        assertTrue(order.indexOf(ids[0]) < order.indexOf(ids[1]))
        assertTrue(order.indexOf(ids[1]) < order.indexOf(ids[2]))
        assertTrue(order.indexOf(ids[2]) < order.indexOf(ids[3]))
    }

    @Test
    fun `engine reports dependency cycles unused libraries and missing error routes`() {
        val root = node("root", "project", NodeKind.Group)
        val first = node("first", "first")
        val second = node("second", "second")
        val libA = node("lib-a", "lib_a")
        val libB = node("lib-b", "lib_b")
        val unused = node("unused", "lib_unused")
        val links = listOf(
            link("p", first.id, second.id),
            link("d1", first.id, libA.id, transportKind = "usage"),
            link("d2", libA.id, libB.id, transportKind = "usage"),
            link("d3", libB.id, libA.id, transportKind = "usage"),
        )
        val report = NetworkAnalysisEngine.analyze(document(root, first, second, libA, libB, unused, *links.toTypedArray()))
        val dependency = report.sections.first { it.id == "dependencies" }
        val quality = report.sections.first { it.id == "quality" }
        val error = report.sections.first { it.id == "error" }

        assertTrue(dependency.findings.any { it.id == "dependency.cycles" })
        assertTrue(dependency.findings.any { it.id == "dependency.unused" && unused.id in it.affectedNodes })
        assertTrue(quality.findings.any { it.id == "quality.noErrorRoute" })
        assertTrue(error.indicators.any { it.id == "error.uncoveredProcessing" && it.value.toInt() > 0 })
    }

    @Test
    fun `principal and error workflows remain separate`() {
        val root = node("root", "project", NodeKind.Group)
        val first = node("first", "first")
        val second = node("second", "second")
        val handler = node("handler", "error_handler")
        val p = link("p", first.id, second.id)
        val e = link("e", first.id, handler.id, sourcePort = "error")
        val snapshot = GraphSnapshot.from(document(root, first, second, handler, p, e))
        val workflows = NetworkGraphAlgorithms.weakComponents(snapshot.principal)

        assertTrue(snapshot.principal.contains(first.id, second.id))
        assertTrue(snapshot.error.contains(first.id, handler.id))
        assertTrue(workflows.any { first.id in it && second.id in it })
        assertTrue(workflows.none { handler.id in it && second.id in it })
    }

    private fun node(id: String, name: String, kind: NodeKind = NodeKind.Processor): Node =
        Node(NodeId(id), name, kind)

    private fun link(
        id: String,
        source: NodeId,
        target: NodeId,
        transportKind: String = "default",
        sourcePort: String = "out",
        interactionKind: String = LinkInteractionKinds.Auto,
    ): Node = Node(
        id = NodeId(id),
        name = id,
        kind = NodeKind.Link,
        link = LinkData(
            sourceNodeId = source,
            sourcePortName = sourcePort,
            targetNodeId = target,
            targetPortName = "in",
            transportKind = transportKind,
            interactionKind = interactionKind,
        ),
    )

    private fun document(vararg nodes: Node): ThreadworkDocument {
        val root = nodes.first { it.id == NodeId("root") }
        return ThreadworkDocument(
            id = "doc",
            name = root.name,
            rootNodeId = root.id,
            nodes = nodes.associateBy(Node::id).toMutableMap(),
        )
    }
}
