package com.threadwork.app.ui

import com.threadwork.core.analysis.AnalysisAction
import com.threadwork.core.analysis.AnalysisFinding
import com.threadwork.core.analysis.AnalysisIndicator
import com.threadwork.core.analysis.AnalysisReport
import com.threadwork.core.analysis.AnalysisSection
import com.threadwork.core.analysis.AnalysisSeverity
import com.threadwork.core.analysis.SparseBooleanGraph
import com.threadwork.core.model.NodeId
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.LayoutManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Text-first architectural report. The canvas remains the graph visualization;
 * this panel is intentionally focused on measurements, interpretation, and safe
 * navigation back to affected model entities.
 */
internal class NetworkAnalysisPanel(
    private val requestRefresh: () -> Unit,
    private val selectEntities: (Collection<NodeId>) -> Unit,
) : JPanel(BorderLayout()) {
    private val reportContent = JPanel()
    private val reportBody = JPanel(BorderLayout())
    private val reportScroll = JScrollPane(reportBody)
    private val matrixVisualization = RawMatrixVisualization(selectEntities)
    private val revisionLabel = JLabel()
    private val stateLabel = JLabel("Analysis not calculated")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        reportContent.layout = ResponsiveColumnsLayout()
        reportContent.border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
        reportScroll.border = BorderFactory.createEmptyBorder()
        reportBody.add(matrixVisualization, BorderLayout.NORTH)
        reportBody.add(reportContent, BorderLayout.CENTER)
        add(toolbar(), BorderLayout.NORTH)
        add(reportScroll, BorderLayout.CENTER)
    }

    fun showReport(newReport: AnalysisReport) {
        revisionLabel.text = "${newReport.snapshot.nodeIds.size} nodes | ${newReport.snapshot.structural.edgeCount} structural links"
        stateLabel.text = "Analysis current"
        matrixVisualization.showReport(newReport)
        reportContent.removeAll()
        newReport.sections.forEach { section ->
            reportContent.add(sectionPanel(section, newReport))
        }
        reportContent.revalidate()
        reportContent.repaint()
    }

    fun markStale() {
        stateLabel.text = "Model changed - refresh required"
    }

    private fun toolbar(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
        add(JLabel("Network Analysis").apply {
            font = font.deriveFont(Font.BOLD, 16f)
            toolTipText = "Read-only analysis of principal, error, dependency, and structural layers"
        }, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(stateLabel.apply {
                foreground = Color.GRAY
            })
            add(revisionLabel.apply {
                foreground = Color.GRAY
                horizontalAlignment = SwingConstants.RIGHT
            })
            add(JButton("Refresh").apply {
                toolTipText = "Recalculate the report from the current model"
                addActionListener { requestRefresh() }
            })
        }, BorderLayout.EAST)
    }

    private fun sectionPanel(section: AnalysisSection, report: AnalysisReport): JPanel {
        val body = JPanel()
        body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
        body.border = BorderFactory.createEmptyBorder(7, 10, 9, 10)
        body.add(wrappedLabel(section.summary, 340))

        if (section.indicators.isNotEmpty()) {
            body.add(Box.createVerticalStrut(7))
            body.add(subheading("Indicators"))
            body.add(indicatorGrid(section.indicators))
        }
        if (section.findings.isNotEmpty()) {
            body.add(Box.createVerticalStrut(7))
            body.add(subheading("Qualitative analysis"))
            section.findings.forEach { finding ->
                body.add(findingPanel(finding, report))
                body.add(Box.createVerticalStrut(5))
            }
        }
        if (section.recommendations.isNotEmpty()) {
            body.add(Box.createVerticalStrut(5))
            body.add(subheading("Recommendations"))
            section.recommendations.forEach { recommendation ->
                body.add(wrappedLabel("- $recommendation", 340))
            }
        }
        if (section.actions.isNotEmpty()) {
            body.add(Box.createVerticalStrut(7))
            body.add(subheading("Actions"))
            section.actions.forEach { action ->
                body.add(actionRow(action))
            }
        }

        val content = JPanel(BorderLayout())
        content.border = BorderFactory.createLineBorder(Color.GRAY)
        val expanded = booleanArrayOf(true)
        val header = JButton("-  ${section.title}").apply {
            horizontalAlignment = SwingConstants.LEFT
            font = font.deriveFont(Font.BOLD)
            border = BorderFactory.createEmptyBorder(7, 9, 7, 9)
            isFocusPainted = false
            addActionListener {
                expanded[0] = !expanded[0]
                headerText(this, expanded[0], section.title)
                if (expanded[0]) content.add(body, BorderLayout.CENTER) else content.remove(body)
                content.revalidate()
                content.repaint()
            }
        }
        content.add(header, BorderLayout.NORTH)
        content.add(body, BorderLayout.CENTER)
        return content
    }

    private fun indicatorGrid(indicators: List<AnalysisIndicator>): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
        indicators.forEachIndexed { index, indicator ->
            val row = index / 2
            val column = index % 2
            val constraints = GridBagConstraints().apply {
                gridx = column * 2
                gridy = row
                weightx = if (column == 0) 0.5 else 0.5
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(1, if (column == 0) 0 else 14, 1, 0)
            }
            add(indicatorCell(indicator), constraints)
        }
    }

    private fun indicatorCell(indicator: AnalysisIndicator): JPanel = JPanel(BorderLayout()).apply {
        val value = JLabel(indicator.value).apply {
            font = font.deriveFont(Font.BOLD)
            horizontalAlignment = SwingConstants.RIGHT
        }
        add(JLabel(indicator.label), BorderLayout.WEST)
        add(value, BorderLayout.EAST)
        toolTipText = indicator.description.takeIf(String::isNotBlank)
        if (indicator.affectedNodes.isNotEmpty()) {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY)
            addMouseListener(ClickableSelectionListener(indicator.affectedNodes))
        }
    }

    private fun findingPanel(finding: AnalysisFinding, report: AnalysisReport): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(severityColor(finding.severity)),
            BorderFactory.createEmptyBorder(5, 7, 5, 7),
        )
        val title = JLabel("${finding.severity.name}  ${finding.title}").apply {
            foreground = severityColor(finding.severity)
            font = font.deriveFont(Font.BOLD)
        }
        add(title)
        add(wrappedLabel(finding.description, 320))
        if (finding.evidence.isNotEmpty()) {
            add(Box.createVerticalStrut(3))
            add(wrappedLabel(finding.evidence.joinToString(" | ") { "${it.label}: ${it.value}" }, 320).apply {
                foreground = Color.GRAY
                font = font.deriveFont(Font.ITALIC)
            })
        }
        if (finding.affectedNodes.isNotEmpty() || finding.affectedLinks.isNotEmpty()) {
            val affected = finding.affectedNodes + finding.affectedLinks
            add(Box.createVerticalStrut(4))
            add(wrappedLabel(affectedNames(affected, report), 320).apply {
                foreground = Color.GRAY
                toolTipText = affected.joinToString("\n") { entityName(it, report) }
            })
        }
        finding.recommendations.forEach { recommendation ->
            add(wrappedLabel("Recommendation: $recommendation", 320))
        }
        finding.actions.forEach { action ->
            add(Box.createVerticalStrut(3))
            add(actionRow(action))
        }
    }

    private fun actionRow(action: AnalysisAction): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 1)).apply {
        val targetIds = action.affectedNodes + action.affectedLinks
        add(JButton(action.title).apply {
            toolTipText = action.description
            isEnabled = targetIds.isNotEmpty()
            addActionListener { selectEntities(targetIds) }
        })
        add(wrappedLabel(action.description, 210).apply { foreground = Color.GRAY })
    }

    private fun subheading(value: String): JLabel = JLabel(value).apply {
        font = font.deriveFont(Font.BOLD)
    }

    private fun wrappedLabel(value: String, width: Int): JLabel = JLabel(
        "<html><div style='width:${width}px'>${escapeHtml(value)}</div></html>",
    )

    private fun affectedNames(ids: List<NodeId>, report: AnalysisReport): String {
        val names = ids.distinct().map { entityName(it, report) }
        return when {
            names.size <= 4 -> names.joinToString(", ")
            else -> names.take(4).joinToString(", ") + " and ${names.size - 4} more"
        }
    }

    private fun entityName(id: NodeId, report: AnalysisReport): String =
        report.snapshot.nodeLabels[id]
            ?: report.snapshot.nodesById[id]?.name
            ?: id.value

    private fun severityColor(severity: AnalysisSeverity): Color = when (severity) {
        AnalysisSeverity.INFO -> Color(70, 150, 190)
        AnalysisSeverity.NOTICE -> Color(190, 150, 60)
        AnalysisSeverity.WARNING -> Color(210, 120, 55)
        AnalysisSeverity.PROBLEM -> Color(205, 70, 70)
    }

    private fun headerText(button: JButton, expanded: Boolean, title: String) {
        button.text = "${if (expanded) "-" else "+"}  $title"
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private inner class ClickableSelectionListener(
        private val ids: List<NodeId>,
    ) : java.awt.event.MouseAdapter() {
        override fun mouseClicked(event: java.awt.event.MouseEvent) {
            if (event.clickCount == 1) selectEntities(ids)
        }
    }
}

