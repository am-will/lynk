import assert from "node:assert/strict";
import test from "node:test";
import { OpenClawRealtimeSessions } from "./OpenClawRealtimeSessions.js";
import { DeviceChatStateStore } from "./OpenClawChatTypes.js";
import type { DeviceChatState, GatewayChatClient } from "./OpenClawChatTypes.js";

class FakeGatewayClient {
  readonly created: Array<{ key?: string; label?: string; model?: string }> = [];

  async createSession(options: { key?: string; label?: string; model?: string }): Promise<unknown> {
    this.created.push(options);
    return { sessionId: `session_${this.created.length}` };
  }
}

const config = {
  openClawChatAgentId: "main",
  openClawChatSessionKey: "agent:main:explicit:open-claw-agent"
};

function createHarness() {
  const client = new FakeGatewayClient();
  const states = new DeviceChatStateStore(config);
  const statuses: string[] = [];
  const realtimeSessions = new OpenClawRealtimeSessions({
    config,
    client: client as unknown as GatewayChatClient,
    states,
    sendState: (_deviceId, status) => {
      if (status) {
        statuses.push(status);
      }
    },
    refreshDevice: async () => undefined
  });
  return { client, states, statuses, realtimeSessions };
}

test("realtime session keys keep the active Hermes harness prefix", async () => {
  const { client, states, realtimeSessions } = createHarness();
  const state = states.stateFor("pixel") as DeviceChatState;
  state.harnessId = "hermes";
  state.sessionKey = "hermes:previous";
  state.model = "hermes:gpt-5.5";

  await realtimeSessions.ensureFreshRealtimeSession("pixel", "Summarize my project");

  assert.match(client.created[0]?.key ?? "", /^hermes:realtime-pixel-/);
  assert.equal(client.created[0]?.model, "hermes:gpt-5.5");
  assert.equal(state.sessionKey, client.created[0]?.key);
  assert.equal(state.sessionId, "session_1");
});

test("realtime session keys keep the active Codex harness prefix", async () => {
  const { client, states, realtimeSessions } = createHarness();
  const state = states.stateFor("pixel") as DeviceChatState;
  state.harnessId = "codex";
  state.sessionKey = "codex:previous";
  state.model = "codex:gpt-5.3-codex";

  await realtimeSessions.ensureFreshRealtimeSession("pixel", "Summarize my project");

  assert.match(client.created[0]?.key ?? "", /^codex:realtime-pixel-/);
  assert.equal(client.created[0]?.model, "codex:gpt-5.3-codex");
  assert.equal(state.sessionKey, client.created[0]?.key);
  assert.equal(state.sessionId, "session_1");
});
