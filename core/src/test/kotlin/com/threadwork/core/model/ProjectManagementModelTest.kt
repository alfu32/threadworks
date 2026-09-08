package com.threadwork.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectManagementModelTest {
    @Test
    fun `qualified names follow the node hierarchy`() {
        val root = Node(NodeId("root"), "project", NodeKind.Group)
        val group = Node(NodeId("group"), "services", NodeKind.Group, parentId = root.id)
        val worker = Node(NodeId("worker"), "listener", NodeKind.Processor, parentId = group.id)
        root.children += group.id
        group.children += worker.id
        val document = ThreadworkDocument(
            id = "project",
            name = "project",
            rootNodeId = root.id,
            nodes = mutableMapOf(root.id to root, group.id to group, worker.id to worker),
        )

        assertEquals("project/services/listener", document.fullyQualifiedName(worker.id))
        assertEquals("project/services", document.fullyQualifiedParentName(worker.id))
        assertEquals("", document.fullyQualifiedParentName(root.id))
    }
}
