import assert from "node:assert/strict";
import test from "node:test";
import { OpenClawChatBridge } from "./OpenClawChatBridge.js";
import type { BridgeConfig } from "./config.js";
import type { PhoneHub } from "./PhoneHub.js";
import type { ChatOutboundMessage } from "../protocol/messages.js";
import type { GatewayEvent, GatewayEventHandler } from "./OpenClawGatewayChatClient.js";

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
  hermesModel: "hermes-agent",
  hermesDefaultSessionId: "hermes-agent",
  hermesRunTimeoutMs: 600_000,
  openAiRealtimeModel: "gpt-realtime-2",
  openAiRealtimeVoice: "marin",
  openAiWebSearchModel: "gpt-5.5"
};

class FakeGatewayClient {
  readonly handlers = new Set<GatewayEventHandler>();
  readonly sent: Array<{ sessionKey: string; message: string; thinking?: string; idempotencyKey?: string }> = [];
  readonly steered: Array<{ sessionKey: string; runId?: string; message: string; thinking?: string; idempotencyKey?: string }> = [];
  readonly created: Array<{ key?: string; label?: string; model?: string }> = [];
  readonly patched: Array<{ sessionKey: string; patch: Record<string, unknown> }> = [];
  readonly aborted: Array<{ sessionKey: string; runId?: string }> = [];
  sessions: Array<Record<string, unknown>> = [];
  models: Array<Record<string, unknown>> = [];
  commands: Array<Record<string, unknown>> = [];
  readonly duplicateLabels = new Set<string>();
  sendError?: Error;
  private runCount = 0;

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    return { sessionId: `${sessionKey}:id`, messages: [] };
  }

  async sendChat(options: { sessionKey: string; message: string; thinking?: string; idempotencyKey?: string }): Promise<{ runId: string; sessionKey: string }> {
    if (this.sendError) {
      throw this.sendError;
    }
    this.runCount += 1;
    this.sent.push(options);
    return { runId: `run_${this.runCount}`, sessionKey: options.sessionKey };
  }

  async steerChat(options: { sessionKey: string; runId?: string; message: string; thinking?: string; idempotencyKey?: string }): Promise<{ runId: string; sessionKey: string }> {
    this.steered.push(options);
    return { runId: options.runId ?? `run_${this.runCount}`, sessionKey: options.sessionKey };
  }

  async abort(sessionKey: string, runId?: string): Promise<unknown> {
    this.aborted.push({ sessionKey, runId });
    return { ok: true };
  }

  async listModels(): Promise<unknown> {
    return { models: this.models };
  }

  async listSessions(): Promise<unknown> {
    return { sessions: this.sessions };
  }

  async createSession(options: { key?: string; label?: string; model?: string }): Promise<unknown> {
    this.created.push(options);
    if (options.label && this.duplicateLabels.has(options.label)) {
      throw new Error(`Session label "${options.label}" is already used`);
    }
    return { key: `agent:main:explicit:${options.key ?? "created"}`, sessionId: `session_${this.created.length}` };
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    this.patched.push({ sessionKey, patch });
    return { ok: true };
  }

  async listCommands(): Promise<unknown> {
    return { commands: this.commands };
  }

  async effectiveTools(): Promise<unknown> {
    return { groups: [] };
  }

  async health(): Promise<unknown> {
    return { ok: true, eventLoop: { degraded: false } };
  }

  close(): void {}

  emit(event: GatewayEvent): void {
    for (const handler of this.handlers) {
      handler(event);
    }
  }
}

function createHarness() {
  const chatMessages: ChatOutboundMessage[] = [];
  const fallbackCalls: unknown[] = [];
  const hub = {
    sendChat(_deviceId: string, message: ChatOutboundMessage) {
      chatMessages.push(message);
    }
  } as unknown as PhoneHub;
  const dispatcher = {
    async handleUserRequest(...args: unknown[]) {
      fallbackCalls.push(args);
      return { finalMessage: "fallback" };
    },
    async stopActiveTurn() {}
  };
  const client = new FakeGatewayClient();
  const bridge = new OpenClawChatBridge(config, hub, dispatcher, undefined, client);
  return { bridge, chatMessages, client, fallbackCalls };
}

