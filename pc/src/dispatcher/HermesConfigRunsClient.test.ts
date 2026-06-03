import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createHermesConfigRunsClient, HermesConfigRunsClient } from "./HermesConfigRunsClient.js";
import type { FetchLike, HermesSseEvent } from "./HermesApiClient.js";
import { HermesRunDriver } from "./HermesRunDriver.js";

function sseResponse(chunks: unknown[]): Response {
  const encoder = new TextEncoder();
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(`data: ${JSON.stringify(chunk)}\n\n`));
      }
      controller.enqueue(encoder.encode("data: [DONE]\n\n"));
      controller.close();
    }
  }), {
    status: 200,
    headers: { "Content-Type": "text/event-stream" }
  });
}

async function withHermesConfigPath<T>(configYaml: string, run: () => Promise<T>): Promise<T> {
  const previousConfigPath = process.env.HERMES_CONFIG_PATH;
  const dir = mkdtempSync(join(tmpdir(), "lynk-hermes-config-"));
  const configPath = join(dir, "profile-config.yaml");
  writeFileSync(configPath, configYaml);
  try {
    process.env.HERMES_CONFIG_PATH = configPath;
    return await run();
  } finally {
    if (previousConfigPath === undefined) {
      delete process.env.HERMES_CONFIG_PATH;
    } else {
      process.env.HERMES_CONFIG_PATH = previousConfigPath;
    }
    rmSync(dir, { recursive: true, force: true });
  }
}

test("Hermes config runs client streams OpenAI-compatible deltas", async () => {
  const requests: Array<{ url: string; body?: unknown }> = [];
  const fetchFn: FetchLike = async (input, init) => {
    const url = String(input);
    requests.push({
      url,
      body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined
    });
    if (url.endsWith("/models")) {
      return new Response(JSON.stringify({ data: [{ id: "MiniMax-M2.7" }] }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    if (url.endsWith("/chat/completions")) {
      return sseResponse([
        { choices: [{ delta: { content: "hello" } }] },
        { choices: [{ delta: { content: " world" } }] }
      ]);
    }
    return new Response("not found", { status: 404 });
  };
  const client = new HermesConfigRunsClient({
    provider: "local-minimax",
    model: "MiniMax-M2.7",
    baseUrl: "http://127.0.0.1:8009/v1",
    apiKey: "test-key",
    contextWindow: 196608
  }, fetchFn);

  assert.deepEqual(await client.health(), {
    ok: true,
    status: "ok",
    mode: "local-openai-stream",
    provider: "local-minimax",
    model: "MiniMax-M2.7",
    message: "Using Hermes config OpenAI-compatible provider for streaming runs."
  });

  const created = await client.createRun({
    input: "Say hello",
    sessionId: "session",
    model: "local-minimax:MiniMax-M2.7",
    instructions: "Be concise.",
    idempotencyKey: "run_1"
  });
  const events: HermesSseEvent[] = [];
  await client.streamRunEvents(created.runId, (event) => events.push(event));
  const status = await client.getRun(created.runId);

  assert.deepEqual(events.filter((event) => event.event === "message").map((event) => (event.data as { delta: string }).delta), ["hello", " world"]);
  assert.equal(status.status, "completed");
  assert.deepEqual(status.output, { text: "hello world" });
  const chatRequestBody = requests.find((request) => request.url.endsWith("/chat/completions"))?.body as Record<string, unknown> | undefined;
  assert.equal(chatRequestBody?.model, "MiniMax-M2.7");
});

test("Hermes run driver maps config adapter deltas into accumulated output", async () => {
  const fetchFn: FetchLike = async (input) => {
    const url = String(input);
    if (url.endsWith("/chat/completions")) {
      return sseResponse([
        { choices: [{ delta: { content: "first" } }] },
        { choices: [{ delta: { content: " second" } }] }
      ]);
    }
    return new Response(JSON.stringify({ data: [{ id: "MiniMax-M2.7" }] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };
  const client = new HermesConfigRunsClient({
    provider: "local-minimax",
    model: "MiniMax-M2.7",
    baseUrl: "http://127.0.0.1:8009/v1"
  }, fetchFn);
  const driver = new HermesRunDriver(client, 10_000);
  const active = await driver.createRun({
    input: "Say two chunks",
    sessionId: "session",
    idempotencyKey: "run_driver"
  });
  const deltas: string[] = [];

  const result = await driver.streamRun(active, (event) => {
    if (event.type === "delta") {
      deltas.push(event.delta);
    }
  });

  assert.deepEqual(deltas, ["first", " second"]);
  assert.equal(result.finalText, "first second");
});

test("Hermes config runs client only supports its configured provider namespace", () => {
  const client = new HermesConfigRunsClient({
    provider: "local-minimax",
    model: "MiniMax-M2.7",
    baseUrl: "http://127.0.0.1:8009/v1"
  });

  assert.equal(client.supportsModel(undefined), true);
  assert.equal(client.supportsModel("MiniMax-M2.7"), true);
  assert.equal(client.supportsModel("local-minimax:MiniMax-M2.7"), true);
  assert.equal(client.supportsModel("anthropic:claude-sonnet-4-6"), false);
  assert.equal(client.supportsModel("anthropic:claude-opus-4-8"), false);
  assert.equal(client.supportsModel("some-other-model"), false);
});

test("Hermes config runs client reads HERMES_CONFIG_PATH profiles", async () => {
  const requestedUrls: string[] = [];
  await withHermesConfigPath([
    "model:",
    "  provider: profile-minimax",
    "  default: Profile-M2",
    "providers:",
    "  profile-minimax:",
    "    api_mode: chat_completions",
    "    base_url: http://127.0.0.1:8999/v1"
  ].join("\n"), async () => {
    const fetchFn: FetchLike = async (input) => {
      requestedUrls.push(String(input));
      return new Response(JSON.stringify({ data: [{ id: "Profile-M2" }] }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    };
    const client = createHermesConfigRunsClient("fallback-model", fetchFn);

    assert.ok(client);
    assert.equal((await client.health() as { provider?: string; model?: string }).provider, "profile-minimax");
  });

  assert.deepEqual(requestedUrls, ["http://127.0.0.1:8999/v1/models"]);
});
