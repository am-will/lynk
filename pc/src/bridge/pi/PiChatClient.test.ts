import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import type {
  AgentSessionEvent,
  AgentSessionRuntime
} from "@earendil-works/pi-coding-agent";
import { PiChatClient, type PiClientLike } from "./PiChatClient.js";
import type { PiModel, PiThinkingLevel } from "./PiSdkClient.js";

const model = {
  provider: "anthropic",
  id: "claude-sonnet-4-5",
  name: "Claude Sonnet 4.5",
  contextWindow: 200_000,
  reasoning: true
} as PiModel;

test("Pi chat adapter lists models and creates workspace-backed sessions", async () => {
  const fake = new FakePiClient();
  const client = new PiChatClient(undefined, fake, null, { defaultModel: "anthropic/claude-sonnet-4-5" });
  const workspaceRoot = mkdtempSync(join(tmpdir(), "lynk-pi-workspace-"));
  const workspacePath = join(workspaceRoot, "project");
  try {
    const models = await client.listModels() as { models: Array<Record<string, unknown>> };
    assert.equal(models.models[0]?.id, "anthropic/claude-sonnet-4-5");
    assert.equal(models.models[0]?.provider, "pi");
    assert.deepEqual(models.models[0]?.reasoningOptions, ["minimal", "low", "medium", "high", "xhigh"]);

    await assert.rejects(
      () => client.createSession({ workspacePath }),
      (error: unknown) => {
        const record = error as { code?: string; workspacePath?: string };
        assert.equal(record.code, "pi.workspace_not_found");
        assert.equal(record.workspacePath, workspacePath);
        return true;
      }
    );

    const created = await client.createSession({
      model: "anthropic/claude-sonnet-4-5",
      workspacePath,
      createWorkspaceIfMissing: true
    }) as Record<string, unknown>;
    assert.equal(created.key, "pi:session-1");
    assert.equal(created.workspacePath, workspacePath);
    assert.equal(created.workspaceName, "project");
    assert.equal(fake.createdRuntimes[0]?.cwd, workspacePath);
    assert.equal(existsSync(workspacePath), true);
  } finally {
    client.close();
    rmSync(workspaceRoot, { recursive: true, force: true });
  }
});

test("Pi chat adapter streams text, reasoning, tools, final usage, and steer", async () => {
  const fake = new FakePiClient();
  const client = new PiChatClient(undefined, fake, null);
  const created = await client.createSession({ model: "anthropic/claude-sonnet-4-5" }) as { key: string };
  const events: Array<{ event: string; payload: Record<string, unknown> }> = [];
  client.addEventListener((event) => events.push(event as { event: string; payload: Record<string, unknown> }));

  const result = await client.sendChat({
    sessionKey: created.key,
    message: "build it",
    thinking: "high",
    idempotencyKey: "run-1"
  });
  assert.equal(result.runId, "run-1");
  await client.steerChat({ sessionKey: created.key, runId: "run-1", message: "prefer tests" });
  fake.session.releasePrompt();
  await waitFor(() => events.some((event) => event.payload.state === "final"));

  assert.equal(fake.session.promptCalls[0]?.message, "build it");
  assert.equal(fake.session.thinkingLevels.at(-1), "high");
  assert.deepEqual(fake.session.steerCalls, ["prefer tests"]);
  assert.ok(events.some((event) => event.event === "chat" && event.payload.state === "delta" && event.payload.delta === "hello "));
  assert.ok(events.some((event) => event.event === "agent" && event.payload.type === "reasoning.delta"));
  assert.ok(events.some((event) => event.event === "agent" && event.payload.type === "tool"));
  const final = events.find((event) => event.payload.state === "final")?.payload;
  assert.equal(final?.message, "done");
  assert.deepEqual(final?.usage, { inputTokens: 3, outputTokens: 5, totalTokens: 8 });
  client.close();
});

test("Pi chat adapter canonicalizes implicit session keys before sending", async () => {
  const fake = new FakePiClient();
  const client = new PiChatClient(undefined, fake, null);
  const events: Array<{ event: string; payload: Record<string, unknown> }> = [];
  client.addEventListener((event) => events.push(event as { event: string; payload: Record<string, unknown> }));

  await assert.rejects(() => client.history("pi:pixel"), /Pi session not found/);
  await client.patchSession("pi:pixel", { model: "anthropic/claude-sonnet-4-5", thinking: "high" });
  const result = await client.sendChat({
    sessionKey: "pi:pixel",
    message: "first implicit run",
    idempotencyKey: "run-canonical"
  });

  assert.equal(result.sessionKey, "pi:session-1");
  assert.notEqual(result.sessionKey, "pi:pixel");
  assert.deepEqual(fake.createdRuntimes.at(-1), {
    cwd: "/tmp",
    model: "anthropic/claude-sonnet-4-5",
    thinkingLevel: "high"
  });
  fake.session.releasePrompt();
  await waitFor(() => events.some((event) => event.payload.state === "final"));
  client.close();
});

test("Pi chat adapter aborts active runs", async () => {
  const fake = new FakePiClient();
  const client = new PiChatClient(undefined, fake, null);
  const created = await client.createSession({}) as { key: string };
  const events: Array<{ event: string; payload: Record<string, unknown> }> = [];
  client.addEventListener((event) => events.push(event as { event: string; payload: Record<string, unknown> }));

  await client.sendChat({ sessionKey: created.key, message: "stop later", idempotencyKey: "run-2" });
  const aborted = await client.abort(created.key, "run-2") as Record<string, unknown>;

  assert.equal(aborted.status, "stopping");
  assert.equal(fake.session.abortCount, 1);
  assert.ok(events.some((event) => event.payload.state === "error" && event.payload.error === "Pi run stopped."));
  client.close();
});

