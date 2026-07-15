import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";

const VOICE_SESSION_ID = "11111111-1111-4111-8111-111111111111";

import {
  AGENT_MODEL_IDS,
  CHAT_ATTACHMENT_MAX_BYTES,
  CHAT_ATTACHMENT_MAX_COUNT,
  CHAT_ATTACHMENT_MAX_MESSAGE_BYTES,
  CHAT_SEND_DELIVERIES,
  MCP_PHONE_TOOL_NAME_BY_COMMAND,
  PHONE_COMMANDS,
  PHONE_COMMAND_RISK,
  REASONING_EFFORTS,
  REALTIME_TOOL_NAMES,
  chatAttachmentSchema,
  chatAttachmentReferenceSchema,
  chatHistoryMessageSchema,
  chatSendMessageSchema,
  phoneOutboundMessageSchema,
  commandMessageSchema,
  observedNodeTargetSchema,
  resultMessageSchema,
  realtimeStartMessageSchema,
  realtimeStopMessageSchema,
  realtimeToolCallMessageSchema,
  registerMessageSchema,
  validatePhoneOutboundMessage
} from "./messages.js";

const repoRoot = resolve(import.meta.dirname, "../../..");

test("iOS registration excludes phone-control capabilities", () => {
  assert.equal(registerMessageSchema.safeParse({
    type: "register",
    deviceId: "iphone",
    token: "token",
    platform: "ios",
    capabilities: ["chat", "realtime_voice", "transcription", "attachments", "local_inference"]
  }).success, true);
  assert.equal(registerMessageSchema.safeParse({
    type: "register",
    deviceId: "iphone",
    token: "token",
    platform: "ios",
    capabilities: ["chat", "phone_control"]
  }).success, false);
  assert.equal(registerMessageSchema.safeParse({
    type: "register",
    deviceId: "legacy-android",
    token: "token",
    capabilities: ["accessibility_tree"]
  }).success, true);
});

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

test("Android and TypeScript command risk classifications stay aligned", () => {
  const source = readRepoFile("android/app/src/main/java/dev/androidagent/accessibility/PhoneCommandPolicy.kt");
  const androidRisks = Object.fromEntries(Array.from(
    source.matchAll(/"([^"]+)" to PhoneCommandRisk\.([A-Za-z]+)/g),
    (match) => [match[1], match[2].toLowerCase()]
  ));
  assert.deepEqual(androidRisks, PHONE_COMMAND_RISK);
  assert.deepEqual(Object.keys(PHONE_COMMAND_RISK).sort(), [...PHONE_COMMANDS].sort());
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
    source.matchAll(
      /LocalToolSpec\("([^"]+)",\s*"[^"]*",\s*"phone",\s*"([^"]+)"(?:,\s*requiresImageInput\s*=\s*(?:true|false))?\)/g
    ),
    (match) => [match[1], match[2]]
  ).sort();
  const expectedTools = Object.entries(MCP_PHONE_TOOL_NAME_BY_COMMAND)
    .map(([command, toolName]) => [toolName, command])
    .sort();

  assert.deepEqual(localTools, expectedTools);
});

test("shared realtime tool names cover delegated phone and OpenClaw controls", () => {
  assert.deepEqual(Object.values(REALTIME_TOOL_NAMES).sort(), [
    "delegate_agent_task",
    "delegate_openclaw_task",
    "hang_up_realtime",
    "run_phone_task",
    "steer_agent_task",
    "steer_openclaw_task",
    "steer_phone_task",
    "stop_agent_task",
    "stop_openclaw_task",
    "stop_phone_task",
    "web_search"
  ]);
});

test("chat send delivery accepts normal queue and steer modes", () => {
  for (const delivery of CHAT_SEND_DELIVERIES) {
    assert.equal(chatSendMessageSchema.safeParse({
      type: "chat.send",
      deviceId: "pixel",
      text: "Adjust the current turn",
      delivery
    }).success, true);
  }
  assert.equal(chatSendMessageSchema.safeParse({
    type: "chat.send",
    deviceId: "pixel",
    text: "Adjust the current turn",
    delivery: "later"
  }).success, false);
});

