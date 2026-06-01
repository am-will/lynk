import assert from "node:assert/strict";
import test from "node:test";
import { createHarness, defaultSessionKey, waitFor } from "./OpenClawChatBridge.testSupport.js";

test("OpenCode sends refresh sessions immediately after first user message", async () => {
  const { bridge, chatMessages, client } = createHarness();
  client.models = [{ id: "opencode:openai/gpt-5.5", harnessId: "opencode", available: true }];

  await bridge.setModel({
    type: "chat.set_model",
    deviceId: "pixel",
    sessionKey: defaultSessionKey("pixel"),
    model: "opencode:openai/gpt-5.5"
  });
  const opencodeSessionKey = client.patched[0]?.sessionKey ?? "opencode:pixel";
  client.sessions = [{
    key: opencodeSessionKey,
    sessionId: "ses_first",
    label: "First message",
    harnessId: "opencode",
    model: "opencode:openai/gpt-5.5",
    workspacePath: "/Users/example/Applications"
  }];
  chatMessages.length = 0;

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    sessionKey: opencodeSessionKey,
    model: "opencode:openai/gpt-5.5",
    text: "first user message"
  });
  await waitFor(() => chatMessages.some((message) => message.type === "chat.sessions"));

  const sessions = chatMessages.find((message) => message.type === "chat.sessions");
  assert.equal(sessions?.sessions[0]?.key, opencodeSessionKey);
  assert.equal(sessions?.sessions[0]?.workspacePath, "/Users/example/Applications");
});

test("new chat workspace options are forwarded only for Codex", async () => {
  const { bridge, client } = createHarness();
  const workspacePath = "/Users/am.will/Applications/cryptoclub";

  await bridge.newSession({
    type: "chat.new_session",
    deviceId: "pixel",
    model: "codex:gpt-5.3-codex",
    workspacePath,
    createWorkspaceIfMissing: true
  });
  await bridge.newSession({
    type: "chat.new_session",
    deviceId: "pixel",
    model: "hermes:gpt-5.5",
    workspacePath,
    createWorkspaceIfMissing: true
  });

  assert.equal(client.created[0]?.workspacePath, workspacePath);
  assert.equal(client.created[0]?.createWorkspaceIfMissing, true);
  assert.equal(client.created[1]?.workspacePath, undefined);
  assert.equal(client.created[1]?.createWorkspaceIfMissing, undefined);
});

test("Codex new chats reuse the active session workspace when no workspace is supplied", async () => {
  const { bridge, client } = createHarness();
  const workspacePath = "/Users/am.will/Applications/open-claw-agent";
  client.sessions = [{
    key: "codex:existing",
    sessionId: "existing",
    label: "Existing Codex chat",
    harnessId: "codex",
    model: "codex:gpt-5.3-codex",
    workspacePath
  }];

  await bridge.selectSession({
    type: "chat.select_session",
    deviceId: "pixel",
    sessionKey: "codex:existing"
  });
  await bridge.newSession({
    type: "chat.new_session",
    deviceId: "pixel"
  });

  assert.equal(client.created.at(-1)?.workspacePath, workspacePath);
});

test("backend readiness reports configured harnesses only when live models exist", async () => {
  const { bridge, client } = createHarness();
  client.models = [
    { id: "gpt-5.5" },
    { id: "codex:gpt-5.3-codex" }
  ];

  assert.deepEqual(await bridge.backendReadiness(), {
    harnesses: {
      openclaw: {
        ok: true,
        configured: true,
        label: "OpenClaw",
        modelCount: 1,
        state: "ready",
        message: "OpenClaw backend is ready."
      },
      hermes: {
        ok: false,
        configured: false,
        label: "Hermes",
        modelCount: 0,
        state: "missing_config",
        message: "Hermes is not configured on the PC bridge.",
        action: "Set HERMES_API_KEY or configure Hermes in the host bridge config, then run host integration refresh."
      },
      codex: {
        ok: true,
        configured: true,
        label: "Codex",
        modelCount: 1,
        state: "ready",
        message: "Codex backend is ready."
      },
      opencode: {
        ok: false,
        configured: false,
        label: "OpenCode",
        modelCount: 0,
        state: "missing_config",
        message: "OpenCode is not configured on the PC bridge.",
        action: "Install OpenCode CLI or configure OPENCODE_SERVER_URL, then run host integration refresh."
      }
    }
  });
});

test("backend readiness counts OpenCode models as OpenCode", async () => {
  const { bridge, client } = createHarness({ opencodeConfigured: true });
  client.models = [
    { id: "opencode:openai/gpt-5.5", harnessId: "opencode", provider: "opencode" }
  ];

  const readiness = await bridge.backendReadiness();

  assert.equal(readiness.harnesses.opencode.ok, true);
  assert.equal(readiness.harnesses.opencode.modelCount, 1);
  assert.equal(readiness.harnesses.opencode.state, "ready");
  assert.equal(readiness.harnesses.openclaw.modelCount, 0);
});

test("open uses the selected session harness in loading status", async () => {
  const { bridge, chatMessages } = createHarness();

  await bridge.open({
    type: "chat.open",
    deviceId: "pixel",
    sessionKey: "hermes:chat"
  });

  assert.equal(chatMessages.find((message) => message.type === "chat.state")?.status, "Loading Hermes chat");
});

test("unhealthy active harness fails before sending with harness-specific guidance", async () => {
  const { bridge, chatMessages, client, fallbackCalls } = createHarness();
  client.healthResponse = {
    harnesses: {
      openclaw: { ok: true },
      hermes: { ok: false, error: "connect ECONNREFUSED 127.0.0.1:8642" }
    }
  };

  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    sessionKey: defaultSessionKey("pixel"),
    text: "Use Hermes",
    model: "hermes:gpt-5.5"
  });

  assert.equal(client.sent.length, 0);
  assert.equal(fallbackCalls.length, 0);
  const error = chatMessages.find((message) => message.type === "chat.error");
  assert.equal(error?.code, "hermes.unreachable");
  assert.match(error?.message ?? "", /Hermes backend is not reachable/);
  assert.match(error?.message ?? "", /HERMES_API_BASE_URL/);
});

test("harness switch ignores stale OpenClaw session key on next send", async () => {
  const { bridge, client } = createHarness();
  const staleOpenClawSession = defaultSessionKey("pixel");

  await bridge.setModel({
    type: "chat.set_model",
    deviceId: "pixel",
    sessionKey: staleOpenClawSession,
    model: "hermes:gpt-5.5"
  });
  await bridge.send({
    type: "chat.send",
    deviceId: "pixel",
    sessionKey: staleOpenClawSession,
    text: "Still route to Hermes",
    model: "hermes:gpt-5.5"
  });

  assert.equal(client.patched[0]?.sessionKey, "hermes:hermes-agent-pixel");
  assert.equal(client.sent[0]?.sessionKey, "hermes:hermes-agent-pixel");
});
