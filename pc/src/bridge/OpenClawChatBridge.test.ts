import assert from "node:assert/strict";
import test from "node:test";
import type { ChatAttachment } from "../protocol/messages.js";
import { ChatClientError, CODEX_WORKSPACE_NOT_FOUND_CODE } from "./chat/ChatErrors.js";
import { createHarness, defaultSessionKey, deferred, waitFor } from "./OpenClawChatBridge.testSupport.js";

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

test("realtime requests apply selected harness model and reasoning", async () => {
  const { bridge, client } = createHarness();

  const request = bridge.handleRealtimeRequest({
    type: "user_request",
    deviceId: "pixel",
    inputType: "text",
    text: "Summarize my project"
  }, {
    taskKind: "general",
    callId: "call_1",
    model: "hermes:gpt-5.5",
    reasoningEffort: "high"
  });
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
  assert.equal(client.created[0]?.model, "hermes:gpt-5.5");
  assert.equal(client.sent[0]?.thinking, "high");
});

test("new chats use uuid labels until first message display name is set", async () => {
  const { bridge, client } = createHarness();

  await bridge.newSession({
    type: "chat.new_session",
    deviceId: "pixel"
  });

  assert.equal(client.created.length, 1);
  assert.match(client.created[0]?.label ?? "", /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  assert.notEqual(client.created[0]?.label, "OpenAgent");

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

test("gateway chat deltas are forwarded before the final message", async () => {
  const { bridge, chatMessages, client } = createHarness();

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Write a streaming response"
  });

  const sessionKey = defaultSessionKey("pixel");
  client.emit({
    event: "chat",
    payload: {
      sessionKey,
      runId: "run_1",
      state: "delta",
      delta: "Hel"
    }
  });
  client.emit({
    event: "chat",
    payload: {
      sessionKey,
      runId: "run_1",
      type: "message.delta",
      data: { textDelta: "lo" }
    }
  });
  client.emit({
    event: "chat",
    payload: {
      sessionKey,
      runId: "run_1",
      state: "final",
      message: "Hello"
    }
  });

  assert.deepEqual(
    chatMessages
      .filter((message) => message.type === "chat.delta" || message.type === "chat.final")
      .map((message) => {
        if (message.type === "chat.delta") {
          return { type: message.type, runId: message.runId, text: message.delta };
        }
        return { type: message.type, runId: message.runId, text: message.text };
      }),
    [
      { type: "chat.delta", runId: "run_1", text: "Hel" },
      { type: "chat.delta", runId: "run_1", text: "lo" },
      { type: "chat.final", runId: "run_1", text: "Hello" }
    ]
  );
});

test("fast mode control command does not replace the active user run", async () => {
  const { bridge, chatMessages, client } = createHarness();

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "hi"
  });
  await bridge.controlCommand({
    type: "chat.control_command",
    deviceId: "pixel",
    command: "fast",
    args: { enabled: false }
  });

  const latestState = chatMessages.filter((message) => message.type === "chat.state").at(-1);
  assert.equal(latestState?.runId, "run_1");
  assert.equal(latestState?.isRunning, true);

  client.emit({
    event: "chat",
    payload: {
      sessionKey: defaultSessionKey("pixel"),
      runId: "run_2",
      state: "final",
      message: ""
    }
  });
  assert.equal(chatMessages.some((message) => message.type === "chat.final" && message.runId === "run_2"), false);
  assert.equal(chatMessages.filter((message) => message.type === "chat.state").at(-1)?.runId, "run_1");

  client.emit({
    event: "chat",
    payload: {
      sessionKey: defaultSessionKey("pixel"),
      runId: "run_1",
      state: "final",
      message: "Done"
    }
  });

  const finalState = chatMessages.filter((message) => message.type === "chat.state").at(-1);
  assert.equal(finalState?.runId, null);
  assert.equal(finalState?.isRunning, false);
});

