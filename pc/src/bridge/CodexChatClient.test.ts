import assert from "node:assert/strict";
import test from "node:test";
import type { AgentRequestOptions, AgentRunResult, AgentStatusSink } from "../dispatcher/AgentClient.js";
import { normalizeCodexUsage } from "../dispatcher/CodexAppServerClient.js";
import type { CodexAppServerClient } from "../dispatcher/CodexAppServerClient.js";
import { CodexChatClient } from "./CodexChatClient.js";

class FakeCodexAppServerClient {
  readonly createdThreads: Array<{ model?: string; baseInstructions?: string }> = [];
  readonly submitted: Array<{ text: string; options: AgentRequestOptions }> = [];
  readonly steered: string[] = [];
  modelsPayload: unknown = { models: [] };
  threadsPayload: unknown = { data: [] };
  threadPayloads: unknown[] = [];
  readThreadPayload: unknown = undefined;
  capabilitiesPayload: unknown = undefined;
  resultUsage?: Record<string, unknown>;
  private nextThread = 1;

  async createThread(options: { model?: string; baseInstructions?: string } = {}): Promise<string> {
    this.createdThreads.push(options);
    return `019e0000-0000-7000-8000-${String(this.nextThread++).padStart(12, "0")}`;
  }

  async submitUserRequest(
    text: string,
    sink: AgentStatusSink,
    options: AgentRequestOptions = {}
  ): Promise<AgentRunResult> {
    this.submitted.push({ text, options });
    sink.done("done");
    return {
      threadId: options.threadId,
      turnId: "turn_1",
      finalMessage: "done",
      usage: this.resultUsage
    };
  }

  async listModels(): Promise<unknown> {
    return this.modelsPayload;
  }

  async listThreads(): Promise<unknown> {
    return this.threadPayloads.shift() ?? this.threadsPayload;
  }

  async readThread(): Promise<unknown> {
    return this.readThreadPayload;
  }

  async readModelProviderCapabilities(): Promise<unknown> {
    return this.capabilitiesPayload;
  }

  async interrupt(): Promise<void> {}

  async steer(text: string): Promise<void> {
    this.steered.push(text);
  }

