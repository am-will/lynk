import assert from "node:assert/strict";
import test from "node:test";
import { codexAndroidPhoneMcpToml, mergeCodexAndroidPhoneMcpConfig } from "./CodexMcpConfig.js";

test("codex MCP merge replaces stale android-phone config", () => {
  const existing = [
    "model = \"gpt-5.5\"",
    "",
    "[mcp_servers.android-phone]",
    "command = \"/Users/example/Applications/android-agent/pc/node_modules/.bin/tsx\"",
    "args = [\"/Users/example/Applications/android-agent/pc/src/mcp/androidPhoneServer.ts\"]",
    "",
    "[mcp_servers.android-phone.env]",
    "PHONE_AGENT_BRIDGE_URL = \"http://127.0.0.1:8787\"",
    "",
    "[mcp_servers.node_repl]",
    "command = \"node\""
  ].join("\n");
  const replacement = codexAndroidPhoneMcpToml({
    command: "/opt/android-agent/pc/node_modules/.bin/tsx",
    args: ["/opt/android-agent/pc/src/mcp/androidPhoneServer.ts"],
    cwd: "/opt/android-agent/pc",
    bridgeUrl: "http://127.0.0.1:8788",
    phoneAgentToken: "secret-token"
  });

  const merged = mergeCodexAndroidPhoneMcpConfig(existing, replacement);

  assert.match(merged, /model = "gpt-5\.5"/);
  assert.match(merged, /\[mcp_servers\.node_repl\]/);
  assert.match(merged, /\/opt\/android-agent\/pc\/src\/mcp\/androidPhoneServer\.ts/);
  assert.match(merged, /PHONE_AGENT_BRIDGE_URL = "http:\/\/127\.0\.0\.1:8788"/);
  assert.match(merged, /PHONE_AGENT_TOKEN = "secret-token"/);
  assert.doesNotMatch(merged, /Applications\/android-agent/);
  assert.doesNotMatch(merged, /127\.0\.0\.1:8787/);
});

test("codex MCP merge creates config from empty TOML", () => {
  const replacement = codexAndroidPhoneMcpToml({
    command: "/repo/pc/node_modules/.bin/tsx",
    args: ["/repo/pc/src/mcp/androidPhoneServer.ts"],
    cwd: "/repo/pc",
    bridgeUrl: "http://127.0.0.1:8788",
    phoneAgentToken: "token"
  });

  assert.equal(mergeCodexAndroidPhoneMcpConfig("", replacement), `${replacement}\n`);
});
