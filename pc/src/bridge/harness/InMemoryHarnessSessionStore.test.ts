import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
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

  it("persists empty sessions when persistEmptySessions is true", () => {
    const { store, storagePath, cleanup } = createStore();
    store.ensureSession("devin:empty");
    const persisted = JSON.parse(readFileSync(storagePath, "utf8")) as { sessions: unknown[] };
    assert.equal(persisted.sessions.length, 1);
    cleanup();
  });

  it("replaceHistory replaces all messages and persists once", () => {
    const { store, storagePath, cleanup } = createStore();
    const session = store.ensureSession("devin:replace");
    store.appendUserMessage(session, "old user");
    store.upsertAssistantMessage(session, "run-1", "old assistant");

    store.replaceHistory("devin:replace", [
      { id: "m1", role: "user", text: "hello", timestamp: 1 },
      { id: "m2", role: "assistant", text: "world", timestamp: 2 }
    ]);

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

  it("replaceHistory preserves identity and metadata", () => {
    const { store, storagePath, cleanup } = createStore();
    const session = store.ensureSession("devin:meta", "session-id");
    store.setMetadata(session, "workspacePath", "/tmp/ws");
    store.replaceHistory("devin:meta", [{ id: "m1", role: "user", text: "hi", timestamp: 1 }]);

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

  it("persists attachment metadata without runtime paths or inline payloads", () => {
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
});
