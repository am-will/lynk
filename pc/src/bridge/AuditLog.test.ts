import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
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
    assert.match(jsonl, /"screenshotBase64":"<base64:6 chars>"/);
    assert.match(jsonl, /"nodesCount":2/);
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
