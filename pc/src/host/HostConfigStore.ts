import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { homedir, platform } from "node:os";
import { fileURLToPath } from "node:url";
import { z } from "zod";

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
  opencodeServerUrl?: string;
  opencodeServerCommand?: string;
  opencodeAgentCwd?: string;
  opencodeServerUsername?: string;
  opencodeServerPassword?: string;
  opencodeDefaultAgent?: string;
  opencodeRunTimeoutSeconds?: number;
  piAgentCwd?: string;
  piAgentDir?: string;
  piDefaultModel?: string;
  piRunTimeoutSeconds?: number;
  devinAcpCommand?: string;
  devinAgentCwd?: string;
  devinRunTimeoutSeconds?: number;
  devinPermissionMode?: string;
  discoveredPaths?: Record<string, string>;
}

export interface LoadedHostBridgeConfig {
  path: string;
  config: HostBridgeConfigFile;
  created: boolean;
}

const positiveSecondsSchema = z.number().int().positive().max(Math.floor(Number.MAX_SAFE_INTEGER / 1_000));
const hostBridgeConfigFileSchema = z.object({
  schemaVersion: z.literal(1),
  phoneAgentToken: z.string().trim().min(1),
  phoneAgentHost: z.string().min(1).optional(),
  phoneAgentPort: z.number().int().min(1).max(65_535).optional(),
  phoneAgentDefaultDevice: z.string().min(1).optional(),
  phoneAgentBridgeUrl: z.string().min(1).optional(),
  openClawGatewayUrl: z.string().min(1).optional(),
  openClawGatewayToken: z.string().optional(),
  openClawGatewayPassword: z.string().optional(),
  openClawChatAgentId: z.string().min(1).optional(),
  openClawChatSessionKey: z.string().min(1).optional(),
  hermesApiBaseUrl: z.string().min(1).optional(),
  hermesApiKey: z.string().optional(),
  hermesModel: z.string().min(1).optional(),
  hermesDefaultSessionId: z.string().min(1).optional(),
  hermesRunTimeoutSeconds: positiveSecondsSchema.optional(),
  openAiApiKey: z.string().optional(),
  openAiRealtimeModel: z.string().min(1).optional(),
  openAiRealtimeVoice: z.string().min(1).optional(),
  openAiWebSearchModel: z.string().min(1).optional(),
  codexAppServerCommand: z.string().min(1).optional(),
  codexAgentCwd: z.string().min(1).optional(),
  codexAppServerApprovalPolicy: z.string().min(1).optional(),
  codexAppServerSandbox: z.string().min(1).optional(),
  opencodeServerUrl: z.string().optional(),
  opencodeServerCommand: z.string().min(1).optional(),
  opencodeAgentCwd: z.string().min(1).optional(),
  opencodeServerUsername: z.string().min(1).optional(),
  opencodeServerPassword: z.string().optional(),
  opencodeDefaultAgent: z.string().optional(),
  opencodeRunTimeoutSeconds: positiveSecondsSchema.optional(),
  piAgentCwd: z.string().min(1).optional(),
  piAgentDir: z.string().optional(),
  piDefaultModel: z.string().optional(),
  piRunTimeoutSeconds: positiveSecondsSchema.optional(),
  devinAcpCommand: z.string().min(1).optional(),
  devinAgentCwd: z.string().min(1).optional(),
  devinRunTimeoutSeconds: positiveSecondsSchema.optional(),
  devinPermissionMode: z.string().optional(),
  discoveredPaths: z.record(z.string(), z.string()).optional()
}).strict();

const DEFAULT_CONFIG_FILE = "config.json";
const hostScriptDir = dirname(fileURLToPath(import.meta.url));
const defaultAgentCwd = resolve(hostScriptDir, "../..");

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
    let decoded: unknown;
    try {
      decoded = JSON.parse(readFileSync(path, "utf8"));
    } catch (error) {
      throw new Error(`Host bridge config at ${path} is not valid JSON: ${error instanceof Error ? error.message : String(error)}`);
    }
    const parsed = hostBridgeConfigFileSchema.safeParse(decoded);
    if (!parsed.success) {
      const details = parsed.error.issues
        .map((issue) => `${issue.path.join(".") || "config"}: ${issue.message}`)
        .join("; ");
      throw new Error(`Host bridge config at ${path} is invalid: ${details}`);
    }
    return {
      path,
      config: parsed.data,
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
    codexAgentCwd: defaultAgentCwd,
    codexAppServerApprovalPolicy: "never",
    codexAppServerSandbox: "workspace-write",
    opencodeServerUrl: "",
    opencodeServerCommand: "opencode serve --hostname 127.0.0.1 --port 4096",
    opencodeAgentCwd: defaultAgentCwd,
    opencodeServerUsername: "opencode",
    opencodeRunTimeoutSeconds: 600,
    piAgentCwd: defaultAgentCwd,
    piAgentDir: "",
    piDefaultModel: "",
    piRunTimeoutSeconds: 600,
    devinAcpCommand: "devin acp",
    devinAgentCwd: defaultAgentCwd,
    devinRunTimeoutSeconds: 600,
    devinPermissionMode: ""
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
    openAiApiKey: redact(config.openAiApiKey),
    opencodeServerPassword: redact(config.opencodeServerPassword)
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

function redact(value: string | undefined): string | undefined {
  if (!value) {
    return value;
  }
  return value.length <= 8 ? "<redacted>" : `${value.slice(0, 4)}...${value.slice(-4)}`;
}
