package com.threadwork.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNotificationQueueTest {
    @Test
    fun `notification types expose stable status markers`() {
        assertEquals("[e]", DesktopNotificationType.Error.marker)
        assertEquals("[w]", DesktopNotificationType.Warning.marker)
        assertEquals("[y]", DesktopNotificationType.Success.marker)
        assertEquals("[i]", DesktopNotificationType.Info.marker)
    }
}
