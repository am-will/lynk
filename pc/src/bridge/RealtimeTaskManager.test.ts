import assert from "node:assert/strict";
import test from "node:test";
import { RealtimeTaskManager } from "./RealtimeTaskManager.js";
import type { AgentRunResult } from "../dispatcher/AgentClient.js";
import type { RealtimeOutboundMessage, RealtimeToolCallMessage } from "../protocol/messages.js";

class Deferred<T> {
  readonly promise: Promise<T>;
  resolve!: (value: T) => void;
  reject!: (error: Error) => void;

  constructor() {
    this.promise = new Promise<T>((resolve, reject) => {
      this.resolve = resolve;
      this.reject = reject;
    });
  }
}

class FakeDispatcher {
  readonly requests: Array<{ text: string; taskKind?: string }> = [];
  readonly stopReasons: string[] = [];
  readonly steers: string[] = [];
  results: Array<Promise<AgentRunResult>> = [];

  async handleUserRequest(request: { text: string }, options?: { taskKind?: string }): Promise<AgentRunResult> {
    this.requests.push({ text: request.text, taskKind: options?.taskKind });
    return await (this.results.shift() ?? Promise.resolve({ finalMessage: `done ${request.text}` }));
  }

  async stopActiveTurn(_deviceId: string, reason?: string): Promise<void> {
    this.stopReasons.push(reason ?? "");
  }

  async steerActiveTurn(_deviceId: string, guidance: string): Promise<void> {
    this.steers.push(guidance);
  }
}

class FakeTaskDelegate {
  readonly requests: Array<{ text: string; taskKind?: string; callId?: string; model?: string; reasoningEffort?: string }> = [];
  readonly stopReasons: string[] = [];
  readonly steers: Array<{ guidance: string; taskKind?: string; callId?: string; model?: string; reasoningEffort?: string }> = [];
  results: Array<Promise<AgentRunResult>> = [];

  async handleRealtimeRequest(request: { text: string }, options: { taskKind?: string; callId?: string; model?: string; reasoningEffort?: string }): Promise<AgentRunResult> {
    this.requests.push({
      text: request.text,
      taskKind: options.taskKind,
      callId: options.callId,
      ...(options.model ? { model: options.model } : {}),
      ...(options.reasoningEffort ? { reasoningEffort: options.reasoningEffort } : {})
    });
    return await (this.results.shift() ?? Promise.resolve({ finalMessage: `chat done ${request.text}` }));
  }

  async stopRealtimeTurn(_deviceId: string, reason?: string): Promise<void> {
    this.stopReasons.push(reason ?? "");
  }

  async steerRealtimeTurn(_deviceId: string, guidance: string, options: { taskKind?: string; callId?: string; model?: string; reasoningEffort?: string }): Promise<void> {
    this.steers.push({
      guidance,
      taskKind: options.taskKind,
      callId: options.callId,
      ...(options.model ? { model: options.model } : {}),
      ...(options.reasoningEffort ? { reasoningEffort: options.reasoningEffort } : {})
    });
  }
}

function toolCall(callId: string, instruction: string, extraArgs: Record<string, unknown> = {}): RealtimeToolCallMessage {
  return {
    type: "realtime.tool_call",
    deviceId: "pixel",
    callId,
    name: "run_phone_task",
    arguments: {
      instruction,
      ...extraArgs
    }
  };
}

function namedToolCall(callId: string, name: string, args: Record<string, unknown> = {}): RealtimeToolCallMessage {
  return {
    type: "realtime.tool_call",
    deviceId: "pixel",
    callId,
    name,
    arguments: args
  };
}

function createHarness(options: { taskTimeoutMs?: number; completedResultTtlMs?: number; now?: () => number } = {}) {
  const dispatcher = new FakeDispatcher();
  const messages: RealtimeOutboundMessage[] = [];
  const manager = new RealtimeTaskManager({
    dispatcher,
    sendRealtime: (_deviceId, message) => messages.push(message),
    taskTimeoutMs: options.taskTimeoutMs,
    completedResultTtlMs: options.completedResultTtlMs,
    now: options.now
  });
  return { dispatcher, manager, messages };
}

