import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readdirSync, rmSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable } from "node:stream";
import test from "node:test";

import { HostBlobStore, HostBlobStoreError, type HostBlobUploadRequest } from "./HostBlobStore.js";

const owner = { deviceId: "phone-1", sessionKey: "codex:session-1" };

test("HostBlobStore streams unknown-length inputs through the hard cap and removes partials", async () => {
  const fixture = createStore({ maxItemBytes: 8, maxAggregateBytes: 32 });
  try {
    await assert.rejects(
      fixture.store.upload(Readable.from([Buffer.alloc(4), Buffer.alloc(5)]), request("blob_oversized", Buffer.alloc(9), undefined)),
      (error: unknown) => error instanceof HostBlobStoreError && error.statusCode === 413
    );
    assert.deepEqual(readdirSync(fixture.root), []);
  } finally {
    fixture.cleanup();
  }
});

test("HostBlobStore rejects declared oversize before reading the body", async () => {
  const fixture = createStore({ maxItemBytes: 8, maxAggregateBytes: 32 });
  let reads = 0;
  const input = new Readable({
    read() {
      reads += 1;
      this.push(Buffer.alloc(1));
      this.push(null);
    }
  });
  try {
    await assert.rejects(
      fixture.store.upload(input, request("blob_declared", Buffer.alloc(9), 9)),
      (error: unknown) => error instanceof HostBlobStoreError && error.statusCode === 413
    );
    assert.equal(reads, 0);
    assert.deepEqual(readdirSync(fixture.root), []);
  } finally {
    fixture.cleanup();
  }
});

test("HostBlobStore removes partial state after interrupted and short uploads", async () => {
  const fixture = createStore({ maxItemBytes: 16, maxAggregateBytes: 64 });
  try {
    const interrupted = new Readable({
      read() {
        this.push(Buffer.from("abc"));
        this.destroy(new Error("client disconnected"));
      }
    });
    await assert.rejects(fixture.store.upload(interrupted, request("blob_interrupted", Buffer.from("abc"), undefined)));
    await assert.rejects(
      fixture.store.upload(Readable.from([Buffer.from("abc")]), request("blob_short-body", Buffer.from("abcde"), 5)),
      /declared 5 bytes but uploaded 3/u
    );
    assert.deepEqual(readdirSync(fixture.root), []);
  } finally {
    fixture.cleanup();
  }
});

test("HostBlobStore verifies checksum and enforces device and session ownership", async () => {
  const fixture = createStore({ maxItemBytes: 16, maxAggregateBytes: 64 });
  const bytes = Buffer.from("hello");
  try {
    const metadata = await fixture.store.upload(Readable.from([bytes]), request("blob_successful", bytes, bytes.length));
    assert.equal(fixture.store.resolve(metadata.id, owner)?.sha256, metadata.sha256);
    assert.equal(fixture.store.resolve(metadata.id, { ...owner, deviceId: "phone-2" }), undefined);
    assert.equal(fixture.store.resolve(metadata.id, { ...owner, sessionKey: "codex:other" }), undefined);
    assert.equal(fixture.store.resolve(metadata.id, owner, "0".repeat(64)), undefined);

    await assert.rejects(
      fixture.store.upload(
        Readable.from([bytes]),
        { ...request("blob_bad-checksum", bytes, bytes.length), sha256: "0".repeat(64) }
      ),
      (error: unknown) => error instanceof HostBlobStoreError && error.statusCode === 422
    );
    assert.equal(readdirSync(fixture.root).some((name) => name.includes("bad-checksum")), false);
  } finally {
    fixture.cleanup();
  }
});

test("HostBlobStore startup cleanup removes stale partials, orphans, and expired pairs", async () => {
  const root = mkdtempSync(join(tmpdir(), "lynk-host-blobs-"));
  const now = 100_000;
  try {
    writeFileSync(join(root, ".blob_abandoned.partial"), "partial");
    writeFileSync(join(root, "blob_orphaned.blob"), "orphan");
    writeFileSync(join(root, "blob_invalid.json"), "{}");
    const store = new HostBlobStore(root, {
      maxItemBytes: 16,
      maxAggregateBytes: 64,
      freeSpaceReserveBytes: 0,
      retentionMs: 1_000,
      now: () => now,
      usableSpaceBytes: () => Number.MAX_SAFE_INTEGER
    });
    const bytes = Buffer.from("hello");
    const metadata = await store.upload(Readable.from([bytes]), request("blob_expiring", bytes, bytes.length));
    const payload = store.resolve(metadata.id, owner)?.path;
    assert.ok(payload);
    utimesSync(payload, new Date(0), new Date(0));

    store.cleanup();

    assert.deepEqual(readdirSync(root), []);
    assert.equal(existsSync(payload), false);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

function request(id: string, bytes: Buffer, declaredSizeBytes: number | undefined): HostBlobUploadRequest {
  return {
    id,
    ...owner,
    displayName: "photo.png",
    mimeType: "image/png",
    kind: "image",
    sha256: createHash("sha256").update(bytes).digest("hex"),
    declaredSizeBytes
  };
}

function createStore(overrides: { maxItemBytes: number; maxAggregateBytes: number }): {
  root: string;
  store: HostBlobStore;
  cleanup: () => void;
} {
  const root = mkdtempSync(join(tmpdir(), "lynk-host-blobs-"));
  return {
    root,
    store: new HostBlobStore(root, {
      ...overrides,
      maxBlobCount: 8,
      freeSpaceReserveBytes: 0,
      retentionMs: 60_000,
      usableSpaceBytes: () => Number.MAX_SAFE_INTEGER
    }),
    cleanup: () => rmSync(root, { recursive: true, force: true })
  };
}
