import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm, stat, utimes, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { AuditLog } from "./AuditLog.js";

test("AuditLog records in memory immediately and flushes writes asynchronously", async () => {
  const dir = await mkdtemp(join(tmpdir(), "open-claw-audit-"));
  try {
    const audit = new AuditLog(10, dir);
    audit.record("phone_command_result", "pixel", {
      screenshotBase64: "abc123",
      nodes: [{ id: "1" }, { id: "2" }]
    });

    const recent = audit.recent(1);
    assert.equal(recent.length, 1);
    assert.equal(recent[0]?.type, "phone_command_result");

    await audit.flush();
    const jsonl = await readFile(join(dir, "phone-agent-audit.jsonl"), "utf8");
    assert.doesNotMatch(jsonl, /abc123|screenshotBase64|"nodes":/u);
    assert.match(jsonl, /"nodesCount":2/);
    if (process.platform !== "win32") {
      assert.equal((await stat(dir)).mode & 0o777, 0o700);
      assert.equal((await stat(join(dir, "phone-agent-audit.jsonl"))).mode & 0o777, 0o600);
    }
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("AuditLog persists only allowlisted metadata and never raw prompts, RPC payloads, tokens, or results", async () => {
  const dir = await mkdtemp(join(tmpdir(), "lynk-audit-redaction-"));
  try {
    const audit = new AuditLog(10, dir);
    audit.record("codex_rpc_request", "pixel", {
      id: 42,
      method: "turn/start",
      params: { prompt: "private prompt", token: "secret-token" },
      result: { content: "private result" },
      message: "raw message",
      attachments: [{ content: "file secret" }],
      metrics: { chars: 123, estimatedTokens: 31 }
    });
    await audit.close();
    const jsonl = await readFile(join(dir, "phone-agent-audit.jsonl"), "utf8");
    assert.match(jsonl, /"method":"turn\/start"/u);
    assert.match(jsonl, /"attachmentsCount":1/u);
    assert.match(jsonl, /"chars":123/u);
    assert.doesNotMatch(jsonl, /private prompt|secret-token|private result|raw message|file secret|params|"result"/u);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("AuditLog rotates by size and caps total retained bytes", async () => {
  const dir = await mkdtemp(join(tmpdir(), "lynk-audit-rotation-"));
  try {
    const audit = new AuditLog(100, dir, { maxFileBytes: 300, maxTotalBytes: 700, maxRetentionAgeMs: 60_000 });
    for (let index = 0; index < 20; index += 1) audit.record("rotation_event", "pixel", { id: `event-${index}`, status: "completed" });
    await audit.close();
    const files = (await readdir(dir)).filter((name) => name.endsWith(".jsonl"));
    assert.ok(files.length >= 1);
    const sizes = await Promise.all(files.map((name) => stat(join(dir, name)).then((info) => info.size)));
    assert.ok(sizes.every((size) => size <= 300));
    assert.ok(sizes.reduce((sum, size) => sum + size, 0) <= 700);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("AuditLog removes expired rotated files during startup", async () => {
  const dir = await mkdtemp(join(tmpdir(), "lynk-audit-retention-"));
  try {
    const stale = join(dir, "phone-agent-audit-legacy-0.jsonl");
    await writeFile(stale, "{}\n");
    await utimes(stale, new Date(0), new Date(0));
    const audit = new AuditLog(10, dir, { maxRetentionAgeMs: 1_000 });
    await audit.flush();
    assert.equal((await readdir(dir)).includes("phone-agent-audit-legacy-0.jsonl"), false);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("AuditLog default directory is independent of process cwd", async () => {
  const configDir = await mkdtemp(join(tmpdir(), "lynk-config-"));
  const previousConfigDir = process.env.PHONE_AGENT_CONFIG_DIR;
  const previousAuditDir = process.env.PHONE_AGENT_AUDIT_DIR;
  try {
    process.env.PHONE_AGENT_CONFIG_DIR = configDir;
    delete process.env.PHONE_AGENT_AUDIT_DIR;

    const audit = new AuditLog(10);
    audit.record("startup_check");
    await audit.flush();

    const jsonl = await readFile(join(configDir, "audit", "phone-agent-audit.jsonl"), "utf8");
    assert.match(jsonl, /"type":"startup_check"/);
  } finally {
    if (previousConfigDir === undefined) {
      delete process.env.PHONE_AGENT_CONFIG_DIR;
    } else {
      process.env.PHONE_AGENT_CONFIG_DIR = previousConfigDir;
    }
    if (previousAuditDir === undefined) {
      delete process.env.PHONE_AGENT_AUDIT_DIR;
    } else {
      process.env.PHONE_AGENT_AUDIT_DIR = previousAuditDir;
    }
    await rm(configDir, { recursive: true, force: true });
  }
});
