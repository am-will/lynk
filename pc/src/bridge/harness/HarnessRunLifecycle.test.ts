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

  assert.equal(active.phase, "running");
  assert.equal(first.activeRunId, "run-1");
  assert.equal(lifecycle.activeByRun(), active);
  assert.throws(() => lifecycle.start(second, "run-2", {}), /busy/);

  lifecycle.clear(active);
  lifecycle.clear(active);

  assert.equal(first.activeRunId, null);
  assert.equal(cleanupCount, 1);
  assert.equal(lifecycle.activeByRun(), undefined);
});

test("HarnessRunLifecycle reserves starting synchronously and rolls failed starts back", () => {
  const lifecycle = new HarnessRunLifecycle<AbortController, { requestId: string }>(undefined, {
    concurrency: "per-session",
    busyMessage: "busy"
  });
  const reservation = lifecycle.reserve("openclaw:chat", { requestId: "request-1" });

  assert.deepEqual(lifecycle.stateFor("openclaw:chat"), {
    phase: "starting",
    reservationId: reservation.reservationId,
    sessionKey: "openclaw:chat",
    metadata: { requestId: "request-1" }
  });
  assert.equal(lifecycle.canEnterHarness(reservation), true);
  assert.throws(() => lifecycle.reserve("openclaw:chat", { requestId: "request-2" }), /busy/);
  assert.equal(lifecycle.rollback(reservation), true);
  assert.equal(lifecycle.rollback(reservation), false);
  assert.deepEqual(lifecycle.stateFor("openclaw:chat"), { phase: "idle", sessionKey: "openclaw:chat" });
});

test("HarnessRunLifecycle promotes, stops, and settles each reservation once", () => {
  const lifecycle = new HarnessRunLifecycle<{ abort: boolean }, { taskKind: "general" }>(undefined, {
    concurrency: "per-session",
    busyMessage: "busy"
  });
  const reservation = lifecycle.reserve("openclaw:chat", { taskKind: "general" });
  const promotion = lifecycle.promote(reservation, "openclaw:canonical", "run-1", { abort: true });

  assert.equal(promotion.stopRequested, false);
  assert.equal(promotion.run.phase, "running");
  assert.deepEqual(lifecycle.stateFor("openclaw:chat"), { phase: "idle", sessionKey: "openclaw:chat" });
  assert.equal(lifecycle.activeFor("openclaw:canonical", "run-1"), promotion.run);

  const stopping = lifecycle.requestStop("openclaw:canonical", "user stop", "run-1");
  assert.equal(stopping.phase, "stopping");
  assert.equal(lifecycle.settle("openclaw:canonical", "run-1"), true);
  assert.equal(lifecycle.settle("openclaw:canonical", "run-1"), false);
});

test("HarnessRunLifecycle preserves stop requests made before a run id exists", () => {
  const lifecycle = new HarnessRunLifecycle<{ abort: boolean }>(undefined, {
    concurrency: "per-session",
    busyMessage: "busy"
  });
  const reservation = lifecycle.reserve("openclaw:chat");

  const stopping = lifecycle.requestStop("openclaw:chat", "stop during start");
  assert.equal(stopping.phase, "stopping");
  assert.equal(stopping.runId, null);
  assert.equal(lifecycle.canEnterHarness(reservation), false);

  const promotion = lifecycle.promote(reservation, "openclaw:chat", "run-1", { abort: true });
  assert.equal(promotion.stopRequested, true);
  assert.equal(promotion.run.phase, "stopping");
  assert.equal(lifecycle.settle("openclaw:chat", "run-1"), true);
});

test("HarnessRunLifecycle isolates reservations by session", () => {
  const lifecycle = new HarnessRunLifecycle<void>(undefined, {
    concurrency: "per-session",
    busyMessage: "busy"
  });
  const first = lifecycle.reserve("session:first");
  const second = lifecycle.reserve("session:second");

  assert.equal(lifecycle.stateForReservation(first)?.phase, "starting");
  assert.equal(lifecycle.stateForReservation(second)?.phase, "starting");
  assert.equal(lifecycle.rollback(first), true);
  assert.equal(lifecycle.stateForReservation(second)?.phase, "starting");
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
