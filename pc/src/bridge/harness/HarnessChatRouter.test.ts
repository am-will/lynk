import assert from "node:assert/strict";
import test from "node:test";
import type { ChatAttachment, ChatCommandOption, ChatModelOption, ChatSessionSummary } from "../../protocol/messages.js";
import type { HarnessId } from "../AgentHarness.js";
import type { BridgeConfig } from "../config.js";
import type { GatewayChatSendResult, GatewayEventHandler, HarnessCapabilities } from "../chat/ChatTransportTypes.js";
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
  openAiWebSearchModel: "gpt-5.5",
  configPath: "/tmp/android-agent-bridge/config.json",
  codexAppServerCommand: "codex app-server --listen stdio://",
  codexAgentCwd: "/tmp",
  codexAppServerApprovalPolicy: "never",
  codexAppServerSandbox: "workspace-write",
  codexConfigured: true
};

class FakeAdapter implements HarnessChatAdapter {
  readonly capabilities: HarnessCapabilities;
  readonly created: Array<{ key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }> = [];
  readonly sent: Array<{ sessionKey: string; message: string; attachments?: ChatAttachment[] }> = [];
  readonly steered: Array<{ sessionKey: string; message: string; attachments?: ChatAttachment[] }> = [];
  readonly sessions: ChatSessionSummary[] = [];
  readonly models: ChatModelOption[] = [];
  readonly commands: ChatCommandOption[] = [];

  constructor(readonly harnessId: HarnessId) {
    this.capabilities = { supportsAttachments: true };
  }

  addEventListener(_handler: GatewayEventHandler): () => void {
    return () => undefined;
  }

  async history(): Promise<{ messages: [] }> {
    return { messages: [] };
  }

  async sendChat(options: { sessionKey: string; message: string; attachments?: ChatAttachment[] }): Promise<GatewayChatSendResult> {
    this.sent.push(options);
    return { runId: `${this.harnessId}_run`, sessionKey: options.sessionKey };
  }

  async steerChat(options: { sessionKey: string; message: string; attachments?: ChatAttachment[] }): Promise<GatewayChatSendResult> {
    this.steered.push(options);
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

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }): Promise<HarnessCreatedSession> {
    this.created.push(options);
    return { key: options.key ?? `${this.harnessId}:created`, sessionId: `${this.harnessId}_session` };
  }

  async patchSession(): Promise<unknown> {
    return {};
  }

  async listCommands(): Promise<ChatCommandOption[]> {
    return this.commands;
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

test("harness router forwards workspace options only to Codex", async () => {
  const { router, openclaw, hermes, codex } = createRouter();
  const workspacePath = "/Users/am.will/Applications/cryptoclub";

  await router.createSession({ model: "gpt-5.5", workspacePath, createWorkspaceIfMissing: true });
  await router.createSession({ model: "hermes:gpt-5.5", workspacePath, createWorkspaceIfMissing: true });
  await router.createSession({ model: "codex:gpt-5.5", workspacePath, createWorkspaceIfMissing: true });

  assert.equal(openclaw.created[0]?.workspacePath, undefined);
  assert.equal(openclaw.created[0]?.createWorkspaceIfMissing, undefined);
  assert.equal(hermes.created[0]?.workspacePath, undefined);
  assert.equal(hermes.created[0]?.createWorkspaceIfMissing, undefined);
  assert.equal(codex.created[0]?.workspacePath, workspacePath);
  assert.equal(codex.created[0]?.createWorkspaceIfMissing, true);
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

test("harness router forwards attachments to host harnesses", async () => {
  const { router, openclaw, hermes, codex } = createRouter();
  const attachment: ChatAttachment = {
    id: "att_1",
    kind: "image",
    displayName: "photo.png",
    mimeType: "image/png",
    sizeBytes: 12,
    contentBase64: "aGVsbG8="
  };

  await router.sendChat({
    sessionKey: "agent:main:main",
    message: "Review this",
    attachments: [attachment]
  });

  assert.deepEqual(openclaw.sent[0]?.attachments, [attachment]);
  await router.sendChat({
    sessionKey: "hermes:chat",
    message: "Review this",
    attachments: [attachment]
  });
  await router.steerChat({
    sessionKey: "codex:chat",
    message: "Review this",
    attachments: [attachment]
  });
  assert.deepEqual(hermes.sent[0]?.attachments, [attachment]);
  assert.deepEqual(codex.steered[0]?.attachments, [attachment]);
});

test("harness router shares OpenClaw skills with Hermes and Codex command lists", async () => {
  const { router, openclaw, hermes, codex } = createRouter();
  openclaw.commands.push(
    { name: "status", description: "Show OpenClaw status", textAliases: ["/status"], acceptsArgs: false },
    { name: "commit-message", description: "Draft a commit message", source: "skill", acceptsArgs: true },
    { name: "code-review", description: "Review code", source: "skill", acceptsArgs: true }
  );
  hermes.commands.push(
    { name: "status", description: "Show Hermes status", textAliases: ["/status"], acceptsArgs: false }
  );
  codex.commands.push(
    { name: "help", description: "Show Codex help", textAliases: ["/help"], acceptsArgs: false },
    { name: "code-review", description: "Codex native review", source: "skill", acceptsArgs: true }
  );

  const hermesPayload = await router.listCommands("hermes:chat") as { commands: ChatCommandOption[] };
  const codexPayload = await router.listCommands("codex:chat") as { commands: ChatCommandOption[] };

  assert.deepEqual(
    hermesPayload.commands.filter((command) => command.source === "skill").map((command) => command.name),
    ["commit-message", "code-review"]
  );
  assert.deepEqual(
    codexPayload.commands.filter((command) => command.source === "skill").map((command) => command.name),
    ["code-review", "commit-message"]
  );
});

test("harness router adds OpenClaw bridge commands only to OpenClaw command lists", async () => {
  const { router, openclaw } = createRouter();
  openclaw.commands.push(
    { name: "status", description: "Show OpenClaw status", textAliases: ["/status"], acceptsArgs: false }
  );

  const openclawPayload = await router.listCommands("agent:main:main") as { commands: ChatCommandOption[] };
  const hermesPayload = await router.listCommands("hermes:chat") as { commands: ChatCommandOption[] };

  assert.deepEqual(
    openclawPayload.commands.map((command) => command.name),
    ["status", "verbose", "reasoning"]
  );
  assert.equal(hermesPayload.commands.some((command) => command.name === "verbose"), false);
  assert.equal(hermesPayload.commands.some((command) => command.name === "reasoning"), false);
});