function defaultSessionKey(deviceId: string): string {
  return `agent:main:explicit:open-claw-agent-${deviceId}`;
}

async function waitFor(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
  assert.ok(predicate());
}

test("realtime requests start a fresh chat only outside the reuse window", async () => {
  const originalNow = Date.now;
  let now = 1_000;
  Date.now = () => now;
  try {
    const { bridge, chatMessages, client } = createHarness();

    const first = bridge.handleRealtimeRequest({
      type: "user_request",
      deviceId: "pixel",
      inputType: "text",
      text: "Summarize my project"
    }, { taskKind: "general", callId: "call_1" });
    await waitFor(() => client.sent.length === 1);
    client.emit({ event: "chat", payload: { sessionKey: client.sent[0]?.sessionKey, runId: "run_1", state: "final", message: "Done one" } });
    assert.deepEqual(await first, { finalMessage: "Done one" });

    now += 14 * 60 * 1000;
    const second = bridge.handleRealtimeRequest({
      type: "user_request",
      deviceId: "pixel",
      inputType: "text",
      text: "Add one more detail"
    }, { taskKind: "general", callId: "call_2" });
    await waitFor(() => client.sent.length === 2);
    client.emit({ event: "chat", payload: { sessionKey: client.sent[1]?.sessionKey, runId: "run_2", state: "final", message: "Done two" } });
    assert.deepEqual(await second, { finalMessage: "Done two" });

    now += 16 * 60 * 1000;
    const third = bridge.handleRealtimeRequest({
      type: "user_request",
      deviceId: "pixel",
      inputType: "text",
      text: "Start fresh"
    }, { taskKind: "general", callId: "call_3" });
    await waitFor(() => client.sent.length === 3);
    client.emit({ event: "chat", payload: { sessionKey: client.sent[2]?.sessionKey, runId: "run_3", state: "final", message: "Done three" } });
    assert.deepEqual(await third, { finalMessage: "Done three" });

    assert.equal(client.created.length, 2);
    assert.equal(client.created[0]?.label, "Summarize my project");
    assert.equal(client.created[1]?.label, "Start fresh");
    assert.deepEqual(
      chatMessages.filter((message) => message.type === "chat.message").map((message) => message.message.text),
      ["Summarize my project", "Add one more detail", "Start fresh"]
    );
  } finally {
    Date.now = originalNow;
  }
});

test("realtime session labels retry with numbered suffixes on duplicates", async () => {
  const { bridge, client } = createHarness();
  client.duplicateLabels.add("Summarize my project");

  const request = bridge.handleRealtimeRequest({
    type: "user_request",
    deviceId: "pixel",
    inputType: "text",
    text: "Summarize my project"
  }, { taskKind: "general", callId: "call_1" });
  await waitFor(() => client.sent.length === 1);
  client.emit({
    event: "chat",
    payload: {
      sessionKey: client.sent[0]?.sessionKey,
      runId: "run_1",
      state: "final",
      message: "Done"
    }
  });

  assert.deepEqual(await request, { finalMessage: "Done" });
  assert.deepEqual(client.created.map((entry) => entry.label), ["Summarize my project", "Summarize my project 2"]);
});

test("new chats use uuid labels until first message display name is set", async () => {
  const { bridge, client } = createHarness();

  await bridge.newSession({
    type: "chat.new_session",
    deviceId: "pixel"
  });

  assert.equal(client.created.length, 1);
  assert.match(client.created[0]?.label ?? "", /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  assert.notEqual(client.created[0]?.label, "Open Claw Agent");

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Summarize my project and next steps"
  });

  assert.deepEqual(client.patched.map((entry) => entry.patch), [
    { displayName: "Summarize my project and next steps" }
  ]);
});

