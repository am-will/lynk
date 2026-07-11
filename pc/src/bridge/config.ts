import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { resolveCommand } from "../host/CommandDiscovery.js";
import { loadOrCreateHostBridgeConfig } from "../host/HostConfigStore.js";
import { isPiSdkInstalled } from "../host/PiInstallation.js";

export interface BridgeConfig {
  host: string;
  port: number;
  token: string;
  defaultDeviceId: string;
  bridgeUrl: string;
  openClawGatewayUrl: string;
  openClawGatewayToken?: string;
  openClawGatewayPassword?: string;
  openClawChatAgentId: string;
  openClawChatSessionKey: string;
  hermesApiBaseUrl: string;
  hermesApiKey?: string;
  hermesCliCommand?: string;
  hermesConfigured?: boolean;
  hermesModel: string;
  hermesDefaultSessionId: string;
  hermesRunTimeoutMs: number;
  openAiApiKey?: string;
  openAiRealtimeModel: string;
  openAiRealtimeVoice: string;
  openAiWebSearchModel: string;
  configPath: string;
  codexAppServerCommand: string;
  codexAgentCwd: string;
  codexAppServerApprovalPolicy: string;
  codexAppServerSandbox: string;
  codexConfigured: boolean;
  opencodeServerUrl?: string;
  opencodeServerCommand?: string;
  opencodeAgentCwd?: string;
  opencodeServerUsername?: string;
  opencodeServerPassword?: string;
  opencodeDefaultAgent?: string;
  opencodeRunTimeoutMs?: number;
  opencodeConfigured?: boolean;
  piAgentCwd: string;
  piAgentDir?: string;
  piDefaultModel?: string;
  piRunTimeoutMs: number;
  piConfigured: boolean;
  devinAcpCommand: string;
  devinAgentCwd: string;
  devinRunTimeoutMs: number;
  devinPermissionMode?: string;
  devinConfigured: boolean;
}

function readOpenClawConfig(): unknown {
  const path = process.env.OPENCLAW_CONFIG_PATH?.trim() || join(homedir(), ".openclaw", "openclaw.json");
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return undefined;
  }
}

function nestedString(value: unknown, path: string[]): string | undefined {
  let current = value;
  for (const segment of path) {
    if (!current || typeof current !== "object") {
      return undefined;
    }
    current = (current as Record<string, unknown>)[segment];
  }
  return typeof current === "string" && current.trim() ? current.trim() : undefined;
}

const weakPhoneAgentTokens = new Set(["12345678"]);

function readPhoneAgentToken(configToken: string): string {
  const token = process.env.PHONE_AGENT_TOKEN?.trim() || configToken;
  if (!token) {
    throw new Error("PHONE_AGENT_TOKEN is required. Generate a strong shared token or let the host bridge config create one.");
  }
  if (weakPhoneAgentTokens.has(token)) {
    throw new Error("PHONE_AGENT_TOKEN uses a known weak default. Generate a strong token and save it on both PC and Android.");
  }
  return token;
}

