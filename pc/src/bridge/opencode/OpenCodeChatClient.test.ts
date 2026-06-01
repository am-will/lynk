import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import type { ChatAttachment } from "../../protocol/messages.js";
import { OpenCodeChatClient, normalizeOpenCodeModels } from "./OpenCodeChatClient.js";
import type { OpenCodeSessionPromptOptions } from "./OpenCodeServerClient.js";

function opencodeEvent(type: string, properties: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    payload: {
      type,
      properties
    }
  };
}

async function* streamEvents(events: unknown[]): AsyncGenerator<unknown> {
  for (const event of events) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    yield event;
  }
}

async function waitFor(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 300; attempt += 1) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.ok(predicate());
}

class FakeOpenCodeServerClient {
  readonly created: Array<{ directory?: string; title?: string; agent?: string; model?: unknown }> = [];
  readonly prompts: OpenCodeSessionPromptOptions[] = [];
  readonly aborts: Array<{ sessionId: string; directory?: string }> = [];
  readonly permissionReplies: Array<{ sessionId: string; permissionId: string; directory?: string; response: string }> = [];
  readonly subscriptions: Array<{ directory?: string; signal?: AbortSignal }> = [];
  readonly commandsRequested: Array<string | undefined> = [];
  readonly toolsRequested: Array<{ directory?: string; providerID: string; modelID: string }> = [];
  providerPayload: unknown = {
    connected: ["openai", "opencode"],
    all: [
      { id: "openai", models: { "gpt-5.5": { id: "gpt-5.5", name: "GPT 5.5", limit: { context: 200_000 } } } },
      { id: "opencode", models: { "mimo-v2.5-free": { id: "mimo-v2.5-free", name: "Mimo v2.5 Free" } } }
    ]
  };
  sessionsPayload: unknown = [];
  commandsPayload: unknown = [{ name: "init", description: "Initialize project" }];
  toolsPayload: unknown = { bash: { name: "bash", description: "Run shell commands" } };
  messagesPayload: unknown = { messages: [] };
  statusPayload: unknown = {};
  statusPayloads: unknown[] = [];
  events: unknown[] = [];
  aborted = false;
  private nextSession = 1;

  constructor(private readonly directory = "/repo") {}

  defaultDirectory(): string {
    return this.directory;
  }

  defaultAgentName(): string {
    return "build";
  }

  async providers(): Promise<unknown> {
    return this.providerPayload;
  }

  async configProviders(): Promise<unknown> {
    return this.providerPayload;
  }

  async listSessions(): Promise<unknown> {
    return this.sessionsPayload;
  }

  async createSession(options: { directory?: string; title?: string; agent?: string; model?: unknown } = {}): Promise<unknown> {
    this.created.push(options);
    return {
      id: `ses_${this.nextSession++}`,
      title: options.title,
      directory: options.directory ?? this.directory
    };
  }

  async getSession(): Promise<unknown> {
    throw new Error("missing session");
  }

  async promptAsync(options: OpenCodeSessionPromptOptions): Promise<unknown> {
    this.prompts.push(options);
    return {};
  }

  async subscribe(directory?: string, options: { signal?: AbortSignal } = {}): Promise<AsyncGenerator<unknown>> {
    this.subscriptions.push({ directory, signal: options.signal });
    return streamEvents(this.events);
  }

  async messages(): Promise<unknown> {
    if (this.aborted) {
      return { messages: [{ info: { role: "assistant" }, parts: [{ type: "text", text: "aborted" }] }] };
    }
    return this.messagesPayload;
  }

  async status(): Promise<unknown> {
    return this.aborted ? {} : this.statusPayloads.shift() ?? this.statusPayload;
  }

  async abort(sessionId: string, directory?: string): Promise<unknown> {
    this.aborted = true;
    this.aborts.push({ sessionId, directory });
    return {};
  }

  async respondToPermission(options: { sessionId: string; permissionId: string; directory?: string; response: string }): Promise<unknown> {
    this.permissionReplies.push(options);
    return {};
  }

  async listCommands(directory?: string): Promise<unknown> {
    this.commandsRequested.push(directory);
    return this.commandsPayload;
  }

