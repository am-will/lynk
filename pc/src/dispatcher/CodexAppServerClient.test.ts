import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { CodexAppServerClient } from "./CodexAppServerClient.js";
import type { AgentStatusSink } from "./AgentClient.js";
import { isAdapterFailure } from "../bridge/harness/AdapterFailure.js";

const sink: AgentStatusSink = {
  info: () => undefined,
  working: () => undefined,
  tool: () => undefined,
  done: () => undefined,
  error: () => undefined
};

test("Codex app-server resumes stored threads before starting turns", async () => {
  const dir = await mkdtemp(join(tmpdir(), "codex-app-server-test-"));
  const logPath = join(dir, "requests.jsonl");
  const scriptPath = join(dir, "fake-app-server.mjs");
  await writeFile(scriptPath, fakeAppServerScript());
  const previousLogPath = process.env.CODEX_FAKE_LOG;
  process.env.CODEX_FAKE_LOG = logPath;
  const client = new CodexAppServerClient(undefined, `"${process.execPath}" "${scriptPath}"`, dir);
  try {
    const result = await client.submitUserRequest("Continue", sink, {
      threadId: "stored_thread",
      model: "gpt-5.3-codex"
    });

    assert.equal(result.threadId, "stored_thread");
    assert.equal(result.finalMessage, "done");
    assert.deepEqual(result.usage, {
      inputTokens: 10,
      outputTokens: 2,
      totalTokens: 12,
      contextTokens: 258400
    });
    const methods = (await readFile(logPath, "utf8"))
      .trim()
      .split(/\n/)
      .map((line) => JSON.parse(line) as { method: string; params?: Record<string, unknown> });
    assert.deepEqual(methods.map((entry) => entry.method), ["initialize", "thread/resume", "turn/start"]);
    assert.equal(methods[1]?.params?.threadId, "stored_thread");
    assert.equal(methods[2]?.params?.threadId, "stored_thread");
  } finally {
    await client.close();
    if (previousLogPath === undefined) {
      delete process.env.CODEX_FAKE_LOG;
    } else {
      process.env.CODEX_FAKE_LOG = previousLogPath;
    }
  }
});

test("Codex app-server can bind instructions to new threads", async () => {
  const dir = await mkdtemp(join(tmpdir(), "codex-app-server-test-"));
  const logPath = join(dir, "requests.jsonl");
  const scriptPath = join(dir, "fake-app-server.mjs");
  await writeFile(scriptPath, fakeAppServerScript());
  const previousLogPath = process.env.CODEX_FAKE_LOG;
  process.env.CODEX_FAKE_LOG = logPath;
  const client = new CodexAppServerClient(undefined, `"${process.execPath}" "${scriptPath}"`, dir);
  try {
    const result = await client.submitUserRequest("Hello", sink, {
      model: "gpt-5.3-codex",
      systemPrompt: "Base instructions",
      useSessionInstructions: true
    });

    assert.equal(result.threadId, "thread_1");
    const methods = (await readFile(logPath, "utf8"))
      .trim()
      .split(/\n/)
      .map((line) => JSON.parse(line) as { method: string; params?: Record<string, unknown> });
    assert.deepEqual(methods.map((entry) => entry.method), ["initialize", "thread/start", "turn/start"]);
    assert.equal(methods[1]?.params?.baseInstructions, "Base instructions");
    assert.deepEqual(methods[2]?.params?.input, [{ type: "text", text: "Hello" }]);
  } finally {
    await client.close();
    if (previousLogPath === undefined) {
      delete process.env.CODEX_FAKE_LOG;
    } else {
      process.env.CODEX_FAKE_LOG = previousLogPath;
    }
  }
});

