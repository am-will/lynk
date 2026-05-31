import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { resolveAndroidPhoneMcpLaunchConfig } from "../host/AndroidPhoneMcpLaunch.js";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const pcRoot = resolve(scriptDir, "../..");
const repoRoot = resolve(pcRoot, "..");
const bridgeUrl = process.env.PHONE_AGENT_BRIDGE_URL ?? "http://127.0.0.1:8788";
const phoneAgentToken = process.env.PHONE_AGENT_TOKEN?.trim();
const openClawCommand = process.env.OPENCLAW_AGENT_COMMAND?.trim() || "openclaw";

if (!phoneAgentToken) {
  throw new Error("PHONE_AGENT_TOKEN is required to configure the android-phone MCP server.");
}

const launch = resolveAndroidPhoneMcpLaunchConfig({ pcRoot });
const config = {
  command: launch.command,
  args: launch.args,
  cwd: launch.cwd,
  env: {
    PHONE_AGENT_BRIDGE_URL: bridgeUrl,
    PHONE_AGENT_TOKEN: phoneAgentToken
  }
};

const args = ["mcp", "set", "android-phone", JSON.stringify(config)];

console.log(`Configuring OpenClaw MCP server "android-phone" for ${repoRoot}`);
console.log(`Bridge URL: ${bridgeUrl}`);

await new Promise<void>((resolvePromise, reject) => {
  const child = spawn(openClawCommand, args, {
    cwd: repoRoot,
    stdio: "inherit",
    env: process.env
  });
  child.on("error", (error) => reject(new Error(`Failed to start ${openClawCommand}: ${error.message}`)));
  child.on("close", (code, signal) => {
    if (code === 0) {
      resolvePromise();
      return;
    }
    reject(new Error(`${openClawCommand} ${args.join(" ")} exited with code ${code ?? "null"} signal ${signal ?? "null"}`));
  });
});
