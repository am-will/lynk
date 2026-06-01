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

test("agent tool events preserve OpenCode permission actions", () => {
  const states = new DeviceChatStateStore({
    openClawChatAgentId: "main",
    openClawChatSessionKey: "agent:main:explicit:open-claw-agent"
  });
  const state = states.stateFor("pixel");
  state.sessionKey = "opencode:ses_1";
  state.runId = "run_1";
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
      type: "chat.tool_event",
      sessionKey: "opencode:ses_1",
      runId: "run_1",
      eventId: "opencode_permission_perm_1",
      toolName: "permission",
      title: "OpenCode permission request",
      status: "blocked",
      actions: [
        { id: "once", label: "Allow Once", command: "opencode.permission", args: { permissionId: "perm_1", response: "once" }, style: "primary" },
        { id: "reject", label: "Reject", command: "opencode.permission", args: { permissionId: "perm_1", response: "reject" }, style: "danger" }
      ]
    }
  });

  assert.equal(messages[0]?.type, "chat.tool_event");
  assert.deepEqual(
    messages[0]?.type === "chat.tool_event" ? messages[0].actions?.map((action) => [action.id, action.command, action.style]) : [],
    [
      ["once", "opencode.permission", "primary"],
      ["reject", "opencode.permission", "danger"]
    ]
  );
});
