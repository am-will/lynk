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

const weakPhoneAgentTokens = new Set([
  "12345678",
  "change-me",
  "password",
  "replace-with-strong-token",
  "secret",
  "test-token",
  "token"
]);
const minimumPhoneAgentTokenLength = 32;
const maximumPhoneAgentTokenLength = 256;
const minimumDistinctTokenCharacters = 8;
const unsafeDevelopmentEnv = "PHONE_AGENT_ALLOW_UNSAFE_DEVELOPMENT";

function readPhoneAgentToken(configToken: string): string {
  const rawEnvironmentToken = process.env.PHONE_AGENT_TOKEN;
  if (rawEnvironmentToken && rawEnvironmentToken !== rawEnvironmentToken.trim()) {
    throw new Error("PHONE_AGENT_TOKEN must not contain leading or trailing whitespace.");
  }
  const token = rawEnvironmentToken?.trim() || configToken;
  if (!token) {
    throw new Error("PHONE_AGENT_TOKEN is required. Generate a strong shared token or let the host bridge config create one.");
  }
  if (/\s|[\u0000-\u001f\u007f]/u.test(token) || token.length > maximumPhoneAgentTokenLength) {
    throw new Error(`PHONE_AGENT_TOKEN must be ${maximumPhoneAgentTokenLength} printable non-whitespace characters or fewer.`);
  }

  const weakness = tokenWeakness(token);
  const allowUnsafeDevelopment = readUnsafeDevelopmentOverride();
  if (weakness && !allowUnsafeDevelopment) {
    throw new Error(
      `PHONE_AGENT_TOKEN ${weakness}. Use the generated 64-character token, or set ${unsafeDevelopmentEnv}=1 only for an isolated development bridge.`
    );
  }
  if (weakness) {
    console.warn(`[security] accepting a weak PHONE_AGENT_TOKEN because ${unsafeDevelopmentEnv}=1; do not expose this bridge to a network.`);
  }
  return token;
}

function readPositiveInt(name: string, fallback: number): number {
  const raw = process.env[name]?.trim();
  return parsePositiveInteger(name, raw || String(fallback));
}

function readPort(configPort: number): number {
  const environmentPort = process.env.PHONE_AGENT_PORT;
  const raw = environmentPort === undefined ? String(configPort) : environmentPort.trim();
  const port = parsePositiveInteger("PHONE_AGENT_PORT", raw);
  if (port > 65_535) {
    throw new Error("PHONE_AGENT_PORT must be an integer between 1 and 65535.");
  }
  return port;
}

function parsePositiveInteger(name: string, raw: string): number {
  if (!/^[1-9]\d*$/u.test(raw)) {
    throw new Error(`${name} must be a positive integer.`);
  }
  const parsed = Number(raw);
  if (!Number.isSafeInteger(parsed) || parsed > Math.floor(Number.MAX_SAFE_INTEGER / 1_000)) {
    throw new Error(`${name} must be a positive safe integer.`);
  }
  return parsed;
}

function readUnsafeDevelopmentOverride(): boolean {
  const raw = process.env[unsafeDevelopmentEnv]?.trim();
  if (!raw) {
    return false;
  }
  if (raw !== "1") {
    throw new Error(`${unsafeDevelopmentEnv} must be exactly 1 or unset.`);
  }
  return true;
}

function tokenWeakness(token: string): string | undefined {
  if (weakPhoneAgentTokens.has(token.toLowerCase())) {
    return "uses a known weak default";
  }
  if (token.length < minimumPhoneAgentTokenLength) {
    return `must contain at least ${minimumPhoneAgentTokenLength} characters`;
  }
  if (new Set(token).size < minimumDistinctTokenCharacters) {
    return `must contain at least ${minimumDistinctTokenCharacters} distinct characters`;
  }
  return undefined;
}

function readRequiredText(name: string, value: string): string {
  const normalized = value.trim();
  if (!normalized || /[\u0000-\u001f\u007f]/u.test(normalized)) {
    throw new Error(`${name} must be a non-empty value without control characters.`);
  }
  return normalized;
}