test("chat send accepts owned blob references and history keeps metadata only", () => {
  const metadata = {
    id: "blob_attachment-1",
    kind: "image",
    displayName: "photo.png",
    mimeType: "image/png",
    sizeBytes: 12
  };
  const attachment = { ...metadata, sha256: "a".repeat(64) };

  assert.equal(chatAttachmentSchema.safeParse(metadata).success, true);
  assert.equal(chatAttachmentReferenceSchema.safeParse(attachment).success, true);
  const parsed = chatSendMessageSchema.parse({
    type: "chat.send",
    deviceId: "pixel",
    sessionKey: "codex:session",
    text: "",
    attachments: [attachment]
  });
  assert.equal(parsed.text, "");
  assert.deepEqual(parsed.attachments, [attachment]);
  const history = chatHistoryMessageSchema.parse({
    role: "user",
    text: "",
    attachments: [attachment]
  });
  assert.deepEqual(history.attachments, [metadata]);
});

test("chat attachments reject inline payloads and enforce item, count, and aggregate limits", () => {
  const oversizedAttachment = {
    id: "blob_attachment-large",
    kind: "file",
    displayName: "large.bin",
    mimeType: "application/octet-stream",
    sizeBytes: CHAT_ATTACHMENT_MAX_BYTES + 1,
    sha256: "a".repeat(64)
  };
  const inlineAttachment = {
    id: "blob_attachment-inline",
    kind: "file",
    displayName: "data.bin",
    mimeType: "application/octet-stream",
    sizeBytes: 12,
    sha256: "a".repeat(64),
    contentBase64: "aGVsbG8="
  };

  assert.equal(chatAttachmentReferenceSchema.safeParse(oversizedAttachment).success, false);
  assert.equal(chatAttachmentReferenceSchema.safeParse(inlineAttachment).success, false);
  const message = (attachments: unknown[]) => ({
    type: "chat.send",
    deviceId: "pixel",
    sessionKey: "codex:session",
    text: "inspect",
    attachments
  });
  const small = {
    id: inlineAttachment.id,
    kind: inlineAttachment.kind,
    displayName: inlineAttachment.displayName,
    mimeType: inlineAttachment.mimeType,
    sizeBytes: 1,
    sha256: inlineAttachment.sha256
  };
  assert.equal(chatSendMessageSchema.safeParse(message(Array.from({ length: CHAT_ATTACHMENT_MAX_COUNT + 1 }, (_, index) => ({
    ...small,
    id: `blob_attachment-${index}`
  })))).success, false);
  const aggregateChunk = Math.floor(CHAT_ATTACHMENT_MAX_MESSAGE_BYTES / 3) + 1;
  assert.equal(chatSendMessageSchema.safeParse(message([
    { ...small, id: "blob_attachment-total1", sizeBytes: aggregateChunk },
    { ...small, id: "blob_attachment-total2", sizeBytes: aggregateChunk },
    { ...small, id: "blob_attachment-total3", sizeBytes: aggregateChunk }
  ])).success, false);
});