  async listTools(options: { directory?: string; providerID: string; modelID: string }): Promise<unknown> {
    this.toolsRequested.push(options);
    return this.toolsPayload;
  }

  async health(): Promise<unknown> {
    return { ok: true };
  }

  async close(): Promise<void> {}
}

test("OpenCode model normalization namespaces provider/model ids", () => {
  const models = normalizeOpenCodeModels({
    connected: ["openai"],
    all: [
      { id: "openai", models: { "gpt-5.5": { id: "gpt-5.5", name: "GPT 5.5", limit: { context: 200_000 } } } },
      { id: "opencode", models: { "mimo-v2.5-free": { id: "mimo-v2.5-free", name: "Mimo" } } }
    ]
  });

  assert.deepEqual(models.map((model) => model.id), ["openai/gpt-5.5", "opencode/mimo-v2.5-free"]);
  assert.equal(models[0]?.provider, "opencode");
  assert.equal(models[0]?.contextWindow, 200_000);
  assert.equal(models[0]?.available, true);
  assert.equal(models[1]?.available, false);
});

test("OpenCode sessions, commands, and tools are normalized with workspace directory", async () => {
  const workspace = mkdtempSync(join(tmpdir(), "opencode-chat-workspace-"));
  const fake = new FakeOpenCodeServerClient(workspace);
  fake.sessionsPayload = [{
    id: "ses_existing",
    title: "Repo work",
    directory: workspace,
    time: { updated: 100 }
  }];
  const client = new OpenCodeChatClient(undefined, fake as never, null);

  try {
    const models = await client.listModels() as { models: Array<Record<string, unknown>> };
    const sessions = await client.listSessions() as { sessions: Array<Record<string, unknown>> };
    const created = await client.createSession({
      label: "New work",
      model: "openai/gpt-5.5",
      workspacePath: workspace
    }) as Record<string, unknown>;
    const commands = await client.listCommands() as { commands: Array<Record<string, unknown>> };
    const tools = await client.effectiveTools(created.key as string) as { tools: Array<Record<string, unknown>> };

    assert.equal(models.models.some((model) => model.id === "openai/gpt-5.5"), true);
    assert.equal(sessions.sessions[0]?.key, "opencode:ses_existing");
    assert.equal(sessions.sessions[0]?.workspacePath, workspace);
    assert.equal(sessions.sessions[0]?.workspaceName, workspace.substring(workspace.lastIndexOf("/") + 1));
    assert.equal(created.key, "opencode:ses_1");
    assert.equal(created.workspacePath, workspace);
    assert.equal(fake.created[0]?.directory, workspace);
    assert.equal(fake.created[0]?.title, "New work");
    assert.deepEqual(commands.commands.map((command) => command.name), ["init"]);
    assert.deepEqual(tools.tools.map((tool) => tool.name), ["bash"]);
    assert.deepEqual(fake.toolsRequested[0], { directory: workspace, providerID: "openai", modelID: "gpt-5.5" });
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("OpenCode prompts stream deltas, reasoning, tools, permissions, usage, and final events", async () => {
  const workspace = mkdtempSync(join(tmpdir(), "opencode-chat-workspace-"));
  const fake = new FakeOpenCodeServerClient(workspace);
  fake.events = [
    opencodeEvent("message.part.updated", { sessionID: "ses_1", part: { id: "reasoning_1", type: "reasoning" } }),
    opencodeEvent("message.part.delta", { sessionID: "ses_1", partID: "reasoning_1", delta: "Checking" }),
    opencodeEvent("message.part.updated", { sessionID: "ses_1", part: { id: "text_1", type: "text" } }),
    opencodeEvent("message.part.delta", { sessionID: "ses_1", partID: "text_1", delta: "lynk" }),
    opencodeEvent("session.next.tool.started", { sessionID: "ses_1", callID: "call_1", tool: "bash", command: "npm test" }),
    opencodeEvent("command.executed", { sessionID: "ses_1", messageID: "cmd_1", name: "init", arguments: "--yes" }),
    opencodeEvent("permission.asked", {
      sessionID: "ses_1",
      id: "perm_1",
      title: "Run shell command",
      type: "tool",
      pattern: ["bash"],
      metadata: { command: "npm test" }
    }),
    opencodeEvent("session.next.step.ended", { sessionID: "ses_1", tokens: { input: 2, output: 3 }, cost: 0.01 }),
    opencodeEvent("session.idle", { sessionID: "ses_1" })
  ];
  fake.messagesPayload = {
    messages: [{ info: { role: "assistant", tokens: { input: 2, output: 3 }, cost: 0.01 }, parts: [{ type: "text", text: "lynk" }] }]
  };
  fake.statusPayloads = [
    { ses_1: { type: "running" } },
    { ses_1: { type: "running" } },
    { ses_1: { type: "running" } },
    {}
  ];
  const client = new OpenCodeChatClient(undefined, fake as never, null);
  const events: Array<{ event: string; payload: Record<string, unknown> }> = [];
  client.addEventListener((event) => events.push(event as { event: string; payload: Record<string, unknown> }));

  try {
    const created = await client.createSession({ label: "Workspace", model: "openai/gpt-5.5", workspacePath: workspace }) as Record<string, unknown>;
    const result = await client.sendChat({
      sessionKey: created.key as string,
      message: "Reply with lynk",
      idempotencyKey: "run_1",
      attachments: [{
        id: "att_1",
        kind: "file",
        displayName: "note.txt",
        mimeType: "text/plain",
        sizeBytes: 4,
        contentBase64: "bHluaw=="
      } satisfies ChatAttachment]
    });
    await waitFor(() => events.some((event) => event.payload.state === "final") && events.some((event) => event.payload.eventId === "opencode_permission_perm_1"));

    const permission = events.find((event) => event.payload.eventId === "opencode_permission_perm_1")?.payload;
    assert.equal(result.sessionKey, "opencode:ses_1");
    assert.equal(fake.subscriptions[0]?.directory, workspace);
    assert.equal(fake.prompts[0]?.directory, workspace);
    assert.deepEqual(fake.prompts[0]?.model, { providerID: "openai", modelID: "gpt-5.5" });
    assert.equal(fake.prompts[0]?.attachments?.[0]?.displayName, "note.txt");
    assert.equal(events.some((event) => event.payload.state === "delta" && event.payload.delta === "lynk"), true);
    assert.equal(events.some((event) => event.payload.type === "reasoning.delta"), true);
    assert.equal(events.some((event) => event.payload.eventId === "opencode_tool_call_1"), true);
    assert.equal(events.some((event) => event.payload.eventId === "opencode_command_cmd_1"), true);
    assert.deepEqual((permission?.actions as Array<Record<string, unknown>> | undefined)?.map((action) => action.command), [
      "opencode.permission",
      "opencode.permission",
      "opencode.permission"
    ]);
    assert.equal(events.some((event) => event.payload.state === "final" && event.payload.message === "lynk"), true);
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("OpenCode permission replies and aborts use the session workspace directory", async () => {
  const workspace = mkdtempSync(join(tmpdir(), "opencode-chat-workspace-"));
  const fake = new FakeOpenCodeServerClient(workspace);
  fake.statusPayload = { ses_1: { type: "running" } };
  const client = new OpenCodeChatClient(undefined, fake as never, null);

  try {
    const created = await client.createSession({ label: "Abortable", model: "opencode/mimo-v2.5-free", workspacePath: workspace }) as Record<string, unknown>;
    await client.respondToPermission({
      sessionKey: created.key as string,
      permissionId: "perm_1",
      response: "always"
    });
    await client.sendChat({ sessionKey: created.key as string, message: "Keep running", idempotencyKey: "run_abort" });
    await waitFor(() => fake.prompts.length === 1);
    await client.abort(created.key as string, "run_abort");

    assert.deepEqual(fake.permissionReplies, [{
      sessionId: "ses_1",
      directory: workspace,
      permissionId: "perm_1",
      response: "always"
    }]);
    assert.deepEqual(fake.aborts, [{ sessionId: "ses_1", directory: workspace }]);
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});
