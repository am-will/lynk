import assert from "node:assert/strict";
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import type { HermesApiClient } from "../dispatcher/HermesApiClient.js";
import { HermesRunDriver } from "../dispatcher/HermesRunDriver.js";
import type { ChatAttachment } from "../protocol/messages.js";
import type { BridgeConfig } from "./config.js";
import { HermesChatClient } from "./HermesChatClient.js";
import type { GatewayEvent } from "./chat/ChatTransportTypes.js";

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
  codexConfigured: true,
  piAgentCwd: "/tmp",
  piRunTimeoutMs: 600_000,
  piConfigured: true
};

class FakeHermesApiClient {
  readonly createdRuns: Array<{ input: string; sessionId: string; instructions?: string; idempotencyKey?: string; attachments?: ChatAttachment[]; serviceTier?: "priority" | null }> = [];
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
  modelsPayload: unknown = { models: [] };
  skillsPayload: unknown = { data: [] };
  toolsetsPayload: unknown = { data: [] };
  healthPayload: unknown = { ok: true };

  async listSessions(): Promise<unknown> {
    return this.sessionsPayload;
  }

  async listSessionMessages(): Promise<unknown> {
    return this.messagesPayload;
  }

  async listModels(): Promise<unknown> {
    return this.modelsPayload;
  }

  async listSkills(): Promise<unknown> {
    return this.skillsPayload;
  }

  async listToolsets(): Promise<unknown> {
    return this.toolsetsPayload;
  }

  async capabilities(): Promise<unknown> {
    return {};
  }

  async health(): Promise<unknown> {
    return this.healthPayload;
  }

  async stopRun(): Promise<void> {}

  async createRun(options: { input: string; sessionId: string; instructions?: string; idempotencyKey?: string; attachments?: ChatAttachment[]; serviceTier?: "priority" | null }): Promise<{ runId: string; sessionId: string }> {
    this.createdRuns.push(options);
    return { runId: `steer_${this.createdRuns.length}`, sessionId: options.sessionId };
  }
}

async function withHermesHome<T>(files: Record<string, string>, run: () => Promise<T>): Promise<T> {
  const previousHome = process.env.HERMES_HOME;
  const home = mkdtempSync(join(tmpdir(), "lynk-hermes-client-"));
  try {
    for (const [name, content] of Object.entries(files)) {
      writeFileSync(join(home, name), content);
    }
    process.env.HERMES_HOME = home;
    return await run();
  } finally {
    if (previousHome === undefined) {
      delete process.env.HERMES_HOME;
    } else {
      process.env.HERMES_HOME = previousHome;
    }
    rmSync(home, { recursive: true, force: true });
  }
}

async function withFakeHermesCli(run: (command: string) => Promise<void>): Promise<void> {
  const home = mkdtempSync(join(tmpdir(), "lynk-hermes-cli-"));
  const command = join(home, "hermes");
  try {
    writeFileSync(command, "#!/bin/sh\nprintf 'cli fallback answer\\n'\n");
    chmodSync(command, 0o755);
    await run(command);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
}

async function waitFor(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  assert.ok(predicate());
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

test("Hermes model listing keeps live API models when local config exists", async () => {
  await withHermesHome({
    "config.yaml": [
      "model:",
      "  default: MiniMax-M2.7",
      "  provider: local-minimax",
      "  context_length: 149429"
    ].join("\n")
  }, async () => {
    const api = new FakeHermesApiClient();
    api.modelsPayload = {
      models: [
        { id: "grok-4.3", name: "Grok 4.3", provider: "xai", context_window: 256000 }
      ]
    };
    const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);

    const payload = await client.listModels() as { models: Array<Record<string, unknown>> };

    assert.equal(payload.models[0]?.id, "xai:grok-4.3");
    assert.equal(payload.models[0]?.modelId, "xai:grok-4.3");
    assert.equal(payload.models[0]?.provider, "xai");
    assert.equal(payload.models[0]?.contextWindow, 256000);
    assert.ok(payload.models.some((model) => model.id === "local-minimax:MiniMax-M2.7"));
  });
});

test("Hermes model listing enriches API models with local metadata", async () => {
  await withHermesHome({
    "config.yaml": [
      "model:",
      "  default: MiniMax-M2.7",
      "  provider: local-minimax",
      "providers:",
      "  local-minimax:",
      "    models:",
      "      MiniMax-M2.7:",
      "        context_length: 149429"
    ].join("\n")
  }, async () => {
    const api = new FakeHermesApiClient();
    api.modelsPayload = {
      models: [
        { id: "MiniMax-M2.7", provider: "local-minimax" }
      ]
    };
    const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);

    const payload = await client.listModels() as { models: Array<Record<string, unknown>> };

    assert.equal(payload.models[0]?.id, "local-minimax:MiniMax-M2.7");
    assert.equal(payload.models[0]?.contextWindow, 149429);
  });
});

