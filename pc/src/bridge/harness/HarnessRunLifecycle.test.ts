import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryHarnessSessionStore } from "./InMemoryHarnessSessionStore.js";
import { HarnessRunLifecycle } from "./HarnessRunLifecycle.js";

test("HarnessRunLifecycle enforces single-run ownership and cleanup", () => {
  const sessions = sessionStore("opencode");
  const lifecycle = new HarnessRunLifecycle(sessions, {
    concurrency: "single",
    busyMessage: "busy"
  });
  const first = sessions.ensureSession("opencode:first");
  const second = sessions.ensureSession("opencode:second");
  let cleanupCount = 0;

  const active = lifecycle.start(first, "run-1", { abort: true }, () => {
    cleanupCount += 1;
  });

  assert.equal(first.activeRunId, "run-1");
  assert.equal(lifecycle.activeByRun(), active);
  assert.throws(() => lifecycle.start(second, "run-2", {}), /busy/);

  lifecycle.clear(active);
  lifecycle.clear(active);

  assert.equal(first.activeRunId, null);
  assert.equal(cleanupCount, 1);
  assert.equal(lifecycle.activeByRun(), undefined);
});

test("HarnessRunLifecycle scopes per-session runs independently", () => {
  const sessions = sessionStore("pi");
  const lifecycle = new HarnessRunLifecycle(sessions, {
    concurrency: "per-session",
    busyMessage: "busy for session"
  });
  const first = sessions.ensureSession("pi:first");
  const second = sessions.ensureSession("pi:second");

  const firstRun = lifecycle.start(first, "run-first", {});
  const secondRun = lifecycle.start(second, "run-second", {});

  assert.equal(lifecycle.activeFor(first.key), firstRun);
  assert.equal(lifecycle.activeFor(second.key), secondRun);
  assert.deepEqual(lifecycle.active(), [firstRun, secondRun]);
  assert.equal(lifecycle.activeFor(first.key, "run-second"), undefined);
  assert.throws(() => lifecycle.start(first, "run-third", {}), /busy for session/);

  lifecycle.close();

  assert.equal(first.activeRunId, null);
  assert.equal(second.activeRunId, null);
  assert.deepEqual(lifecycle.active(), []);
});

test("HarnessRunLifecycle isolates identical run IDs across sessions", () => {
  const sessions = sessionStore("pi");
  const lifecycle = new HarnessRunLifecycle(sessions, {
    concurrency: "per-session",
    busyMessage: "busy for session"
  });
  const first = sessions.ensureSession("pi:first");
  const second = sessions.ensureSession("pi:second");
  const firstRun = lifecycle.start(first, "shared-run", { owner: "first" });
  const secondRun = lifecycle.start(second, "shared-run", { owner: "second" });

  assert.equal(lifecycle.activeFor(first.key, "shared-run"), firstRun);
  assert.equal(lifecycle.activeFor(second.key, "shared-run"), secondRun);
  lifecycle.clear(firstRun);
  assert.equal(first.activeRunId, null);
  assert.equal(lifecycle.activeFor(second.key, "shared-run"), secondRun);
  lifecycle.clear(secondRun);
  assert.equal(second.activeRunId, null);
});

function sessionStore(harnessId: "opencode" | "pi"): InMemoryHarnessSessionStore {
  return new InMemoryHarnessSessionStore(harnessId, {
    defaultModel: "test-model",
    modelProvider: harnessId
  });
}