test("Pi chat adapter scopes active runs by session", async () => {
  const fake = new FakePiClient();
  const client = new PiChatClient(undefined, fake, null);
  const first = await client.createSession({}) as { key: string };
  const second = await client.createSession({}) as { key: string };

  const firstRun = await client.sendChat({ sessionKey: first.key, message: "first", idempotencyKey: "run-first" });
  const secondRun = await client.sendChat({ sessionKey: second.key, message: "second", idempotencyKey: "run-second" });

  assert.equal(firstRun.sessionKey, first.key);
  assert.equal(secondRun.sessionKey, second.key);
  assert.equal(fake.sessions[0]?.promptCalls[0]?.message, "first");
  assert.equal(fake.sessions[1]?.promptCalls[0]?.message, "second");
  await assert.rejects(
    () => client.sendChat({ sessionKey: first.key, message: "blocked" }),
    /already running for this session/
  );
  fake.sessions[0]?.releasePrompt();
  fake.sessions[1]?.releasePrompt();
  await waitFor(() => fake.sessions.every((session) => session.messages.length > 0));
  client.close();
});

class FakePiClient implements PiClientLike {
  readonly sessions: FakePiSession[] = [];
  readonly createdRuntimes: Array<{ cwd?: string; model?: string; thinkingLevel?: string | null }> = [];

  get session(): FakePiSession {
    const latest = this.sessions.at(-1);
    assert.ok(latest);
    return latest;
  }

  defaultCwd(): string {
    return "/tmp";
  }

  listModels(): PiModel[] {
    return [model];
  }

  async createRuntime(options: { cwd?: string; model?: string; thinkingLevel?: string | null } = {}): Promise<AgentSessionRuntime> {
    this.createdRuntimes.push(options);
    const session = new FakePiSession(`session-${this.sessions.length + 1}`);
    this.sessions.push(session);
    session.applyOptions(options);
    return {
      cwd: options.cwd ?? this.defaultCwd(),
      session,
      switchSession: async () => undefined,
      newSession: async () => undefined,
      dispose: async () => undefined
    } as unknown as AgentSessionRuntime;
  }

  async runWithTimeout<T>(_label: string, run: () => Promise<T>): Promise<T> {
    return await run();
  }

  async abort(runtime: AgentSessionRuntime | undefined): Promise<void> {
    await runtime?.session.abort();
  }

  async close(): Promise<void> {}

  async health(): Promise<Record<string, unknown>> {
    return { ok: true };
  }

  findModel(): PiModel | undefined {
    return model;
  }

  normalizeThinkingLevel(level: string | null | undefined): PiThinkingLevel {
    return (level === "none" ? "off" : level ?? "medium") as PiThinkingLevel;
  }
}

class FakePiSession {
  constructor(readonly sessionId = "session-1") {}
  sessionName = "Pi Test Session";
  sessionFile = "/tmp/pi-session.jsonl";
  model = model;
  messages: unknown[] = [];
  readonly promptCalls: Array<{ message: string; images: unknown[] }> = [];
  readonly steerCalls: string[] = [];
  readonly thinkingLevels: string[] = [];
  abortCount = 0;
  private handlers = new Set<(event: AgentSessionEvent) => void>();
  private promptRelease: (() => void) | undefined;

  applyOptions(options: { model?: string; thinkingLevel?: string | null }): void {
    if (options.thinkingLevel) {
      this.setThinkingLevel(options.thinkingLevel as PiThinkingLevel);
    }
  }

  subscribe(handler: (event: AgentSessionEvent) => void): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async prompt(message: string, options: { images?: unknown[] } = {}): Promise<void> {
    this.promptCalls.push({ message, images: options.images ?? [] });
    await new Promise<void>((resolve) => {
      this.promptRelease = resolve;
    });
    this.emit({ type: "message_update", assistantMessageEvent: { type: "text_delta", delta: "hello " } });
    this.emit({ type: "message_update", assistantMessageEvent: { type: "thinking_delta", delta: "thinking" } });
    this.emit({ type: "tool_execution_start", toolCallId: "tool-1", toolName: "bash", args: { command: "pwd" } });
    this.emit({ type: "tool_execution_update", toolCallId: "tool-1", toolName: "bash", args: { command: "pwd" }, partialResult: "/tmp" });
    this.emit({ type: "tool_execution_end", toolCallId: "tool-1", toolName: "bash", result: "/tmp", isError: false });
    const messageRecord = { role: "assistant", content: "done", usage: { input: 3, output: 5 } };
    this.messages.push(messageRecord);
    this.emit({ type: "turn_end", message: messageRecord });
  }

  releasePrompt(): void {
    this.promptRelease?.();
  }

  async steer(message: string): Promise<void> {
    this.steerCalls.push(message);
  }

  async abort(): Promise<void> {
    this.abortCount += 1;
  }

  async setModel(nextModel: PiModel): Promise<void> {
    this.model = nextModel;
  }

  setThinkingLevel(level: PiThinkingLevel): void {
    this.thinkingLevels.push(level);
  }

  getAllTools(): Array<{ name: string; description?: string }> {
    return [{ name: "bash", description: "Run a shell command" }];
  }

  private emit(event: Record<string, unknown>): void {
    for (const handler of this.handlers) {
      handler(event as AgentSessionEvent);
    }
  }
}

async function waitFor(predicate: () => boolean, timeoutMs = 1000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.ok(predicate());
}