  async close(): Promise<void> {}
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

test("Codex new sessions create and reuse app-server threads", async () => {
  const fake = new FakeCodexAppServerClient();
  const client = new CodexChatClient(undefined, fake as unknown as CodexAppServerClient, null);

  const created = await client.createSession({
    key: "codex:chat",
    label: "Chat",
    model: "gpt-5.3-codex"
  }) as { key?: string; sessionId?: string };

  assert.equal(created.key, "codex:019e0000-0000-7000-8000-000000000001");
  assert.equal(created.sessionId, "019e0000-0000-7000-8000-000000000001");
  assert.equal(fake.createdThreads[0]?.model, "gpt-5.3-codex");
  assert.match(fake.createdThreads[0]?.baseInstructions ?? "", /android-control skill/);

  await client.sendChat({
    sessionKey: created.key!,
    message: "Hello",
    idempotencyKey: "run_1"
  });
  await waitFor(() => fake.submitted.length === 1);

  assert.equal(fake.createdThreads.length, 1);
  assert.equal(fake.submitted[0]?.options.threadId, "019e0000-0000-7000-8000-000000000001");
  assert.equal(fake.submitted[0]?.options.useSessionInstructions, true);
  assert.equal(fake.submitted[0]?.text, "Hello");
});

test("Codex sends from implicit sessions create a durable thread first", async () => {
  const fake = new FakeCodexAppServerClient();
  const client = new CodexChatClient(undefined, fake as unknown as CodexAppServerClient, null);

  await client.sendChat({
    sessionKey: "codex:default",
    message: "Hello",
    idempotencyKey: "run_1"
  });
  await waitFor(() => fake.submitted.length === 1);

  assert.equal(fake.createdThreads.length, 1);
  assert.match(fake.createdThreads[0]?.baseInstructions ?? "", /android-control skill/);
  assert.equal(fake.submitted[0]?.options.threadId, "019e0000-0000-7000-8000-000000000001");
  assert.equal(fake.submitted[0]?.options.useSessionInstructions, true);
  assert.equal(fake.submitted[0]?.text, "Hello");

  const payload = await client.listSessions() as { sessions: Array<{ key?: string; sessionId?: string }> };
  assert.deepEqual(payload.sessions.map((session) => [session.key, session.sessionId]), [
    ["codex:default", "019e0000-0000-7000-8000-000000000001"]
  ]);
});

test("Codex lists app-server threads as workspace-aware sessions", async () => {
  const fake = new FakeCodexAppServerClient();
  fake.threadsPayload = {
    data: [{
      id: "019e56b1-639e-7ad2-b078-3106a2ee0874",
      name: "Design harness icon ideas",
      preview: "help me come up with a grid of icon ideas",
      cwd: "/Users/am.will/Applications/open-claw-agent",
      path: "/Users/am.will/.codex/sessions/2026/05/23/rollout.jsonl",
      source: "vscode",
      modelProvider: "openai",
      gitInfo: { sha: "abc", branch: "main" },
      updatedAt: 1779575368
    }]
  };
  const client = new CodexChatClient(undefined, fake as unknown as CodexAppServerClient, null);

  const payload = await client.listSessions() as { sessions: Array<Record<string, unknown>> };

  assert.equal(payload.sessions[0]?.key, "codex:019e56b1-639e-7ad2-b078-3106a2ee0874");
  assert.equal(payload.sessions[0]?.displayName, "Design harness icon ideas");
  assert.equal(payload.sessions[0]?.workspaceName, "open-claw-agent");
  assert.equal(payload.sessions[0]?.workspacePath, "/Users/am.will/Applications/open-claw-agent");
  assert.equal(payload.sessions[0]?.threadPath, "/Users/am.will/.codex/sessions/2026/05/23/rollout.jsonl");
  assert.equal(payload.sessions[0]?.updatedAt, 1779575368000);
});

test("Codex paginates app-server thread listing for desktop history", async () => {
  const fake = new FakeCodexAppServerClient();
  fake.threadPayloads = [
    {
      data: [{
        id: "019e56b1-639e-7ad2-b078-3106a2ee0874",
        name: "First page",
        cwd: "/Users/am.will/Applications/open-claw-agent",
        gitInfo: { sha: "abc", branch: "main" }
      }],
      nextCursor: "next"
    },
    {
      data: [{
        id: "019e5a80-a4d1-7c12-a8e5-50ab3349df73",
        name: "Second page",
        cwd: "/Users/am.will/Applications/cryptoclub"
      }],
      nextCursor: null
    }
  ];
  const client = new CodexChatClient(undefined, fake as unknown as CodexAppServerClient, null);

  const payload = await client.listSessions() as { sessions: Array<Record<string, unknown>> };

  assert.deepEqual(payload.sessions.map((session) => session.displayName), ["First page", "Second page"]);
  assert.deepEqual(payload.sessions.map((session) => session.workspaceName), ["open-claw-agent", null]);
});

test("Codex marks non-workspace sessions as quick chats", async () => {
  const fake = new FakeCodexAppServerClient();
  fake.threadsPayload = {
    data: [{
      id: "019e580b-e691-7843-bce7-03c2312e9017",
      name: null,
      preview: "give me a quick answer",
      cwd: "/Users/am.will/Documents/Codex/2026-05-23/give-me-a-quick-answer",
      source: "vscode",
      modelProvider: "openai",
      gitInfo: {}
    }]
  };
  const client = new CodexChatClient(undefined, fake as unknown as CodexAppServerClient, null);

  const payload = await client.listSessions() as { sessions: Array<Record<string, unknown>> };

  assert.equal(payload.sessions[0]?.workspacePath, null);
  assert.equal(payload.sessions[0]?.workspaceName, null);
});


test("Codex history reads persisted thread turns", async () => {
  const fake = new FakeCodexAppServerClient();
  fake.readThreadPayload = {
    thread: {
      id: "019e56b1-639e-7ad2-b078-3106a2ee0874",
      cwd: "/Users/am.will/Applications/open-claw-agent",
      turns: [{
        id: "turn_1",
        items: [
          { type: "userMessage", id: "item-1", content: [{ type: "text", text: "hello" }] },
          { type: "agentMessage", id: "item-2", text: "hi there" },
          { type: "reasoning", id: "item-3", text: "hidden" }
        ]
      }]
    }
  };
  const client = new CodexChatClient(undefined, fake as unknown as CodexAppServerClient, null);

  const history = await client.history("codex:019e56b1-639e-7ad2-b078-3106a2ee0874") as { messages: Array<Record<string, unknown>> };

  assert.deepEqual(history.messages.map((message) => [message.role, message.text]), [
    ["user", "hello"],
    ["assistant", "hi there"]
  ]);
});

test("Codex persists token usage returned from app-server runs", async () => {
  const fake = new FakeCodexAppServerClient();
  fake.resultUsage = normalizeCodexUsage({
    threadId: "thread_1",
    turnId: "turn_1",
    tokenUsage: {
      last: {
        cachedInputTokens: 3_072,
        inputTokens: 5_152,
        outputTokens: 16,
        reasoningOutputTokens: 0,
        totalTokens: 5_168
      },
      modelContextWindow: 258_400,
      total: {
        cachedInputTokens: 3_072,
        inputTokens: 5_152,
        outputTokens: 16,
        reasoningOutputTokens: 0,
        totalTokens: 5_168
      }
    }
  });
  const client = new CodexChatClient(undefined, fake as unknown as CodexAppServerClient, null);

  await client.sendChat({
    sessionKey: "codex:default",
    message: "Hello",
    idempotencyKey: "run_1"
  });
  await waitFor(() => fake.submitted.length === 1);

  const payload = await client.listSessions() as { sessions: Array<Record<string, unknown>> };
  assert.equal(payload.sessions[0]?.inputTokens, 5_152);
  assert.equal(payload.sessions[0]?.outputTokens, 16);
  assert.equal(payload.sessions[0]?.totalTokens, 5_168);
  assert.equal(payload.sessions[0]?.contextTokens, 258_400);
});

test("Codex chat steering calls app-server turn steer for the active run", async () => {
  const fake = new FakeCodexAppServerClient();
  const client = new CodexChatClient(undefined, fake as unknown as CodexAppServerClient, null);
  await client.createSession({ key: "codex:default", model: "gpt-5.3-codex" });
  (client as unknown as { active?: { sessionKey: string; runId: string } }).active = {
    sessionKey: "codex:default",
    runId: "run_1"
  };

  await client.steerChat({
    sessionKey: "codex:default",
    runId: "run_1",
    message: "Focus on failing tests"
  });

  assert.deepEqual(fake.steered, ["Focus on failing tests"]);
});

test("Codex model list resolves context windows from capabilities", async () => {
  const fake = new FakeCodexAppServerClient();
  fake.modelsPayload = {
    data: [{
      id: "gpt-5.3-codex",
      displayName: "GPT-5.3 Codex",
      supportedReasoningEfforts: ["medium"]
    }]
  };
  fake.capabilitiesPayload = { limits: { maxInputTokens: 300_000 } };
  const client = new CodexChatClient(undefined, fake as unknown as CodexAppServerClient, null);

  const payload = await client.listModels() as { models: Array<Record<string, unknown>> };

  assert.equal(payload.models[0]?.contextWindow, 300_000);
});