function createHarnessWithSearch(output = "search result") {
  const dispatcher = new FakeDispatcher();
  const messages: RealtimeOutboundMessage[] = [];
  const queries: string[] = [];
  const locations: unknown[] = [];
  const manager = new RealtimeTaskManager({
    dispatcher,
    sendRealtime: (_deviceId, message) => messages.push(message),
    webSearch: {
      async search({ query, location }) {
        queries.push(query);
        locations.push(location);
        return output;
      }
    },
    getRealtimeApiKey: () => "test-key",
    getRealtimeLocation: () => ({ latitude: 31.7619, longitude: -106.485, accuracyMeters: 100 })
  });
  return { dispatcher, manager, messages, queries, locations };
}

function createHarnessWithDelegate(options: { taskTimeoutMs?: number; model?: string; reasoningEffort?: string } = {}) {
  const delegate = new FakeTaskDelegate();
  const messages: RealtimeOutboundMessage[] = [];
  const manager = new RealtimeTaskManager({
    taskDelegate: delegate,
    sendRealtime: (_deviceId, message) => messages.push(message),
    getRealtimeRoutingContext: () => ({
      model: options.model,
      reasoningEffort: options.reasoningEffort
    }),
    taskTimeoutMs: options.taskTimeoutMs
  });
  return { delegate, manager, messages };
}

