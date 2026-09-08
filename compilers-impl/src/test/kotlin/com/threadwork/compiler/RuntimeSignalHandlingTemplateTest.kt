package com.threadwork.compiler

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeSignalHandlingTemplateTest {
    @Test
    fun `C PHP and Node signal handlers record a signal for model shutdown logic`() {
        val cRuntime = runtime("c")
        val cHandler = cRuntime.substringAfter("static void threadwork_shutdown_signal_handler")
            .substringBefore("threadwork_error_t threadwork_runner__init")
        assertTrue(cHandler.contains("shutdown_signal = signal_number;"))
        assertFalse(cHandler.contains("running = 0;"))

        val phpRuntime = runtime("php")
        val phpHandler = phpRuntime.substringAfter("\$handleSignal = function")
            .substringBefore("pcntl_signal(SIGINT")
        assertTrue(phpHandler.contains("\$this->shutdownSignal = \$signal;"))
        assertFalse(phpHandler.contains("shutdownRequest"))

        val nodeRuntime = runtime("nodejs")
        val nodeHandler = nodeRuntime.substringAfter("const requestShutdown =")
            .substringBefore("process.once(\"SIGINT\"")
        assertTrue(nodeHandler.contains("this.shutdownSignal = signalNumber;"))
        assertFalse(nodeHandler.contains("this.shutdownRequest"))
    }

    private fun runtime(language: String): String = requireNotNull(
        javaClass.getResourceAsStream("/compiler-templates/$language/runtime.peb"),
    ).bufferedReader().use { it.readText() }
}
