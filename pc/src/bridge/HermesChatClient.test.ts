import assert from "node:assert/strict";
import test from "node:test";
import type { HermesApiClient } from "../dispatcher/HermesApiClient.js";
import type { BridgeConfig } from "./config.js";
import { HermesChatClient } from "./HermesChatClient.js";

const config: BridgeConfig = {
  host: "127.0.0.1",
  port: 8788,
  token: "token",
  defaultDeviceId: "pixel",
  bridgeUrl: "http://127.0.0.1:8788",
  openClawGatewayUrl: "ws://127.0.0.1:18789",
  openClawChatAgentId: "main",
  openClawChatSessionKey: "agent:main:explicit:open-claw-agent",
  hermesApiBaseUrl: "http://127.0.0.1:8642/v1",
  hermesApiKey: "hermes-key",
  hermesModel: "hermes-agent",
  hermesDefaultSessionId: "hermes-agent",
  hermesRunTimeoutMs: 600_000,
  openAiRealtimeModel: "gpt-realtime-2",
  openAiRealtimeVoice: "marin",
  openAiWebSearchModel: "gpt-5.5"
};

class FakeHermesApiClient {
  readonly createdRuns: Array<{ input: string; sessionId: string; idempotencyKey?: string }> = [];
  sessionsPayload: unknown = {
    sessions: [{
      session_id: "20260521_211022_1f4f0b",
      model: "hermes-agent",
      timestamp: "2026-05-22T03:10:22.000Z",
      preview: "Previous Hermes chat",
      token_counts: { input: 10, output: 4, total: 14 }
    }]
  };
  messagesPayload: unknown = {
    messages: [{
      message_id: "msg_1",
      role: "user",
      content: "Hello Hermes",
      timestamp: "2026-05-22T03:10:22.000Z"
    }, {
      message_id: "msg_2",
      role: "assistant",
      content: "Hello back",
      timestamp: "2026-05-22T03:10:23.000Z"
    }]
  };

  async listSessions(): Promise<unknown> {
    return this.sessionsPayload;
  }

  async listSessionMessages(): Promise<unknown> {
    return this.messagesPayload;
  }

  async listModels(): Promise<unknown> {
    return { models: [] };
  }

  async capabilities(): Promise<unknown> {
    return {};
  }

  async health(): Promise<unknown> {
    return { ok: true };
  }

  async stopRun(): Promise<void> {}

  async createRun(options: { input: string; sessionId: string; idempotencyKey?: string }): Promise<{ runId: string; sessionId: string }> {
    this.createdRuns.push(options);
    return { runId: `steer_${this.createdRuns.length}`, sessionId: options.sessionId };
  }
}

test("Hermes lists sessions from dashboard API", async () => {
  const client = new HermesChatClient(config, new FakeHermesApiClient() as unknown as HermesApiClient, null);

  const payload = await client.listSessions() as { sessions: Array<Record<string, unknown>> };

  assert.deepEqual(payload.sessions.map((session) => ({
    key: session.key,
    sessionId: session.sessionId,
    label: session.label,
    model: session.model,
    totalTokens: session.totalTokens
  })), [{
    key: "hermes:20260521_211022_1f4f0b",
    sessionId: "20260521_211022_1f4f0b",
    label: "Previous Hermes chat",
    model: "hermes-agent",
    totalTokens: 14
  }]);
});

test("Hermes history loads messages from dashboard API for selected sessions", async () => {
  const client = new HermesChatClient(config, new FakeHermesApiClient() as unknown as HermesApiClient, null);

  const payload = await client.history("hermes:20260521_211022_1f4f0b") as { messages: Array<Record<string, unknown>> };

  assert.deepEqual(payload.messages.map((message) => ({
    id: message.id,
    role: message.role,
    text: message.text
  })), [{
    id: "msg_1",
    role: "user",
    text: "Hello Hermes"
  }, {
    id: "msg_2",
    role: "assistant",
    text: "Hello back"
  }]);
});

test("Hermes chat steering uses the active run driver", async () => {
  const api = new FakeHermesApiClient();
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);
  (client as unknown as { activeRuns: Map<string, { sessionKey: string; active: { runId: string; sessionId: string; controller: AbortController } }> }).activeRuns.set("run_1", {
    sessionKey: "hermes:chat",
    active: {
      runId: "run_1",
      sessionId: "session_1",
      controller: new AbortController()
    }
  });

  await client.steerChat({
    sessionKey: "hermes:chat",
    runId: "run_1",
    message: "Narrow the scope",
    idempotencyKey: "steer_1"
  });

  assert.equal(api.createdRuns[0]?.input, "Additional user guidance for the active Hermes task:\nNarrow the scope");
  assert.equal(api.createdRuns[0]?.sessionId, "session_1");
  assert.match(api.createdRuns[0]?.idempotencyKey ?? "", /^hermes-steer-run_1-/);
});
