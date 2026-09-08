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

    fun rememberHorizontalSidePanelWidths(
        outerSplitPane: JSplitPane,
        innerSplitPane: JSplitPane,
        leadingKey: String,
        trailingKey: String,
        defaultLeadingWidth: Int,
        defaultTrailingWidth: Int,
    ) {
        require(outerSplitPane.orientation == JSplitPane.HORIZONTAL_SPLIT)
        require(innerSplitPane.orientation == JSplitPane.HORIZONTAL_SPLIT)
        outerSplitPane.resizeWeight = 0.0
        innerSplitPane.resizeWeight = 1.0

        var leadingWidth = preferences.getInt(leadingKey, defaultLeadingWidth).coerceAtLeast(0)
        var trailingWidth = preferences.getInt(trailingKey, defaultTrailingWidth).coerceAtLeast(0)
        var applyingLayout = false
        var layoutApplied = false
        var layoutScheduled = false

        fun applyLayout() {
            layoutScheduled = false
            if (outerSplitPane.width <= outerSplitPane.dividerSize + innerSplitPane.dividerSize) return
            val layout = horizontalThreePanelLayout(
                totalWidth = outerSplitPane.width,
                outerDividerWidth = outerSplitPane.dividerSize,
                innerDividerWidth = innerSplitPane.dividerSize,
                leadingWidth = leadingWidth,
                trailingWidth = trailingWidth,
            )
            applyingLayout = true
            try {
                outerSplitPane.dividerLocation = layout.outerDividerLocation
                outerSplitPane.doLayout()
                innerSplitPane.dividerLocation = layout.innerDividerLocation
                innerSplitPane.doLayout()
                layoutApplied = true
            } finally {
                applyingLayout = false
            }
        }

        fun scheduleLayout() {
            if (layoutScheduled) return
            layoutScheduled = true
            SwingUtilities.invokeLater(::applyLayout)
        }

        outerSplitPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = scheduleLayout()
        })
        innerSplitPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = scheduleLayout()
        })
        outerSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (applyingLayout || !layoutApplied) return@addPropertyChangeListener
            leadingWidth = outerSplitPane.dividerLocation.coerceAtLeast(0)
            preferences.putInt(leadingKey, leadingWidth)
            scheduleLayout()
        }
        innerSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (applyingLayout || !layoutApplied) return@addPropertyChangeListener
            trailingWidth = (
                innerSplitPane.width - innerSplitPane.dividerSize - innerSplitPane.dividerLocation
                ).coerceAtLeast(0)
            preferences.putInt(trailingKey, trailingWidth)
        }
        scheduleLayout()
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

internal data class HorizontalThreePanelLayout(
    val outerDividerLocation: Int,
    val innerDividerLocation: Int,
)

internal fun horizontalThreePanelLayout(
    totalWidth: Int,
    outerDividerWidth: Int,
    innerDividerWidth: Int,
    leadingWidth: Int,
    trailingWidth: Int,
): HorizontalThreePanelLayout {
    val panelWidth = (totalWidth - outerDividerWidth.coerceAtLeast(0) - innerDividerWidth.coerceAtLeast(0))
        .coerceAtLeast(0)
    val actualLeadingWidth = leadingWidth.coerceIn(0, panelWidth)
    val remainingWidth = panelWidth - actualLeadingWidth
    val actualTrailingWidth = trailingWidth.coerceIn(0, remainingWidth)
    return HorizontalThreePanelLayout(
        outerDividerLocation = actualLeadingWidth,
        innerDividerLocation = remainingWidth - actualTrailingWidth,
    )
}
