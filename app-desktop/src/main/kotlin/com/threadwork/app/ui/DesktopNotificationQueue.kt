package com.threadwork.app.ui

import com.threadwork.app.fonts.ThreadworkFonts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
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

/** Keeps notifications until dismissed in an owner-bound transparent native window. */
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
        background = TRANSPARENT
        viewport.isOpaque = false
        viewport.background = TRANSPARENT
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    }
    private val overlay = JPanel(BorderLayout()).apply {
        isOpaque = false
        background = TRANSPARENT
        border = BorderFactory.createEmptyBorder()
        add(scrollPane, BorderLayout.CENTER)
    }
    private var window: JWindow? = null
    private var anchor: JComponent? = null
    private val autoHideTimer = Timer(AUTO_HIDE_DELAY_MS) {
        window?.isVisible = false
    }.apply {
        isRepeats = false
    }

    fun publish(notification: DesktopNotification, anchor: JComponent) {
        notifications += notification
        this.anchor = anchor
        show(autoHide = true)
    }

    fun toggle(anchor: JComponent) {
        this.anchor = anchor
        if (window?.isVisible == true) {
            autoHideTimer.stop()
            window?.isVisible = false
        } else {
            show(autoHide = false)
        }
    }

    private fun show(autoHide: Boolean) {
        if (notifications.isEmpty()) return
        val currentAnchor = anchor ?: return
        rebuild()
        val currentWindow = windowFor(currentAnchor)
        currentWindow.pack()
        position(currentWindow, currentAnchor)
        currentWindow.isVisible = true
        if (autoHide) autoHideTimer.restart() else autoHideTimer.stop()
    }

    private fun windowFor(anchor: JComponent): JWindow {
        val owner = SwingUtilities.getWindowAncestor(anchor)
        val existing = window
        if (existing != null && existing.owner === owner) return existing
        existing?.dispose()
        return JWindow(owner).also { newWindow ->
            newWindow.background = TRANSPARENT
            newWindow.rootPane.isOpaque = false
            newWindow.contentPane = JPanel(BorderLayout()).apply {
                isOpaque = false
                background = TRANSPARENT
                add(overlay, BorderLayout.CENTER)
            }
            window = newWindow
        }
    }

    private fun position(window: Window, anchor: JComponent) {
        val location = anchor.locationOnScreen
        window.setLocation(
            location.x + anchor.width - window.width,
            location.y - window.height - OVERLAY_GAP,
        )
    }

    private fun rebuild() {
        cards.removeAll()
        notifications.forEachIndexed { index, notification ->
            cards.add(notificationCard(notification))
            if (index != notifications.lastIndex) cards.add(javax.swing.Box.createVerticalStrut(CARD_GAP))
        }
        cards.revalidate()
        cards.repaint()
        val preferred = cards.preferredSize
        scrollPane.preferredSize = Dimension(
            preferred.width.coerceIn(MIN_WIDTH, MAX_WIDTH),
            preferred.height.coerceAtMost(MAX_HEIGHT),
        )
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
                    window?.pack()
                    anchor?.let { currentAnchor -> window?.let { position(it, currentAnchor) } }
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
                window?.isVisible = false
            } else {
                show(autoHide = false)
            }
        }
    }

    private companion object {
        const val AUTO_HIDE_DELAY_MS = 5_000
        const val CARD_GAP = 6
        const val MIN_WIDTH = 320
        const val MAX_WIDTH = 560
        const val MAX_HEIGHT = 360
        const val OVERLAY_GAP = 4
        val TRANSPARENT = Color(0, 0, 0, 0)
    }
}
