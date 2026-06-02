import assert from "node:assert/strict";
import test from "node:test";
import { HermesConfigRunsClient } from "./HermesConfigRunsClient.js";
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
