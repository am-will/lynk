import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync, writeFileSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { getBridgeConfig } from "./config.js";

const originalToken = process.env.PHONE_AGENT_TOKEN;
const originalConfigPath = process.env.PHONE_AGENT_CONFIG_PATH;
const originalPath = process.env.PATH;
const originalDevinPermissionMode = process.env.DEVIN_PERMISSION_MODE;
let tempRoot: string | undefined;

test.beforeEach(async () => {
  tempRoot = await mkdtemp(join(tmpdir(), "android-agent-config-test-"));
  process.env.PHONE_AGENT_CONFIG_PATH = join(tempRoot, "config.json");
  process.env.PATH = "";
});

test.afterEach(async () => {
  if (originalToken === undefined) {
    delete process.env.PHONE_AGENT_TOKEN;
  } else {
    process.env.PHONE_AGENT_TOKEN = originalToken;
  }
  if (originalConfigPath === undefined) {
    delete process.env.PHONE_AGENT_CONFIG_PATH;
  } else {
    process.env.PHONE_AGENT_CONFIG_PATH = originalConfigPath;
  }
  process.env.PATH = originalPath;
  if (originalDevinPermissionMode === undefined) {
    delete process.env.DEVIN_PERMISSION_MODE;
  } else {
    process.env.DEVIN_PERMISSION_MODE = originalDevinPermissionMode;
  }
  if (tempRoot) {
    await rm(tempRoot, { recursive: true, force: true });
    tempRoot = undefined;
  }
});

test("getBridgeConfig creates a persistent host token when env is missing", () => {
  delete process.env.PHONE_AGENT_TOKEN;
  const config = getBridgeConfig();
  assert.equal(config.token.length, 64);
  assert.equal(getBridgeConfig().token, config.token);
});

test("getBridgeConfig rejects the known weak default token", () => {
  process.env.PHONE_AGENT_TOKEN = "12345678";
  assert.throws(
    () => getBridgeConfig(),
    /known weak default/
  );
});

test("getBridgeConfig accepts a non-default token", () => {
  process.env.PHONE_AGENT_TOKEN = "strong-local-test-token";
  assert.equal(getBridgeConfig().token, "strong-local-test-token");
});

test("getBridgeConfig uses Devin defaults and env overrides", () => {
  process.env.PHONE_AGENT_TOKEN = "strong-local-test-token";
  const defaults = getBridgeConfig();
  assert.equal(defaults.devinAcpCommand, "devin acp");
  assert.equal(defaults.devinRunTimeoutMs, 600_000);
  assert.equal(defaults.devinPermissionMode, undefined);

  const hostConfig = JSON.parse(readFileSync(defaults.configPath, "utf8")) as Record<string, unknown>;
  hostConfig.devinPermissionMode = "bypass";
  writeFileSync(defaults.configPath, `${JSON.stringify(hostConfig, null, 2)}\n`, { mode: 0o600 });
  process.env.DEVIN_PERMISSION_MODE = "  ";
  assert.equal(getBridgeConfig().devinPermissionMode, "bypass");

  process.env.DEVIN_ACP_COMMAND = "/opt/devin/bin/devin acp";
  process.env.DEVIN_AGENT_CWD = "/tmp/devin-cwd";
  process.env.DEVIN_RUN_TIMEOUT_SECONDS = "300";
  process.env.DEVIN_PERMISSION_MODE = "bypass";
  try {
    const overridden = getBridgeConfig();
    assert.equal(overridden.devinAcpCommand, "/opt/devin/bin/devin acp");
    assert.equal(overridden.devinAgentCwd, "/tmp/devin-cwd");
    assert.equal(overridden.devinRunTimeoutMs, 300_000);
    assert.equal(overridden.devinPermissionMode, "bypass");

    process.env.DEVIN_RUN_TIMEOUT_SECONDS = "0";
    assert.throws(() => getBridgeConfig(), /DEVIN_RUN_TIMEOUT_SECONDS must be a positive integer/);
    process.env.DEVIN_RUN_TIMEOUT_SECONDS = "300";
    process.env.DEVIN_PERMISSION_MODE = "unsafe";
    assert.throws(() => getBridgeConfig(), /DEVIN_PERMISSION_MODE/);
  } finally {
    delete process.env.DEVIN_PERMISSION_MODE;
    delete process.env.DEVIN_RUN_TIMEOUT_SECONDS;
    delete process.env.DEVIN_AGENT_CWD;
    delete process.env.DEVIN_ACP_COMMAND;
  }
});
