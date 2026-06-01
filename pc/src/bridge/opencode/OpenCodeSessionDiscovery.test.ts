import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import { listOpenCodeStoredSessions } from "./OpenCodeSessionDiscovery.js";

test("OpenCode storage discovery reads SQLite project and global sessions and filters empty sessions", () => {
  const dataDir = mkTempDir();
  const db = new DatabaseSync(join(dataDir, "opencode.db"));
  try {
    db.exec(`
      CREATE TABLE session (
        id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        directory TEXT NOT NULL,
        time_created INTEGER NOT NULL,
        time_updated INTEGER NOT NULL,
        model TEXT,
        cost REAL DEFAULT 0 NOT NULL,
        tokens_input INTEGER DEFAULT 0 NOT NULL,
        tokens_output INTEGER DEFAULT 0 NOT NULL,
        tokens_reasoning INTEGER DEFAULT 0 NOT NULL,
        tokens_cache_read INTEGER DEFAULT 0 NOT NULL,
        tokens_cache_write INTEGER DEFAULT 0 NOT NULL,
        time_archived INTEGER
      );
      CREATE TABLE message (
        id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL,
        time_created INTEGER NOT NULL,
        time_updated INTEGER NOT NULL,
        data TEXT NOT NULL
      );
    `);
    insertSession(db, "ses_repo", "Repo session", "/repo", 300, { providerID: "openai", id: "gpt-5.5" });
    insertSession(db, "ses_global", "Global session", "/Users/example/Applications", 200, { providerID: "opencode", id: "mimo-v2.5-free" });
    insertSession(db, "ses_empty", "Empty session", "/empty", 400);
    insertMessage(db, "msg_repo_user", "ses_repo", "user");
    insertMessage(db, "msg_repo_assistant", "ses_repo", "assistant");
    insertMessage(db, "msg_global_user", "ses_global", "user");
  } finally {
    db.close();
  }

  try {
    const sessions = listOpenCodeStoredSessions({ dataDir });
    assert.deepEqual(sessions.map((session) => session.id), ["ses_repo", "ses_global"]);
    assert.deepEqual(sessions.map((session) => session.directory), ["/repo", "/Users/example/Applications"]);
    assert.equal(sessions[0]?.model, "openai/gpt-5.5");
    assert.equal(sessions[1]?.model, "opencode/mimo-v2.5-free");
  } finally {
    rmSync(dataDir, { recursive: true, force: true });
  }
});

test("OpenCode storage discovery reads legacy project and global storage folders", () => {
  const dataDir = mkTempDir();
  try {
    const projectStorage = join(dataDir, "project", "repo-slug", "storage", "session");
    const globalStorage = join(dataDir, "project", "global", "storage", "session");
    mkdirSync(projectStorage, { recursive: true });
    mkdirSync(globalStorage, { recursive: true });
    writeFileSync(join(projectStorage, "ses_project.json"), JSON.stringify({
      id: "ses_project",
      title: "Project storage",
      directory: "/repo",
      time: { created: 100, updated: 300 },
      model: { providerID: "openai", modelID: "gpt-5.5" },
      messages: [{ id: "msg_project_user", role: "user" }]
    }));
    writeFileSync(join(globalStorage, "ses_global.json"), JSON.stringify({
      id: "ses_global",
      title: "Global storage",
      directory: "/Users/example/Applications",
      time: { created: 100, updated: 200 },
      messages: [{ id: "msg_global_user", role: "user" }]
    }));
    writeFileSync(join(globalStorage, "ses_empty.json"), JSON.stringify({
      id: "ses_empty",
      title: "Empty storage",
      directory: "/Users/example/empty",
      time: { created: 100, updated: 400 },
      messages: []
    }));

    const sessions = listOpenCodeStoredSessions({ dataDir });
    assert.deepEqual(sessions.map((session) => session.id), ["ses_project", "ses_global"]);
    assert.deepEqual(sessions.map((session) => session.directory), ["/repo", "/Users/example/Applications"]);
    assert.equal(sessions[0]?.model, "openai/gpt-5.5");
  } finally {
    rmSync(dataDir, { recursive: true, force: true });
  }
});

function mkTempDir(): string {
  return mkdtempSync(join(tmpdir(), "opencode-discovery-"));
}

function insertSession(db: DatabaseSync, id: string, title: string, directory: string, updatedAt: number, model?: unknown): void {
  db.prepare(`
    INSERT INTO session (
      id, title, directory, time_created, time_updated, model,
      tokens_input, tokens_output, tokens_reasoning, tokens_cache_read, tokens_cache_write, cost
    )
    VALUES (?, ?, ?, ?, ?, ?, 1, 2, 3, 4, 5, 0.01)
  `).run(id, title, directory, 100, updatedAt, model ? JSON.stringify(model) : null);
}

function insertMessage(db: DatabaseSync, id: string, sessionId: string, role: string): void {
  db.prepare("INSERT INTO message (id, session_id, time_created, time_updated, data) VALUES (?, ?, 100, 100, ?)")
    .run(id, sessionId, JSON.stringify({ role }));
}
