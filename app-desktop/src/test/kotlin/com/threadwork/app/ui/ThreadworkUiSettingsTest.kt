package com.threadwork.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadworkUiSettingsTest {
    @Test
    fun `center width is derived from fixed side panel widths`() {
        val totalWidth = 1_600
        val outerDividerWidth = 10
        val innerDividerWidth = 10
        val layout = horizontalThreePanelLayout(
            totalWidth = totalWidth,
            outerDividerWidth = outerDividerWidth,
            innerDividerWidth = innerDividerWidth,
            leadingWidth = 300,
            trailingWidth = 420,
        )

        assertEquals(300, layout.outerDividerLocation)
        assertEquals(860, layout.innerDividerLocation)
        assertEquals(
            totalWidth - outerDividerWidth - innerDividerWidth - 300 - 420,
            layout.innerDividerLocation,
        )
    }

    @Test
    fun `narrow windows never produce negative divider locations`() {
        val layout = horizontalThreePanelLayout(
            totalWidth = 700,
            outerDividerWidth = 10,
            innerDividerWidth = 10,
            leadingWidth = 420,
            trailingWidth = 420,
        )

        assertEquals(420, layout.outerDividerLocation)
        assertEquals(0, layout.innerDividerLocation)
        assertTrue(layout.outerDividerLocation >= 0)
        assertTrue(layout.innerDividerLocation >= 0)
    }
}
