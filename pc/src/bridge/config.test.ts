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
const configEnvironmentKeys = [
  "PHONE_AGENT_ALLOW_UNSAFE_DEVELOPMENT",
  "PHONE_AGENT_PORT",
  "PHONE_AGENT_ADB_REVERSE",
  "PHONE_AGENT_HOST",
  "PHONE_AGENT_DEFAULT_DEVICE",
  "PHONE_AGENT_BRIDGE_URL",
  "OPENCLAW_GATEWAY_URL",
  "HERMES_API_BASE_URL",
  "OPENCODE_SERVER_URL"
] as const;
const originalConfigEnvironment = new Map(configEnvironmentKeys.map((key) => [key, process.env[key]]));
const strongTestToken = "strong-local-test-token-0123456789abcdef";
let tempRoot: string | undefined;

test.beforeEach(async () => {
  tempRoot = await mkdtemp(join(tmpdir(), "android-agent-config-test-"));
  process.env.PHONE_AGENT_CONFIG_PATH = join(tempRoot, "config.json");
  process.env.PATH = "";
  for (const key of configEnvironmentKeys) {
    delete process.env[key];
  }
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
  for (const [key, value] of originalConfigEnvironment) {
    if (value === undefined) {
      delete process.env[key];
    } else {
      process.env[key] = value;
    }
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
  process.env.PHONE_AGENT_TOKEN = strongTestToken;
  assert.equal(getBridgeConfig().token, strongTestToken);
});

test("getBridgeConfig uses Devin defaults and env overrides", () => {
  process.env.PHONE_AGENT_TOKEN = strongTestToken;
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

test("getBridgeConfig rejects short and trivially repetitive tokens", () => {
  for (const weakToken of ["short-development-token", "a".repeat(64)]) {
    process.env.PHONE_AGENT_TOKEN = weakToken;
    assert.throws(() => getBridgeConfig(), /PHONE_AGENT_TOKEN .*at least/);
  }
});

test("getBridgeConfig requires an exact unsafe-development opt-in for weak tokens", (t) => {
  process.env.PHONE_AGENT_TOKEN = "test-token";
  process.env.PHONE_AGENT_ALLOW_UNSAFE_DEVELOPMENT = "true";
  assert.throws(
    () => getBridgeConfig(),
    /PHONE_AGENT_ALLOW_UNSAFE_DEVELOPMENT must be exactly 1 or unset/
  );

  const warning = t.mock.method(console, "warn", () => {});
  process.env.PHONE_AGENT_ALLOW_UNSAFE_DEVELOPMENT = "1";
  assert.equal(getBridgeConfig().token, "test-token");
  assert.equal(warning.mock.callCount(), 1);
});

test("getBridgeConfig parses ports as exact bounded integers", () => {
  process.env.PHONE_AGENT_TOKEN = strongTestToken;
  for (const invalidPort of ["", "0", "12.5", "8788junk", "65536", "1e3", "9007199254740991"]) {
    process.env.PHONE_AGENT_PORT = invalidPort;
    assert.throws(() => getBridgeConfig(), /PHONE_AGENT_PORT must be/);
  }

  process.env.PHONE_AGENT_PORT = "65535";
  assert.equal(getBridgeConfig().port, 65_535);
});

test("getBridgeConfig persists ADB reverse self-healing with an exact env override", () => {
  process.env.PHONE_AGENT_TOKEN = strongTestToken;
  const defaults = getBridgeConfig();
  assert.equal(defaults.adbReverseEnabled, false);

  const hostConfig = JSON.parse(readFileSync(defaults.configPath, "utf8")) as Record<string, unknown>;
  hostConfig.phoneAgentAdbReverse = true;
  writeFileSync(defaults.configPath, `${JSON.stringify(hostConfig, null, 2)}\n`, { mode: 0o600 });
  assert.equal(getBridgeConfig().adbReverseEnabled, true);

  process.env.PHONE_AGENT_ADB_REVERSE = "0";
  assert.equal(getBridgeConfig().adbReverseEnabled, false);
  process.env.PHONE_AGENT_ADB_REVERSE = "1";
  assert.equal(getBridgeConfig().adbReverseEnabled, true);
  process.env.PHONE_AGENT_ADB_REVERSE = "true";
  assert.throws(() => getBridgeConfig(), /PHONE_AGENT_ADB_REVERSE must be exactly/);
});

test("getBridgeConfig validates network-facing URL schemes and credentials", () => {
  process.env.PHONE_AGENT_TOKEN = strongTestToken;

  process.env.PHONE_AGENT_BRIDGE_URL = "ws://127.0.0.1:8788";
  assert.throws(() => getBridgeConfig(), /PHONE_AGENT_BRIDGE_URL/);
  delete process.env.PHONE_AGENT_BRIDGE_URL;

  process.env.OPENCLAW_GATEWAY_URL = "http://127.0.0.1:18789";
  assert.throws(() => getBridgeConfig(), /OPENCLAW_GATEWAY_URL/);
  delete process.env.OPENCLAW_GATEWAY_URL;

  process.env.HERMES_API_BASE_URL = "http://user:password@127.0.0.1:8642/v1";
  assert.throws(() => getBridgeConfig(), /HERMES_API_BASE_URL/);
});
