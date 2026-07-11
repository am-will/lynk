import assert from "node:assert/strict";
import test from "node:test";
import { AdapterFailure, isAdapterFailure, withAdapterDeadline } from "./AdapterFailure.js";

test("adapter deadline distinguishes timeout from cancellation", async () => {
  await assert.rejects(
    withAdapterDeadline(new Promise<never>(() => undefined), {
      timeoutMs: 5,
      harnessId: "codex",
      operation: "thread/read"
    }),
    (error) => isAdapterFailure(error, "timeout") && error.harnessId === "codex"
  );

  const controller = new AbortController();
  const pending = withAdapterDeadline(new Promise<never>(() => undefined), {
    timeoutMs: 1_000,
    operation: "run",
    signal: controller.signal
  });
  controller.abort();
  await assert.rejects(pending, (error) => isAdapterFailure(error, "cancelled"));
});

test("adapter failures preserve stable structured codes", () => {
  const failure = new AdapterFailure("auth", "Hermes rejected credentials", {
    harnessId: "hermes",
    operation: "history"
  });

  assert.equal(failure.code, "auth");
  assert.equal(failure.harnessId, "hermes");
  assert.equal(failure.operation, "history");
});