/** Lays report sections out in two or three columns without forcing equal row heights. */
private class ResponsiveColumnsLayout(
    private val minimumColumnWidth: Int = 370,
    private val gap: Int = 10,
) : LayoutManager {
    override fun addLayoutComponent(name: String?, component: Component?) = Unit

    override fun removeLayoutComponent(component: Component?) = Unit

    override fun preferredLayoutSize(parent: java.awt.Container): Dimension = layoutSize(parent, parent.width)

    override fun minimumLayoutSize(parent: java.awt.Container): Dimension =
        layoutSize(parent, minimumColumnWidth)

    override fun layoutContainer(parent: java.awt.Container) {
        val insets = parent.insets
        val availableWidth = (parent.width - insets.left - insets.right).coerceAtLeast(minimumColumnWidth)
        val columns = columnCount(availableWidth)
        val columnWidth = ((availableWidth - gap * (columns - 1)) / columns).coerceAtLeast(1)
        val components = parent.components.toList()
        val rowHeights = IntArray((components.size + columns - 1) / columns)
        components.forEachIndexed { index, component ->
            val row = index / columns
            rowHeights[row] = maxOf(rowHeights[row], component.preferredSize.height)
        }
        var y = insets.top
        rowHeights.forEachIndexed { row, rowHeight ->
            val rowStart = row * columns
            val rowEnd = minOf(rowStart + columns, components.size)
            for (index in rowStart until rowEnd) {
                val column = index - rowStart
                val x = insets.left + column * (columnWidth + gap)
                components[index].setBounds(x, y, columnWidth, rowHeight)
            }
            y += rowHeight + gap
        }
    }

    private fun layoutSize(parent: java.awt.Container, widthHint: Int): Dimension {
        val insets = parent.insets
        val availableWidth = (widthHint - insets.left - insets.right).coerceAtLeast(minimumColumnWidth)
        val columns = columnCount(availableWidth)
        val columnWidth = ((availableWidth - gap * (columns - 1)) / columns).coerceAtLeast(1)
        val rowHeights = IntArray((parent.componentCount + columns - 1) / columns)
        parent.components.forEachIndexed { index, component ->
            val row = index / columns
            rowHeights[row] = maxOf(rowHeights[row], component.preferredSize.height)
        }
        val height = insets.top + insets.bottom + rowHeights.sum() + gap * (rowHeights.size - 1).coerceAtLeast(0)
        return Dimension(insets.left + insets.right + columnWidth * columns + gap * (columns - 1), height)
    }

    private fun columnCount(availableWidth: Int): Int = when {
        availableWidth >= minimumColumnWidth * 3 + gap * 2 -> 3
        availableWidth >= minimumColumnWidth * 2 + gap -> 2
        else -> 1
    }
}

