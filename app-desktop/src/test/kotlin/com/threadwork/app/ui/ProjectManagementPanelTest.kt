package com.threadwork.app.ui

import com.threadwork.core.model.NodeKind
import com.threadwork.core.model.ProjectStatus
import com.threadwork.core.model.fullyQualifiedName
import com.threadwork.storage.InMemoryDocumentRepository
import com.threadwork.storage.newDocument
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectManagementPanelTest {
    @Test
    fun `tickets exclude unassigned nodes links and project root and sort by qualified name`() {
        val repository = InMemoryDocumentRepository(newDocument("project"))
        val root = repository.getDocument().rootNodeId
        val zeta = repository.createNode(root, "zeta", NodeKind.Group)
        val zetaTask = repository.createNode(zeta.id, "task", NodeKind.Processor)
        val alpha = repository.createNode(root, "alpha", NodeKind.Group)
        val alphaTask = repository.createNode(alpha.id, "task", NodeKind.Processor)
        val hidden = repository.createNode(root, "hidden", NodeKind.Processor)
        repository.updateNodeStatus(hidden.id, null)
        val link = repository.createLink(root, "relationship", alphaTask.id, "out", zetaTask.id, "in")
        repository.updateNodeStatus(zetaTask.id, ProjectStatus.TESTING)

        val tickets = projectTickets(repository.getDocument())

        assertEquals(
            listOf("project/alpha", "project/alpha/task", "project/zeta", "project/zeta/task"),
            tickets.map { repository.getDocument().fullyQualifiedName(it.id) },
        )
        assertEquals(false, tickets.any { it.id == root || it.id == hidden.id || it.id == link.id })
    }
}