test("Hermes fast mode control command patches session instead of sending slash prompt", async () => {
  const { bridge, chatMessages, client } = createHarness();
  await bridge.selectSession({
    type: "chat.select_session",
    deviceId: "pixel",
    sessionKey: "hermes:chat"
  });
  client.patched.length = 0;

  await bridge.controlCommand({
    type: "chat.control_command",
    deviceId: "pixel",
    command: "fast",
    args: { enabled: true }
  });

  assert.equal(client.sent.length, 0);
  assert.deepEqual(client.patched, [{
    sessionKey: "hermes:chat",
    patch: { fastMode: true }
  }]);
  const latestState = chatMessages.filter((message) => message.type === "chat.state").at(-1);
  assert.equal(latestState?.sessionKey, "hermes:chat");
  assert.equal(latestState?.fastMode, true);
  assert.equal(latestState?.status, "Fast mode enabled");

  client.patched.length = 0;
  await bridge.controlCommand({
    type: "chat.control_command",
    deviceId: "pixel",
    command: "/fast status",
    args: {}
  });

  assert.equal(client.sent.length, 0);
  assert.equal(client.patched.length, 0);
  assert.equal(chatMessages.filter((message) => message.type === "chat.state").at(-1)?.status, "Fast mode enabled");
});

test("harness permission action commands route through selected session harness", async () => {
  const { bridge, chatMessages, client } = createHarness();
  await bridge.selectSession({
    type: "chat.select_session",
    deviceId: "pixel",
    sessionKey: "opencode:ses_1"
  });

  await bridge.controlCommand({
    type: "chat.control_command",
    deviceId: "pixel",
    command: "opencode.permission",
    args: { permissionId: "perm_1", response: "once" }
  });

  assert.deepEqual(client.permissionReplies, [{
    sessionKey: "opencode:ses_1",
    permissionId: "perm_1",
    response: "once"
  }]);
  assert.equal(chatMessages.filter((message) => message.type === "chat.state").at(-1)?.status, "OpenCode permission approved.");
});

test("harness permission action commands reject invalid replies before routing", async () => {
  const { bridge, chatMessages, client } = createHarness();
  await bridge.selectSession({
    type: "chat.select_session",
    deviceId: "pixel",
    sessionKey: "opencode:ses_1"
  });

  await bridge.controlCommand({
    type: "chat.control_command",
    deviceId: "pixel",
    command: "opencode.permission",
    args: { permissionId: "perm_1", response: "forever" }
  });

  assert.deepEqual(client.permissionReplies, []);
  assert.equal(chatMessages.filter((message) => message.type === "chat.state").at(-1)?.status, "Invalid OpenCode permission reply.");
});

test("chat send forwards attachments to the gateway client", async () => {
  const { bridge, client } = createHarness();
  const attachment: ChatAttachment = {
    id: "att_1",
    kind: "image",
    displayName: "photo.png",
    mimeType: "image/png",
    sizeBytes: 12,
    contentBase64: "aGVsbG8="
  };

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "",
    attachments: [attachment]
  });

  assert.equal(client.sent.length, 1);
  assert.equal(client.sent[0]?.message, "Please review the attached image.");
  assert.deepEqual(client.sent[0]?.attachments, [attachment]);
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

test("coded chat failures preserve structured error details", async () => {
  const { bridge, chatMessages, client } = createHarness();
  client.sendError = new ChatClientError("Codex workspace folder not found: ~/missing", {
    code: CODEX_WORKSPACE_NOT_FOUND_CODE,
    workspacePath: "~/missing"
  });

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Use Codex",
    model: "codex:gpt-5.3-codex"
  });

  const error = chatMessages.find((message) => message.type === "chat.error");
  assert.equal(error?.message, "Codex workspace folder not found: ~/missing");
  assert.equal(error?.code, CODEX_WORKSPACE_NOT_FOUND_CODE);
  assert.equal(error?.workspacePath, "~/missing");
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

