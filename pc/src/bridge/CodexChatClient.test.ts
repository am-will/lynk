import assert from "node:assert/strict";
import test from "node:test";
import type { AgentRequestOptions, AgentRunResult, AgentStatusSink } from "../dispatcher/AgentClient.js";
import type { CodexAppServerClient } from "../dispatcher/CodexAppServerClient.js";
import { CodexChatClient } from "./CodexChatClient.js";

class FakeCodexAppServerClient {
  readonly createdThreads: Array<{ model?: string; baseInstructions?: string }> = [];
  readonly submitted: Array<{ text: string; options: AgentRequestOptions }> = [];
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
      finalMessage: "done"
    };
  }

  async listModels(): Promise<unknown> {
    return { models: [] };
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
  assert.deepEqual(fake.createdThreads, [{ model: "gpt-5.3-codex" }]);

  await client.sendChat({
    sessionKey: "codex:chat",
    message: "Hello",
    idempotencyKey: "run_1"
  });
  await waitFor(() => fake.submitted.length === 1);

  assert.equal(fake.createdThreads.length, 1);
  assert.equal(fake.submitted[0]?.options.threadId, "thread_1");
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
  assert.equal(fake.submitted[0]?.options.threadId, "thread_1");

  const payload = await client.listSessions() as { sessions: Array<{ key?: string; sessionId?: string }> };
  assert.deepEqual(payload.sessions.map((session) => [session.key, session.sessionId]), [
    ["codex:default", "thread_1"]
  ]);
});
