import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { loadOrCreateHostBridgeConfig } from "./HostConfigStore.js";

test("new host bridge config defaults agent cwd to package root, not launcher cwd", async () => {
  const dir = await mkdtemp(join(tmpdir(), "lynk-host-config-"));
  const originalCwd = process.cwd();
  try {
    process.chdir("/");
    const { config } = loadOrCreateHostBridgeConfig(join(dir, "config.json"));

    assert.notEqual(config.codexAgentCwd, "/");
    assert.notEqual(config.opencodeAgentCwd, "/");
    assert.notEqual(config.piAgentCwd, "/");
    assert.notEqual(config.devinAgentCwd, "/");
    assert.match(config.codexAgentCwd ?? "", /\/pc$/);
    assert.equal(config.opencodeAgentCwd, config.codexAgentCwd);
    assert.equal(config.piAgentCwd, config.codexAgentCwd);
    assert.equal(config.devinAgentCwd, config.codexAgentCwd);
  } finally {
    process.chdir(originalCwd);
    await rm(dir, { recursive: true, force: true });
  }
});

test("existing host bridge config rejects unknown fields and invalid port types", async () => {
  const dir = await mkdtemp(join(tmpdir(), "lynk-host-config-invalid-"));
  const path = join(dir, "config.json");
  try {
    await writeFile(path, JSON.stringify({
      schemaVersion: 1,
      phoneAgentToken: "token",
      phoneAgentPort: "8788"
    }));
    assert.throws(() => loadOrCreateHostBridgeConfig(path), /phoneAgentPort/);

    await writeFile(path, JSON.stringify({
      schemaVersion: 1,
      phoneAgentToken: "token",
      phoneAgentPort: 8788,
      phoneAgentPrt: 8789
    }));
    assert.throws(() => loadOrCreateHostBridgeConfig(path), /Unrecognized key.*phoneAgentPrt/);

    await writeFile(path, "{not-json");
    assert.throws(() => loadOrCreateHostBridgeConfig(path), /is not valid JSON/);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