test("explicit phone chat uses gateway session so session fast mode applies", async () => {
  const { bridge, client, fallbackCalls } = createHarness();

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Open the Settings app on my phone",
    model: "gpt-5.4",
    reasoningEffort: "low"
  });

  assert.equal(fallbackCalls.length, 0);
  assert.equal(client.sent.length, 1);
  assert.deepEqual(client.patched.map((entry) => entry.patch), [{ model: "gpt-5.4" }]);
  assert.match(client.sent[0]?.message ?? "", /Phone-control turn hint/);
  assert.match(client.sent[0]?.message ?? "", /User request:\nOpen the Settings app on my phone/);
  assert.equal(client.sent[0]?.thinking, "low");
});

test("gateway fallback preserves explicit phone task kind", async () => {
  const { bridge, client, fallbackCalls } = createHarness();
  client.sendError = new Error("gateway unavailable");

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Open the Settings app on my phone"
  });

  assert.equal(fallbackCalls.length, 1);
  assert.deepEqual((fallbackCalls[0] as unknown[])[1], { taskKind: "phone" });
});

test("non-OpenClaw send failure emits chat error without gateway fallback", async () => {
  const { bridge, chatMessages, client, fallbackCalls } = createHarness();
  client.sendError = new Error("hermes unavailable");

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Use Hermes",
    model: "hermes:gpt-5.5"
  });

  assert.equal(fallbackCalls.length, 0);
  assert.equal(client.sent.length, 0);
  const error = chatMessages.find((message) => message.type === "chat.error");
  assert.equal(error?.message, "hermes unavailable");
});

test("default gateway chat sessions are scoped per device", async () => {
  const { bridge, client } = createHarness();

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Summarize my project"
  });
  await bridge.send({
    type: "chat.send",
    deviceId: "fold",
    text: "Summarize my project"
  });

  assert.deepEqual(client.sent.map((entry) => entry.sessionKey), [
    defaultSessionKey("pixel"),
    defaultSessionKey("fold")
  ]);
});

test("queued chat sends wait for the active run to finish", async () => {
  const { bridge, chatMessages, client } = createHarness();

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "First prompt"
  });
  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Next prompt",
    delivery: "queue"
  });

  assert.equal(client.sent.length, 1);
  assert.equal(chatMessages.filter((message) => message.type === "chat.state").at(-1)?.status, "OpenClaw queued message for next turn");

  client.emit({ event: "chat", payload: { sessionKey: client.sent[0]?.sessionKey, runId: "run_1", state: "final", message: "Done first" } });
  await waitFor(() => client.sent.length === 2);

  assert.equal(client.sent[1]?.message, "Next prompt");
});

test("steered chat sends target the active harness run", async () => {
  const { bridge, client } = createHarness();

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "First prompt"
  });
  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Narrow the scope",
    delivery: "steer"
  });

  assert.equal(client.sent.length, 1);
  assert.deepEqual(client.steered.map((entry) => ({
    sessionKey: entry.sessionKey,
    runId: entry.runId,
    message: entry.message
  })), [{
    sessionKey: defaultSessionKey("pixel"),
    runId: "run_1",
    message: "Narrow the scope"
  }]);
});

test("queue and steer slash commands override active delivery mode", async () => {
  const { bridge, client } = createHarness();

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "First prompt"
  });
  await bridge.controlCommand({
    type: "chat.control_command",
    deviceId: "pixel",
    command: "/queue \"Follow up later\"",
    args: {}
  });
  await bridge.controlCommand({
    type: "chat.control_command",
    deviceId: "pixel",
    command: "/steer Refine the active run",
    args: {}
  });

  assert.equal(client.sent.length, 1);
  assert.equal(client.steered[0]?.message, "Refine the active run");

  client.emit({ event: "chat", payload: { sessionKey: client.sent[0]?.sessionKey, runId: "run_1", state: "final", message: "Done first" } });
  await waitFor(() => client.sent.length === 2);
  assert.equal(client.sent[1]?.message, "Follow up later");
});

