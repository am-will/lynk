import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { InMemoryHarnessSessionStore } from "./InMemoryHarnessSessionStore.js";
import type { ResolvedChatAttachment } from "../../attachments/AttachmentTypes.js";

describe("InMemoryHarnessSessionStore", () => {
  function createStore(persistEmpty = true): { store: InMemoryHarnessSessionStore; storagePath: string; cleanup: () => void } {
    const dir = mkdtempSync(join(tmpdir(), "harness-store-"));
    const storagePath = join(dir, "sessions.json");
    const store = new InMemoryHarnessSessionStore("devin", {
      defaultModel: "default",
      modelProvider: "devin",
      storagePath,
      persistEmptySessions: persistEmpty
    });
    return {
      store,
      storagePath,
      cleanup: () => rmSync(dir, { recursive: true, force: true })
    };
  }

  it("persists empty sessions when persistEmptySessions is true", async () => {
    const { store, storagePath, cleanup } = createStore();
    store.ensureSession("devin:empty");
    await store.flushPersistence();
    const persisted = JSON.parse(readFileSync(storagePath, "utf8")) as { sessions: unknown[] };
    assert.equal(persisted.sessions.length, 1);
    cleanup();
  });

  it("replaceHistory replaces all messages and persists once", async () => {
    const { store, storagePath, cleanup } = createStore();
    const session = store.ensureSession("devin:replace");
    store.appendUserMessage(session, "old user");
    store.upsertAssistantMessage(session, "run-1", "old assistant");

    store.replaceHistory("devin:replace", [
      { id: "m1", role: "user", text: "hello", timestamp: 1 },
      { id: "m2", role: "assistant", text: "world", timestamp: 2 }
    ]);
    await store.flushPersistence();

    const history = store.history("devin:replace");
    assert.equal(history.messages.length, 2);
    assert.equal(history.messages[0]?.text, "hello");
    assert.equal(history.messages[1]?.text, "world");

    const persisted = JSON.parse(readFileSync(storagePath, "utf8")) as { sessions: Array<{ messages: unknown[] }> };
    assert.equal(persisted.sessions[0]?.messages.length, 2);
    cleanup();
  });

  it("replaceHistory filters unsupported roles and missing text", () => {
    const { store, cleanup } = createStore();
    store.ensureSession("devin:filter");
    store.replaceHistory("devin:filter", [
      { id: "m1", role: "user", text: "ok", timestamp: 1 },
      { id: "m2", role: "narrator", text: "skip", timestamp: 2 },
      { id: "m3", role: "assistant", text: "", timestamp: 3 }
    ]);
    const history = store.history("devin:filter");
    assert.equal(history.messages.length, 1);
    assert.equal(history.messages[0]?.text, "ok");
    cleanup();
  });

  it("replaceHistory preserves identity and metadata", async () => {
    const { store, storagePath, cleanup } = createStore();
    const session = store.ensureSession("devin:meta", "session-id");
    store.setMetadata(session, "workspacePath", "/tmp/ws");
    store.replaceHistory("devin:meta", [{ id: "m1", role: "user", text: "hi", timestamp: 1 }]);
    await store.flushPersistence();

    const reloaded = new InMemoryHarnessSessionStore("devin", {
      defaultModel: "default",
      modelProvider: "devin",
      storagePath,
      persistEmptySessions: true
    });
    const reloadedSession = reloaded.ensureSession("devin:meta");
    assert.equal(reloadedSession.sessionId, "session-id");
    assert.equal(reloadedSession.metadata?.workspacePath, "/tmp/ws");
    assert.equal(reloaded.history("devin:meta").messages[0]?.text, "hi");
    cleanup();
  });

  it("persists attachment metadata without runtime paths or inline payloads", async () => {
    const { store, storagePath, cleanup } = createStore();
    const session = store.ensureSession("devin:attachments");
    const attachment: ResolvedChatAttachment = {
      id: "blob_attachment-1",
      kind: "image",
      displayName: "photo.png",
      mimeType: "image/png",
      sizeBytes: 5,
      sha256: "a".repeat(64),
      localPath: "/private/host/blob"
    };
    store.appendUserMessage(session, "inspect", "run-1", [attachment]);
    await store.flushPersistence();

    const persistedText = readFileSync(storagePath, "utf8");
    const history = store.history("devin:attachments");

    assert.equal(persistedText.includes("localPath"), false);
    assert.equal(persistedText.includes("contentBase64"), false);
    assert.equal(persistedText.includes("/private/host/blob"), false);
    assert.deepEqual(history.messages[0]?.attachments, [{
      id: "blob_attachment-1",
      kind: "image",
      displayName: "photo.png",
      mimeType: "image/png",
      sizeBytes: 5
    }]);
    cleanup();
  });

  it("migrates a legacy catalog once without deleting its source", async () => {
    const dir = mkdtempSync(join(tmpdir(), "harness-migrate-"));
    const legacy = join(dir, "legacy.json");
    const storagePath = join(dir, "private", "sessions.json");
    writeFileSync(legacy, JSON.stringify({
      version: 1,
      harnessId: "devin",
      sessions: [{ key: "devin:old", sessionId: "old", label: "Old", messages: [], updatedAt: 1 }]
    }));
    const store = new InMemoryHarnessSessionStore("devin", {
      defaultModel: "default",
      modelProvider: "devin",
      storagePath,
      legacyStoragePaths: [legacy]
    });
    assert.equal(store.requireSession("devin:old")?.label, "Old");
    assert.equal(readFileSync(legacy, "utf8").includes("devin:old"), true);
    if (process.platform !== "win32") assert.equal(statSync(storagePath).mode & 0o777, 0o600);
    await store.close();
    rmSync(dir, { recursive: true, force: true });
  });

  it("quarantines corrupt catalogs and recovers the previous durable generation", async () => {
    const { store, storagePath, cleanup } = createStore();
    store.ensureSession("devin:first");
    await store.flushPersistence();
    store.ensureSession("devin:second");
    await store.flushPersistence();
    await store.close();
    writeFileSync(storagePath, "{corrupt");
    const recovered = new InMemoryHarnessSessionStore("devin", {
      defaultModel: "default",
      modelProvider: "devin",
      storagePath
    });
    assert.equal(recovered.requireSession("devin:first")?.sessionId, "first");
    assert.equal(recovered.requireSession("devin:second"), undefined);
    assert.equal(readFileSync(storagePath, "utf8").includes("devin:first"), true);
    await recovered.close();
    cleanup();
  });

  it("bounds histories and schedules persistence without blocking the event loop", async () => {
    const { store, cleanup } = createStore();
    const session = store.ensureSession("devin:bounded");
    let immediateRan = false;
    setImmediate(() => { immediateRan = true; });
    for (let index = 0; index < 550; index += 1) store.appendUserMessage(session, `message ${index}`);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(immediateRan, true);
    assert.equal(store.history("devin:bounded").messages.length, 500);
    await store.close();
    cleanup();
  });
});