test("realtime start accepts selected chat backend model IDs", () => {
  for (const model of ["gpt-5.5", "hermes:gpt-5.5", "codex:gpt-5.3-codex", "opencode:openai/gpt-5.5", "pi:anthropic/claude-sonnet-4-5", "devin:default", "local-litertlm"]) {
    assert.equal(realtimeStartMessageSchema.safeParse({
      type: "realtime.start",
      deviceId: "pixel",
      voiceSessionId: VOICE_SESSION_ID,
      sdp: "v=0\r\n...",
      model,
      reasoningEffort: "medium"
    }).success, true, `expected ${model} to parse`);
  }

  assert.equal(realtimeStartMessageSchema.safeParse({
    type: "realtime.start",
    deviceId: "pixel",
    voiceSessionId: VOICE_SESSION_ID,
    sdp: "v=0\r\n...",
    model: ""
  }).success, false);
  assert.equal(realtimeStartMessageSchema.safeParse({
    type: "realtime.start",
    deviceId: "pixel",
    voiceSessionId: VOICE_SESSION_ID,
    sdp: "v=0\r\n...",
    model: "devin:"
  }).success, false);
  assert.equal(realtimeStartMessageSchema.safeParse({
    type: "realtime.start",
    deviceId: "pixel",
    voiceSessionId: VOICE_SESSION_ID,
    sdp: "v=0\r\n...",
    model: "other:gpt-5.5"
  }).success, false);
  assert.equal(realtimeStartMessageSchema.safeParse({
    type: "realtime.start",
    deviceId: "pixel",
    voiceSessionId: VOICE_SESSION_ID,
    sdp: "v=0\r\n...",
    reasoningEffort: "extreme"
  }).success, false);
});

test("realtime tool calls accept selected backend routing context", () => {
  assert.equal(realtimeToolCallMessageSchema.safeParse({
    type: "realtime.tool_call",
    deviceId: "pixel",
    voiceSessionId: VOICE_SESSION_ID,
    callId: "call_1",
    name: "delegate_agent_task",
    model: "codex:gpt-5.3-codex",
    reasoningEffort: "high",
    arguments: { instruction: "Summarize" }
  }).success, true);

  assert.equal(realtimeToolCallMessageSchema.safeParse({
    type: "realtime.tool_call",
    deviceId: "pixel",
    voiceSessionId: VOICE_SESSION_ID,
    callId: "call_1",
    name: "delegate_agent_task",
    model: "local-litertlm",
    reasoningEffort: "extreme",
    arguments: { instruction: "Summarize" }
  }).success, false);
});

test("every realtime wire message requires a UUID voiceSessionId", () => {
  const inboundWithoutOwners = [
    { type: "realtime.start", deviceId: "pixel", sdp: "v=0\r\n..." },
    { type: "realtime.stop", deviceId: "pixel" },
    { type: "realtime.tool_call", deviceId: "pixel", callId: "call_1", name: "stop_phone_task", arguments: {} }
  ];
  const inboundSchemas = [realtimeStartMessageSchema, realtimeStopMessageSchema, realtimeToolCallMessageSchema];
  inboundWithoutOwners.forEach((message, index) => {
    assert.equal(inboundSchemas[index]!.safeParse(message).success, false);
    assert.equal(inboundSchemas[index]!.safeParse({ ...message, voiceSessionId: "not-a-uuid" }).success, false);
  });

  assert.equal(phoneOutboundMessageSchema.safeParse({
    type: "realtime.error",
    deviceId: "pixel",
    message: "uncorrelated"
  }).success, false);
  assert.equal(phoneOutboundMessageSchema.safeParse({
    type: "realtime.error",
    deviceId: "pixel",
    voiceSessionId: "not-a-uuid",
    message: "malformed owner"
  }).success, false);
});