test("simultaneous sends reserve one session before either crosses the harness boundary", async () => {
  const { bridge, chatMessages, client } = createHarness();
  const sendGate = deferred();
  client.sendGate = sendGate.promise;

  const first = bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "First prompt",
    idempotencyKey: "request_first"
  });
  await waitFor(() => client.sent.length === 1);

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Racing prompt",
    idempotencyKey: "request_racing"
  });

  assert.equal(client.sent.length, 1);
  assert.equal(
    chatMessages.filter((message) => message.type === "chat.error" && message.runId === "request_racing").length,
    1
  );

  sendGate.resolve();
  await first;
  client.emit({
    event: "chat",
    payload: {
      sessionKey: defaultSessionKey("pixel"),
      runId: "run_1",
      state: "final",
      message: "First finished"
    }
  });

  assert.equal(
    chatMessages.filter((message) => message.type === "chat.final" && message.runId === "run_1").length,
    1
  );
});

test("stop during harness admission settles the caller once and aborts the promoted run", async () => {
  const { bridge, chatMessages, client } = createHarness();
  const sendGate = deferred();
  client.sendGate = sendGate.promise;

  const sending = bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Long start",
    idempotencyKey: "request_stopped"
  });
  await waitFor(() => client.sent.length === 1);

  await bridge.stop({
    type: "chat.stop",
    deviceId: "pixel",
    reason: "Stopped during start"
  });
  assert.deepEqual(client.aborted, []);

  sendGate.resolve();
  await sending;
  assert.deepEqual(client.aborted, [{
    sessionKey: defaultSessionKey("pixel"),
    runId: "run_1"
  }]);

  client.emit({
    event: "chat",
    payload: {
      sessionKey: defaultSessionKey("pixel"),
      runId: "run_1",
      state: "final",
      message: "Late terminal"
    }
  });

  const terminals = chatMessages.filter((message) =>
    (message.type === "chat.final" || message.type === "chat.error") && message.runId === "run_1"
  );
  assert.equal(terminals.length, 1);
  assert.equal(terminals[0]?.type, "chat.error");
  assert.match(terminals[0]?.type === "chat.error" ? terminals[0].message : "", /Stopped during start/);
});

test("harness rejection rolls admission back so the session can retry", async () => {
  const { bridge, chatMessages, client } = createHarness();
  client.sendError = new Error("Hermes rejected the run");

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Rejected prompt",
    model: "hermes:gpt-5.5",
    idempotencyKey: "request_rejected"
  });
  client.sendError = undefined;
  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Retry prompt",
    idempotencyKey: "request_retry"
  });

  assert.equal(client.sent.length, 1);
  assert.equal(client.sent[0]?.sessionKey, "hermes:hermes-agent-pixel");
  assert.equal(
    chatMessages.filter((message) => message.type === "chat.error" && message.runId === "request_rejected").length,
    1
  );

  client.emit({
    event: "chat",
    payload: {
      sessionKey: "hermes:hermes-agent-pixel",
      runId: "run_1",
      state: "final",
      message: "Retry finished"
    }
  });
  assert.equal(
    chatMessages.filter((message) => message.type === "chat.final" && message.runId === "run_1").length,
    1
  );
});

test("terminal events racing send acknowledgement are forwarded exactly once", async () => {
  const { bridge, chatMessages, client } = createHarness();
  client.beforeSendResolve = (result) => {
    client.emit({
      event: "chat",
      payload: {
        sessionKey: result.sessionKey,
        runId: result.runId,
        state: "final",
        message: "Finished before acknowledgement"
      }
    });
  };

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Very fast prompt"
  });
  client.beforeSendResolve = undefined;
  client.emit({
    event: "chat",
    payload: {
      sessionKey: defaultSessionKey("pixel"),
      runId: "run_1",
      state: "final",
      message: "Duplicate terminal"
    }
  });

  assert.equal(
    chatMessages.filter((message) => message.type === "chat.final" && message.runId === "run_1").length,
    1
  );
  const finalState = chatMessages.filter((message) => message.type === "chat.state").at(-1);
  assert.equal(finalState?.runId, null);
  assert.equal(finalState?.isRunning, false);
});

