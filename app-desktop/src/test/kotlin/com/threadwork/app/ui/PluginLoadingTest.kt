package com.threadwork.app.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class PluginLoadingTest {
    @Test
    fun `plugin discovery does not abort startup when its folder is unavailable`() {
        val unavailableFolder = Files.createTempFile("threadwork-plugin-folder", ".file")
        try {
            assertTrue(loadDesktopPlugins(unavailableFolder).isEmpty())
            assertTrue(loadCompilerPlugins(unavailableFolder).isEmpty())
        } finally {
            Files.deleteIfExists(unavailableFolder)
        }
    }
}