test("Hermes model listing suppresses generic API proxy when provider models are discovered", async () => {
  await withHermesHome({
    "config.yaml": [
      "model:",
      "  default: gpt-5.5",
      "  provider: openai-codex"
    ].join("\n"),
    "auth.json": JSON.stringify({
      credential_pool: {
        "openai-codex": [{}]
      }
    })
  }, async () => {
    const api = new FakeHermesApiClient();
    api.modelsPayload = { data: [{ id: "gpt-5.5", owned_by: "hermes" }] };
    const client = new HermesChatClient({ ...config, hermesModel: "talos" }, api as unknown as HermesApiClient, null);

    const payload = await client.listModels() as { models: Array<Record<string, unknown>> };

    assert.equal(payload.models.some((model) => model.id === "gpt-5.5" && model.provider === "hermes"), false);
    assert.equal(payload.models[0]?.id, "openai-codex:gpt-5.5");
  });
});

test("Hermes lists native API skills as skill commands", async () => {
  const api = new FakeHermesApiClient();
  api.skillsPayload = {
    data: [{ name: "android-control", description: "Control Android", category: "phone" }]
  };
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);

  const payload = await client.listCommands() as { commands: Array<Record<string, unknown>> };
  const skill = payload.commands.find((command) => command.name === "android-control");

  assert.equal(skill?.source, "skill");
  assert.deepEqual(skill?.textAliases, ["/skill android-control"]);
  assert.equal(skill?.description, "Control Android");
  assert.ok(payload.commands.some((command) => command.name === "skill"));
});

test("Hermes lists native API toolsets as effective tools", async () => {
  const api = new FakeHermesApiClient();
  api.toolsetsPayload = {
    data: [{ name: "terminal", label: "Terminal", enabled: true, tools: ["terminal", "process"] }, { name: "browser", label: "Browser", enabled: false, tools: ["browser_click"] }]
  };
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);

  const payload = await client.effectiveTools() as { tools: Array<Record<string, unknown>> };

  assert.deepEqual(payload.tools.map((tool) => tool.id), ["terminal", "process"]);
  assert.equal(payload.tools[0]?.group, "Terminal");
  assert.equal(payload.tools[0]?.source, "terminal");
});

test("Hermes health normalizes native API status responses", async () => {
  const api = new FakeHermesApiClient();
  api.healthPayload = { status: "ok" };
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);

  const payload = await client.health() as Record<string, unknown>;

  assert.equal(payload.ok, true);
  assert.equal(payload.mode, "api");
  assert.equal(payload.status, "ok");
});

