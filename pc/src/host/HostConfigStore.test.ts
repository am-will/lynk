import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
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
    assert.match(config.codexAgentCwd ?? "", /\/pc$/);
    assert.equal(config.opencodeAgentCwd, config.codexAgentCwd);
    assert.equal(config.piAgentCwd, config.codexAgentCwd);
  } finally {
    process.chdir(originalCwd);
    await rm(dir, { recursive: true, force: true });
  }
});
