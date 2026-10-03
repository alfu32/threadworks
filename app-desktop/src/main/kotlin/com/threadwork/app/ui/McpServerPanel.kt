package com.threadwork.app.ui

import com.threadwork.mcp.McpServerController
import com.threadwork.mcp.McpServerStatus
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** Small host-side control surface; MCP protocol and model operations live in mcp-server. */
class McpServerPanel(
    private val controller: McpServerController,
) : JPanel(BorderLayout(8, 8)) {
    private val stateLabel = JLabel()
    private val endpointField = JTextField().apply {
        isEditable = false
        border = BorderFactory.createTitledBorder("Endpoint")
    }
    private val messageLabel = JLabel()
    private val startButton = JButton("Start")
    private val restartButton = JButton("Restart")
    private val stopButton = JButton("Stop")

    init {
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(JPanel(BorderLayout(8, 8)).apply {
            add(stateLabel, BorderLayout.WEST)
            add(endpointField, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(startButton)
                add(restartButton)
                add(stopButton)
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JTextArea(
            "The local MCP endpoint lets compatible agents inspect and edit the open Threadwork model. " +
                "It binds to loopback only and uses the standard MCP Streamable HTTP JSON-RPC endpoint.",
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = this@McpServerPanel.background
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        }, BorderLayout.CENTER)
        add(messageLabel, BorderLayout.SOUTH)

        startButton.addActionListener { controller.start() }
        restartButton.addActionListener { controller.restart() }
        stopButton.addActionListener { controller.stop() }
        controller.addStatusListener(::showStatus)
    }

    private fun showStatus(status: McpServerStatus) {
        val update = {
            stateLabel.text = if (status.running) "MCP: running" else "MCP: stopped"
            endpointField.text = status.endpoint
            messageLabel.text = status.message
            startButton.isEnabled = !status.running
            restartButton.isEnabled = status.running
            stopButton.isEnabled = status.running
        }
        if (SwingUtilities.isEventDispatchThread()) update() else SwingUtilities.invokeLater(update)
    }
}