test("realtime phone requests include fast phone loop guidance in gateway run", async () => {
  const { bridge, client } = createHarness();

  const request = bridge.handleRealtimeRequest({
    type: "user_request",
    deviceId: "pixel",
    inputType: "text",
    text: "Open Gemini"
  }, { taskKind: "phone", callId: "call_1" });
  await waitFor(() => client.sent.length === 1);
  client.emit({ event: "chat", payload: { sessionKey: client.sent[0]?.sessionKey, runId: "run_1", state: "final", message: "Done" } });

  assert.match(client.sent[0]?.message ?? "", /Avoid redundant phone_observe/);
  assert.match(client.sent[0]?.message ?? "", /User request:\nOpen Gemini/);
  assert.deepEqual(await request, { finalMessage: "Done" });
});

test("completed background session runs emit reply notifications without switching timeline", async () => {
  const { bridge, chatMessages, client } = createHarness();

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Work on the current session"
  });
  const original = client.sent[0]!;
  await bridge.selectSession({
    type: "chat.select_session",
    deviceId: "pixel",
    sessionKey: "agent:main:explicit:other"
  });

  const beforeFinal = chatMessages.length;
  client.emit({
    event: "chat",
    payload: {
      sessionKey: original.sessionKey,
      runId: "run_1",
      state: "final",
      message: "Background answer"
    }
  });

  const emitted = chatMessages.slice(beforeFinal);
  assert.equal(emitted.some((message) => message.type === "chat.final"), false);
  const reply = emitted.find((message) => message.type === "chat.reply_available");
  assert.ok(reply);
  assert.equal(reply.sessionKey, original.sessionKey);
  assert.equal(reply.runId, "run_1");
  assert.equal(reply.status, "completed");
  assert.equal(reply.textPreview, "Background answer");
});

test("reasoning and model changes do not append chat messages", async () => {
  const { bridge, chatMessages, client } = createHarness();

  await bridge.setReasoning({
    type: "chat.set_reasoning",
    deviceId: "pixel",
    reasoningEffort: "high"
  });
  await bridge.setReasoning({
    type: "chat.set_reasoning",
    deviceId: "pixel",
    reasoningEffort: "off"
  });
  await bridge.setModel({
    type: "chat.set_model",
    deviceId: "pixel",
    model: "gpt-5.4"
  });
  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Use the selected model"
  });

  assert.deepEqual(client.sent.map((entry) => entry.message), ["/think high", "/think high", "/model gpt-5.4", "Use the selected model"]);
  assert.deepEqual(client.patched.map((entry) => entry.patch), []);
  assert.equal(chatMessages.filter((message) => message.type === "chat.message").length, 0);
});

test("model metadata refresh does not clobber selected model override", async () => {
  const { bridge, chatMessages, client } = createHarness();
  client.sessions = [{
    key: defaultSessionKey("pixel"),
    sessionId: "session_1",
    model: "gpt-5.5"
  }];

  await bridge.open({
    type: "chat.open",
    deviceId: "pixel"
  });
  await bridge.setModel({
    type: "chat.set_model",
    deviceId: "pixel",
    model: "gpt-5.4"
  });
  await bridge.controlCommand({
    type: "chat.control_command",
    deviceId: "pixel",
    command: "status",
    args: {}
  });

  const latestState = chatMessages.filter((message) => message.type === "chat.state").at(-1);
  assert.equal(latestState?.model, "gpt-5.4");
});

