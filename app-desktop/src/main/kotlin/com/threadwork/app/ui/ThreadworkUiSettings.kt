package com.threadwork.app.ui

import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.prefs.Preferences
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

internal object ThreadworkUiSettings {
    private const val PREF_NODE = "com/threadwork/app/ui"
    private val preferences = Preferences.userRoot().node(PREF_NODE)

    fun rememberDividerLocation(splitPane: JSplitPane, key: String, defaultLocation: Int? = null) {
        val stored = preferences.getInt(key, -1)
        when {
            stored >= 0 -> splitPane.dividerLocation = stored
            defaultLocation != null -> splitPane.dividerLocation = defaultLocation
        }
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            splitPane.dividerLocation.takeIf { it >= 0 }?.let { preferences.putInt(key, it) }
        }
    }

    fun rememberLeadingPanelWidth(splitPane: JSplitPane, key: String, defaultWidth: Int) {
        splitPane.resizeWeight = 0.0
        rememberFixedPanelWidth(splitPane, key, defaultWidth, leading = true)
    }

    fun rememberTrailingPanelWidth(splitPane: JSplitPane, key: String, defaultWidth: Int) {
        splitPane.resizeWeight = 1.0
        rememberFixedPanelWidth(splitPane, key, defaultWidth, leading = false)
    }

    private fun rememberFixedPanelWidth(
        splitPane: JSplitPane,
        key: String,
        defaultWidth: Int,
        leading: Boolean,
    ) {
        val panelWidth = preferences.getInt(key, defaultWidth).coerceAtLeast(0)
        var restored = false

        fun restoreWidth() {
            if (restored || splitPane.width <= splitPane.dividerSize) return
            val dividerLocation = if (leading) {
                panelWidth
            } else {
                splitPane.width - splitPane.dividerSize - panelWidth
            }
            splitPane.dividerLocation = dividerLocation.coerceIn(0, splitPane.width - splitPane.dividerSize)
            restored = true
        }

        splitPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = restoreWidth()
        })
        SwingUtilities.invokeLater(::restoreWidth)
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            val width = if (leading) {
                splitPane.dividerLocation
            } else {
                splitPane.width - splitPane.dividerSize - splitPane.dividerLocation
            }
            width.takeIf { it >= 0 }?.let { preferences.putInt(key, it) }
        }
    }
}
