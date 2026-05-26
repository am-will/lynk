import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { homedir, platform } from "node:os";

export interface HostBridgeConfigFile {
  schemaVersion: 1;
  phoneAgentToken: string;
  phoneAgentHost?: string;
  phoneAgentPort?: number;
  phoneAgentDefaultDevice?: string;
  phoneAgentBridgeUrl?: string;
  openClawGatewayUrl?: string;
  openClawGatewayToken?: string;
  openClawGatewayPassword?: string;
  openClawChatAgentId?: string;
  openClawChatSessionKey?: string;
  hermesApiBaseUrl?: string;
  hermesApiKey?: string;
  hermesModel?: string;
  hermesDefaultSessionId?: string;
  hermesRunTimeoutSeconds?: number;
  openAiApiKey?: string;
  openAiRealtimeModel?: string;
  openAiRealtimeVoice?: string;
  openAiWebSearchModel?: string;
  codexAppServerCommand?: string;
  codexAgentCwd?: string;
  codexAppServerApprovalPolicy?: string;
  codexAppServerSandbox?: string;
  discoveredPaths?: Record<string, string>;
}

export interface LoadedHostBridgeConfig {
  path: string;
  config: HostBridgeConfigFile;
  created: boolean;
}

const DEFAULT_CONFIG_FILE = "config.json";

export function defaultHostBridgeConfigDir(): string {
  const explicit = process.env.PHONE_AGENT_CONFIG_DIR?.trim();
  if (explicit) {
    return explicit;
  }
  switch (platform()) {
    case "darwin":
      return join(homedir(), "Library", "Application Support", "Android Agent Bridge");
    case "win32":
      return join(process.env.ProgramData?.trim() || join(homedir(), "AppData", "Roaming"), "AndroidAgentBridge");
    default:
      return join(process.env.XDG_CONFIG_HOME?.trim() || join(homedir(), ".config"), "android-agent-bridge");
  }
}

export function defaultHostBridgeConfigPath(): string {
  return process.env.PHONE_AGENT_CONFIG_PATH?.trim() || join(defaultHostBridgeConfigDir(), DEFAULT_CONFIG_FILE);
}

export function loadOrCreateHostBridgeConfig(path = defaultHostBridgeConfigPath()): LoadedHostBridgeConfig {
  if (existsSync(path)) {
    const parsed = JSON.parse(readFileSync(path, "utf8")) as Partial<HostBridgeConfigFile>;
    const token = firstNonEmpty(parsed.phoneAgentToken);
    if (!token) {
      throw new Error(`Host bridge config at ${path} does not include phoneAgentToken.`);
    }
    return {
      path,
      config: {
        schemaVersion: 1,
        ...parsed,
        phoneAgentToken: token
      },
      created: false
    };
  }

  const config: HostBridgeConfigFile = {
    schemaVersion: 1,
    phoneAgentToken: randomBytes(32).toString("hex"),
    phoneAgentHost: "0.0.0.0",
    phoneAgentPort: 8788,
    phoneAgentDefaultDevice: "openclaw-agent",
    phoneAgentBridgeUrl: "http://127.0.0.1:8788",
    openClawGatewayUrl: "ws://127.0.0.1:18789",
    openClawChatAgentId: "main",
    openClawChatSessionKey: "agent:main:explicit:open-claw-agent",
    hermesApiBaseUrl: "http://127.0.0.1:8642/v1",
    hermesModel: "hermes-agent",
    hermesDefaultSessionId: "hermes-agent",
    hermesRunTimeoutSeconds: 600,
    openAiRealtimeModel: "gpt-realtime-2",
    openAiRealtimeVoice: "marin",
    openAiWebSearchModel: "gpt-5.5",
    codexAppServerCommand: "codex app-server --listen stdio://",
    codexAgentCwd: process.cwd(),
    codexAppServerApprovalPolicy: "never",
    codexAppServerSandbox: "workspace-write"
  };
  writeHostBridgeConfig(path, config);
  return { path, config, created: true };
}

export function writeHostBridgeConfig(path: string, config: HostBridgeConfigFile): void {
  mkdirSyncRecursive(dirname(path));
  writeFileSync(path, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
}

export function redactedHostBridgeConfig(config: HostBridgeConfigFile): Record<string, unknown> {
  return {
    ...config,
    phoneAgentToken: redact(config.phoneAgentToken),
    openClawGatewayToken: redact(config.openClawGatewayToken),
    openClawGatewayPassword: redact(config.openClawGatewayPassword),
    hermesApiKey: redact(config.hermesApiKey),
    openAiApiKey: redact(config.openAiApiKey)
  };
}

export async function ensureHostBridgeConfigDir(): Promise<string> {
  const dir = defaultHostBridgeConfigDir();
  await mkdir(dir, { recursive: true });
  return dir;
}

function mkdirSyncRecursive(path: string): void {
  mkdirSync(path, { recursive: true });
}

function firstNonEmpty(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function redact(value: string | undefined): string | undefined {
  if (!value) {
    return value;
  }
  return value.length <= 8 ? "<redacted>" : `${value.slice(0, 4)}...${value.slice(-4)}`;
}
