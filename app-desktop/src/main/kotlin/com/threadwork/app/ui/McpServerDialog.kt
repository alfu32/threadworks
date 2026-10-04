package com.threadwork.app.ui

import com.threadwork.mcp.McpPingResult
import com.threadwork.mcp.McpServerController
import com.threadwork.mcp.McpServerStatus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/** Temporary management dialog for the local MCP server and its HTTP activity. */
class McpServerDialog(
    owner: Window,
    private val controller: McpServerController,
) : JDialog(owner, "MCP Server Management", ModalityType.MODELESS) {
    private val stateLabel = JLabel("MCP SERVER: CHECKING...").apply {
        font = font.deriveFont(Font.BOLD, 16f)
        horizontalAlignment = SwingConstants.LEFT
    }
    private val endpointField = readOnlyField("MCP endpoint")
    private val pingField = readOnlyField("Liveness ping")
    private val messageLabel = JLabel()
    private val accessLogArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
    }
    private val startButton = JButton("Start")
    private val restartButton = JButton("Restart")
    private val stopButton = JButton("Stop")
    private val pingButton = JButton("Ping now")
    private var controllerRunning = false
    private var pingAlive: Boolean? = null
    private var pingMessage = ""
    private val pollingTimer = Timer(15_000) { ping() }.apply { isRepeats = true }
    private val statusListener: (McpServerStatus) -> Unit = ::showStatus
    private val accessLogListener: (String) -> Unit = ::showAccessLog

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        layout = BorderLayout(10, 10)
        rootPane.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        add(JPanel(BorderLayout(10, 10)).apply {
            add(stateLabel, BorderLayout.NORTH)
            add(JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                add(endpointField)
                add(pingField)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(startButton)
                add(restartButton)
                add(stopButton)
                add(pingButton)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)

        add(JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createTitledBorder("HTTP access log")
            add(JScrollPane(accessLogArea), BorderLayout.CENTER)
        }, BorderLayout.CENTER)

        add(JPanel(BorderLayout()).apply {
            add(messageLabel, BorderLayout.CENTER)
            add(JButton("Close").apply { addActionListener { dispose() } }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)

        startButton.addActionListener {
            controller.start()
            ping()
        }
        restartButton.addActionListener {
            controller.restart()
            ping()
        }
        stopButton.addActionListener { controller.stop() }
        pingButton.addActionListener { ping() }

        controller.addStatusListener(statusListener)
        controller.addAccessLogListener(accessLogListener)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                pollingTimer.stop()
                controller.removeStatusListener(statusListener)
                controller.removeAccessLogListener(accessLogListener)
            }
        })
        setSize(Dimension(980, 620))
        setMinimumSize(Dimension(760, 420))
        setLocationRelativeTo(owner)
        pollingTimer.start()
        ping()
    }

    private fun readOnlyField(title: String): JTextField = JTextField().apply {
        isEditable = false
        border = BorderFactory.createTitledBorder(title)
        maximumSize = Dimension(Int.MAX_VALUE, 42)
    }

    private fun showStatus(status: McpServerStatus) {
        val update = {
            controllerRunning = status.running
            if (!status.running) pingAlive = false
            endpointField.text = status.endpoint
            pingField.text = status.pingEndpoint
            messageLabel.text = status.message
            startButton.isEnabled = !status.running
            restartButton.isEnabled = status.running
            stopButton.isEnabled = status.running
            updateState()
        }
        if (SwingUtilities.isEventDispatchThread()) update() else SwingUtilities.invokeLater(update)
    }

    private fun showAccessLog(value: String) {
        val update = {
            accessLogArea.text = value
            accessLogArea.caretPosition = accessLogArea.document.length
        }
        if (SwingUtilities.isEventDispatchThread()) update() else SwingUtilities.invokeLater(update)
    }

    private fun ping() {
        pingAlive = null
        pingMessage = "Checking ${controller.status.pingEndpoint}..."
        updateState()
        controller.pingAsync(::showPing)
    }

    private fun showPing(result: McpPingResult) {
        val update = {
            if (controller.status.running) {
                pingAlive = result.alive
                pingMessage = result.message
            } else {
                pingAlive = false
                pingMessage = "MCP server is stopped"
            }
            updateState()
        }
        if (SwingUtilities.isEventDispatchThread()) update() else SwingUtilities.invokeLater(update)
    }

    private fun updateState() {
        val state = when {
            !controllerRunning -> "MCP SERVER: OFF"
            pingAlive == true -> "MCP SERVER: ON"
            pingAlive == false -> "MCP SERVER: OFF (NOT RESPONDING)"
            else -> "MCP SERVER: CHECKING..."
        }
        stateLabel.text = state
        stateLabel.foreground = when {
            !controllerRunning || pingAlive == false -> Color(0xff9b1c1c)
            pingAlive == true -> Color(0xff18723b)
            else -> Color(0xff8a6500)
        }
        if (pingMessage.isNotBlank()) messageLabel.text = pingMessage
    }
}
