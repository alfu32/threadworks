package com.threadwork.app.ui

import com.threadwork.core.analysis.AnalysisAction
import com.threadwork.core.analysis.AnalysisFinding
import com.threadwork.core.analysis.AnalysisIndicator
import com.threadwork.core.analysis.AnalysisReport
import com.threadwork.core.analysis.AnalysisSection
import com.threadwork.core.analysis.AnalysisSeverity
import com.threadwork.core.model.NodeId
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants

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
    private val reportScroll = JScrollPane(reportContent)
    private val revisionLabel = JLabel()
    private val stateLabel = JLabel("Analysis not calculated")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        reportContent.layout = BoxLayout(reportContent, BoxLayout.Y_AXIS)
        reportContent.border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
        reportScroll.border = BorderFactory.createEmptyBorder()
        add(toolbar(), BorderLayout.NORTH)
        add(reportScroll, BorderLayout.CENTER)
    }

    fun showReport(newReport: AnalysisReport) {
        revisionLabel.text = "${newReport.snapshot.nodeIds.size} nodes | ${newReport.snapshot.structural.edgeCount} structural links"
        stateLabel.text = "Analysis current"
        reportContent.removeAll()
        newReport.sections.forEach { section ->
            reportContent.add(sectionPanel(section, newReport))
            reportContent.add(Box.createVerticalStrut(8))
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
        body.add(wrappedLabel(section.summary, 850))

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
                body.add(wrappedLabel("- $recommendation", 850))
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
        add(wrappedLabel(finding.description, 810))
        if (finding.evidence.isNotEmpty()) {
            add(Box.createVerticalStrut(3))
            add(JLabel(finding.evidence.joinToString(" | ") { "${it.label}: ${it.value}" }).apply {
                foreground = Color.GRAY
                font = font.deriveFont(Font.ITALIC)
            })
        }
        if (finding.affectedNodes.isNotEmpty() || finding.affectedLinks.isNotEmpty()) {
            val affected = finding.affectedNodes + finding.affectedLinks
            add(Box.createVerticalStrut(4))
            add(JLabel(affectedNames(affected, report)).apply {
                foreground = Color.GRAY
                toolTipText = affected.joinToString("\n") { entityName(it, report) }
            })
        }
        finding.recommendations.forEach { recommendation ->
            add(wrappedLabel("Recommendation: $recommendation", 810))
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
        add(JLabel(action.description).apply {
            foreground = Color.GRAY
        })
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