test("PC outbound phone messages have validating schemas for dev and tests", () => {
  const originalNodeEnv = process.env.NODE_ENV;
  const originalValidateOutbound = process.env.PHONE_AGENT_VALIDATE_OUTBOUND;
  process.env.NODE_ENV = "test";
  delete process.env.PHONE_AGENT_VALIDATE_OUTBOUND;
  const outboundMessages = [
    { id: "cmd_1", type: "command", requestOwner: "host:test", command: "observe_screen", args: {} },
    { type: "command.cancel", commandId: "cmd_1", requestOwner: "host:test", reason: "timed out" },
    { type: "agent_status", deviceId: "pixel", status: "info", text: "Registered pixel" },
    { type: "realtime.sdp", deviceId: "pixel", voiceSessionId: VOICE_SESSION_ID, sdp: "answer" },
    { type: "realtime.transcript_delta", deviceId: "pixel", voiceSessionId: VOICE_SESSION_ID, role: "assistant", delta: "hi", isFinal: false },
    { type: "realtime.item_added", deviceId: "pixel", voiceSessionId: VOICE_SESSION_ID, item: { id: "item_1" } },
    { type: "realtime.speech_started", deviceId: "pixel", voiceSessionId: VOICE_SESSION_ID, role: "user", itemId: null },
    { type: "realtime.error", deviceId: "pixel", voiceSessionId: VOICE_SESSION_ID, message: "bad" },
    { type: "realtime.closed", deviceId: "pixel", voiceSessionId: VOICE_SESSION_ID, reason: null },
    { type: "realtime.tool_result", deviceId: "pixel", voiceSessionId: VOICE_SESSION_ID, callId: "call_1", ok: false, status: "failed", error: "bad" },
    { type: "realtime.task_status", deviceId: "pixel", voiceSessionId: VOICE_SESSION_ID, running: false, queued: 0, currentTask: null, completed: 0, failed: 1 },
    { type: "chat.state", deviceId: "pixel", sessionKey: "session", isRunning: false },
    { type: "chat.history", deviceId: "pixel", sessionKey: "session", messages: [] },
    { type: "chat.message", deviceId: "pixel", sessionKey: "session", message: { role: "user", text: "hello" } },
    { type: "chat.delta", deviceId: "pixel", sessionKey: "session", runId: "run", delta: "hi" },
    { type: "chat.reasoning_delta", deviceId: "pixel", sessionKey: "session", runId: "run", delta: "thinking" },
    { type: "chat.reasoning_clear", deviceId: "pixel", sessionKey: "session", runId: null },
    { type: "chat.final", deviceId: "pixel", sessionKey: "session", runId: "run", text: "done" },
    { type: "chat.error", deviceId: "pixel", sessionKey: "session", runId: "run", message: "failed" },
    { type: "chat.reply_available", deviceId: "pixel", sessionKey: "session", runId: "run", status: "completed", textPreview: "done", harnessId: "openclaw", harnessLabel: "OpenClaw", model: "gpt-5.5" },
    {
      type: "chat.tool_event",
      deviceId: "pixel",
      sessionKey: "session",
      eventId: "tool_1",
      toolName: "exec",
      title: "Ran command",
      status: "completed",
      actions: [{ id: "once", label: "Allow Once", command: "opencode.permission", args: { permissionId: "perm_1", response: "once" }, style: "primary" }]
    },
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

test("command and result envelopes carry scoped approval capabilities", () => {
  const approvedCommand = {
    id: "cmd_approved",
    type: "command",
    requestOwner: "host:mcp:test",
    command: "tap_node",
    args: { observationId: "123e4567-e89b-12d3-a456-426614174000", nodeId: "n1" },
    approvalCapability: "abcdefghijklmnopqrstuvwxyz123456"
  };
  assert.equal(commandMessageSchema.safeParse(approvedCommand).success, true);
  assert.equal(commandMessageSchema.safeParse({ ...approvedCommand, requestOwner: "" }).success, false);
  assert.equal(commandMessageSchema.safeParse({ ...approvedCommand, approvalCapability: "short" }).success, false);
  assert.equal(resultMessageSchema.safeParse({
    id: "cmd_approval",
    type: "result",
    ok: true,
    approvalCapability: "abcdefghijklmnopqrstuvwxyz123456",
    approvalExpiresAtMs: 2_000_000_000,
    approvedAction: "Tap observed node n1"
  }).success, true);
});

test("node commands require an explicit observation generation", () => {
  const target = { observationId: "123e4567-e89b-12d3-a456-426614174000", nodeId: "n17" };
  assert.equal(observedNodeTargetSchema.safeParse(target).success, true);
  assert.equal(observedNodeTargetSchema.safeParse({ nodeId: "n17" }).success, false);
  assert.equal(commandMessageSchema.safeParse({
    id: "cmd_node",
    type: "command",
    requestOwner: "host:test",
    command: "tap_node",
    args: { nodeId: "n17" }
  }).success, false);
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
