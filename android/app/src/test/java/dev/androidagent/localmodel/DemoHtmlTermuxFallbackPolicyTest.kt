package dev.androidagent.localmodel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class DemoHtmlTermuxFallbackPolicyTest {
    @Before
    fun enableDemoFallback() {
        System.setProperty("openclaw.local.demoFallback", "true")
    }

    @After
    fun clearDemoFallback() {
        System.clearProperty("openclaw.local.demoFallback")
    }

    @Test
    fun emptyCommandFallbackOnlyAppliesToHtmlTermuxRequests() {
        val fallback = DemoHtmlTermuxFallbackPolicy.fallbackForEmptyCommand(
            "Create an HTML project in Termux at /sdcard/Download/demo"
        )

        assertNotNull(fallback)
        assertEquals("/sdcard/Download/demo", fallback?.targetPath)
        assertTrue(fallback?.args?.getString("command")?.contains("index.html") == true)
    }

    @Test
    fun emptyCommandFallbackIgnoresNormalRequests() {
        val fallback = DemoHtmlTermuxFallbackPolicy.fallbackForEmptyCommand("What is the weather?")

        assertNull(fallback)
    }

    @Test
    fun replacementAddsBrowserOpenForHtmlBrowserRequests() {
        val fallback = DemoHtmlTermuxFallbackPolicy.replacementFor(
            userText = "Create an html calculator in termux and open it in the browser",
            args = JSONObject().put("command", "mkdir -p /sdcard/Download/calc")
        )

        val command = fallback?.args?.getString("command").orEmpty()
        assertNotNull(fallback)
        assertTrue(command.contains("OpenAgent Calculator"))
        assertTrue(command.contains("android.intent.action.VIEW"))
    }
}
