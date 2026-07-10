import assert from "node:assert/strict";
import test from "node:test";
import { createDefaultDevinAuthProbeRunner, probeDevinAuthStatus, type DevinAuthProbeRunner, type ProbeDevinAuthStatusOptions } from "./DevinAuthProbe.js";
import type { CommandResolution } from "./CommandDiscovery.js";

function fakeRunner(result: { exitCode: number | null; signal: NodeJS.Signals | null } | Error): DevinAuthProbeRunner {
  return {
    run: async () => {
      if (result instanceof Error) {
        throw result;
      }
      return result;
    }
  };
}

function fakeResolveCommand(resolution: Partial<CommandResolution>): (command: string) => CommandResolution {
  return () => ({
    command: resolution.command ?? "devin acp",
    executable: resolution.executable ?? "devin",
    resolvedPath: resolution.resolvedPath,
    available: resolution.available ?? Boolean(resolution.resolvedPath)
  });
}

test("probeDevinAuthStatus reports authenticated on exit code zero", async () => {
  const result = await probeDevinAuthStatus({
    command: "devin acp",
    runner: fakeRunner({ exitCode: 0, signal: null }),
    resolveCommand: fakeResolveCommand({ resolvedPath: "/opt/devin" })
  });

  assert.deepEqual(result, { status: "authenticated", path: "/opt/devin" });
});

test("probeDevinAuthStatus reports not authenticated on non-zero exit", async () => {
  const result = await probeDevinAuthStatus({
    command: "devin acp",
    runner: fakeRunner({ exitCode: 1, signal: null }),
    resolveCommand: fakeResolveCommand({ resolvedPath: "/opt/devin" })
  });

  assert.deepEqual(result, { status: "not_authenticated", path: "/opt/devin" });
});

test("probeDevinAuthStatus reports not installed when command is missing", async () => {
  const result = await probeDevinAuthStatus({
    command: "devin acp",
    runner: fakeRunner({ exitCode: 0, signal: null }),
    resolveCommand: fakeResolveCommand({ resolvedPath: undefined, available: false })
  });

  assert.deepEqual(result, { status: "not_installed" });
});

test("probeDevinAuthStatus reports timeout on timeout error", async () => {
  const result = await probeDevinAuthStatus({
    command: "devin acp",
    runner: fakeRunner(new Error("timeout")),
    resolveCommand: fakeResolveCommand({ resolvedPath: "/opt/devin" })
  });

  assert.deepEqual(result, { status: "timeout", path: "/opt/devin" });
});

test("probeDevinAuthStatus reports spawn error on launch failure", async () => {
  const result = await probeDevinAuthStatus({
    command: "devin acp",
    runner: fakeRunner(new Error("spawn EACCES")),
    resolveCommand: fakeResolveCommand({ resolvedPath: "/opt/devin" })
  });

  assert.deepEqual(result, { status: "spawn_error", path: "/opt/devin" });
});

test("probeDevinAuthStatus never includes auth output", async () => {
  const calls: unknown[] = [];
  const capturingRunner: DevinAuthProbeRunner = {
    run: async (executable, args) => {
      calls.push({ executable, args });
      return { exitCode: 0, signal: null };
    }
  };

  const result = await probeDevinAuthStatus({
    command: "devin acp",
    runner: capturingRunner,
    resolveCommand: fakeResolveCommand({ resolvedPath: "/opt/devin" })
  });

  assert.deepEqual(result, { status: "authenticated", path: "/opt/devin" });
  assert.equal(calls.length, 1);
  const call = calls[0] as { executable: string; args: string[] };
  assert.equal(call.executable, "/opt/devin");
  assert.deepEqual(call.args, ["auth", "status"]);
});

test("probeDevinAuthStatus defaults to devin acp command", async () => {
  const result = await probeDevinAuthStatus({
    command: "devin acp",
    runner: fakeRunner({ exitCode: 0, signal: null }),
    resolveCommand: fakeResolveCommand({ resolvedPath: undefined, available: false })
  });

  assert.equal(result.status, "not_installed");
});

test("default runner rejects with timeout for a hung child", async () => {
  const runner = createDefaultDevinAuthProbeRunner();
  await assert.rejects(
    runner.run(process.execPath, ["-e", "setTimeout(() => {}, 60000)"], { timeoutMs: 50 }),
    /timeout/
  );
});

test("default runner resolves non-timeout child exit codes", async () => {
  const runner = createDefaultDevinAuthProbeRunner();
  const success = await runner.run(process.execPath, ["-e", "process.exit(0)"], { timeoutMs: 5000 });
  assert.deepEqual(success, { exitCode: 0, signal: null });

  const failure = await runner.run(process.execPath, ["-e", "process.exit(1)"], { timeoutMs: 5000 });
  assert.deepEqual(failure, { exitCode: 1, signal: null });
});