test("Codex app-server includes image attachments in turn input", async () => {
  const dir = await mkdtemp(join(tmpdir(), "codex-app-server-test-"));
  const logPath = join(dir, "requests.jsonl");
  const scriptPath = join(dir, "fake-app-server.mjs");
  await writeFile(scriptPath, fakeAppServerScript());
  const previousLogPath = process.env.CODEX_FAKE_LOG;
  process.env.CODEX_FAKE_LOG = logPath;
  const client = new CodexAppServerClient(undefined, `"${process.execPath}" "${scriptPath}"`, dir);
  try {
    await client.submitUserRequest("Review this", sink, {
      model: "gpt-5.3-codex",
      useSessionInstructions: true,
      attachments: [{
        id: "att_1",
        kind: "image",
        displayName: "screenshot.png",
        mimeType: "image/png",
        sizeBytes: 5,
        contentBase64: "aGVsbG8="
      }]
    });

    const methods = (await readFile(logPath, "utf8"))
      .trim()
      .split(/\n/)
      .map((line) => JSON.parse(line) as { method: string; params?: Record<string, unknown> });
    assert.deepEqual(methods[2]?.params?.input, [
      { type: "text", text: "Review this" },
      { type: "image", url: "data:image/png;base64,aGVsbG8=" }
    ]);
  } finally {
    await client.close();
    if (previousLogPath === undefined) {
      delete process.env.CODEX_FAKE_LOG;
    } else {
      process.env.CODEX_FAKE_LOG = previousLogPath;
    }
  }
});

test("Codex app-server shares one startup generation across concurrent RPCs", async () => {
  const dir = await mkdtemp(join(tmpdir(), "codex-lifecycle-test-"));
  const logPath = join(dir, "requests.jsonl");
  const scriptPath = join(dir, "lifecycle-app-server.mjs");
  await writeFile(scriptPath, lifecycleAppServerScript());
  const previousLogPath = process.env.CODEX_FAKE_LOG;
  process.env.CODEX_FAKE_LOG = logPath;
  const client = new CodexAppServerClient(undefined, `"${process.execPath}" "${scriptPath}"`, dir);
  try {
    await Promise.all([client.listModels(), client.listThreads()]);
    const methods = (await readFile(logPath, "utf8")).trim().split(/\n/);
    assert.equal(methods.filter((method) => method === "initialize").length, 1);
    assert.equal(methods.filter((method) => method === "model/list").length, 1);
    assert.equal(methods.filter((method) => method === "thread/list").length, 1);
  } finally {
    await client.close();
    restoreEnv("CODEX_FAKE_LOG", previousLogPath);
  }
});

test("Codex RPC timeout tears down the hung generation and permits clean restart", async () => {
  const dir = await mkdtemp(join(tmpdir(), "codex-timeout-test-"));
  const scriptPath = join(dir, "timeout-app-server.mjs");
  const counterPath = join(dir, "starts.txt");
  await writeFile(scriptPath, restartableAppServerScript("hang"));
  const previousCounter = process.env.CODEX_FAKE_COUNTER;
  const previousTimeout = process.env.CODEX_RPC_TIMEOUT_MS;
  process.env.CODEX_FAKE_COUNTER = counterPath;
  process.env.CODEX_RPC_TIMEOUT_MS = "150";
  const client = new CodexAppServerClient(undefined, `"${process.execPath}" "${scriptPath}"`, dir);
  try {
    await assert.rejects(client.listModels(), (error) => isAdapterFailure(error, "timeout"));
    const result = await client.listModels() as { models?: unknown[] };
    assert.deepEqual(result.models, []);
    assert.equal(Number(await readFile(counterPath, "utf8")), 2);
  } finally {
    await client.close();
    restoreEnv("CODEX_FAKE_COUNTER", previousCounter);
    restoreEnv("CODEX_RPC_TIMEOUT_MS", previousTimeout);
  }
});

