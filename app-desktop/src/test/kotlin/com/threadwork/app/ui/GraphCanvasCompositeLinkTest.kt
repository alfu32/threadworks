package com.threadwork.app.ui

import com.threadwork.core.model.Node
import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.NodePort
import com.threadwork.core.model.PortDirection
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.roundToInt

class GraphCanvasCompositeLinkTest {
    @Test
    fun `collapsed composite preserves the external part of a piercing link`() {
        val repository = InMemoryDocumentRepository(newDocument("collapsed link"))
        val root = repository.getDocument().rootNodeId
        val composite = repository.createNode(root, "group", NodeKind.Group)
        val source = repository.createNode(composite.id, "source", NodeKind.Processor)
        val target = repository.createNode(root, "target", NodeKind.Processor)
        position(repository, source, 400, 300)
        position(repository, target, 1_200, 300)
        repository.addPort(source.id, NodePort("out", "out", PortDirection.Output))
        repository.addPort(target.id, NodePort("in", "in", PortDirection.Input))
        val link = repository.createLink(root, "payload", source.id, "out", target.id, "in")
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})
        canvas.refreshBoundsFromChildren()

        collapse(composite)
        canvas.refreshBoundsFromChildren()

        val boundary = canvas.boundaryPortLocations(link.id).single().second
        val rendered = canvas.renderedLinkPoints(link.id)
        assertTrue(canvas.isLinkVisible(link.id))
        assertEquals(boundary, rendered.first())
        assertEquals(composite.layout.x.roundToInt() + composite.layout.width.roundToInt(), boundary.x)
        assertTrue(rendered.last().x > boundary.x)
    }

    @Test
    fun `collapsed target composite trims only the internal route suffix`() {
        val repository = InMemoryDocumentRepository(newDocument("collapsed target link"))
        val root = repository.getDocument().rootNodeId
        val source = repository.createNode(root, "source", NodeKind.Processor)
        val composite = repository.createNode(root, "group", NodeKind.Group)
        val target = repository.createNode(composite.id, "target", NodeKind.Processor)
        position(repository, source, 0, 300)
        position(repository, target, 900, 300)
        repository.addPort(source.id, NodePort("out", "out", PortDirection.Output))
        repository.addPort(target.id, NodePort("in", "in", PortDirection.Input))
        val link = repository.createLink(root, "payload", source.id, "out", target.id, "in")
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})
        canvas.refreshBoundsFromChildren()

        collapse(composite)
        canvas.refreshBoundsFromChildren()

        val boundary = canvas.boundaryPortLocations(link.id).single().second
        val rendered = canvas.renderedLinkPoints(link.id)
        assertTrue(canvas.isLinkVisible(link.id))
        assertEquals(boundary, rendered.last())
        assertTrue(rendered.first().x < boundary.x)
    }

    @Test
    fun `piercing and direct ports share top down composite rows`() {
        val repository = InMemoryDocumentRepository(newDocument("ordered boundary ports"))
        val root = repository.getDocument().rootNodeId
        val composite = repository.createNode(root, "group", NodeKind.Group)
        val highSource = repository.createNode(composite.id, "high source", NodeKind.Processor)
        val lowSource = repository.createNode(composite.id, "low source", NodeKind.Processor)
        val highTarget = repository.createNode(root, "high target", NodeKind.Processor)
        val middleTarget = repository.createNode(root, "middle target", NodeKind.Processor)
        val lowTarget = repository.createNode(root, "low target", NodeKind.Processor)
        position(repository, highSource, 400, 300)
        position(repository, lowSource, 400, 500)
        position(repository, highTarget, 1_300, 100)
        position(repository, middleTarget, 1_300, 400)
        position(repository, lowTarget, 1_300, 700)
        listOf(highSource, lowSource, composite).forEach {
            repository.addPort(it.id, NodePort("out", "out", PortDirection.Output))
        }
        listOf(highTarget, middleTarget, lowTarget).forEach {
            repository.addPort(it.id, NodePort("in", "in", PortDirection.Input))
        }
        val highLink = repository.createLink(root, "high", highSource.id, "out", highTarget.id, "in")
        val middleLink = repository.createLink(root, "middle", composite.id, "out", middleTarget.id, "in")
        val lowLink = repository.createLink(root, "low", lowSource.id, "out", lowTarget.id, "in")
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})
        canvas.refreshBoundsFromChildren()
        collapse(composite)
        canvas.refreshBoundsFromChildren()

        val rows = listOf(
            canvas.boundaryPortLocations(highLink.id).single().second.y,
            canvas.renderedLinkPoints(middleLink.id).first().y,
            canvas.boundaryPortLocations(lowLink.id).single().second.y,
        )

        assertEquals(
            listOf(28, 58, 88),
            rows.sorted().map { it - composite.layout.y.roundToInt() },
        )
    }

    @Test
    fun `boundary rows follow direct line intersection height`() {
        val repository = InMemoryDocumentRepository(newDocument("intersection order"))
        val root = repository.getDocument().rootNodeId
        val composite = repository.createNode(root, "group", NodeKind.Group)
        val topSource = repository.createNode(composite.id, "top source", NodeKind.Processor)
        val bottomSource = repository.createNode(composite.id, "bottom source", NodeKind.Processor)
        val target = repository.createNode(root, "target", NodeKind.Processor)
        position(repository, topSource, 400, 200)
        position(repository, bottomSource, 400, 600)
        position(repository, target, 1_300, 400)
        listOf(topSource, bottomSource).forEach {
            repository.addPort(it.id, NodePort("out", "out", PortDirection.Output))
        }
        repository.addPort(target.id, NodePort("in", "in", PortDirection.Input))
        val topLink = repository.createLink(root, "top", topSource.id, "out", target.id, "in")
        val bottomLink = repository.createLink(root, "bottom", bottomSource.id, "out", target.id, "in")
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})
        canvas.refreshBoundsFromChildren()
        collapse(composite)
        canvas.refreshBoundsFromChildren()

        val topPort = canvas.boundaryPortLocations(topLink.id).single().second
        val bottomPort = canvas.boundaryPortLocations(bottomLink.id).single().second
        assertEquals(30, bottomPort.y - topPort.y)
        assertTrue(topPort.y < bottomPort.y)
    }

    @Test
    fun `piercing ports use only the left and right composite sides`() {
        val repository = InMemoryDocumentRepository(newDocument("vertical boundary ports"))
        val root = repository.getDocument().rootNodeId
        val composite = repository.createNode(root, "group", NodeKind.Group)
        val source = repository.createNode(composite.id, "source", NodeKind.Processor)
        val leftTarget = repository.createNode(root, "left", NodeKind.Processor)
        val rightTarget = repository.createNode(root, "right", NodeKind.Processor)
        position(repository, source, 600, 400)
        position(repository, leftTarget, 0, 0)
        position(repository, rightTarget, 1_400, 800)
        repository.addPort(source.id, NodePort("out", "out", PortDirection.Output))
        listOf(leftTarget, rightTarget).forEach {
            repository.addPort(it.id, NodePort("in", "in", PortDirection.Input))
        }
        val leftLink = repository.createLink(root, "left", source.id, "out", leftTarget.id, "in")
        val rightLink = repository.createLink(root, "right", source.id, "out", rightTarget.id, "in")
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})
        canvas.refreshBoundsFromChildren()
        collapse(composite)
        canvas.refreshBoundsFromChildren()

        val left = composite.layout.x.roundToInt()
        val top = composite.layout.y.roundToInt()
        val right = left + composite.layout.width.roundToInt()
        val leftPort = canvas.boundaryPortLocations(leftLink.id).single().second
        val rightPort = canvas.boundaryPortLocations(rightLink.id).single().second
        assertEquals(Point(left, top + 28), leftPort)
        assertEquals(Point(right, top + 28), rightPort)
    }

    @Test
    fun `outer collapsed composite hides nested internal boundary segments`() {
        val repository = InMemoryDocumentRepository(newDocument("nested collapsed link"))
        val root = repository.getDocument().rootNodeId
        val outer = repository.createNode(root, "outer", NodeKind.Group)
        val inner = repository.createNode(outer.id, "inner", NodeKind.Group)
        val source = repository.createNode(inner.id, "source", NodeKind.Processor)
        val target = repository.createNode(root, "target", NodeKind.Processor)
        position(repository, source, 600, 400)
        position(repository, target, 1_600, 400)
        repository.addPort(source.id, NodePort("out", "out", PortDirection.Output))
        repository.addPort(target.id, NodePort("in", "in", PortDirection.Input))
        val link = repository.createLink(root, "payload", source.id, "out", target.id, "in")
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})
        canvas.refreshBoundsFromChildren()

        collapse(outer)
        canvas.refreshBoundsFromChildren()

        val ports = canvas.boundaryPortLocations(link.id).toMap()
        val rendered = canvas.renderedLinkPoints(link.id)
        assertEquals(ports.getValue(outer.id), rendered.first())
        assertTrue(ports.getValue(inner.id) !in rendered)
    }

    private fun position(
        repository: InMemoryDocumentRepository,
        node: Node,
        x: Int,
        y: Int,
    ) {
        repository.updateNodeLayout(
            node.id,
            node.layout.copy(
                x = x.toDouble(),
                y = y.toDouble(),
                width = 200.0,
                height = 70.0,
                closedWidth = 200.0,
                closedHeight = 70.0,
                openWidth = 200.0,
                openHeight = 70.0,
            ),
        )
    }

    private fun collapse(node: Node) {
        node.layout.isExpanded = false
        node.layout.width = node.layout.closedWidth
        node.layout.height = node.layout.closedHeight
    }
}
