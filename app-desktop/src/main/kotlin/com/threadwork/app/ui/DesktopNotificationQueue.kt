package com.threadwork.app.ui

import com.threadwork.app.fonts.ThreadworkFonts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager

enum class DesktopNotificationType(
    val marker: String,
    val color: Color,
) {
    Error("[e]", Color(0xc73a3a)),
    Warning("[w]", Color(0xb87900)),
    Success("[y]", Color(0x23845a)),
    Info("[i]", Color(0x2878b8)),
}

data class DesktopNotification(
    val type: DesktopNotificationType,
    val title: String,
    val details: String = "",
)

/**
 * Keeps application notifications until explicitly dismissed while presenting
 * them as a small floating queue anchored to the status bar.
 */
class DesktopNotificationQueue {
    private val notifications = mutableListOf<DesktopNotification>()
    private val cards = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
    }
    private val scrollPane = JScrollPane(cards).apply {
        border = BorderFactory.createEmptyBorder()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    }
    private val popup = JPopupMenu().apply {
        layout = BorderLayout()
        isOpaque = false
        border = BorderFactory.createEmptyBorder()
        add(scrollPane, BorderLayout.CENTER)
    }
    private val autoHideTimer = Timer(AUTO_HIDE_DELAY_MS) {
        popup.isVisible = false
    }.apply {
        isRepeats = false
    }

    fun publish(notification: DesktopNotification, anchor: JComponent) {
        notifications += notification
        show(anchor, autoHide = true)
    }

    fun toggle(anchor: JComponent) {
        if (popup.isVisible) {
            autoHideTimer.stop()
            popup.isVisible = false
        } else {
            show(anchor, autoHide = false)
        }
    }

    private fun show(anchor: JComponent, autoHide: Boolean) {
        if (notifications.isEmpty()) return
        rebuild(anchor)
        popup.pack()
        popup.show(anchor, anchor.width - popup.preferredSize.width, -popup.preferredSize.height - 4)
        if (autoHide) {
            autoHideTimer.restart()
        } else {
            autoHideTimer.stop()
        }
    }

    private fun rebuild(anchor: JComponent) {
        cards.removeAll()
        notifications.forEachIndexed { index, notification ->
            cards.add(notificationCard(notification, anchor))
            if (index != notifications.lastIndex) cards.add(javax.swing.Box.createVerticalStrut(CARD_GAP))
        }
        cards.revalidate()
        cards.repaint()
        updatePopupSize()
    }

    private fun updatePopupSize() {
        val preferred = cards.preferredSize
        scrollPane.preferredSize = Dimension(
            preferred.width.coerceIn(MIN_WIDTH, MAX_WIDTH),
            preferred.height.coerceAtMost(MAX_HEIGHT),
        )
    }

    private fun notificationCard(notification: DesktopNotification, anchor: JComponent): JComponent = JPanel(BorderLayout(8, 0)).apply {
        background = UIManager.getColor("Panel.background") ?: Color.WHITE
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder((UIManager.getColor("Component.borderColor") ?: Color.GRAY).darker()),
            BorderFactory.createEmptyBorder(7, 8, 7, 6),
        )
        maximumSize = Dimension(MAX_WIDTH, Int.MAX_VALUE)
        alignmentX = JComponent.LEFT_ALIGNMENT

        add(JLabel(notification.type.marker).apply {
            foreground = notification.type.color
            font = ThreadworkFonts.codeFont(12f).deriveFont(Font.BOLD)
            verticalAlignment = SwingConstants.TOP
        }, BorderLayout.WEST)

        val content = JPanel(BorderLayout(0, 5)).apply {
            isOpaque = false
            add(JLabel(notification.title).apply {
                font = ThreadworkFonts.designerFont(12f)
            }, BorderLayout.NORTH)
        }
        if (notification.details.isNotBlank()) {
            val details = JTextArea(notification.details).apply {
                isEditable = false
                isFocusable = false
                isOpaque = false
                foreground = UIManager.getColor("Label.foreground")
                font = ThreadworkFonts.codeFont(11.5f)
                border = BorderFactory.createEmptyBorder()
                lineWrap = false
                wrapStyleWord = false
                isVisible = false
            }
            val detailToggle = JToggleButton("details").apply {
                isOpaque = false
                margin = java.awt.Insets(0, 3, 0, 3)
                font = ThreadworkFonts.designerFont(11f)
                addActionListener {
                    details.isVisible = isSelected
                    text = if (isSelected) "hide" else "details"
                    content.revalidate()
                    updatePopupSize()
                    popup.pack()
                }
            }
            content.add(details, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(detailToggle)
                add(dismissButton(notification, anchor))
            }, BorderLayout.EAST)
        } else {
            add(dismissButton(notification, anchor), BorderLayout.EAST)
        }
        add(content, BorderLayout.CENTER)
    }

    private fun dismissButton(notification: DesktopNotification, anchor: JComponent): JButton = JButton("[x]").apply {
        toolTipText = "Dismiss notification"
        margin = java.awt.Insets(0, 4, 0, 4)
        font = ThreadworkFonts.designerFont(11f)
        addActionListener {
            notifications.remove(notification)
            if (notifications.isEmpty()) {
                autoHideTimer.stop()
                popup.isVisible = false
            } else {
                rebuild(anchor)
                popup.pack()
            }
        }
    }

    private companion object {
        const val AUTO_HIDE_DELAY_MS = 5_000
        const val CARD_GAP = 6
        const val MIN_WIDTH = 320
        const val MAX_WIDTH = 560
        const val MAX_HEIGHT = 360
    }
}
