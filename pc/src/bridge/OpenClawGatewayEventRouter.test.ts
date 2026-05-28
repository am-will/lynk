import assert from "node:assert/strict";
import test from "node:test";
import type { ChatOutboundMessage } from "../protocol/messages.js";
import { OpenClawGatewayEventRouter } from "./OpenClawGatewayEventRouter.js";
import { DeviceChatStateStore } from "./OpenClawChatTypes.js";

test("agent channel deltas for tracked pending runs bypass the active run filter", () => {
  const states = new DeviceChatStateStore({
    openClawChatAgentId: "main",
    openClawChatSessionKey: "agent:main:explicit:open-claw-agent"
  });
  const state = states.stateFor("pixel");
  state.sessionKey = "agent:main:active";
  state.runId = "run_active";
  state.pendingRuns.set("run_background", {
    sessionKey: state.sessionKey,
    sessionId: null,
    taskKind: "general",
    startedAt: 1
  });
  const messages: ChatOutboundMessage[] = [];
  const router = new OpenClawGatewayEventRouter({
    states,
    sendChat: (_deviceId, message) => messages.push(message),
    sendState: () => {},
    sendReasoningClear: () => {},
    settleRun: () => {},
    drainQueuedSends: () => {},
    sendReplyAvailable: () => {},
    refreshMetadata: async () => {},
    sendHistory: async () => {}
  });

  router.handleEvent({
    event: "agent",
    payload: {
      type: "message.delta",
      sessionKey: state.sessionKey,
      runId: "run_background",
      data: { textDelta: "Hi" }
    }
  });

  assert.deepEqual(messages, [{
    type: "chat.delta",
    deviceId: "pixel",
    sessionKey: state.sessionKey,
    runId: "run_background",
    delta: "Hi",
    replace: false
  }]);
});
