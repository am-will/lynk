import { existsSync, readFileSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  codexAndroidPhoneMcpToml,
  defaultCodexConfigPath,
  mergeCodexAndroidPhoneMcpConfig
} from "../host/CodexMcpConfig.js";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const pcRoot = resolve(scriptDir, "../..");
const repoRoot = resolve(pcRoot, "..");
const tsxBin = resolve(pcRoot, "node_modules/.bin/tsx");
const mcpServer = resolve(pcRoot, "src/mcp/androidPhoneServer.ts");

if (!existsSync(tsxBin)) {
  throw new Error(`Missing tsx executable at ${tsxBin}. Run npm install in ${pcRoot}.`);
}

const phoneAgentToken = process.env.PHONE_AGENT_TOKEN?.trim();
if (!phoneAgentToken) {
  throw new Error("PHONE_AGENT_TOKEN is required to configure the android-phone MCP server.");
}

const bridgeUrl = process.env.PHONE_AGENT_BRIDGE_URL ?? "http://127.0.0.1:8788";
const configPath = defaultCodexConfigPath();
const existingToml = existsSync(configPath) ? readFileSync(configPath, "utf8") : "";
const nextToml = mergeCodexAndroidPhoneMcpConfig(
  existingToml,
  codexAndroidPhoneMcpToml({
    command: tsxBin,
    args: [mcpServer],
    cwd: pcRoot,
    bridgeUrl,
    phoneAgentToken
  })
);

await mkdir(dirname(configPath), { recursive: true });
await writeFile(configPath, nextToml, { mode: 0o600 });

console.log(`Configured Codex MCP server "android-phone" for ${repoRoot}`);
console.log(`Codex config: ${configPath}`);
console.log(`Bridge URL: ${bridgeUrl}`);