test("Hermes lists sessions with flat token count fields", async () => {
  const api = new FakeHermesApiClient();
  api.sessionsPayload = {
    sessions: [{
      session_id: "flat_tokens",
      model: "hermes-agent",
      preview: "Flat usage",
      input_tokens: 20,
      output_tokens: 5,
      total_tokens: 25
    }]
  };
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);

  const payload = await client.listSessions() as { sessions: Array<Record<string, unknown>> };
  const session = payload.sessions[0];

  assert.equal(session?.inputTokens, 20);
  assert.equal(session?.outputTokens, 5);
  assert.equal(session?.totalTokens, 25);
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

test("Hermes falls back to CLI when runs API is not configured", async () => {
  await withFakeHermesCli(async (command) => {
    const client = new HermesChatClient({
      ...config,
      hermesApiKey: undefined,
      hermesCliCommand: command,
      hermesConfigured: true,
      hermesModel: "local-minimax:MiniMax-M2.7"
    }, undefined, null);
    const events: GatewayEvent[] = [];
    client.addEventListener((event) => events.push(event));

    const health = await client.health() as Record<string, unknown>;
    assert.equal(health.ok, true);
    assert.equal(health.mode, "cli");

    const result = await client.sendChat({
      sessionKey: "hermes:chat",
      message: "Use fallback",
      idempotencyKey: "run_cli"
    });
    await waitFor(() => events.some((event) => event.event === "chat"));

    assert.deepEqual(result, { runId: "run_cli", sessionKey: "hermes:chat" });
    assert.deepEqual(events.at(-1), {
      event: "chat",
      payload: {
        sessionKey: "hermes:chat",
        runId: "run_cli",
        state: "final",
        message: "cli fallback answer"
      }
    });
  });
});

test("Hermes history keeps local messages missing from remote history", async () => {
  const api = new FakeHermesApiClient();
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);

  await client.sendChat({
    sessionKey: "hermes:20260521_211022_1f4f0b",
    message: "Unsynced prompt",
    idempotencyKey: "run_1"
  });
  const payload = await client.history("hermes:20260521_211022_1f4f0b") as { messages: Array<Record<string, unknown>> };

  assert.equal(payload.messages.at(-1)?.text, "Unsynced prompt");
});

test("Hermes forwards chat attachments to run creation", async () => {
  const api = new FakeHermesApiClient();
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);
  const attachment: ChatAttachment = {
    id: "att_1",
    kind: "image",
    displayName: "photo.png",
    mimeType: "image/png",
    sizeBytes: 5,
    contentBase64: "aGVsbG8="
  };

  await client.sendChat({
    sessionKey: "hermes:chat",
    message: "Review this",
    attachments: [attachment],
    idempotencyKey: "run_1"
  });

  assert.deepEqual(api.createdRuns[0]?.attachments, [attachment]);
});

test("Hermes includes recent local context in follow-up run instructions", async () => {
  const api = new FakeHermesApiClient();
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);

  await client.sendChat({
    sessionKey: "hermes:chat",
    message: "Remember the word otter",
    idempotencyKey: "run_1"
  });
  await client.sendChat({
    sessionKey: "hermes:chat",
    message: "What word did I ask you to remember?",
    idempotencyKey: "run_2"
  });

  assert.equal(api.createdRuns[1]?.input, "What word did I ask you to remember?");
  assert.match(api.createdRuns[1]?.instructions ?? "", /Remember the word otter/);
});

test("Hermes fast mode is stored on the session and forwarded to run creation", async () => {
  const api = new FakeHermesApiClient();
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);

  await client.patchSession("hermes:chat", { fastMode: true });
  const sessions = await client.listSessions() as { sessions: Array<Record<string, unknown>> };
  await client.sendChat({
    sessionKey: "hermes:chat",
    message: "Use fast mode",
    idempotencyKey: "run_1"
  });

  assert.equal(sessions.sessions.find((session) => session.key === "hermes:chat")?.fastMode, true);
  assert.equal(api.createdRuns[0]?.serviceTier, "priority");
});

test("Hermes session listing does not emit remote reply notifications", async () => {
  const api = new FakeHermesApiClient();
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);
  const events: GatewayEvent[] = [];
  client.addEventListener((event) => events.push(event));

  await client.listSessions();
  api.sessionsPayload = {
    sessions: [{
      session_id: "20260521_211022_1f4f0b",
      model: "hermes-agent",
      timestamp: "2026-05-22T03:11:22.000Z",
      preview: "Cron Hermes chat"
    }]
  };
  api.messagesPayload = {
    messages: [{
      message_id: "msg_3",
      role: "assistant",
      content: "Cron complete",
      timestamp: "2026-05-22T03:11:22.000Z"
    }]
  };
  await client.listSessions();

  assert.equal(events.length, 0);
});

