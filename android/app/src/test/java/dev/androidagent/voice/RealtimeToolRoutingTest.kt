package dev.androidagent.voice

import dev.androidagent.AgentModelOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RealtimeToolRoutingTest {
    @Test
    fun localModelRoutesDelegatedTasksLocally() {
        assertEquals(
            RealtimeToolExecutionRoute.Local,
            RealtimeToolRouting.routeFor(
                AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                RealtimeToolRouting.intentFor("delegate_agent_task", JSONObject().put("instruction", "Summarize"))
            )
        )
        assertEquals(
            RealtimeToolExecutionRoute.Local,
            RealtimeToolRouting.routeFor(
                AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                RealtimeToolRouting.intentFor("run_phone_task", JSONObject().put("instruction", "Open Settings"))
            )
        )
        assertEquals(
            RealtimeToolExecutionRoute.Local,
            RealtimeToolRouting.routeFor(
                AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                RealtimeToolRouting.intentFor("stop_agent_task", JSONObject().put("reason", "stop"))
            )
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
        assertEquals(
            RealtimeToolIntent.StartTask(RealtimeTaskIntentKind.Phone, "Open Settings"),
            RealtimeToolRouting.intentFor("run_phone_task", JSONObject().put("instruction", " Open Settings "))
        )
        assertEquals(
            RealtimeToolIntent.StartTask(RealtimeTaskIntentKind.General, "Summarize"),
            RealtimeToolRouting.intentFor("delegate_agent_task", JSONObject().put("task", "Summarize"))
        )
        assertEquals(
            RealtimeToolIntent.SteerTask(RealtimeTaskIntentKind.General, "Focus on unread"),
            RealtimeToolRouting.intentFor("steer_agent_task", JSONObject().put("guidance", "Focus on unread"))
        )
    }

    @Test
    fun stopToolDetectionCoversAgentAndPhoneStops() {
        assertEquals(
            RealtimeToolIntent.StopTask(RealtimeTaskIntentKind.General, "Stop now"),
            RealtimeToolRouting.intentFor("stop_agent_task", JSONObject().put("reason", "Stop now"))
        )
        assertEquals(
            RealtimeToolIntent.StopTask(RealtimeTaskIntentKind.Phone, "Stopped by realtime voice"),
            RealtimeToolRouting.intentFor("stop_phone_task", JSONObject())
        )
        assertFalse(RealtimeToolRouting.intentFor("delegate_agent_task", JSONObject()) is RealtimeToolIntent.StopTask)
    }

    @Test
    fun bridgeOnlyAndUnsupportedToolsDoNotRouteLocal() {
        assertEquals(
            RealtimeToolIntent.BridgeOnly,
            RealtimeToolRouting.intentFor("web_search", JSONObject().put("query", "news"))
        )
        assertEquals(
            RealtimeToolExecutionRoute.Bridge,
            RealtimeToolRouting.routeFor(
                AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                RealtimeToolRouting.intentFor("web_search", JSONObject().put("query", "news"))
            )
        )
        assertEquals(
            RealtimeToolExecutionRoute.Bridge,
            RealtimeToolRouting.routeFor(
                AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                RealtimeToolRouting.intentFor("unknown_tool", JSONObject())
            )
        )
    }
}
