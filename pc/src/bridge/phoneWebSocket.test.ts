import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { WebSocket } from "ws";

import { bindPhoneSocket } from "./phoneWebSocket.js";
import { ChatClientError, CODEX_WORKSPACE_NOT_FOUND_CODE } from "./chat/ChatErrors.js";
import type { RegisterMessage } from "../protocol/messages.js";

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
  newSessionError?: Error;

  async open(message: unknown): Promise<void> {
    this.opens.push(message);
  }

  async send(): Promise<void> {}
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
  readonly errors: Array<{ deviceId: string; message: string }> = [];
  readonly starts: unknown[] = [];

  sendRealtimeError(deviceId: string, message: string): void {
    this.errors.push({ deviceId, message });
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

function bindFakes() {
  const socket = new FakeSocket();
  const hub = new FakeHub();
  const audit = new FakeAudit();
  const dispatcher = new FakeDispatcher();
  const chatBridge = new FakeChatBridge();
  const realtime = new FakeRealtime();
  const realtimeTaskManager = new FakeRealtimeTaskManager();
  const stops: Array<{ deviceId: string; reason: string }> = [];
  bindPhoneSocket(socket as unknown as WebSocket, {
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
  });
  return { socket, hub, audit, dispatcher, chatBridge, realtime, realtimeTaskManager, stops };
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

test("phone websocket rejects wrong tokens and pre-register messages", () => {
  const wrongToken = bindFakes();
  wrongToken.socket.receive({ type: "register", deviceId: "phone", token: "wrong", capabilities: [] });
  assert.deepEqual(wrongToken.socket.closed, { code: 4001, reason: "invalid token" });

  const preRegister = bindFakes();
  preRegister.socket.receive({ type: "user_request", inputType: "text", deviceId: "phone", text: "hello" });
  assert.deepEqual(preRegister.socket.closed, { code: 4002, reason: "register first" });
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

test("phone websocket reports realtime device mismatches and malformed realtime messages", () => {
  const { socket, realtime } = bindFakes();
  register(socket, "registered-phone");

  socket.receive({
    type: "realtime.tool_call",
    deviceId: "other-phone",
    callId: "call_1",
    name: "run_phone_task",
    arguments: { instruction: "Open Settings" }
  });
  socket.receive({ type: "realtime.start", deviceId: "registered-phone" });

  assert.equal(realtime.errors.length, 2);
  assert.match(realtime.errors[0].message, /does not match registered device/);
  assert.equal(realtime.errors[1].deviceId, "registered-phone");
  assert.match(realtime.errors[1].message, /sdp/);
});

test("phone websocket fails realtime work on disconnect", () => {
  const { socket, hub, realtimeTaskManager } = bindFakes();
  register(socket);

  socket.emit("close");

  assert.equal(hub.unregistered, true);
  assert.deepEqual(realtimeTaskManager.failed, [{ deviceId: "phone", reason: "Phone WebSocket disconnected" }]);
});