function results(messages: RealtimeOutboundMessage[]) {
  return messages.filter((message) => message.type === "realtime.tool_result");
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

test("rejects invalid run_phone_task arguments", async () => {
  const { manager, messages } = createHarness();

  await manager.handleToolCall(toolCall("call_1", ""));

  assert.equal(results(messages).at(-1)?.ok, false);
  assert.match(results(messages).at(-1)?.error ?? "", /non-empty instruction/);
});

test("queues one active task at a time", async () => {
  const first = new Deferred<AgentRunResult>();
  const second = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness();
  dispatcher.results = [first.promise, second.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Messages"));
  await manager.handleToolCall(toolCall("call_2", "Open Alice"));

  assert.deepEqual(dispatcher.requests.map((request) => request.text), ["Open Messages"]);
  assert.equal(messages.filter((message) => message.type === "realtime.task_status").at(-1)?.queued, 1);

  first.resolve({ finalMessage: "Messages opened" });
  await waitFor(() => dispatcher.requests.length === 2);
  second.resolve({ finalMessage: "Alice opened" });
  await waitFor(() => results(messages).length === 2);

  assert.deepEqual(dispatcher.requests.map((request) => request.text), ["Open Messages", "Open Alice"]);
  assert.deepEqual(dispatcher.requests.map((request) => request.taskKind), ["phone", "phone"]);
  assert.equal(results(messages)[0]?.status, "completed");
  assert.equal(results(messages)[1]?.status, "completed");
});

test("ignores duplicate call IDs while active", async () => {
  const first = new Deferred<AgentRunResult>();
  const { dispatcher, manager } = createHarness();
  dispatcher.results = [first.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(toolCall("call_1", "Open Settings"));

  assert.deepEqual(dispatcher.requests.map((request) => request.text), ["Open Settings"]);
  first.resolve({ finalMessage: "Settings opened" });
});

test("replays completed duplicate call IDs only within the result TTL", async () => {
  let now = 1_000;
  const { dispatcher, manager, messages } = createHarness({
    completedResultTtlMs: 10,
    now: () => now
  });

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await waitFor(() => results(messages).some((message) => message.callId === "call_1"));
  const completedCount = results(messages).length;

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  assert.equal(dispatcher.requests.length, 1);
  assert.equal(results(messages).length, completedCount + 1);

  now += 11;
  await manager.handleToolCall(toolCall("call_1", "Open Settings again"));
  await waitFor(() => dispatcher.requests.length === 2);

  assert.equal(results(messages).at(-1)?.status, "completed");
});

test("times out and stops a hung task", async () => {
  const never = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness({ taskTimeoutMs: 1 });
  dispatcher.results = [never.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await waitFor(() => results(messages).some((message) => message.status === "timeout"));

  assert.equal(dispatcher.stopReasons.length, 1);
  assert.equal(results(messages).at(-1)?.status, "timeout");
});

test("interrupt cancels the active task before starting the next one", async () => {
  const first = new Deferred<AgentRunResult>();
  const second = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness();
  dispatcher.results = [first.promise, second.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(toolCall("call_2", "Open Messages", { urgency: "interrupt" }));
  await waitFor(() => dispatcher.requests.length === 2);

  assert.equal(dispatcher.stopReasons.length, 1);
  assert.equal(results(messages).find((message) => message.callId === "call_1")?.status, "cancelled");
  assert.deepEqual(dispatcher.requests.map((request) => request.text), ["Open Settings", "Open Messages"]);

  second.resolve({ finalMessage: "Messages opened" });
  first.resolve({ finalMessage: "Settings opened late" });
});

test("stop_phone_task cancels active and queued realtime phone tasks", async () => {
  const first = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness();
  dispatcher.results = [first.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(toolCall("call_2", "Open Messages"));
  await manager.handleToolCall(namedToolCall("call_stop", "stop_phone_task", { reason: "User said stop" }));

  assert.deepEqual(dispatcher.stopReasons, ["User said stop"]);
  assert.equal(results(messages).find((message) => message.callId === "call_1")?.status, "cancelled");
  assert.equal(results(messages).find((message) => message.callId === "call_2")?.status, "cancelled");
  assert.equal(results(messages).find((message) => message.callId === "call_stop")?.status, "completed");
  assert.equal(results(messages).find((message) => message.callId === "call_stop")?.createResponse, false);

  first.resolve({ finalMessage: "Settings opened late" });
});

test("failDevice stops active realtime work and emits failed results", async () => {
  const first = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness();
  dispatcher.results = [first.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(toolCall("call_2", "Open Messages"));
  await manager.failDevice("pixel", "Phone WebSocket disconnected");

  assert.deepEqual(dispatcher.stopReasons, ["Phone WebSocket disconnected"]);
  assert.equal(results(messages).find((message) => message.callId === "call_1")?.status, "failed");
  assert.equal(results(messages).find((message) => message.callId === "call_2")?.status, "failed");
  assert.equal(messages.filter((message) => message.type === "realtime.task_status").at(-1)?.running, false);
  assert.equal(messages.filter((message) => message.type === "realtime.task_status").at(-1)?.queued, 0);

  const resultCount = results(messages).length;
  first.resolve({ finalMessage: "Settings opened late" });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(results(messages).length, resultCount);
});

test("steer_phone_task injects guidance into active turn", async () => {
  const first = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness();
  dispatcher.results = [first.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(namedToolCall("call_steer", "steer_phone_task", { guidance: "Actually open Bluetooth settings." }));

  assert.deepEqual(dispatcher.steers, ["Actually open Bluetooth settings."]);
  assert.equal(results(messages).find((message) => message.callId === "call_steer")?.status, "completed");
  assert.equal(results(messages).find((message) => message.callId === "call_steer")?.createResponse, false);

  first.resolve({ finalMessage: "Settings opened" });
});

test("steer_phone_task becomes a normal phone task when no turn is active", async () => {
  const { dispatcher, manager, messages } = createHarness();

  await manager.handleToolCall(namedToolCall("call_steer", "steer_phone_task", { guidance: "Open the first video on this screen." }));
  await waitFor(() => results(messages).some((message) => message.callId === "call_steer"));

  assert.deepEqual(dispatcher.requests.map((request) => request.text), ["Open the first video on this screen."]);
  assert.deepEqual(dispatcher.requests.map((request) => request.taskKind), ["phone"]);
  assert.deepEqual(dispatcher.steers, []);
  assert.equal(results(messages).find((message) => message.callId === "call_steer")?.status, "completed");
});

test("web_search returns search output without starting a phone task", async () => {
  const { dispatcher, manager, messages, queries, locations } = createHarnessWithSearch("It is sunny.");

  await manager.handleToolCall(namedToolCall("call_search", "web_search", { query: "El Paso weather today" }));

  assert.deepEqual(queries, ["El Paso weather today"]);
  assert.deepEqual(locations, [{ latitude: 31.7619, longitude: -106.485, accuracyMeters: 100 }]);
  assert.deepEqual(dispatcher.requests, []);
  assert.equal(results(messages).find((message) => message.callId === "call_search")?.output, "It is sunny.");
});

test("delegate_openclaw_task routes general realtime work to dispatcher", async () => {
  const { dispatcher, manager, messages } = createHarness();

  await manager.handleToolCall(namedToolCall("call_general", "delegate_openclaw_task", { instruction: "Summarize my inbox" }));
  await waitFor(() => results(messages).some((message) => message.callId === "call_general"));

  assert.deepEqual(dispatcher.requests, [{ text: "Summarize my inbox", taskKind: "general" }]);
  assert.equal(results(messages).find((message) => message.callId === "call_general")?.output, "done Summarize my inbox");
});

test("generic agent realtime tools route general harness work", async () => {
  const { delegate, manager, messages } = createHarnessWithDelegate();

  await manager.handleToolCall(namedToolCall("call_general", "delegate_agent_task", { instruction: "Summarize my inbox" }));
  await manager.handleToolCall(namedToolCall("call_steer", "steer_agent_task", { guidance: "Focus on unread messages." }));
  await manager.handleToolCall(namedToolCall("call_stop", "stop_agent_task", { reason: "User said stop." }));
  await waitFor(() => results(messages).length === 3);

  assert.deepEqual(delegate.requests[0], { text: "Summarize my inbox", taskKind: "general", callId: "call_general" });
  assert.equal(results(messages).find((message) => message.callId === "call_general")?.output, "chat done Summarize my inbox");
  assert.equal(results(messages).find((message) => message.callId === "call_steer")?.status, "completed");
  assert.equal(results(messages).find((message) => message.callId === "call_stop")?.status, "completed");
});

test("task delegate receives realtime routing context", async () => {
  const { delegate, manager, messages } = createHarnessWithDelegate({
    model: "hermes:gpt-5.5",
    reasoningEffort: "high"
  });

  await manager.handleToolCall(namedToolCall("call_general", "delegate_agent_task", { instruction: "Summarize my inbox" }));
  await manager.handleToolCall(namedToolCall("call_steer", "steer_agent_task", { guidance: "Focus on unread messages." }));
  await waitFor(() => results(messages).length === 2);

  assert.deepEqual(delegate.requests[0], {
    text: "Summarize my inbox",
    taskKind: "general",
    callId: "call_general",
    model: "hermes:gpt-5.5",
    reasoningEffort: "high"
  });
  assert.deepEqual(delegate.steers[0], {
    guidance: "Focus on unread messages.",
    taskKind: "general",
    callId: "call_steer",
    model: "hermes:gpt-5.5",
    reasoningEffort: "high"
  });
});

test("task delegate receives visible realtime OpenClaw and phone requests", async () => {
  const { delegate, manager, messages } = createHarnessWithDelegate();

  await manager.handleToolCall(namedToolCall("call_general", "delegate_openclaw_task", { instruction: "Summarize my inbox" }));
  await manager.handleToolCall(toolCall("call_phone", "Open Settings"));
  await waitFor(() => results(messages).length === 2);

  assert.deepEqual(delegate.requests, [
    { text: "Summarize my inbox", taskKind: "general", callId: "call_general" },
    { text: "Open Settings", taskKind: "phone", callId: "call_phone" }
  ]);
  assert.equal(results(messages).find((message) => message.callId === "call_general")?.output, "chat done Summarize my inbox");
  assert.equal(results(messages).find((message) => message.callId === "call_phone")?.output, "chat done Open Settings");
});

test("task delegate receives visible steer and stop requests", async () => {
  const first = new Deferred<AgentRunResult>();
  const { delegate, manager, messages } = createHarnessWithDelegate();
  delegate.results = [first.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(namedToolCall("call_steer", "steer_phone_task", { guidance: "Actually open Bluetooth settings." }));
  await manager.handleToolCall(namedToolCall("call_stop", "stop_phone_task", { reason: "User said stop" }));

  assert.deepEqual(delegate.steers, [{ guidance: "Actually open Bluetooth settings.", taskKind: "phone", callId: "call_steer" }]);
  assert.deepEqual(delegate.stopReasons, ["User said stop"]);
  assert.equal(results(messages).find((message) => message.callId === "call_steer")?.status, "completed");
  assert.equal(results(messages).find((message) => message.callId === "call_stop")?.status, "completed");

  first.resolve({ finalMessage: "Settings opened late" });
});
