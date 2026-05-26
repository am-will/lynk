import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { getBridgeConfig } from "./config.js";

const originalToken = process.env.PHONE_AGENT_TOKEN;
const originalConfigPath = process.env.PHONE_AGENT_CONFIG_PATH;
const originalPath = process.env.PATH;
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
