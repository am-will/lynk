import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { createServer } from "node:http";
import { once } from "node:events";
import test from "node:test";
import { WebSocket } from "ws";

import { bindPhoneSocket, createPhoneWebSocketServer, type PhoneWebSocketDependencies } from "./phoneWebSocket.js";
import { ChatClientError, CODEX_WORKSPACE_NOT_FOUND_CODE } from "./chat/ChatErrors.js";
import type { RegisterMessage } from "../protocol/messages.js";
import type { PhoneWebSocketIngressOptions } from "./webSocketIngress.js";
import { createPhoneUpgradeHandler } from "./webSocketUpgrade.js";

const token = "test-token";

class FakeSocket extends EventEmitter {
  readonly sent: string[] = [];
  closed?: { code: number; reason: string };
  readyState: number = WebSocket.OPEN;

  send(data: string): void {
    this.sent.push(data);
  }

  close(code: number, reason: string): void {
    this.closed = { code, reason };
    this.readyState = WebSocket.CLOSED;
    this.emit("close");
  }

  receive(message: unknown): void {
    this.emit("message", Buffer.from(JSON.stringify(message)));
  }
}

class FakeHub {
  registered?: RegisterMessage;
  unregistered = false;
  results: unknown[] = [];
  statuses: unknown[] = [];
  chats: unknown[] = [];

  register(message: RegisterMessage): void {
    this.registered = message;
  }

  unregister(): void {
    this.unregistered = true;
  }

  handleResult(_deviceId: string, message: unknown): void {
    this.results.push(message);
  }

  sendStatus(_deviceId: string | undefined, message: unknown): void {
    this.statuses.push(message);
  }

  sendChat(_deviceId: string, message: unknown): void {
    this.chats.push(message);
  }
}

class FakeAudit {
  readonly events: unknown[] = [];

  record(type: string, deviceId: string | undefined, data: unknown): void {
    this.events.push({ type, deviceId, data });
  }
}

class FakeDispatcher {
  readonly requests: unknown[] = [];

  async handleUserRequest(message: unknown): Promise<{ finalMessage: string }> {
    this.requests.push(message);
    return { finalMessage: "done" };
  }
}

class FakeChatBridge {
  readonly opens: unknown[] = [];
  readonly sends: unknown[] = [];
  newSessionError?: Error;

  async open(message: unknown): Promise<void> {
    this.opens.push(message);
  }

  async send(message: unknown): Promise<void> {
    this.sends.push(message);
  }
  async stop(): Promise<void> {}
  async selectSession(): Promise<void> {}
  async newSession(): Promise<void> {
    if (this.newSessionError) {
      throw this.newSessionError;
    }
  }
  async setModel(): Promise<void> {}
  async setReasoning(): Promise<void> {}
  async controlCommand(): Promise<void> {}
}

class FakeRealtime {
  readonly errors: Array<{ deviceId: string; voiceSessionId: string; message: string }> = [];
  readonly starts: unknown[] = [];

  sendRealtimeError(deviceId: string, voiceSessionId: string, message: string): void {
    this.errors.push({ deviceId, voiceSessionId, message });
  }

  async startRealtimeSession(message: unknown): Promise<void> {
    this.starts.push(message);
  }

  async handleRealtimeStop(): Promise<void> {}
  async handleRealtimeHangUpToolCall(): Promise<void> {}
  disconnectDevice(): void {}
}

class FakeRealtimeTaskManager {
  readonly toolCalls: unknown[] = [];
  readonly failed: Array<{ deviceId: string; reason: string }> = [];

  async handleToolCall(message: unknown): Promise<void> {
    this.toolCalls.push(message);
  }

  failDevice(deviceId: string, reason: string): void {
    this.failed.push({ deviceId, reason });
  }
}

function buildFakes() {
  const socket = new FakeSocket();
  const hub = new FakeHub();
  const audit = new FakeAudit();
  const dispatcher = new FakeDispatcher();
  const chatBridge = new FakeChatBridge();
  const realtime = new FakeRealtime();
  const realtimeTaskManager = new FakeRealtimeTaskManager();
  const stops: Array<{ deviceId: string; reason: string }> = [];
  const dependencies: PhoneWebSocketDependencies = {
    config: { token },
    hub: hub as never,
    audit,
    dispatcher: dispatcher as never,
    chatBridge: chatBridge as never,
    realtime: realtime as never,
    realtimeTaskManager: realtimeTaskManager as never,
    stopAgentWork: async (deviceId, reason) => {
      stops.push({ deviceId, reason });
    }
  };
  return { socket, hub, audit, dispatcher, chatBridge, realtime, realtimeTaskManager, stops, dependencies };
}

