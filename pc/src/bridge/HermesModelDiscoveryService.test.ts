import assert from "node:assert/strict";
import test from "node:test";
import type { ChatModelOption } from "../protocol/messages.js";
import { HermesModelDiscoveryService } from "./HermesModelDiscoveryService.js";

const models: ChatModelOption[] = [{ id: "hermes-model", label: "Hermes model" }];

test("Hermes discovery is single-flight and cached until TTL", async () => {
  let now = 1_000;
  let calls = 0;
  let resolve!: (value: ChatModelOption[]) => void;
  const runner = () => {
    calls += 1;
    return new Promise<ChatModelOption[]>((done) => { resolve = done; });
  };
  const service = new HermesModelDiscoveryService(runner, () => now, 100);

  const first = service.get("default");
  const concurrent = service.get("default");
  assert.equal(calls, 1);
  resolve(models);
  assert.equal((await first).status, "fresh");
  assert.equal((await concurrent).status, "fresh");

  await service.get("default");
  assert.equal(calls, 1);
  now += 101;
  const expired = service.get("default");
  assert.equal(calls, 2);
  resolve(models);
  await expired;
});

test("Hermes discovery exposes stale and unavailable states and supports invalidation", async () => {
  let fail = false;
  let calls = 0;
  const service = new HermesModelDiscoveryService(async () => {
    calls += 1;
    if (fail) throw new Error("offline");
    return models;
  }, () => calls * 1_000, 1);

  assert.equal((await service.get("default")).status, "fresh");
  fail = true;
  assert.equal((await service.get("default", { force: true })).status, "stale");
  service.invalidate();
  assert.equal((await service.get("default")).status, "unavailable");
});

test("Hermes asynchronous discovery yields to the event loop", async () => {
  let eventLoopTicked = false;
  const service = new HermesModelDiscoveryService(async () => {
    await new Promise<void>((resolve) => setImmediate(() => {
      eventLoopTicked = true;
      resolve();
    }));
    return models;
  });

  const result = await service.get("default");

  assert.equal(eventLoopTicked, true);
  assert.equal(result.status, "fresh");
});