function readPositiveInt(name: string, fallback: number): number {
  const raw = process.env[name]?.trim();
  if (!raw) {
    return fallback;
  }
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer.`);
  }
  return parsed;
}

function readDevinPermissionMode(value: string | undefined): string | undefined {
  const mode = value?.trim();
  if (!mode) return undefined;
  if (!new Set(["accept-edits", "ask", "plan", "bypass"]).has(mode)) {
    throw new Error("DEVIN_PERMISSION_MODE must be accept-edits, ask, plan, or bypass.");
  }
  return mode;
}

export function getBridgeConfig(): BridgeConfig {
  const hostConfig = loadOrCreateHostBridgeConfig();
  const host = hostConfig.config;
  const port = Number.parseInt(process.env.PHONE_AGENT_PORT ?? String(host.phoneAgentPort ?? 8788), 10);
  const openClawConfig = readOpenClawConfig();
  const codexAppServerCommand = process.env.CODEX_APP_SERVER_COMMAND ?? host.codexAppServerCommand ?? "codex app-server --listen stdio://";
  const codexResolution = resolveCommand(codexAppServerCommand);
  const opencodeServerUrl = process.env.OPENCODE_SERVER_URL?.trim() || host.opencodeServerUrl?.trim() || undefined;
  const opencodeServerCommand = process.env.OPENCODE_SERVER_COMMAND ?? host.opencodeServerCommand ?? "opencode serve --hostname 127.0.0.1 --port 4096";
  const opencodeResolution = resolveCommand(opencodeServerCommand);
  const hermesApiKey = process.env.HERMES_API_KEY?.trim() || host.hermesApiKey?.trim() || undefined;
  const hermesCliCommand = process.env.HERMES_COMMAND?.trim() || "hermes";
  const hermesCliResolution = resolveCommand(hermesCliCommand);
  return {
    host: process.env.PHONE_AGENT_HOST ?? host.phoneAgentHost ?? "0.0.0.0",
    port,
    token: readPhoneAgentToken(host.phoneAgentToken),
    defaultDeviceId: process.env.PHONE_AGENT_DEFAULT_DEVICE ?? host.phoneAgentDefaultDevice ?? "openclaw-agent",
    bridgeUrl: process.env.PHONE_AGENT_BRIDGE_URL ?? host.phoneAgentBridgeUrl ?? `http://127.0.0.1:${port}`,
    openClawGatewayUrl: process.env.OPENCLAW_GATEWAY_URL ?? host.openClawGatewayUrl ?? "ws://127.0.0.1:18789",
    openClawGatewayToken: process.env.OPENCLAW_GATEWAY_TOKEN ?? host.openClawGatewayToken ?? nestedString(openClawConfig, ["gateway", "auth", "token"]) ?? nestedString(openClawConfig, ["gateway", "remote", "token"]),
    openClawGatewayPassword: process.env.OPENCLAW_GATEWAY_PASSWORD ?? host.openClawGatewayPassword ?? nestedString(openClawConfig, ["gateway", "auth", "password"]) ?? nestedString(openClawConfig, ["gateway", "remote", "password"]),
    openClawChatAgentId: process.env.OPENCLAW_CHAT_AGENT_ID ?? host.openClawChatAgentId ?? "main",
    openClawChatSessionKey: process.env.OPENCLAW_CHAT_SESSION_KEY ?? host.openClawChatSessionKey ?? "agent:main:explicit:open-claw-agent",
    hermesApiBaseUrl: (process.env.HERMES_API_BASE_URL ?? host.hermesApiBaseUrl ?? "http://127.0.0.1:8642/v1").replace(/\/+$/, ""),
    hermesApiKey,
    hermesCliCommand,
    hermesConfigured: Boolean(hermesApiKey) || hermesCliResolution.available,
    hermesModel: process.env.HERMES_MODEL?.trim() || host.hermesModel?.trim() || "hermes-agent",
    hermesDefaultSessionId: process.env.HERMES_DEFAULT_SESSION_ID?.trim() || host.hermesDefaultSessionId?.trim() || "hermes-agent",
    hermesRunTimeoutMs: readPositiveInt("HERMES_RUN_TIMEOUT_SECONDS", host.hermesRunTimeoutSeconds ?? 600) * 1000,
    openAiApiKey: process.env.OPENAI_API_KEY ?? host.openAiApiKey,
    openAiRealtimeModel: process.env.OPENAI_REALTIME_MODEL ?? host.openAiRealtimeModel ?? "gpt-realtime-2",
    openAiRealtimeVoice: process.env.OPENAI_REALTIME_VOICE ?? host.openAiRealtimeVoice ?? "marin",
    openAiWebSearchModel: process.env.OPENAI_WEB_SEARCH_MODEL ?? host.openAiWebSearchModel ?? "gpt-5.5",
    configPath: hostConfig.path,
    codexAppServerCommand,
    codexAgentCwd: process.env.CODEX_AGENT_CWD ?? host.codexAgentCwd ?? process.cwd(),
    codexAppServerApprovalPolicy: process.env.CODEX_APP_SERVER_APPROVAL_POLICY?.trim() || host.codexAppServerApprovalPolicy || "never",
    codexAppServerSandbox: process.env.CODEX_APP_SERVER_SANDBOX?.trim() || host.codexAppServerSandbox || "workspace-write",
    codexConfigured: codexResolution.available,
    opencodeServerUrl,
    opencodeServerCommand,
    opencodeAgentCwd: process.env.OPENCODE_AGENT_CWD ?? host.opencodeAgentCwd ?? process.cwd(),
    opencodeServerUsername: process.env.OPENCODE_SERVER_USERNAME?.trim() || host.opencodeServerUsername?.trim() || "opencode",
    opencodeServerPassword: process.env.OPENCODE_SERVER_PASSWORD?.trim() || host.opencodeServerPassword?.trim() || undefined,
    opencodeDefaultAgent: process.env.OPENCODE_DEFAULT_AGENT?.trim() || host.opencodeDefaultAgent?.trim() || undefined,
    opencodeRunTimeoutMs: readPositiveInt("OPENCODE_RUN_TIMEOUT_SECONDS", host.opencodeRunTimeoutSeconds ?? 600) * 1000,
    opencodeConfigured: Boolean(opencodeServerUrl) || opencodeResolution.available,
    piAgentCwd: process.env.PI_AGENT_CWD ?? host.piAgentCwd ?? process.cwd(),
    piAgentDir: process.env.PI_AGENT_DIR?.trim() || host.piAgentDir?.trim() || undefined,
    piDefaultModel: process.env.PI_DEFAULT_MODEL?.trim() || host.piDefaultModel?.trim() || undefined,
    piRunTimeoutMs: readPositiveInt("PI_RUN_TIMEOUT_SECONDS", host.piRunTimeoutSeconds ?? 600) * 1000,
    piConfigured: isPiSdkInstalled(),
    devinAcpCommand: process.env.DEVIN_ACP_COMMAND?.trim() || host.devinAcpCommand?.trim() || "devin acp",
    devinAgentCwd: process.env.DEVIN_AGENT_CWD?.trim() || host.devinAgentCwd?.trim() || process.cwd(),
    devinRunTimeoutMs: readPositiveInt("DEVIN_RUN_TIMEOUT_SECONDS", host.devinRunTimeoutSeconds ?? 600) * 1000,
    devinPermissionMode: readDevinPermissionMode(process.env.DEVIN_PERMISSION_MODE?.trim() || host.devinPermissionMode),
    devinConfigured: resolveCommand(process.env.DEVIN_ACP_COMMAND?.trim() || host.devinAcpCommand?.trim() || "devin acp").available
  };
}
