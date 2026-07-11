import assert from "node:assert/strict";
import test from "node:test";
import { chmod, mkdtemp, readFile, readdir, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  atomicWritePrivateFile,
  DebouncedAtomicJsonWriter,
  enforcePrivateFileSync,
  migrateLegacyFile,
  readJsonWithRecovery
} from "./PrivatePersistence.js";

test("private atomic writes enforce Unix permissions and preserve old content on failure", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "lynk-private-write-"));
  t.after(() => import("node:fs/promises").then(({ rm }) => rm(root, { recursive: true, force: true })));
  const path = join(root, "private", "state.json");
  await atomicWritePrivateFile(path, "old\n");
  if (process.platform !== "win32") {
    assert.equal((await stat(join(root, "private"))).mode & 0o777, 0o700);
    assert.equal((await stat(path)).mode & 0o777, 0o600);
  }
  await assert.rejects(
    atomicWritePrivateFile(path, "new\n", { beforeRename: () => { throw new Error("injected crash"); } }),
    /injected crash/u
  );
  assert.equal(await readFile(path, "utf8"), "old\n");
  assert.equal((await readdir(join(root, "private"))).filter((entry) => entry.endsWith(".tmp")).length, 0);
});

test("corrupt primary is quarantined and recovered from the last backup", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "lynk-recovery-"));
  t.after(() => import("node:fs/promises").then(({ rm }) => rm(root, { recursive: true, force: true })));
  const path = join(root, "state.json");
  await atomicWritePrivateFile(path, '{"value":1}\n');
  await atomicWritePrivateFile(path, '{"value":2}\n');
  await writeFile(path, "{broken");
  const result = await readJsonWithRecovery(path, () => ({ value: 0 }), isValueRecord);
  assert.equal(result.source, "backup");
  assert.deepEqual(result.value, { value: 1 });
  assert.match(result.quarantinedPath ?? "", /\.corrupt-/u);
  assert.deepEqual(JSON.parse(await readFile(path, "utf8")), { value: 1 });
});

test("debounced writes serialize concurrent updates without blocking the event loop", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "lynk-debounce-"));
  t.after(() => import("node:fs/promises").then(({ rm }) => rm(root, { recursive: true, force: true })));
  const path = join(root, "state.json");
  const writer = new DebouncedAtomicJsonWriter<{ value: number }>(path, 20);
  let timerRan = false;
  setImmediate(() => { timerRan = true; });
  for (let value = 0; value < 1_000; value += 1) writer.schedule({ value });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(timerRan, true);
  await writer.close();
  assert.deepEqual(JSON.parse(await readFile(path, "utf8")), { value: 999 });
});

test("legacy migration is verified, private, retained at source, and idempotent", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "lynk-migrate-"));
  t.after(() => import("node:fs/promises").then(({ rm }) => rm(root, { recursive: true, force: true })));
  const source = join(root, "legacy.json");
  const destination = join(root, "data", "sessions", "state.json");
  const marker = join(root, "data", "migrations", "sessions-v1.json");
  await writeFile(source, '{"legacy":true}\n');
  const first = await migrateLegacyFile(destination, [source], marker);
  const second = await migrateLegacyFile(destination, [source], marker);
  assert.equal(first.migrated, true);
  assert.equal(second.migrated, false);
  assert.equal(await readFile(source, "utf8"), '{"legacy":true}\n');
  assert.equal(await readFile(destination, "utf8"), '{"legacy":true}\n');
  if (process.platform !== "win32") assert.equal((await stat(destination)).mode & 0o777, 0o600);
});

test("oversized payloads are rejected before replacing durable state", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "lynk-bounds-"));
  t.after(() => import("node:fs/promises").then(({ rm }) => rm(root, { recursive: true, force: true })));
  const path = join(root, "state.json");
  await atomicWritePrivateFile(path, "safe");
  await assert.rejects(atomicWritePrivateFile(path, "too-large", { maxBytes: 4 }), /exceeds/u);
  assert.equal(await readFile(path, "utf8"), "safe");
});

test("existing sensitive files are tightened when adopted", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "lynk-permissions-"));
  t.after(() => import("node:fs/promises").then(({ rm }) => rm(root, { recursive: true, force: true })));
  const path = join(root, "nested", "state.json");
  await import("node:fs/promises").then(({ mkdir }) => mkdir(join(root, "nested")));
  await writeFile(path, "{}", { mode: 0o644 });
  enforcePrivateFileSync(path);
  if (process.platform !== "win32") {
    assert.equal((await stat(join(root, "nested"))).mode & 0o777, 0o700);
    assert.equal((await stat(path)).mode & 0o777, 0o600);
  }
});

function isValueRecord(value: unknown): value is { value: number } {
  return Boolean(value && typeof value === "object" && typeof (value as { value?: unknown }).value === "number");
}
