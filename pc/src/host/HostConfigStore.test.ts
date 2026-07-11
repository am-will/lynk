import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { loadOrCreateHostBridgeConfig } from "./HostConfigStore.js";

test("new host bridge config defaults agent cwd outside package and launcher roots", async () => {
  const dir = await mkdtemp(join(tmpdir(), "lynk-host-config-"));
  const originalCwd = process.cwd();
  try {
    process.chdir("/");
    const { config } = loadOrCreateHostBridgeConfig(join(dir, "config.json"));

    assert.notEqual(config.codexAgentCwd, "/");
    assert.notEqual(config.opencodeAgentCwd, "/");
    assert.notEqual(config.piAgentCwd, "/");
    assert.notEqual(config.devinAgentCwd, "/");
    assert.equal(config.codexAgentCwd, homedir());
    assert.equal(config.opencodeAgentCwd, config.codexAgentCwd);
    assert.equal(config.piAgentCwd, config.codexAgentCwd);
    assert.equal(config.devinAgentCwd, config.codexAgentCwd);
  } finally {
    process.chdir(originalCwd);
    await rm(dir, { recursive: true, force: true });
  }
});

test("host config writes are private and recover a corrupt primary from backup", async () => {
  const dir = await mkdtemp(join(tmpdir(), "lynk-host-config-recovery-"));
  const path = join(dir, "config.json");
  try {
    const first = loadOrCreateHostBridgeConfig(path).config;
    const updated = { ...first, phoneAgentPort: 9999 };
    const { writeHostBridgeConfig } = await import("./HostConfigStore.js");
    writeHostBridgeConfig(path, updated);
    await writeFile(path, "{broken");
    const recovered = loadOrCreateHostBridgeConfig(path);
    assert.equal(recovered.config.phoneAgentPort, first.phoneAgentPort);
    assert.equal(JSON.parse(await readFile(path, "utf8")).phoneAgentPort, first.phoneAgentPort);
    if (process.platform !== "win32") {
      assert.equal((await stat(dir)).mode & 0o777, 0o700);
      assert.equal((await stat(path)).mode & 0o777, 0o600);
    }
  } finally {
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
