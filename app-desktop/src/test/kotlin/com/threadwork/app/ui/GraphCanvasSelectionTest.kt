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
    fun `link mode permits selecting the same entity as source and target`() {
        val repository = InMemoryDocumentRepository(newDocument("self link creation"))
        val root = repository.getDocument().rootNodeId
        val node = repository.createNode(root, "node", NodeKind.Processor)
        position(repository, node, 100, 100)
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {})
        canvas.setMode(CanvasMode.CreateLink)
        canvas.refreshBoundsFromChildren()

        click(canvas, 120, 120)
        click(canvas, 120, 120)

        val link = repository.getDocument().nodes.values.single { it.isLink }
        assertEquals(node.id, link.link?.sourceNodeId)
        assertEquals(node.id, link.link?.targetNodeId)
    }

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

    @Test
    fun `shift click toggles an entity in the selection`() {
        val repository = InMemoryDocumentRepository(newDocument("shift click selection"))
        val root = repository.getDocument().rootNodeId
        val first = repository.createNode(root, "first", NodeKind.Processor)
        val second = repository.createNode(root, "second", NodeKind.Processor)
        position(repository, first, 100, 100)
        position(repository, second, 400, 100)
        val selection = linkedSetOf<NodeId>()
        val canvas = GraphCanvas(repository, selection, {}, {}, {})
        canvas.refreshBoundsFromChildren()

        click(canvas, 120, 120)
        click(canvas, 420, 120, shift = true)
        assertEquals(linkedSetOf(first.id, second.id), selection)

        click(canvas, 420, 120, shift = true)
        assertEquals(linkedSetOf(first.id), selection)
    }

    @Test
    fun `shift window toggles entities without clearing the selection`() {
        val repository = InMemoryDocumentRepository(newDocument("shift window selection"))
        val root = repository.getDocument().rootNodeId
        val first = repository.createNode(root, "first", NodeKind.Processor)
        val second = repository.createNode(root, "second", NodeKind.Processor)
        position(repository, first, 100, 100)
        position(repository, second, 400, 100)
        val selection = linkedSetOf<NodeId>()
        val canvas = GraphCanvas(repository, selection, {}, {}, {})
        canvas.refreshBoundsFromChildren()

        click(canvas, 120, 120)
        press(canvas, 350, 50, shift = true)
        drag(canvas, 650, 220, shift = true)
        release(canvas, 650, 220, shift = true)
        assertEquals(linkedSetOf(first.id, second.id), selection)

        press(canvas, 350, 50, shift = true)
        drag(canvas, 650, 220, shift = true)
        release(canvas, 650, 220, shift = true)
        assertEquals(linkedSetOf(first.id), selection)
    }

    @Test
    fun `archetype insertion is placed at the chosen canvas point`() {
        val repository = InMemoryDocumentRepository(newDocument("archetype placement"))
        val archetypeRepository = InMemoryDocumentRepository(newDocument("sample archetype"))
        val archetypeRoot = archetypeRepository.getDocument().rootNodeId
        val archetypeNode = archetypeRepository.createNode(archetypeRoot, "sample", NodeKind.Processor)
        position(archetypeRepository, archetypeNode, 40, 60)
        var inserted = false
        val canvas = GraphCanvas(repository, linkedSetOf(), {}, {}, {}, onArchetypeInserted = { inserted = true })
        canvas.armArchetypeInsertion(archetypeRepository.getDocument())

        click(canvas, 600, 400)

        val copy = repository.getDocument().nodes.values.single { it.name == "sample" }
        assertEquals(600.0, copy.layout.x)
        assertEquals(400.0, copy.layout.y)
        assertTrue(inserted)
    }

    private fun press(
        canvas: GraphCanvas,
        x: Int,
        y: Int,
        alt: Boolean = false,
        shift: Boolean = false,
    ) {
        val modifiers = (if (alt) InputEvent.ALT_DOWN_MASK else 0) or
            (if (shift) InputEvent.SHIFT_DOWN_MASK else 0)
        val event = MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, modifiers, x, y, 1, false, MouseEvent.BUTTON1)
        canvas.mouseListeners.forEach { it.mousePressed(event) }
    }

    private fun click(canvas: GraphCanvas, x: Int, y: Int, shift: Boolean = false) {
        press(canvas, x, y, shift = shift)
        release(canvas, x, y, shift = shift)
    }

    private fun drag(canvas: GraphCanvas, x: Int, y: Int, shift: Boolean = false) {
        val modifiers = if (shift) InputEvent.SHIFT_DOWN_MASK else 0
        val event = MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0, modifiers, x, y, 0, false, MouseEvent.BUTTON1)
        canvas.mouseMotionListeners.forEach { it.mouseDragged(event) }
    }

    private fun release(canvas: GraphCanvas, x: Int, y: Int, shift: Boolean = false) {
        val modifiers = if (shift) InputEvent.SHIFT_DOWN_MASK else 0
        val event = MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, modifiers, x, y, 1, false, MouseEvent.BUTTON1)
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
