import { homedir } from "node:os";
import { resolve } from "node:path";

export interface CodexAndroidPhoneMcpConfig {
  command: string;
  args: string[];
  cwd: string;
  bridgeUrl: string;
  phoneAgentToken: string;
  startupTimeoutSec?: number;
  toolTimeoutSec?: number;
}

const ANDROID_PHONE_SECTION = "mcp_servers.android-phone";

export function defaultCodexConfigPath(): string {
  return process.env.CODEX_CONFIG_PATH?.trim() || resolve(homedir(), ".codex", "config.toml");
}

export function codexAndroidPhoneMcpToml(config: CodexAndroidPhoneMcpConfig): string {
  return [
    `[${ANDROID_PHONE_SECTION}]`,
    `command = ${tomlString(config.command)}`,
    `args = [${config.args.map(tomlString).join(", ")}]`,
    `cwd = ${tomlString(config.cwd)}`,
    "enabled = true",
    `startup_timeout_sec = ${config.startupTimeoutSec ?? 20}`,
    `tool_timeout_sec = ${config.toolTimeoutSec ?? 60}`,
    "",
    `[${ANDROID_PHONE_SECTION}.env]`,
    `PHONE_AGENT_BRIDGE_URL = ${tomlString(config.bridgeUrl)}`,
    `PHONE_AGENT_TOKEN = ${tomlString(config.phoneAgentToken)}`
  ].join("\n");
}

export function mergeCodexAndroidPhoneMcpConfig(existingToml: string, androidPhoneToml: string): string {
  const keptLines: string[] = [];
  let skippingAndroidPhoneSection = false;

  for (const line of existingToml.replace(/\s+$/, "").split(/\r?\n/)) {
    const section = tomlSectionName(line);
    if (section) {
      skippingAndroidPhoneSection = section === ANDROID_PHONE_SECTION || section.startsWith(`${ANDROID_PHONE_SECTION}.`);
    }
    if (!skippingAndroidPhoneSection) {
      keptLines.push(line);
    }
  }

  const kept = keptLines.join("\n").trimEnd();
  if (!kept) {
    return `${androidPhoneToml}\n`;
  }
  return `${kept}\n\n${androidPhoneToml}\n`;
}

function tomlSectionName(line: string): string | undefined {
  const match = /^\s*\[([^\]]+)\]\s*$/.exec(line);
  return match?.[1]?.trim();
}

function tomlString(value: string): string {
  return JSON.stringify(value);
}