private class RawMatrixVisualization(
    private val selectEntities: (Collection<NodeId>) -> Unit,
) : JPanel(BorderLayout()) {
    private val tabs = JTabbedPane()
    private val emptyLabel = JLabel("Calculate the report to inspect the raw P/E/D/U matrices.")

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.GRAY),
            BorderFactory.createEmptyBorder(7, 9, 9, 9),
        )
        add(JPanel(BorderLayout()).apply {
            add(JLabel("Raw adjacency matrices").apply {
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            add(JLabel("Rows: source / dependent | columns: target / dependency | click a 1-cell to select endpoints").apply {
                foreground = Color.GRAY
                horizontalAlignment = SwingConstants.RIGHT
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(emptyLabel, BorderLayout.CENTER)
        preferredSize = Dimension(900, 350)
    }

    fun showReport(report: AnalysisReport) {
        removeAll()
        val header = JPanel(BorderLayout()).apply {
            add(JLabel("Raw adjacency matrices").apply {
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            add(JLabel("Rows: source / dependent | columns: target / dependency | click a 1-cell to select endpoints").apply {
                foreground = Color.GRAY
                horizontalAlignment = SwingConstants.RIGHT
            }, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        tabs.removeAll()
        matrixDefinitions(report).forEach { definition ->
            tabs.addTab(
                "${definition.id}  ${definition.title}",
                MatrixTablePanel(definition, report, selectEntities),
            )
            tabs.setToolTipTextAt(tabs.tabCount - 1, definition.description)
        }
        add(tabs, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun matrixDefinitions(report: AnalysisReport): List<MatrixDefinition> = listOf(
        MatrixDefinition("P", "Principal data", "Normal data transport relationships.", report.snapshot.principal, Color(40, 155, 165)),
        MatrixDefinition("E", "Error data", "Error and failure transport relationships.", report.snapshot.error, Color(205, 85, 85)),
        MatrixDefinition("D", "Dependencies", "Dependent-to-dependency capability relationships.", report.snapshot.dependency, Color(205, 145, 55)),
        MatrixDefinition("U", "Structural union", "Non-semantic union of P, E, and D.", report.snapshot.structural, Color(120, 100, 180)),
    )
}

private data class MatrixDefinition(
    val id: String,
    val title: String,
    val description: String,
    val graph: SparseBooleanGraph,
    val activeColor: Color,
)

private class MatrixTablePanel(
    definition: MatrixDefinition,
    report: AnalysisReport,
    selectEntities: (Collection<NodeId>) -> Unit,
) : JPanel(BorderLayout()) {
    init {
        val table = MatrixTable(definition, report.snapshot.nodeLabels, selectEntities)
        val scroll = JScrollPane(table).apply {
            border = BorderFactory.createEmptyBorder()
        }
        add(JLabel("${definition.title}: ${definition.graph.edgeCount} directed relationships", SwingConstants.LEFT).apply {
            foreground = Color.GRAY
            border = BorderFactory.createEmptyBorder(4, 2, 4, 2)
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }
}

private class MatrixTable(
    definition: MatrixDefinition,
    labels: Map<NodeId, String>,
    private val selectEntities: (Collection<NodeId>) -> Unit,
) : JTable(MatrixTableModel(definition.graph, labels)) {
    private val matrixModel: MatrixTableModel get() = model as MatrixTableModel

    init {
        autoResizeMode = AUTO_RESIZE_OFF
        rowHeight = 24
        setShowGrid(true)
        gridColor = Color(100, 100, 100)
        fillsViewportHeight = true
        tableHeader.preferredSize = Dimension(tableHeader.preferredSize.width, 58)
        tableHeader.defaultRenderer = MatrixHeaderRenderer()
        columnModel.getColumn(0).preferredWidth = 210
        columnModel.getColumn(0).minWidth = 150
        for (column in 1 until columnCount) {
            columnModel.getColumn(column).preferredWidth = 64
            columnModel.getColumn(column).minWidth = 52
        }
        setDefaultRenderer(String::class.java, MatrixCellRenderer(definition.activeColor))
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 1) return
                val row = rowAtPoint(event.point)
                val column = columnAtPoint(event.point)
                if (row < 0 || column <= 0) return
                val source = matrixModel.nodeIdAt(row)
                val target = matrixModel.nodeIdAt(column - 1)
                if (matrixModel.graph.contains(source, target)) {
                    selectEntities(listOf(source, target))
                }
            }
        })
    }

    override fun getToolTipText(event: MouseEvent): String {
        val row = rowAtPoint(event.point)
        val column = columnAtPoint(event.point)
        if (row < 0) return ""
        val source = matrixModel.labelAt(row)
        if (column == 0) return source
        val target = matrixModel.labelAt(column - 1)
        val value = matrixModel.getValueAt(row, column)
        return "$source -> $target: $value"
    }
}

private class MatrixTableModel(
    val graph: SparseBooleanGraph,
    private val labels: Map<NodeId, String>,
) : AbstractTableModel() {
    override fun getRowCount(): Int = graph.nodeIds.size

    override fun getColumnCount(): Int = graph.nodeIds.size + 1

    override fun getColumnName(column: Int): String = when {
        column == 0 -> "Node"
        else -> "${column}: ${abbreviate(labels[graph.nodeIds[column - 1]].orEmpty(), 14)}"
    }

    override fun getColumnClass(column: Int): Class<*> = String::class.java

    override fun getValueAt(rowIndex: Int, columnIndex: Int): String = when {
        columnIndex == 0 -> "${rowIndex + 1}. ${abbreviate(labels[graph.nodeIds[rowIndex]].orEmpty(), 26)}"
        graph.contains(graph.nodeIds[rowIndex], graph.nodeIds[columnIndex - 1]) -> "1"
        else -> "0"
    }

    fun nodeIdAt(row: Int): NodeId = graph.nodeIds[row]

    fun labelAt(index: Int): String = labels[graph.nodeIds[index]].orEmpty()

    private fun abbreviate(value: String, maxLength: Int): String =
        if (value.length <= maxLength) value else value.take(maxLength - 3) + "..."
}

private class MatrixCellRenderer(
    private val activeColor: Color,
) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        horizontalAlignment = if (column == 0) SwingConstants.LEFT else SwingConstants.CENTER
        isOpaque = true
        val base = UIManager.getColor("Table.background") ?: table.background
        val foreground = UIManager.getColor("Table.foreground") ?: table.foreground
        if (column == 0) {
            background = UIManager.getColor("TableHeader.background") ?: base
            this.foreground = foreground
            font = font.deriveFont(Font.PLAIN, 11f)
        } else {
            val active = value == "1"
            val diagonal = row == column - 1
            background = when {
                active && diagonal -> activeColor.brighter()
                active -> activeColor
                diagonal -> Color(activeColor.red, activeColor.green, activeColor.blue, 35)
                else -> base
            }
            this.foreground = if (active) Color.WHITE else foreground
            font = font.deriveFont(Font.BOLD)
        }
        return this
    }
}

private class MatrixHeaderRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        font = font.deriveFont(Font.PLAIN, 10f)
        return this
    }
}
