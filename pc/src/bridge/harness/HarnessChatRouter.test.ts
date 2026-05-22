import assert from "node:assert/strict";
import test from "node:test";
import type { ChatModelOption, ChatSessionSummary } from "../../protocol/messages.js";
import type { HarnessId } from "../AgentHarness.js";
import type { BridgeConfig } from "../config.js";
import type { GatewayChatSendResult, GatewayEventHandler } from "../OpenClawGatewayChatClient.js";
import { HarnessChatRouter } from "./HarnessChatRouter.js";
import type { HarnessChatAdapter, HarnessCreatedSession, HarnessSessionList } from "./HarnessChatAdapter.js";

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

class FakeAdapter implements HarnessChatAdapter {
  readonly created: Array<{ key?: string; label?: string; model?: string }> = [];
  readonly sent: Array<{ sessionKey: string; message: string }> = [];
  readonly sessions: ChatSessionSummary[] = [];
  readonly models: ChatModelOption[] = [];

  constructor(readonly harnessId: HarnessId) {}

  addEventListener(_handler: GatewayEventHandler): () => void {
    return () => undefined;
  }

  async history(): Promise<{ messages: [] }> {
    return { messages: [] };
  }

  async sendChat(options: { sessionKey: string; message: string }): Promise<GatewayChatSendResult> {
    this.sent.push(options);
    return { runId: `${this.harnessId}_run`, sessionKey: options.sessionKey };
  }

  async abort(): Promise<unknown> {
    return {};
  }

  async listModels(): Promise<ChatModelOption[]> {
    return this.models;
  }

  async listSessions(): Promise<HarnessSessionList> {
    return { sessions: this.sessions, reasoningOptions: [] };
  }

  async createSession(options: { key?: string; label?: string; model?: string }): Promise<HarnessCreatedSession> {
    this.created.push(options);
    return { key: options.key ?? `${this.harnessId}:created`, sessionId: `${this.harnessId}_session` };
  }

  async patchSession(): Promise<unknown> {
    return {};
  }

  async listCommands(): Promise<[]> {
    return [];
  }

  async effectiveTools(): Promise<[]> {
    return [];
  }

  async health(): Promise<unknown> {
    return { ok: true };
  }

  close(): void {}
}

function createRouter(overrides: Partial<BridgeConfig> = {}) {
  const openclaw = new FakeAdapter("openclaw");
  const hermes = new FakeAdapter("hermes");
  const codex = new FakeAdapter("codex");
  const router = new HarnessChatRouter({ ...config, ...overrides }, undefined, [openclaw, hermes, codex]);
  return { router, openclaw, hermes, codex };
}

test("harness router routes bare and namespaced model selections", async () => {
  const { router, openclaw, hermes, codex } = createRouter();

  await router.createSession({ model: "gpt-5.5" });
  await router.createSession({ model: "hermes:gpt-5.5" });
  await router.createSession({ model: "codex:gpt-5.5" });

  assert.deepEqual(openclaw.created.map((entry) => entry.model), ["gpt-5.5"]);
  assert.deepEqual(hermes.created.map((entry) => entry.model), ["gpt-5.5"]);
  assert.deepEqual(codex.created.map((entry) => entry.model), ["gpt-5.5"]);
});

test("harness router lets explicit non-default session keys choose the harness", async () => {
  const { router, openclaw, codex } = createRouter();

  const created = await router.createSession({
    key: "codex:phone-pixel-123",
    model: "gpt-5.3-codex"
  }) as HarnessCreatedSession;

  assert.equal(openclaw.created.length, 0);
  assert.equal(codex.created.length, 1);
  assert.equal(codex.created[0]?.key, "codex:phone-pixel-123");
  assert.equal(codex.created[0]?.model, "gpt-5.3-codex");
  assert.equal(created.key, "codex:phone-pixel-123");
});

test("harness router scopes session lists to the active harness", async () => {
  const { router, openclaw, hermes, codex } = createRouter();
  openclaw.sessions.push({ key: "agent:main:openclaw", label: "OpenClaw" });
  hermes.sessions.push({ key: "hermes:chat", label: "Hermes" });
  codex.sessions.push({ key: "codex:chat", label: "Codex" });

  const payload = await router.listSessions(50, "hermes") as { sessions: ChatSessionSummary[] };

  assert.deepEqual(payload.sessions.map((session) => session.key), ["hermes:chat"]);
  assert.equal(payload.sessions[0]?.harnessId, "hermes");
});

test("harness router rejects disabled Hermes session keys clearly", async () => {
  const openclaw = new FakeAdapter("openclaw");
  const codex = new FakeAdapter("codex");
  const router = new HarnessChatRouter({ ...config, hermesApiKey: undefined }, undefined, [openclaw, codex]);

  await assert.rejects(
    router.sendChat({ sessionKey: "hermes:chat", message: "Use Hermes" }),
    /hermes harness is not configured/
  );
});
