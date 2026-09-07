package com.threadwork.app.ui

import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNotificationQueueTest {
    @Test
    fun `published notifications render in a transparent layered overlay`() {
        SwingUtilities.invokeAndWait {
            val host = JLayeredPane().apply { setSize(900, 700) }
            val statusBar = JPanel().apply { setSize(900, 24) }
            val queue = DesktopNotificationQueue()
            queue.install(host, statusBar)

            queue.publish(
                DesktopNotification(
                    DesktopNotificationType.Success,
                    "generated example",
                    " - example.c",
                ),
            )

            assertEquals(1, host.componentCount)
            val overlay = host.getComponent(0)
            assertTrue(overlay.isVisible)
            assertFalse(overlay.isOpaque)
            assertTrue(overlay.width > 0)
            assertTrue(overlay.height > 0)

            queue.toggle()
            assertFalse(overlay.isVisible)
            queue.toggle()
            assertTrue(overlay.isVisible)
        }
    }
}
