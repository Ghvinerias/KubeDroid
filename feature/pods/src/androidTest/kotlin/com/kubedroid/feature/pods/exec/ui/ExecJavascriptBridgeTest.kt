package com.kubedroid.feature.pods.exec.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecJavascriptBridgeTest {

    @Test
    fun onInput_andOnResize_forwardArguments() {
        var capturedInput: String? = null
        var capturedResize: Pair<Int, Int>? = null
        val bridge = ExecJavascriptBridge(
            onTerminalInput = { capturedInput = it },
            onTerminalResize = { cols, rows -> capturedResize = cols to rows },
            onTerminalReady = {},
            onTerminalTapped = {},
        )

        bridge.onInput("kubectl get pods\n")
        bridge.onResize(columns = 132, rows = 41)

        assertEquals("kubectl get pods\n", capturedInput)
        assertEquals(132 to 41, capturedResize)
    }

    @Test
    fun onReady_andOnTap_invokeCallbacks() {
        var readyCalled = false
        var tapCalled = false
        val bridge = ExecJavascriptBridge(
            onTerminalInput = {},
            onTerminalResize = { _, _ -> },
            onTerminalReady = { readyCalled = true },
            onTerminalTapped = { tapCalled = true },
        )

        bridge.onReady()
        bridge.onTap()

        assertTrue(readyCalled)
        assertTrue(tapCalled)
    }
}
