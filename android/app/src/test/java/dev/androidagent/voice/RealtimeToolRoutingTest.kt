package dev.androidagent.voice

import dev.androidagent.AgentModelOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeToolRoutingTest {
    @Test
    fun localModelRoutesDelegatedTasksLocally() {
        assertEquals(
            RealtimeToolExecutionRoute.Local,
            RealtimeToolRouting.routeFor(AgentModelOptions.LOCAL_LITERT_MODEL_ID, "delegate_agent_task")
        )
        assertEquals(
            RealtimeToolExecutionRoute.Local,
            RealtimeToolRouting.routeFor(AgentModelOptions.LOCAL_LITERT_MODEL_ID, "run_phone_task")
        )
        assertEquals(
            RealtimeToolExecutionRoute.Local,
            RealtimeToolRouting.routeFor(AgentModelOptions.LOCAL_LITERT_MODEL_ID, "stop_agent_task")
        )
    }

    @Test
    fun bridgeToolsStayOnBridgeForLocalModel() {
        assertEquals(
            RealtimeToolExecutionRoute.Bridge,
            RealtimeToolRouting.routeFor(AgentModelOptions.LOCAL_LITERT_MODEL_ID, "web_search")
        )
        assertEquals(
            RealtimeToolExecutionRoute.Bridge,
            RealtimeToolRouting.routeFor(AgentModelOptions.LOCAL_LITERT_MODEL_ID, "hang_up_realtime")
        )
    }

    @Test
    fun hostModelsRouteDelegatedTasksToBridge() {
        assertEquals(
            RealtimeToolExecutionRoute.Bridge,
            RealtimeToolRouting.routeFor("hermes:gpt-5.5", "delegate_agent_task")
        )
    }

    @Test
    fun instructionAcceptsInstructionTaskOrGuidance() {
        assertEquals("Open Settings", RealtimeToolRouting.instruction(JSONObject().put("instruction", " Open Settings ")))
        assertEquals("Summarize", RealtimeToolRouting.instruction(JSONObject().put("task", "Summarize")))
        assertEquals("Focus on unread", RealtimeToolRouting.instruction(JSONObject().put("guidance", "Focus on unread")))
    }

    @Test
    fun stopToolDetectionCoversAgentAndPhoneStops() {
        assertTrue(RealtimeToolRouting.isStopTool("stop_agent_task"))
        assertTrue(RealtimeToolRouting.isStopTool("stop_phone_task"))
        assertFalse(RealtimeToolRouting.isStopTool("delegate_agent_task"))
    }
}
