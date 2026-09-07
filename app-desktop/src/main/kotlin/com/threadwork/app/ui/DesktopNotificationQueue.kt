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
import javax.swing.JLayeredPane
import javax.swing.JPanel
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

/** Keeps notifications until dismissed while drawing the queue over the app's layout. */
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
        background = Color(0, 0, 0, 0)
        viewport.isOpaque = false
        viewport.background = Color(0, 0, 0, 0)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    }
    private val overlay = JPanel(BorderLayout()).apply {
        isOpaque = false
        background = Color(0, 0, 0, 0)
        border = BorderFactory.createEmptyBorder()
        add(scrollPane, BorderLayout.CENTER)
    }
    private var host: JLayeredPane? = null
    private var statusBar: JComponent? = null
    private val autoHideTimer = Timer(AUTO_HIDE_DELAY_MS) {
        overlay.isVisible = false
    }.apply {
        isRepeats = false
    }

    fun install(host: JLayeredPane, statusBar: JComponent) {
        this.host = host
        this.statusBar = statusBar
        overlay.isVisible = false
        host.add(overlay, JLayeredPane.POPUP_LAYER)
    }

    fun publish(notification: DesktopNotification) {
        notifications += notification
        show(autoHide = true)
    }

    fun toggle() {
        if (overlay.isVisible) {
            autoHideTimer.stop()
            overlay.isVisible = false
        } else {
            show(autoHide = false)
        }
    }

    fun reposition() {
        val currentHost = host ?: return
        if (!overlay.isVisible) return
        val preferred = overlay.preferredSize
        overlay.setBounds(
            (currentHost.width - preferred.width - OVERLAY_MARGIN).coerceAtLeast(0),
            (currentHost.height - (statusBar?.height ?: 0) - preferred.height - OVERLAY_MARGIN).coerceAtLeast(0),
            preferred.width,
            preferred.height,
        )
        currentHost.repaint()
    }

    private fun show(autoHide: Boolean) {
        if (notifications.isEmpty()) return
        rebuild()
        overlay.isVisible = true
        reposition()
        if (autoHide) autoHideTimer.restart() else autoHideTimer.stop()
    }

    private fun rebuild() {
        cards.removeAll()
        notifications.forEachIndexed { index, notification ->
            cards.add(notificationCard(notification))
            if (index != notifications.lastIndex) cards.add(javax.swing.Box.createVerticalStrut(CARD_GAP))
        }
        cards.revalidate()
        cards.repaint()
        updateOverlaySize()
    }

    private fun updateOverlaySize() {
        val preferred = cards.preferredSize
        scrollPane.preferredSize = Dimension(
            preferred.width.coerceIn(MIN_WIDTH, MAX_WIDTH),
            preferred.height.coerceAtMost(MAX_HEIGHT),
        )
        overlay.revalidate()
    }

    private fun notificationCard(notification: DesktopNotification): JComponent = JPanel(BorderLayout(8, 0)).apply {
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
                    updateOverlaySize()
                    reposition()
                }
            }
            content.add(details, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(detailToggle)
                add(dismissButton(notification))
            }, BorderLayout.EAST)
        } else {
            add(dismissButton(notification), BorderLayout.EAST)
        }
        add(content, BorderLayout.CENTER)
    }

    private fun dismissButton(notification: DesktopNotification): JButton = JButton("[x]").apply {
        toolTipText = "Dismiss notification"
        margin = java.awt.Insets(0, 4, 0, 4)
        font = ThreadworkFonts.designerFont(11f)
        addActionListener {
            notifications.remove(notification)
            if (notifications.isEmpty()) {
                autoHideTimer.stop()
                overlay.isVisible = false
            } else {
                rebuild()
                reposition()
            }
        }
    }

    private companion object {
        const val AUTO_HIDE_DELAY_MS = 5_000
        const val CARD_GAP = 6
        const val MIN_WIDTH = 320
        const val MAX_WIDTH = 560
        const val MAX_HEIGHT = 360
        const val OVERLAY_MARGIN = 10
    }
}
