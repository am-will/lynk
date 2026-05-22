import assert from "node:assert/strict";
import test from "node:test";
import type { AgentRequestOptions, AgentRunResult, AgentStatusSink } from "../dispatcher/AgentClient.js";
import { normalizeCodexUsage } from "../dispatcher/CodexAppServerClient.js";
import type { CodexAppServerClient } from "../dispatcher/CodexAppServerClient.js";
import { CodexChatClient } from "./CodexChatClient.js";

class FakeCodexAppServerClient {
  readonly createdThreads: Array<{ model?: string; baseInstructions?: string }> = [];
  readonly submitted: Array<{ text: string; options: AgentRequestOptions }> = [];
  modelsPayload: unknown = { models: [] };
  capabilitiesPayload: unknown = undefined;
  resultUsage?: Record<string, unknown>;
  private nextThread = 1;

  async createThread(options: { model?: string; baseInstructions?: string } = {}): Promise<string> {
    this.createdThreads.push(options);
    return `thread_${this.nextThread++}`;
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

  async readModelProviderCapabilities(): Promise<unknown> {
    return this.capabilitiesPayload;
  }

  async interrupt(): Promise<void> {}

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

  assert.equal(created.key, "codex:chat");
  assert.equal(created.sessionId, "thread_1");
  assert.equal(fake.createdThreads[0]?.model, "gpt-5.3-codex");
  assert.match(fake.createdThreads[0]?.baseInstructions ?? "", /Phone-control policy/);

  await client.sendChat({
    sessionKey: "codex:chat",
    message: "Hello",
    idempotencyKey: "run_1"
  });
  await waitFor(() => fake.submitted.length === 1);

  assert.equal(fake.createdThreads.length, 1);
  assert.equal(fake.submitted[0]?.options.threadId, "thread_1");
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
  assert.match(fake.createdThreads[0]?.baseInstructions ?? "", /Phone-control policy/);
  assert.equal(fake.submitted[0]?.options.threadId, "thread_1");
  assert.equal(fake.submitted[0]?.options.useSessionInstructions, true);
  assert.equal(fake.submitted[0]?.text, "Hello");

  const payload = await client.listSessions() as { sessions: Array<{ key?: string; sessionId?: string }> };
  assert.deepEqual(payload.sessions.map((session) => [session.key, session.sessionId]), [
    ["codex:default", "thread_1"]
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