function readUrl(name: string, value: string, protocols: readonly string[]): string {
  const normalized = readRequiredText(name, value);
  let parsed: URL;
  try {
    parsed = new URL(normalized);
  } catch {
    throw new Error(`${name} must be a valid ${protocols.join(" or ")} URL.`);
  }
  if (!protocols.includes(parsed.protocol) || parsed.username || parsed.password) {
    throw new Error(`${name} must be a credential-free ${protocols.join(" or ")} URL.`);
  }
  return normalized;
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
  const port = readPort(host.phoneAgentPort ?? 8788);
  const bridgeHost = readRequiredText("PHONE_AGENT_HOST", process.env.PHONE_AGENT_HOST ?? host.phoneAgentHost ?? "0.0.0.0");
  const bridgeUrl = readUrl(
    "PHONE_AGENT_BRIDGE_URL",
    process.env.PHONE_AGENT_BRIDGE_URL ?? host.phoneAgentBridgeUrl ?? `http://127.0.0.1:${port}`,
    ["http:", "https:"]
  );
  const openClawGatewayUrl = readUrl(
    "OPENCLAW_GATEWAY_URL",
    process.env.OPENCLAW_GATEWAY_URL ?? host.openClawGatewayUrl ?? "ws://127.0.0.1:18789",
    ["ws:", "wss:"]
  );
  const hermesApiBaseUrl = readUrl(
    "HERMES_API_BASE_URL",
    process.env.HERMES_API_BASE_URL ?? host.hermesApiBaseUrl ?? "http://127.0.0.1:8642/v1",
    ["http:", "https:"]
  ).replace(/\/+$/, "");
  const openClawConfig = readOpenClawConfig();
  const codexAppServerCommand = process.env.CODEX_APP_SERVER_COMMAND ?? host.codexAppServerCommand ?? "codex app-server --listen stdio://";
  const codexResolution = resolveCommand(codexAppServerCommand);
  const rawOpenCodeServerUrl = process.env.OPENCODE_SERVER_URL?.trim() || host.opencodeServerUrl?.trim() || undefined;
  const opencodeServerUrl = rawOpenCodeServerUrl
    ? readUrl("OPENCODE_SERVER_URL", rawOpenCodeServerUrl, ["http:", "https:"])
    : undefined;
  const opencodeServerCommand = process.env.OPENCODE_SERVER_COMMAND ?? host.opencodeServerCommand ?? "opencode serve --hostname 127.0.0.1 --port 4096";
  const opencodeResolution = resolveCommand(opencodeServerCommand);
  const hermesApiKey = process.env.HERMES_API_KEY?.trim() || host.hermesApiKey?.trim() || undefined;
  const hermesCliCommand = process.env.HERMES_COMMAND?.trim() || "hermes";
  const hermesCliResolution = resolveCommand(hermesCliCommand);
  return {
    host: bridgeHost,
    port,
    token: readPhoneAgentToken(host.phoneAgentToken),
    defaultDeviceId: readRequiredText("PHONE_AGENT_DEFAULT_DEVICE", process.env.PHONE_AGENT_DEFAULT_DEVICE ?? host.phoneAgentDefaultDevice ?? "openclaw-agent"),
    bridgeUrl,
    openClawGatewayUrl,
    openClawGatewayToken: process.env.OPENCLAW_GATEWAY_TOKEN ?? host.openClawGatewayToken ?? nestedString(openClawConfig, ["gateway", "auth", "token"]) ?? nestedString(openClawConfig, ["gateway", "remote", "token"]),
    openClawGatewayPassword: process.env.OPENCLAW_GATEWAY_PASSWORD ?? host.openClawGatewayPassword ?? nestedString(openClawConfig, ["gateway", "auth", "password"]) ?? nestedString(openClawConfig, ["gateway", "remote", "password"]),
    openClawChatAgentId: process.env.OPENCLAW_CHAT_AGENT_ID ?? host.openClawChatAgentId ?? "main",
    openClawChatSessionKey: process.env.OPENCLAW_CHAT_SESSION_KEY ?? host.openClawChatSessionKey ?? "agent:main:explicit:open-claw-agent",
    hermesApiBaseUrl,
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