function bindFakes(options: PhoneWebSocketIngressOptions = {}) {
  const fakes = buildFakes();
  bindPhoneSocket(fakes.socket as unknown as WebSocket, fakes.dependencies, options);
  return fakes;
}

async function flushPromises(): Promise<void> {
  await new Promise((resolve) => setImmediate(resolve));
}

function register(socket: FakeSocket, deviceId = "phone"): void {
  socket.receive({ type: "register", deviceId, token, capabilities: ["observe_screen"] });
}

test("phone websocket registers phones and preserves registered ack prefix", () => {
  const { socket, hub, audit } = bindFakes();
  register(socket);

  assert.equal(hub.registered?.deviceId, "phone");
  assert.deepEqual(audit.events.map((event) => (event as { type: string }).type), ["phone_registered"]);
  assert.match((hub.statuses[0] as { text: string }).text, /^Registered /);
});

test("iOS registration rejects phone-control realtime tool calls", async () => {
  const { socket, realtime, realtimeTaskManager } = bindFakes();
  socket.receive({
    type: "register",
    deviceId: "iphone",
    token,
    platform: "ios",
    capabilities: ["chat", "realtime_voice"]
  });
  socket.receive({
    type: "realtime.tool_call",
    deviceId: "iphone",
    voiceSessionId: "11111111-1111-4111-8111-111111111111",
    callId: "call-1",
    name: "run_phone_task",
    arguments: { instruction: "Open Settings" }
  });
  await flushPromises();

  assert.equal(realtimeTaskManager.toolCalls.length, 0);
  assert.match(realtime.errors[0]?.message ?? "", /unavailable on iOS/);
});

test("phone websocket rejects wrong tokens and pre-register messages", () => {
  const wrongToken = bindFakes();
  wrongToken.socket.receive({ type: "register", deviceId: "phone", token: "wrong", capabilities: [] });
  assert.deepEqual(wrongToken.socket.closed, { code: 4001, reason: "invalid token" });

  const preRegister = bindFakes();
  preRegister.socket.receive({ type: "user_request", inputType: "text", deviceId: "phone", text: "hello" });
  assert.deepEqual(preRegister.socket.closed, { code: 4002, reason: "register first" });
});

test("phone websocket rejects re-registration and cross-device claims centrally", async () => {
  const repeated = bindFakes();
  register(repeated.socket, "phone-a");
  register(repeated.socket, "phone-b");
  assert.deepEqual(repeated.socket.closed, { code: 4004, reason: "already registered" });
  assert.equal(repeated.hub.registered?.deviceId, "phone-a");

  const spoofedChat = bindFakes();
  register(spoofedChat.socket, "phone-a");
  spoofedChat.socket.receive({ type: "chat.open", deviceId: "phone-b" });
  await flushPromises();
  assert.deepEqual(spoofedChat.socket.closed, { code: 4004, reason: "device identity mismatch" });
  assert.equal(spoofedChat.chatBridge.opens.length, 0);

  const spoofedLegacy = bindFakes();
  register(spoofedLegacy.socket, "phone-a");
  spoofedLegacy.socket.receive({ type: "user_request", inputType: "text", deviceId: "phone-b", text: "hello" });
  await flushPromises();
  assert.deepEqual(spoofedLegacy.socket.closed, { code: 4004, reason: "device identity mismatch" });
  assert.equal(spoofedLegacy.dispatcher.requests.length, 0);
});

test("phone websocket routes chat.open and agent stop controls", async () => {
  const { socket, chatBridge, stops } = bindFakes();
  register(socket);

  socket.receive({ type: "chat.open", deviceId: "phone", sessionKey: "chat" });
  socket.receive({ type: "agent_control", deviceId: "phone", action: "stop", reason: "enough" });
  await flushPromises();

  assert.deepEqual(chatBridge.opens, [{ type: "chat.open", deviceId: "phone", sessionKey: "chat" }]);
  assert.deepEqual(stops, [{ deviceId: "phone", reason: "enough" }]);
});

