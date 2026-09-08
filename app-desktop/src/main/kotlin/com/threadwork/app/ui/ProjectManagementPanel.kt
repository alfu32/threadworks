package com.threadwork.app.ui

import com.threadwork.core.model.Node
import com.threadwork.core.model.NodeId
import com.threadwork.core.model.ProjectStatus
import com.threadwork.core.model.ThreadworkDocument
import com.threadwork.core.model.fullyQualifiedName
import com.threadwork.core.model.fullyQualifiedParentName
import com.threadwork.storage.DocumentRepository
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

internal fun projectTickets(document: ThreadworkDocument): List<Node> =
    document.nodes.values
        .asSequence()
        .filter { node ->
            node.id != document.rootNodeId &&
                !node.isLink &&
                node.status != null
        }
        .sortedWith(
            compareBy<Node> { document.fullyQualifiedName(it.id).lowercase() }
                .thenBy { it.id.value },
        )
        .toList()

private data class ProjectTicketListEntry(
    val status: ProjectStatus,
    val nodeId: NodeId? = null,
    val label: String = status.name,
) {
    val isHeader: Boolean get() = nodeId == null
}

internal class ProjectManagementPanel(
    private val repository: DocumentRepository,
    private val detailInspector: InspectorPanel,
    private val onNodeSelected: (NodeId) -> Unit,
) : JPanel(CardLayout()) {
    private companion object {
        const val BOARD = "board"
        const val TICKET = "ticket"
        const val LANE_WIDTH = 220
    }

    private val board = JPanel(BorderLayout())
    private val ticketListModel = DefaultListModel<ProjectTicketListEntry>()
    private val ticketList = JList(ticketListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = 28
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val entry = value as? ProjectTicketListEntry
                val component = super.getListCellRendererComponent(
                    list,
                    entry?.label.orEmpty(),
                    index,
                    isSelected && entry?.isHeader == false,
                    cellHasFocus,
                ) as JLabel
                if (entry?.isHeader == true) {
                    component.background = UIManager.getColor("List.background")
                    component.foreground = UIManager.getColor("List.foreground")
                    component.font = component.font.deriveFont(Font.BOLD)
                    component.border = BorderFactory.createEmptyBorder(8, 8, 2, 4)
                    component.isEnabled = false
                } else {
                    component.isEnabled = true
                    entry?.nodeId?.let(repository::getNode)?.let { node ->
                        val palette = ThreadworkAppearance.palette()
                        component.background = palette.fillForNode(repository.getDocument(), node)
                        component.foreground = palette[DesignerColorKey.TextPrimary]
                        component.border = BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(
                                0,
                                4,
                                0,
                                0,
                                if (isSelected) {
                                    palette[DesignerColorKey.Selection]
                                } else {
                                    palette.strokeForNode(repository.getDocument(), node)
                                },
                            ),
                            BorderFactory.createEmptyBorder(2, 16, 2, 4),
                        )
                    }
                }
                return component
            }
        }
    }
    private val workspace = JPanel(BorderLayout())
    private var activeNodeId: NodeId? = null

    init {
        ticketList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            ticketList.selectedValue?.nodeId
                ?.takeIf { it != activeNodeId }
                ?.let(::openTicket)
        }
        workspace.add(
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 5)).apply {
                add(JButton("Board").apply {
                    toolTipText = "Return to the project Kanban board"
                    addActionListener { showBoard() }
                })
                add(JLabel("Project tickets"))
            },
            BorderLayout.NORTH,
        )
        workspace.add(
            JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                JScrollPane(ticketList),
                JScrollPane(detailInspector),
            ).apply {
                dividerLocation = 360
                resizeWeight = 0.0
            },
            BorderLayout.CENTER,
        )
        add(board, BOARD)
        add(workspace, TICKET)
        refresh()
    }

    fun refresh() {
        val document = repository.getDocument()
        val tickets = projectTickets(document)
        rebuildBoard(document, tickets)
        rebuildTicketList(document, tickets)
        val active = activeNodeId?.let(document.nodes::get)
        if (activeNodeId != null && (active == null || active.status == null || active.isLink)) {
            showBoard()
        } else {
            activeNodeId?.let(detailInspector::bind)
        }
    }

    private fun rebuildBoard(document: ThreadworkDocument, tickets: List<Node>) {
        val lanes = JPanel(GridLayout(1, ProjectStatus.entries.size, 8, 0)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            preferredSize = Dimension(ProjectStatus.entries.size * LANE_WIDTH, 600)
        }
        ProjectStatus.entries.forEach { status ->
            val laneTickets = tickets.filter { it.status == status }
            val contents = JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
                laneTickets.forEachIndexed { index, node ->
                    add(ticketButton(document, node))
                    if (index < laneTickets.lastIndex) add(Box.createVerticalStrut(6))
                }
            }
            lanes.add(
                JPanel(BorderLayout(0, 6)).apply {
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(
                            javax.swing.UIManager.getColor("Separator.foreground") ?: java.awt.Color.GRAY,
                        ),
                        BorderFactory.createEmptyBorder(4, 4, 4, 4),
                    )
                    add(
                        JLabel("${status.name}  ${laneTickets.size}", SwingConstants.LEADING).apply {
                            font = font.deriveFont(Font.BOLD)
                            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
                        },
                        BorderLayout.NORTH,
                    )
                    add(JScrollPane(contents).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
                },
            )
        }
        board.removeAll()
        board.add(JScrollPane(lanes).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        board.revalidate()
        board.repaint()
    }

    private fun ticketButton(document: ThreadworkDocument, node: Node): JComponent =
        ThreadworkAppearance.palette().let { palette ->
            val fill = palette.fillForNode(document, node)
            val stroke = palette.strokeForNode(document, node)
            JButton(
                "<html><b>${escapeHtml(node.name.ifBlank { node.id.value })}</b><br>" +
                    "<font color='${ThreadworkAppearance.colorToHex(palette[DesignerColorKey.TextMuted])}'>" +
                    "${escapeHtml(document.fullyQualifiedParentName(node.id))}</font></html>",
            ).apply {
                background = fill
                foreground = palette[DesignerColorKey.TextPrimary]
                isOpaque = true
                isContentAreaFilled = true
                horizontalAlignment = SwingConstants.LEADING
                maximumSize = Dimension(Int.MAX_VALUE, 58)
                preferredSize = Dimension(LANE_WIDTH - 24, 58)
                alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(stroke, 2),
                    BorderFactory.createEmptyBorder(5, 7, 5, 7),
                )
                toolTipText = document.fullyQualifiedName(node.id)
                addActionListener { openTicket(node.id) }
            }
        }

    private fun rebuildTicketList(document: ThreadworkDocument, tickets: List<Node>) {
        ticketListModel.clear()
        ProjectStatus.entries.forEach { status ->
            ticketListModel.addElement(ProjectTicketListEntry(status))
            tickets.filter { it.status == status }.forEach { node ->
                ticketListModel.addElement(
                    ProjectTicketListEntry(
                        status = status,
                        nodeId = node.id,
                        label = document.fullyQualifiedName(node.id),
                    ),
                )
            }
        }
        selectActiveListEntry()
    }

    private fun openTicket(nodeId: NodeId) {
        activeNodeId = nodeId
        detailInspector.bind(nodeId)
        onNodeSelected(nodeId)
        (layout as CardLayout).show(this, TICKET)
        selectActiveListEntry()
    }

    private fun selectActiveListEntry() {
        val selectedIndex = (0 until ticketListModel.size())
            .firstOrNull { ticketListModel.getElementAt(it).nodeId == activeNodeId }
            ?: return
        if (ticketList.selectedIndex != selectedIndex) ticketList.selectedIndex = selectedIndex
        SwingUtilities.invokeLater { ticketList.ensureIndexIsVisible(selectedIndex) }
    }

    private fun showBoard() {
        activeNodeId = null
        ticketList.clearSelection()
        detailInspector.bind(null)
        (layout as CardLayout).show(this, BOARD)
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