test("Hermes emits chat events for externally updated sessions after baseline", async () => {
  const api = new FakeHermesApiClient();
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);
  const events: GatewayEvent[] = [];
  client.addEventListener((event) => events.push(event));

  await client.syncRemoteReplies();
  assert.equal(events.length, 0);

  api.sessionsPayload = {
    sessions: [{
      session_id: "20260521_211022_1f4f0b",
      model: "hermes-agent",
      timestamp: "2026-05-22T03:11:22.000Z",
      preview: "Cron Hermes chat"
    }]
  };
  api.messagesPayload = {
    messages: [{
      message_id: "msg_1",
      role: "user",
      content: "Run the cron",
      timestamp: "2026-05-22T03:11:00.000Z"
    }, {
      message_id: "msg_3",
      role: "assistant",
      content: "Cron complete",
      timestamp: "2026-05-22T03:11:22.000Z"
    }]
  };

  await client.syncRemoteReplies();
  await client.syncRemoteReplies();

  assert.equal(events.length, 1);
  assert.deepEqual(events[0], {
    event: "chat",
    payload: {
      sessionKey: "hermes:20260521_211022_1f4f0b",
      runId: "hermes-external:20260521_211022_1f4f0b:msg_3",
      state: "final",
      message: "Cron complete"
    }
  });
});

test("Hermes baselines sessions that appear after an empty initial list", async () => {
  const api = new FakeHermesApiClient();
  api.sessionsPayload = { sessions: [] };
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);
  const events: GatewayEvent[] = [];
  client.addEventListener((event) => events.push(event));

  await client.syncRemoteReplies();
  api.sessionsPayload = {
    sessions: [{
      session_id: "20260521_211022_1f4f0b",
      model: "hermes-agent",
      timestamp: "2026-05-22T03:10:22.000Z",
      preview: "Existing Hermes chat"
    }]
  };
  api.messagesPayload = {
    messages: [{
      message_id: "msg_2",
      role: "assistant",
      content: "Existing answer",
      timestamp: "2026-05-22T03:10:22.000Z"
    }]
  };

  await client.syncRemoteReplies();
  await client.syncRemoteReplies();
  assert.equal(events.length, 0);

  api.sessionsPayload = {
    sessions: [{
      session_id: "20260521_211022_1f4f0b",
      model: "hermes-agent",
      timestamp: "2026-05-22T03:11:22.000Z",
      preview: "New Hermes chat"
    }]
  };
  api.messagesPayload = {
    messages: [{
      message_id: "msg_3",
      role: "assistant",
      content: "New answer",
      timestamp: "2026-05-22T03:11:22.000Z"
    }]
  };

  await client.syncRemoteReplies();

  assert.equal(events.length, 1);
  assert.deepEqual(events[0], {
    event: "chat",
    payload: {
      sessionKey: "hermes:20260521_211022_1f4f0b",
      runId: "hermes-external:20260521_211022_1f4f0b:msg_3",
      state: "final",
      message: "New answer"
    }
  });
});

test("Hermes chat steering uses the active run driver", async () => {
  const api = new FakeHermesApiClient();
  const client = new HermesChatClient(config, api as unknown as HermesApiClient, null);
  (client as unknown as {
    activeRuns: Map<string, {
      sessionKey: string;
      active: { runId: string; sessionId: string; controller: AbortController };
      mode: "api";
      transport: {
        mode: "api";
        driver: HermesRunDriver;
        supportsSteering: boolean;
        health(): Promise<unknown>;
      };
    }>;
  }).activeRuns.set("run_1", {
    sessionKey: "hermes:chat",
    active: {
      runId: "run_1",
      sessionId: "session_1",
      controller: new AbortController()
    },
    mode: "api",
    transport: {
      mode: "api",
      driver: new HermesRunDriver(api as unknown as HermesApiClient, config.hermesRunTimeoutMs),
      supportsSteering: true,
      health: () => api.health()
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
