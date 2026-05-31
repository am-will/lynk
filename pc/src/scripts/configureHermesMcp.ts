import { existsSync, readFileSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { resolveAndroidPhoneMcpLaunchConfig } from "../host/AndroidPhoneMcpLaunch.js";
import { resolveHermesConfigPath } from "../host/HermesConfigPath.js";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const pcRoot = resolve(scriptDir, "../..");
const repoRoot = resolve(pcRoot, "..");

interface HermesMcpServerConfig {
  command: string;
  args: string[];
  cwd: string;
  env: Record<string, string>;
  timeout: number;
  connect_timeout: number;
}

function buildAndroidPhoneMcpConfig(options: {
  bridgeUrl: string;
  phoneAgentToken: string;
}): HermesMcpServerConfig {
  const launch = resolveAndroidPhoneMcpLaunchConfig({ pcRoot });
  return {
    command: launch.command,
    args: launch.args,
    cwd: launch.cwd,
    env: {
      PHONE_AGENT_BRIDGE_URL: options.bridgeUrl,
      PHONE_AGENT_TOKEN: options.phoneAgentToken
    },
    timeout: 120,
    connect_timeout: 30
  };
}

function yamlString(value: string): string {
  return JSON.stringify(value);
}

function serverConfigYaml(config: HermesMcpServerConfig): string {
  return [
    "  android_phone:",
    `    command: ${yamlString(config.command)}`,
    "    args:",
    ...config.args.map((arg) => `      - ${yamlString(arg)}`),
    `    cwd: ${yamlString(config.cwd)}`,
    "    env:",
    ...Object.entries(config.env).map(([key, value]) => `      ${key}: ${yamlString(value)}`),
    `    timeout: ${config.timeout}`,
    `    connect_timeout: ${config.connect_timeout}`
  ].join("\n");
}

function mergeHermesMcpConfig(existingYaml: string, serverConfig: HermesMcpServerConfig): string {
  const replacement = serverConfigYaml(serverConfig);
  if (!existingYaml.trim()) {
    return `mcp_servers:\n${replacement}\n`;
  }
  const lines = existingYaml.replace(/\s+$/, "").split(/\r?\n/);
  const mcpIndex = lines.findIndex((line) => /^mcp_servers:\s*$/.test(line));
  if (mcpIndex === -1) {
    return `${lines.join("\n")}\n\nmcp_servers:\n${replacement}\n`;
  }

  const androidIndex = lines.findIndex((line, index) => index > mcpIndex && /^  android_phone:\s*$/.test(line));
  if (androidIndex === -1) {
    const next = [...lines];
    next.splice(mcpIndex + 1, 0, replacement);
    return `${next.join("\n")}\n`;
  }

  let endIndex = androidIndex + 1;
  while (endIndex < lines.length && (/^\s/.test(lines[endIndex] ?? "") || !(lines[endIndex] ?? "").trim())) {
    if (/^  [A-Za-z0-9_-]+:\s*$/.test(lines[endIndex] ?? "")) {
      break;
    }
    endIndex += 1;
  }
  const next = [...lines.slice(0, androidIndex), replacement, ...lines.slice(endIndex)];
  return `${next.join("\n")}\n`;
}

const phoneAgentToken = process.env.PHONE_AGENT_TOKEN?.trim();
if (!phoneAgentToken) {
  throw new Error("PHONE_AGENT_TOKEN is required to configure the android-phone MCP server.");
}

const bridgeUrl = process.env.PHONE_AGENT_BRIDGE_URL ?? "http://127.0.0.1:8788";
const configPath = resolveHermesConfigPath();
const existingYaml = existsSync(configPath) ? readFileSync(configPath, "utf8") : "";
const nextYaml = mergeHermesMcpConfig(existingYaml, buildAndroidPhoneMcpConfig({ bridgeUrl, phoneAgentToken }));

await mkdir(dirname(configPath), { recursive: true });
await writeFile(configPath, nextYaml);

console.log(`Configured Hermes MCP server "android_phone" for ${repoRoot}`);
console.log(`Hermes config: ${configPath}`);
console.log(`Bridge URL: ${bridgeUrl}`);