test("phone websocket preserves structured chat errors", async () => {
  const { socket, hub, chatBridge } = bindFakes();
  chatBridge.newSessionError = new ChatClientError("Codex workspace folder not found: ~/missing", {
    code: CODEX_WORKSPACE_NOT_FOUND_CODE,
    workspacePath: "~/missing"
  });
  register(socket);

  socket.receive({
    type: "chat.new_session",
    deviceId: "phone",
    model: "codex:gpt-5.3-codex",
    workspacePath: "~/missing"
  });
  await flushPromises();

  assert.deepEqual(hub.chats[0], {
    type: "chat.error",
    deviceId: "phone",
    message: "Codex workspace folder not found: ~/missing",
    code: CODEX_WORKSPACE_NOT_FOUND_CODE,
    workspacePath: "~/missing"
  });
});

test("phone websocket rejects realtime device spoofing and reports malformed realtime messages", () => {
  const { socket, realtime } = bindFakes();
  register(socket, "registered-phone");

  socket.receive({
    type: "realtime.tool_call",
    deviceId: "other-phone",
    voiceSessionId: "11111111-1111-4111-8111-111111111111",
    callId: "call_1",
    name: "run_phone_task",
    arguments: { instruction: "Open Settings" }
  });
  assert.deepEqual(socket.closed, { code: 4004, reason: "device identity mismatch" });
  assert.equal(realtime.errors.length, 0);

  const malformed = bindFakes();
  register(malformed.socket, "registered-phone");
  malformed.socket.receive({
    type: "realtime.start",
    deviceId: "registered-phone",
    voiceSessionId: "11111111-1111-4111-8111-111111111111"
  });
  assert.equal(malformed.realtime.errors[0].deviceId, "registered-phone");
  assert.match(malformed.realtime.errors[0].message, /sdp/);
});

test("phone websocket fails realtime work on disconnect", () => {
  const { socket, hub, realtimeTaskManager } = bindFakes();
  register(socket);

  socket.emit("close");

  assert.equal(hub.unregistered, true);
  assert.deepEqual(realtimeTaskManager.failed, [{ deviceId: "phone", reason: "Phone WebSocket disconnected" }]);
});

test("phone websocket closes clients that miss the registration deadline", (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const { socket } = bindFakes({ registrationTimeoutMs: 25 });

  t.mock.timers.tick(25);

  assert.deepEqual(socket.closed, { code: 4002, reason: "registration timeout" });
});

test("phone websocket enforces per-connection message rate budgets", () => {
  const { socket } = bindFakes({
    messageRateCapacity: 2,
    messageRateRefillMs: 1_000,
    now: () => 0
  });
  register(socket);
  socket.receive({ type: "invalid" });
  socket.receive({ type: "invalid" });

  assert.deepEqual(socket.closed, { code: 4008, reason: "message rate limit" });
});

test("phone websocket applies the control-frame limit to inline attachments", async () => {
  const bounded = bindFakes({ controlFrameMaxBytes: 128 });
  register(bounded.socket);
  bounded.socket.receive({
    type: "agent_control",
    deviceId: "phone",
    action: "stop",
    reason: "x".repeat(256)
  });
  assert.deepEqual(bounded.socket.closed, { code: 1009, reason: "control payload too large" });

  const attachment = bindFakes({ controlFrameMaxBytes: 128 });
  register(attachment.socket);
  attachment.socket.receive({
    type: "chat.send",
    deviceId: "phone",
    text: "inspect",
    attachments: [{
      id: "attachment-1",
      kind: "image",
      displayName: "image.png",
      mimeType: "image/png",
      sizeBytes: 192,
      contentBase64: Buffer.alloc(192).toString("base64")
    }]
  });
  await flushPromises();

  assert.deepEqual(attachment.socket.closed, { code: 1009, reason: "control payload too large" });
  assert.equal(attachment.chatBridge.sends.length, 0);
});

test("websocket server closes an oversized frame without crashing", async (t) => {
  const fakes = buildFakes();
  const wss = createPhoneWebSocketServer(fakes.dependencies, {
    maxPayloadBytes: 128,
    registrationMaxBytes: 64,
    controlFrameMaxBytes: 64,
    heartbeatIntervalMs: 60_000
  });
  const server = createServer();
  server.on("upgrade", createPhoneUpgradeHandler(wss));
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    wss.close();
    await new Promise<void>((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  });
  const address = server.address();
  assert(address && typeof address === "object");
  const client = new WebSocket(`ws://127.0.0.1:${address.port}/phone`);
  client.on("error", () => {});
  await once(client, "open");

  client.send("x".repeat(256));
  const [code] = await once(client, "close");

  assert.equal(code, 1009);
});
