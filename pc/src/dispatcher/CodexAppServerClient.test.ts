import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { CodexAppServerClient } from "./CodexAppServerClient.js";
import type { AgentStatusSink } from "./AgentClient.js";

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
