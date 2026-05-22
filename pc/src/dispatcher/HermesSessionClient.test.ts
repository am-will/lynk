import assert from "node:assert/strict";
import test from "node:test";
import { HermesSessionClient, type HermesSessionClientConfig } from "./HermesSessionClient.js";
import type { HermesApiClient, HermesSseEvent } from "./HermesApiClient.js";

const config: HermesSessionClientConfig = {
  apiBaseUrl: "http://127.0.0.1:8642/v1",
  apiKey: "test-key",
  model: "hermes-agent",
  defaultSessionId: "hermes-agent",
  runTimeoutMs: 10_000
};

class Deferred<T> {
  readonly promise: Promise<T>;
  resolve!: (value: T) => void;
  reject!: (error: Error) => void;

  constructor() {
    this.promise = new Promise<T>((resolve, reject) => {
      this.resolve = resolve;
      this.reject = reject;
    });
  }
}

class FakeHermesApi {
  readonly createRunCalls: Array<{ input: string; sessionId: string; model?: string; idempotencyKey?: string }> = [];
  events: HermesSseEvent[] = [];
  streamBlocker?: Deferred<void>;
  streaming = false;
  private nextRun = 1;

  async createRun(options: { input: string; sessionId: string; model?: string; idempotencyKey?: string }): Promise<{ runId: string; sessionId: string }> {
    this.createRunCalls.push(options);
    return { runId: `run_${this.nextRun++}`, sessionId: options.sessionId };
  }

  async streamRunEvents(_runId: string, onEvent: (event: HermesSseEvent) => void, signal?: AbortSignal): Promise<void> {
    this.streaming = true;
    for (const event of this.events) {
      if (signal?.aborted) {
        return;
      }
      onEvent(event);
    }
    if (this.streamBlocker) {
      await this.streamBlocker.promise;
    }
  }

  async getRun(runId: string): Promise<{ runId: string; sessionId: string; raw: Record<string, unknown> }> {
    return {
      runId,
      sessionId: this.createRunCalls[0]?.sessionId ?? config.defaultSessionId,
      raw: { status: "completed" }
    };
  }

  async stopRun(): Promise<void> {}
}

const sink = {
  info() {},
  working() {},
  tool() {},
  done() {},
  error() {}
};

async function waitFor(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
  assert.ok(predicate());
}

test("Hermes dispatcher accumulates streamed deltas into final output", async () => {
  const api = new FakeHermesApi();
  api.events = [
    { event: "message.delta", data: { delta: "Hello " }, raw: "" },
    { event: "message.delta", data: { delta: "world" }, raw: "" }
  ];
  const client = new HermesSessionClient(config, undefined, api as unknown as HermesApiClient);

  const result = await client.submitUserRequest("Say hi", sink);

  assert.equal(result.finalMessage, "Hello world");
});

test("Hermes steering uses the active session id", async () => {
  const api = new FakeHermesApi();
  api.streamBlocker = new Deferred<void>();
  const client = new HermesSessionClient(config, undefined, api as unknown as HermesApiClient);

  const run = client.submitUserRequest("Continue project work", sink, { deviceId: "pixel" });
  await waitFor(() => api.streaming);
  await client.steer("Narrow the scope");
  api.streamBlocker.resolve();
  await run;

  assert.equal(api.createRunCalls[0]?.sessionId, "hermes-agent-pixel");
  assert.equal(api.createRunCalls[1]?.sessionId, "hermes-agent-pixel");
});
