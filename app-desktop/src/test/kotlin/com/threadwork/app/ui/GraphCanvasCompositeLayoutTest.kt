package com.threadwork.app.ui

import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.NodeLayout
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphCanvasCompositeLayoutTest {
    @Test
    fun `terminal nodes use the compact default height`() {
        val repository = InMemoryDocumentRepository(newDocument("compact nodes"))
        val node = repository.createNode(repository.getDocument().rootNodeId, "worker", NodeKind.Processor)
        repository.updateNodeLayout(
            node.id,
            node.layout.copy(height = 100.0, closedHeight = 100.0, openHeight = 100.0),
        )
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})

        canvas.refreshBoundsFromChildren()

        assertEquals(70.0, node.layout.height)
        assertEquals(70.0, node.layout.closedHeight)
        assertEquals(70.0, node.layout.openHeight)
    }

    @Test
    fun `collapsed composites reserve room for every metadata line`() {
        val repository = InMemoryDocumentRepository(newDocument("collapsed composite"))
        val root = repository.getDocument().rootNodeId
        val composite = repository.createNode(root, "group", NodeKind.Group)
        composite.layout.isExpanded = false
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})

        canvas.refreshBoundsFromChildren()

        assertEquals(106.0, composite.layout.height)
        assertEquals(106.0, composite.layout.closedHeight)
    }

    @Test
    fun `empty composites discard their former child envelope width`() {
        val repository = InMemoryDocumentRepository(newDocument("emptied composite"))
        val root = repository.getDocument().rootNodeId
        val composite = repository.createNode(root, "group", NodeKind.Group)
        val child = repository.createNode(composite.id, "child", NodeKind.Processor)
        repository.updateNodeLayout(child.id, NodeLayout(x = 400.0, y = 300.0, width = 700.0, height = 70.0))
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})
        canvas.refreshBoundsFromChildren()
        assertTrue(composite.layout.openWidth > 200.0)

        repository.moveNode(child.id, root)
        canvas.refreshBoundsFromChildren()

        assertTrue(composite.isComposite)
        assertEquals(200.0, composite.layout.openWidth)
        assertEquals(200.0, composite.layout.closedWidth)
        assertEquals(200.0, composite.layout.width)
    }

    @Test
    fun `expanded composite reserves horizontal routing clearance around children`() {
        val repository = InMemoryDocumentRepository(newDocument("routing clearance"))
        val root = repository.getDocument().rootNodeId
        val composite = repository.createNode(root, "group", NodeKind.Group)
        val leftChild = repository.createNode(composite.id, "left", NodeKind.Processor)
        val rightChild = repository.createNode(composite.id, "right", NodeKind.Processor)
        repository.updateNodeLayout(leftChild.id, NodeLayout(x = 1_000.0, y = 600.0, width = 240.0, height = 120.0))
        repository.updateNodeLayout(rightChild.id, NodeLayout(x = 1_800.0, y = 600.0, width = 240.0, height = 120.0))
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})

        canvas.refreshBoundsFromChildren()

        val leftClearance = leftChild.layout.x - composite.layout.x
        val rightClearance = composite.layout.x + composite.layout.width - rightChild.layout.x - rightChild.layout.width
        assertTrue(leftClearance >= 180.0, "left clearance was $leftClearance")
        assertTrue(rightClearance >= 180.0, "right clearance was $rightClearance")
    }

    @Test
    fun `recomputed composite bounds survive collapse and expansion`() {
        val repository = InMemoryDocumentRepository(newDocument("resizable composite"))
        val root = repository.getDocument().rootNodeId
        val composite = repository.createNode(root, "group", NodeKind.Group)
        val fixedChild = repository.createNode(composite.id, "fixed", NodeKind.Processor)
        val movedChild = repository.createNode(composite.id, "moved", NodeKind.Processor)
        repository.updateNodeLayout(fixedChild.id, NodeLayout(x = 400.0, y = 300.0, width = 180.0, height = 70.0))
        repository.updateNodeLayout(movedChild.id, NodeLayout(x = 700.0, y = 300.0, width = 180.0, height = 70.0))
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})
        canvas.refreshBoundsFromChildren()
        val originalOpenWidth = composite.layout.openWidth

        repository.updateNodeLayout(movedChild.id, movedChild.layout.copy(x = movedChild.layout.x + 500.0))
        canvas.refreshBoundsFromChildren()
        val resizedOpenWidth = composite.layout.openWidth
        composite.layout.isExpanded = false
        canvas.refreshBoundsFromChildren()

        assertTrue(resizedOpenWidth > originalOpenWidth)
        assertEquals(resizedOpenWidth, composite.layout.openWidth)
        assertEquals(composite.layout.closedWidth, composite.layout.width)

        composite.layout.isExpanded = true
        canvas.refreshBoundsFromChildren()

        assertEquals(resizedOpenWidth, composite.layout.openWidth)
        assertEquals(resizedOpenWidth, composite.layout.width)
    }
}