test("one device can own independent runs in separate sessions", async () => {
  const { bridge, chatMessages, client } = createHarness();
  const sendGate = deferred();
  client.sendGate = sendGate.promise;
  const sessionA = "agent:main:explicit:session-a";
  const sessionB = "agent:main:explicit:session-b";

  const first = bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    sessionKey: sessionA,
    text: "Session A"
  });
  await waitFor(() => client.sent.length === 1);
  const second = bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    sessionKey: sessionB,
    text: "Session B"
  });
  await waitFor(() => client.sent.length === 2);

  assert.deepEqual(client.sent.map((entry) => entry.sessionKey), [sessionA, sessionB]);
  sendGate.resolve();
  await Promise.all([first, second]);

  client.emit({ event: "chat", payload: { sessionKey: sessionA, runId: "run_1", state: "final", message: "A done" } });
  client.emit({ event: "chat", payload: { sessionKey: sessionB, runId: "run_2", state: "final", message: "B done" } });
  assert.equal(
    chatMessages.filter((message) => message.type === "chat.reply_available" && message.runId === "run_1").length,
    1
  );
  assert.equal(
    chatMessages.filter((message) => message.type === "chat.final" && message.runId === "run_2").length,
    1
  );
});

test("queue and steer retain their delivery semantics while a run is starting", async () => {
  const { bridge, client } = createHarness();
  const sendGate = deferred();
  client.sendGate = sendGate.promise;

  const first = bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Start slowly"
  });
  await waitFor(() => client.sent.length === 1);
  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Next turn",
    delivery: "queue"
  });
  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Use a narrower scope",
    delivery: "steer"
  });
  assert.equal(client.sent.length, 1);
  assert.equal(client.steered.length, 0);

  sendGate.resolve();
  await first;
  await waitFor(() => client.steered.length === 1);
  assert.equal(client.steered[0]?.runId, "run_1");
  assert.equal(client.steered[0]?.message, "Use a narrower scope");

  client.emit({
    event: "chat",
    payload: {
      sessionKey: defaultSessionKey("pixel"),
      runId: "run_1",
      state: "final",
      message: "First done"
    }
  });
  await waitFor(() => client.sent.length === 2);
  assert.equal(client.sent[1]?.message, "Next turn");
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

  assert.match(client.sent[0]?.message ?? "", /\$android-control skill/);
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
  assert.equal(reply.harnessId, "openclaw");
  assert.equal(reply.harnessLabel, "OpenClaw");
});

test("host initiated terminal events emit reply notifications without switching timeline", async () => {
  const { bridge, chatMessages, client } = createHarness();
  const cronSessionKey = "agent:main:explicit:nightly-cron";
  client.sessions = [{
    key: cronSessionKey,
    sessionId: "cron-session",
    displayName: "Nightly cron"
  }];
  await bridge.open({
    type: "chat.open",
    deviceId: "pixel"
  });

  const beforeFinal = chatMessages.length;
  client.emit({
    event: "chat",
    payload: {
      sessionKey: cronSessionKey,
      runId: "cron-run-1",
      state: "final",
      message: "Cron finished"
    }
  });

  const emitted = chatMessages.slice(beforeFinal);
  assert.equal(emitted.some((message) => message.type === "chat.final"), false);
  const reply = emitted.find((message) => message.type === "chat.reply_available");
  assert.ok(reply);
  assert.equal(reply.sessionKey, cronSessionKey);
  assert.equal(reply.runId, "cron-run-1");
  assert.equal(reply.status, "completed");
  assert.equal(reply.textPreview, "Cron finished");
  assert.equal(reply.sessionId, "cron-session");
  assert.equal(reply.sessionDisplayName, "Nightly cron");
  assert.equal(reply.harnessId, "openclaw");
  assert.equal(reply.harnessLabel, "OpenClaw");
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
  const messagesAfterSettings = chatMessages.filter((message) => message.type === "chat.message");
  assert.equal(messagesAfterSettings.length, 0);

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    text: "Use the selected model"
  });

  assert.deepEqual(client.sent.map((entry) => entry.message), ["/think high", "/think high", "/model gpt-5.4", "Use the selected model"]);
  assert.deepEqual(client.patched.map((entry) => entry.patch), []);
  assert.deepEqual(
    chatMessages.filter((message) => message.type === "chat.message").map((message) => message.message.text),
    ["Use the selected model"]
  );
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
