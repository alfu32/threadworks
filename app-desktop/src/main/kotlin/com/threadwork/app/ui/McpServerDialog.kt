package com.threadwork.app.ui

import com.threadwork.mcp.McpHealthResult
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
    private val healthField = readOnlyField("Health endpoint")
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
    private val healthButton = JButton("Check health")
    private var controllerRunning = false
    private var healthAlive: Boolean? = null
    private var healthMessage = ""
    private val pollingTimer = Timer(15_000) { checkHealth() }.apply { isRepeats = true }
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
                add(healthField)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(startButton)
                add(restartButton)
                add(stopButton)
                add(healthButton)
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
            checkHealth()
        }
        restartButton.addActionListener {
            controller.restart()
            checkHealth()
        }
        stopButton.addActionListener { controller.stop() }
        healthButton.addActionListener { checkHealth() }

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
        checkHealth()
    }

    private fun readOnlyField(title: String): JTextField = JTextField().apply {
        isEditable = false
        border = BorderFactory.createTitledBorder(title)
        maximumSize = Dimension(Int.MAX_VALUE, 42)
    }

    private fun showStatus(status: McpServerStatus) {
        val update = {
            controllerRunning = status.running
            if (!status.running) healthAlive = false
            endpointField.text = status.endpoint
            healthField.text = status.healthEndpoint
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

    private fun checkHealth() {
        healthAlive = null
        healthMessage = "Checking ${controller.status.healthEndpoint}..."
        updateState()
        controller.healthAsync(::showHealth)
    }

    private fun showHealth(result: McpHealthResult) {
        val update = {
            if (controller.status.running) {
                healthAlive = result.alive
                healthMessage = result.message
            } else {
                healthAlive = false
                healthMessage = "MCP server is stopped"
            }
            updateState()
        }
        if (SwingUtilities.isEventDispatchThread()) update() else SwingUtilities.invokeLater(update)
    }

    private fun updateState() {
        val state = when {
            !controllerRunning -> "MCP SERVER: OFF"
            healthAlive == true -> "MCP SERVER: ON"
            healthAlive == false -> "MCP SERVER: OFF (NOT RESPONDING)"
            else -> "MCP SERVER: CHECKING..."
        }
        stateLabel.text = state
        stateLabel.foreground = when {
            !controllerRunning || healthAlive == false -> Color(0xff9b1c1c.toInt())
            healthAlive == true -> Color(0xff18723b.toInt())
            else -> Color(0xff8a6500.toInt())
        }
        if (healthMessage.isNotBlank()) messageLabel.text = healthMessage
    }
}
