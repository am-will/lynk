import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { chmod, lstat, lutimes, mkdtemp, readFile, readdir, stat, symlink, utimes, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  atomicWritePrivateFile,
  atomicWritePrivateFileSync,
  cleanupAtomicLeftovers,
  DebouncedAtomicJsonWriter,
  enforcePrivateFileSync,
  migrateLegacyFile,
  readJsonWithRecovery
} from "./PrivatePersistence.js";

test("sync atomic writes fsync the parent directory after rename", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "lynk-private-sync-durable-"));
  t.after(() => import("node:fs/promises").then(({ rm }) => rm(root, { recursive: true, force: true })));
  const path = join(root, "nested", "state.json");
  let directorySyncs = 0;

  atomicWritePrivateFileSync(path, "durable\n", {
    keepBackup: false,
    directorySync: (directory) => {
      directorySyncs += 1;
      assert.equal(directory, join(root, "nested"));
      assert.equal(readFileSync(path, "utf8"), "durable\n");
    }
  });

  assert.equal(directorySyncs, 1);
});

test("concurrent atomic writers never scavenge another writer's live temp", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "lynk-private-race-"));
  t.after(() => import("node:fs/promises").then(({ rm }) => rm(root, { recursive: true, force: true })));
  const path = join(root, "state.json");
  const entered = deferred();
  const release = deferred();
  let firstTemp = "";

  const first = atomicWritePrivateFile(path, "first\n", {
    keepBackup: false,
    beforeRename: async (temporaryPath) => {
      firstTemp = temporaryPath;
      entered.resolve();
      await release.promise;
    }
  });
  await entered.promise;
  const second = atomicWritePrivateFile(path, "second\n", { keepBackup: false });
  await second;

  assert.equal((await lstat(firstTemp)).isFile(), true);
  release.resolve();
  await first;
  assert.equal(await readFile(path, "utf8"), "first\n");
});

test("startup scavenging removes only bounded stale regular temp files", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "lynk-private-scavenge-"));
  t.after(() => import("node:fs/promises").then(({ rm }) => rm(root, { recursive: true, force: true })));
  const path = join(root, "state.json");
  const fresh = join(root, ".state.json.1.fresh.tmp");
  const stale = join(root, ".state.json.2.stale.tmp");
  const unrelated = join(root, ".other.json.3.stale.tmp");
  const symlinkTarget = join(root, "target.txt");
  const linked = join(root, ".state.json.4.link.tmp");
  await Promise.all([
    writeFile(fresh, "fresh"),
    writeFile(stale, "stale"),
    writeFile(unrelated, "unrelated"),
    writeFile(symlinkTarget, "target")
  ]);
  await symlink(symlinkTarget, linked);
  const now = Date.now();
  await utimes(stale, new Date(now - 86_400_000), new Date(now - 86_400_000));
  await lutimes(linked, new Date(now - 86_400_000), new Date(now - 86_400_000));

  await cleanupAtomicLeftovers(path, { staleAfterMs: 60_000, now });

  const names = await readdir(root);
  assert.equal(names.includes(".state.json.1.fresh.tmp"), true);
  assert.equal(names.includes(".state.json.2.stale.tmp"), false);
  assert.equal(names.includes(".other.json.3.stale.tmp"), true);
  assert.equal(names.includes(".state.json.4.link.tmp"), true);
  assert.equal(await readFile(symlinkTarget, "utf8"), "target");
});

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

test("debounced writer serializes generations and reports close failures truthfully", async () => {
  const entered = deferred();
  const release = deferred();
  const writes: number[] = [];
  let active = 0;
  let maxActive = 0;
  const writer = new DebouncedAtomicJsonWriter<{ value: number }>("/unused/state.json", 1, 1024, async (_path, data) => {
    active += 1;
    maxActive = Math.max(maxActive, active);
    const value = JSON.parse(String(data)) as { value: number };
    writes.push(value.value);
    if (value.value === 1) {
      entered.resolve();
      await release.promise;
    }
    active -= 1;
  });

  writer.schedule({ value: 1 });
  const firstFlush = writer.flush();
  await entered.promise;
  writer.schedule({ value: 2 });
  const secondFlush = writer.flush();
  release.resolve();
  await Promise.all([firstFlush, secondFlush]);
  await writer.close();

  assert.deepEqual(writes, [1, 2]);
  assert.equal(maxActive, 1);

  let fail = true;
  const failing = new DebouncedAtomicJsonWriter<{ value: number }>("/unused/failing.json", 1, 1024, async () => {
    if (fail) throw new Error("injected disk failure");
  });
  failing.schedule({ value: 1 });
  await assert.rejects(failing.close(), /injected disk failure/u);
  fail = false;
  failing.schedule({ value: 2 });
  await failing.close();
  assert.throws(() => failing.schedule({ value: 3 }), /closed/u);
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

function deferred(): { promise: Promise<void>; resolve(): void } {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}