test("model metadata does not override the selected session model by list order", async () => {
  const { bridge, chatMessages, client } = createHarness();
  client.models = [{
    key: "openai-codex/gpt-5.4",
    name: "gpt-5.4",
    available: false
  }, {
    key: "openai-codex/gpt-5.5",
    name: "gpt-5.5",
    available: true
  }];
  client.sessions = [{
    key: defaultSessionKey("pixel"),
    sessionId: "session_1",
    model: "gpt-5.4"
  }];

  await bridge.open({
    type: "chat.open",
    deviceId: "pixel"
  });

  const latestState = chatMessages.filter((message) => message.type === "chat.state").at(-1);
  assert.equal(latestState?.model, "gpt-5.4");
});

test("usage metadata uses matching model context window", async () => {
  const { bridge, chatMessages, client } = createHarness();
  const sessionKey = "hermes:hermes-agent-pixel";
  client.models = [{
    id: "hermes:local-minimax:MiniMax-M2.7",
    modelId: "local-minimax:MiniMax-M2.7",
    harnessId: "hermes",
    label: "Local MiniMax",
    contextWindow: 149_429,
    available: true
  }];
  client.sessions = [{
    key: sessionKey,
    sessionId: "hermes-agent-pixel",
    harnessId: "hermes",
    model: "hermes:MiniMax-M2.7",
    totalTokens: 12_000
  }];

  await bridge.setModel({
    type: "chat.set_model",
    deviceId: "pixel",
    model: "hermes:local-minimax:MiniMax-M2.7"
  });

  const usage = chatMessages.filter((message) => message.type === "chat.usage").at(-1);
  const sessions = chatMessages.filter((message) => message.type === "chat.sessions").at(-1);
  assert.equal(usage?.usage.contextTokens, 149_429);
  assert.equal(usage?.usage.totalTokens, 12_000);
  assert.equal(sessions?.sessions[0]?.contextTokens, 149_429);
});

test("static Android fallback model does not override connected model id", async () => {
  const { bridge, client } = createHarness();
  client.sessions = [{
    key: defaultSessionKey("pixel"),
    sessionId: "session_1",
    model: "openai-codex/gpt-5.5"
  }];

  await bridge.open({
    type: "chat.open",
    deviceId: "pixel"
  });
  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Use default model",
    model: "gpt-5.5"
  });

  assert.deepEqual(client.patched.map((entry) => entry.patch), []);
});

test("help slash command emits a visible command list without starting a run", async () => {
  const { bridge, chatMessages, client } = createHarness();
  client.commands = [{
    name: "help",
    description: "Show available slash commands",
    textAliases: ["/help", "/commands"],
    acceptsArgs: false
  }, {
    name: "fast",
    description: "Toggle fast mode",
    textAliases: ["/fast"],
    acceptsArgs: true
  }];

  await bridge.controlCommand({
    type: "chat.control_command",
    deviceId: "pixel",
    command: "/help",
    args: {}
  });

  assert.equal(client.sent.length, 0);
  const message = chatMessages.find((entry) => entry.type === "chat.message");
  assert.equal(message?.message.role, "system");
  assert.match(message?.message.text ?? "", /Help/);
  assert.match(message?.message.text ?? "", /\/commands/);
  assert.match(message?.message.text ?? "", /\/fast/);
});

test("realtime steer and stop are visible user messages on the active chat", async () => {
  const { bridge, chatMessages, client } = createHarness();
  const request = bridge.handleRealtimeRequest({
    type: "user_request",
    deviceId: "pixel",
    inputType: "text",
    text: "Open settings"
  }, { taskKind: "phone", callId: "call_1" });
  await waitFor(() => client.sent.length === 1);

  await bridge.steerRealtimeTurn("pixel", "Actually open Bluetooth settings", { taskKind: "phone", callId: "call_steer" });
  await bridge.stopRealtimeTurn("pixel", "Stop the realtime task");
  await assert.rejects(request, /Stop the realtime task/);

  assert.equal(client.aborted.length, 1);
  assert.deepEqual(
    chatMessages.filter((message) => message.type === "chat.message").map((message) => message.message.text),
    ["Open settings", "Actually open Bluetooth settings", "Stop the realtime task"]
  );
});
