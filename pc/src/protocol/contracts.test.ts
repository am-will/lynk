import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";

import {
  AGENT_MODEL_IDS,
  MCP_PHONE_TOOL_NAME_BY_COMMAND,
  PHONE_COMMANDS,
  REASONING_EFFORTS,
  REALTIME_TOOL_NAMES,
  phoneOutboundMessageSchema,
  validatePhoneOutboundMessage
} from "./messages.js";

const repoRoot = resolve(import.meta.dirname, "../../..");

function readRepoFile(path: string): string {
  return readFileSync(resolve(repoRoot, path), "utf8");
}

test("Android command executor handles every protocol phone command", () => {
  const source = readRepoFile("android/app/src/main/java/dev/androidagent/accessibility/AccessibilityCommandExecutor.kt");
  const start = source.indexOf("return when (command) {");
  const end = source.indexOf("            else ->", start);
  assert.ok(start >= 0 && end > start, "Could not find command dispatch block");
  const commandBlock = source.slice(start, end);
  const androidCommands = Array.from(commandBlock.matchAll(/^\s*"([^"]+)"\s*->/gm), (match) => match[1]).sort();
  assert.deepEqual(androidCommands, [...PHONE_COMMANDS].sort());
});

test("Android model and reasoning options match protocol enums", () => {
  const source = readRepoFile("android/app/src/main/java/dev/androidagent/AgentModelOptions.kt");
  const androidModels = Array.from(source.matchAll(/ModelOption\("([^"]+)"/g), (match) => match[1]);
  const androidReasoning = Array.from(source.matchAll(/ReasoningOption\("([^"]+)"/g), (match) => match[1]);
  assert.deepEqual(androidModels, [...AGENT_MODEL_IDS]);
  assert.deepEqual(androidReasoning, [...REASONING_EFFORTS]);
});

test("Android local phone tool registry matches shared MCP command names", () => {
  const source = readRepoFile("android/app/src/main/java/dev/androidagent/localmodel/LocalToolSpecs.kt");
  const localTools = Array.from(
    source.matchAll(/LocalToolSpec\("([^"]+)",\s*"[^"]*",\s*"phone",\s*"([^"]+)"\)/g),
    (match) => [match[1], match[2]]
  ).sort();
  const expectedTools = Object.entries(MCP_PHONE_TOOL_NAME_BY_COMMAND)
    .map(([command, toolName]) => [toolName, command])
    .sort();

  assert.deepEqual(localTools, expectedTools);
});

test("shared realtime tool names cover delegated phone and OpenClaw controls", () => {
  assert.deepEqual(Object.values(REALTIME_TOOL_NAMES).sort(), [
    "delegate_openclaw_task",
    "hang_up_realtime",
    "run_phone_task",
    "steer_openclaw_task",
    "steer_phone_task",
    "stop_openclaw_task",
    "stop_phone_task",
    "web_search"
  ]);
});

test("PC outbound phone messages have validating schemas for dev and tests", () => {
  const originalNodeEnv = process.env.NODE_ENV;
  const originalValidateOutbound = process.env.PHONE_AGENT_VALIDATE_OUTBOUND;
  process.env.NODE_ENV = "test";
  delete process.env.PHONE_AGENT_VALIDATE_OUTBOUND;
  const outboundMessages = [
    { id: "cmd_1", type: "command", command: "observe_screen", args: {} },
    { type: "agent_status", deviceId: "pixel", status: "info", text: "Registered pixel" },
    { type: "realtime.sdp", deviceId: "pixel", sdp: "answer" },
    { type: "realtime.transcript_delta", deviceId: "pixel", role: "assistant", delta: "hi", isFinal: false },
    { type: "realtime.item_added", deviceId: "pixel", item: { id: "item_1" } },
    { type: "realtime.speech_started", deviceId: "pixel", role: "user", itemId: null },
    { type: "realtime.error", deviceId: "pixel", message: "bad" },
    { type: "realtime.closed", deviceId: "pixel", reason: null },
    { type: "realtime.tool_result", deviceId: "pixel", callId: "call_1", ok: false, status: "failed", error: "bad" },
    { type: "realtime.task_status", deviceId: "pixel", running: false, queued: 0, currentTask: null, completed: 0, failed: 1 },
    { type: "chat.state", deviceId: "pixel", sessionKey: "session", isRunning: false },
    { type: "chat.history", deviceId: "pixel", sessionKey: "session", messages: [] },
    { type: "chat.message", deviceId: "pixel", sessionKey: "session", message: { role: "user", text: "hello" } },
    { type: "chat.delta", deviceId: "pixel", sessionKey: "session", runId: "run", delta: "hi" },
    { type: "chat.reasoning_delta", deviceId: "pixel", sessionKey: "session", runId: "run", delta: "thinking" },
    { type: "chat.reasoning_clear", deviceId: "pixel", sessionKey: "session", runId: null },
    { type: "chat.final", deviceId: "pixel", sessionKey: "session", runId: "run", text: "done" },
    { type: "chat.error", deviceId: "pixel", sessionKey: "session", runId: "run", message: "failed" },
    { type: "chat.reply_available", deviceId: "pixel", sessionKey: "session", runId: "run", status: "completed", textPreview: "done" },
    { type: "chat.tool_event", deviceId: "pixel", sessionKey: "session", eventId: "tool_1", toolName: "exec", title: "Ran command", status: "completed" },
    { type: "chat.models", deviceId: "pixel", models: [], reasoningOptions: [] },
    { type: "chat.commands", deviceId: "pixel", commands: [] },
    { type: "chat.tools", deviceId: "pixel", sessionKey: "session", tools: [] },
    { type: "chat.sessions", deviceId: "pixel", sessions: [], selectedSessionKey: "session" },
    { type: "chat.usage", deviceId: "pixel", sessionKey: "session", usage: {} }
  ];

  try {
    for (const message of outboundMessages) {
      assert.equal(phoneOutboundMessageSchema.safeParse(message).success, true, `expected ${message.type} to parse`);
      validatePhoneOutboundMessage(message as never);
    }

    assert.throws(
      () => validatePhoneOutboundMessage({ type: "chat.state", deviceId: "pixel", sessionKey: "session", isRunning: "no" } as never),
      /Invalid outbound phone message/
    );
  } finally {
    if (originalNodeEnv === undefined) {
      delete process.env.NODE_ENV;
    } else {
      process.env.NODE_ENV = originalNodeEnv;
    }
    if (originalValidateOutbound === undefined) {
      delete process.env.PHONE_AGENT_VALIDATE_OUTBOUND;
    } else {
      process.env.PHONE_AGENT_VALIDATE_OUTBOUND = originalValidateOutbound;
    }
  }
});

test("outbound validation is disabled in production mode", () => {
  const originalNodeEnv = process.env.NODE_ENV;
  process.env.NODE_ENV = "production";
  try {
    assert.doesNotThrow(() => {
      validatePhoneOutboundMessage({ type: "chat.state", deviceId: "pixel", sessionKey: "session", isRunning: "no" } as never);
    });
  } finally {
    if (originalNodeEnv === undefined) {
      delete process.env.NODE_ENV;
    } else {
      process.env.NODE_ENV = originalNodeEnv;
    }
  }
});