test("Codex child exit rejects pending RPC and stale callbacks cannot poison restart", async () => {
  const dir = await mkdtemp(join(tmpdir(), "codex-exit-test-"));
  const scriptPath = join(dir, "exit-app-server.mjs");
  const counterPath = join(dir, "starts.txt");
  await writeFile(scriptPath, restartableAppServerScript("exit"));
  const previousCounter = process.env.CODEX_FAKE_COUNTER;
  process.env.CODEX_FAKE_COUNTER = counterPath;
  const client = new CodexAppServerClient(undefined, `"${process.execPath}" "${scriptPath}"`, dir);
  try {
    await assert.rejects(client.listModels(), (error) => isAdapterFailure(error, "unavailable"));
    const result = await client.listModels() as { models?: unknown[] };
    assert.deepEqual(result.models, []);
    assert.equal(Number(await readFile(counterPath, "utf8")), 2);
  } finally {
    await client.close();
    restoreEnv("CODEX_FAKE_COUNTER", previousCounter);
  }
});

function fakeAppServerScript(): string {
  return `
import { appendFileSync } from "node:fs";
import { createInterface } from "node:readline";

const logPath = process.env.CODEX_FAKE_LOG;
const lines = createInterface({ input: process.stdin });

function write(message) {
  process.stdout.write(JSON.stringify(message) + "\\n");
}

for await (const line of lines) {
  if (!line.trim()) continue;
  const message = JSON.parse(line);
  if (message.id) {
    appendFileSync(logPath, JSON.stringify({ method: message.method, params: message.params }) + "\\n");
  }
  if (message.method === "initialize") {
    write({ id: message.id, result: {} });
  } else if (message.method === "thread/start") {
    write({ id: message.id, result: { thread: { id: "thread_1" } } });
  } else if (message.method === "thread/resume") {
    write({ id: message.id, result: { thread: { id: message.params.threadId } } });
  } else if (message.method === "turn/start") {
    write({ id: message.id, result: { turn: { id: "turn_1" } } });
    setTimeout(() => {
      write({ method: "item/agentMessage/delta", params: { delta: "done" } });
      write({
        method: "thread/tokenUsage/updated",
        params: {
          threadId: message.params.threadId,
          turnId: "turn_1",
          tokenUsage: {
            modelContextWindow: 258400,
            total: {
              inputTokens: 10,
              outputTokens: 2,
              totalTokens: 12
            }
          }
        }
      });
      write({ method: "turn/completed", params: { turn: { id: "turn_1", status: "completed" } } });
    }, 20);
  } else if (message.method === "initialized") {
    // notification
  }
}
`;
}

function lifecycleAppServerScript(): string {
  return `
import { appendFileSync } from "node:fs";
import { createInterface } from "node:readline";
const lines = createInterface({ input: process.stdin });
const write = (message) => process.stdout.write(JSON.stringify(message) + "\\n");
for await (const line of lines) {
  const message = JSON.parse(line);
  if (!message.id) continue;
  appendFileSync(process.env.CODEX_FAKE_LOG, message.method + "\\n");
  if (message.method === "initialize") write({ id: message.id, result: {} });
  if (message.method === "model/list") write({ id: message.id, result: { models: [] } });
  if (message.method === "thread/list") write({ id: message.id, result: { data: [] } });
}
`;
}

function restartableAppServerScript(firstBehavior: "hang" | "exit"): string {
  return `
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";
const counterPath = process.env.CODEX_FAKE_COUNTER;
const start = existsSync(counterPath) ? Number(readFileSync(counterPath, "utf8")) + 1 : 1;
writeFileSync(counterPath, String(start));
const lines = createInterface({ input: process.stdin });
const write = (message) => process.stdout.write(JSON.stringify(message) + "\\n");
for await (const line of lines) {
  const message = JSON.parse(line);
  if (message.method === "initialize") write({ id: message.id, result: {} });
  if (message.method === "model/list") {
    if (start === 1 && ${JSON.stringify(firstBehavior)} === "exit") process.exit(23);
    if (start === 1 && ${JSON.stringify(firstBehavior)} === "hang") continue;
    write({ id: message.id, result: { models: [] } });
  }
}
`;
}

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) delete process.env[name];
  else process.env[name] = value;
}
