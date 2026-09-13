package com.threadwork.app.ui

import com.threadwork.core.model.Node
import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.NodeId
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphCanvasSelectionTest {
    @Test
    fun `selection click is committed on release`() {
        val repository = InMemoryDocumentRepository(newDocument("selection click"))
        val root = repository.getDocument().rootNodeId
        val node = repository.createNode(root, "node", NodeKind.Processor)
        position(repository, node, 100, 100)
        val selection = linkedSetOf<NodeId>()
        val canvas = GraphCanvas(repository, selection, {}, {}, {})
        canvas.refreshBoundsFromChildren()

        press(canvas, 120, 120)
        assertTrue(selection.isEmpty())

        release(canvas, 120, 120)
        assertEquals(linkedSetOf(node.id), selection)
    }

    @Test
    fun `a drag from inside an entity creates a selection window without alt`() {
        val repository = InMemoryDocumentRepository(newDocument("selection window"))
        val root = repository.getDocument().rootNodeId
        val node = repository.createNode(root, "node", NodeKind.Processor)
        position(repository, node, 100, 100)
        val selection = linkedSetOf<NodeId>()
        val canvas = GraphCanvas(repository, selection, {}, {}, {})
        canvas.refreshBoundsFromChildren()

        press(canvas, 120, 120)
        drag(canvas, 80, 80)
        release(canvas, 80, 80)

        assertEquals(linkedSetOf(node.id), selection)
    }

    private fun press(canvas: GraphCanvas, x: Int, y: Int, alt: Boolean = false) {
        val modifiers = if (alt) InputEvent.ALT_DOWN_MASK else 0
        val event = MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, modifiers, x, y, 1, false, MouseEvent.BUTTON1)
        canvas.mouseListeners.forEach { it.mousePressed(event) }
    }

    private fun drag(canvas: GraphCanvas, x: Int, y: Int) {
        val event = MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0, 0, x, y, 0, false, MouseEvent.BUTTON1)
        canvas.mouseMotionListeners.forEach { it.mouseDragged(event) }
    }

    private fun release(canvas: GraphCanvas, x: Int, y: Int) {
        val event = MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1)
        canvas.mouseListeners.forEach { it.mouseReleased(event) }
    }

    private fun position(repository: InMemoryDocumentRepository, node: Node, x: Int, y: Int) {
        repository.updateNodeLayout(
            node.id,
            node.layout.copy(
                x = x.toDouble(),
                y = y.toDouble(),
                width = 180.0,
                height = 70.0,
                closedWidth = 180.0,
                closedHeight = 70.0,
                openWidth = 180.0,
                openHeight = 70.0,
            ),
        )
    }
}
