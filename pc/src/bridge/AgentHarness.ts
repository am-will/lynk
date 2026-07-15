import { randomUUID } from "node:crypto";
import type { ChatModelOption, ChatSessionSummary } from "../protocol/messages.js";
import type { BridgeConfig } from "./config.js";
import { defaultSessionKeyForDevice } from "./OpenClawChatTypes.js";

export const HARNESS_IDS = ["openclaw", "hermes", "codex", "opencode", "pi", "devin"] as const;
export type HarnessId = typeof HARNESS_IDS[number];

export interface HarnessInfo {
  id: HarnessId;
  label: string;
  enabled: boolean;
  supportsWorkspaces: boolean;
}

type HarnessEnabledConfig = Pick<BridgeConfig, "hermesApiKey" | "hermesConfigured" | "codexConfigured" | "opencodeConfigured" | "piConfigured" | "devinConfigured">;

type DefaultSessionKeyConfig = Pick<BridgeConfig, "openClawChatAgentId" | "openClawChatSessionKey" | "hermesDefaultSessionId">;

export interface HarnessDescriptor {
  id: HarnessId;
  label: string;
  supportsWorkspaces: boolean;
  requiresExplicitSessionCreation: boolean;
  enabled(config: HarnessEnabledConfig): boolean;
  defaultSessionKey(config: DefaultSessionKeyConfig, deviceId: string): string;
  readinessAction: string;
  recoveryAction: string;
}

export interface HarnessModelSelection {
  harnessId: HarnessId;
  modelId: string;
  selectionId: string;
}

export const DEFAULT_HARNESS_ID: HarnessId = "openclaw";

const HARNESS_DESCRIPTORS = {
  openclaw: {
    id: "openclaw",
    label: "OpenClaw",
    supportsWorkspaces: false,
    requiresExplicitSessionCreation: false,
    enabled: () => true,
    defaultSessionKey: (config, deviceId) => defaultSessionKeyForDevice(config, deviceId),
    readinessAction: "Install and start OpenClaw Gateway, then run host integration refresh.",
    recoveryAction: "Start OpenClaw Gateway with `openclaw gateway start` or choose a healthy harness in the model picker."
  },
  hermes: {
    id: "hermes",
    label: "Hermes",
    supportsWorkspaces: false,
    requiresExplicitSessionCreation: false,
    enabled: (config) => Boolean(config.hermesConfigured ?? config.hermesApiKey),
    defaultSessionKey: (config, deviceId) => `hermes:${sanitizeSessionSegment(config.hermesDefaultSessionId)}-${sanitizeSessionSegment(deviceId)}`,
    readinessAction: "Set HERMES_API_KEY or configure Hermes in the host bridge config, then run host integration refresh.",
    recoveryAction: "Verify `HERMES_API_BASE_URL` points at a Lynk-compatible Hermes runs API and that `HERMES_API_KEY` is set."
  },
  codex: {
    id: "codex",
    label: "Codex",
    supportsWorkspaces: true,
    requiresExplicitSessionCreation: false,
    enabled: (config) => config.codexConfigured,
    defaultSessionKey: (_config, deviceId) => `codex:${sanitizeSessionSegment(deviceId)}`,
    readinessAction: "Install Codex CLI with app-server support, then run host integration refresh.",
    recoveryAction: "Verify the Codex app-server command and workspace are configured, then try again."
  },
  opencode: {
    id: "opencode",
    label: "OpenCode",
    supportsWorkspaces: true,
    requiresExplicitSessionCreation: false,
    enabled: (config) => Boolean(config.opencodeConfigured),
    defaultSessionKey: (_config, deviceId) => `opencode:${sanitizeSessionSegment(deviceId)}`,
    readinessAction: "Install OpenCode CLI or configure OPENCODE_SERVER_URL, then run host integration refresh.",
    recoveryAction: "Verify the OpenCode server URL or serve command and workspace are configured, then try again."
  },
  pi: {
    id: "pi",
    label: "Pi",
    supportsWorkspaces: true,
    requiresExplicitSessionCreation: false,
    enabled: (config) => Boolean(config.piConfigured),
    defaultSessionKey: (_config, deviceId) => `pi:${sanitizeSessionSegment(deviceId)}`,
    readinessAction: "Configure Pi credentials and available models in the Pi agent directory, then run host integration refresh.",
    recoveryAction: "Verify Pi SDK credentials, model availability, and workspace configuration, then try again."
  },
  devin: {
    id: "devin",
    label: "Devin",
    supportsWorkspaces: true,
    requiresExplicitSessionCreation: true,
    enabled: (config) => Boolean(config.devinConfigured),
    defaultSessionKey: (_config, deviceId) => `devin:${sanitizeSessionSegment(deviceId)}`,
    readinessAction: "Install and authenticate the Devin CLI, then run host integration refresh.",
    recoveryAction: "Verify `devin auth status` succeeds and the ACP command is configured, then try again."
  }
} as const satisfies Record<HarnessId, HarnessDescriptor>;

const HARNESS_PREFIXES = new Set<string>(HARNESS_IDS);

export function harnessDescriptor(harnessId: HarnessId): HarnessDescriptor {
  return HARNESS_DESCRIPTORS[harnessId];
}

export function harnessDescriptors(): HarnessDescriptor[] {
  return HARNESS_IDS.map((id) => HARNESS_DESCRIPTORS[id]);
}

export function harnessLabel(harnessId: HarnessId): string {
  return harnessDescriptor(harnessId).label;
}

export function harnessInfos(config: HarnessEnabledConfig): HarnessInfo[] {
  return harnessDescriptors().map((descriptor) => ({
    id: descriptor.id,
    label: descriptor.label,
    enabled: descriptor.enabled(config),
    supportsWorkspaces: descriptor.supportsWorkspaces
  }));
}

export function isWorkspaceAwareHarness(harnessId: HarnessId): boolean {
  return harnessDescriptor(harnessId).supportsWorkspaces;
}

export function requiresExplicitSessionCreation(harnessId: HarnessId): boolean {
  return harnessDescriptor(harnessId).requiresExplicitSessionCreation;
}

export function encodeHarnessModel(harnessId: HarnessId, modelId: string): string {
  const trimmed = modelId.trim();
  return harnessId === DEFAULT_HARNESS_ID ? trimmed : `${harnessId}:${trimmed}`;
}

export function parseHarnessModel(value: string | undefined | null): HarnessModelSelection | undefined {
  const trimmed = value?.trim();
  if (!trimmed) {
    return undefined;
  }
  const separatorIndex = trimmed.indexOf(":");
  if (separatorIndex > 0) {
    const prefix = trimmed.slice(0, separatorIndex).toLowerCase();
    if (HARNESS_PREFIXES.has(prefix)) {
      const modelId = trimmed.slice(separatorIndex + 1).trim();
      if (modelId) {
        const harnessId = prefix as HarnessId;
        return {
          harnessId,
          modelId,
          selectionId: encodeHarnessModel(harnessId, modelId)
        };
      }
    }
  }
  return {
    harnessId: DEFAULT_HARNESS_ID,
    modelId: trimmed,
    selectionId: encodeHarnessModel(DEFAULT_HARNESS_ID, trimmed)
  };
}

export function isHarnessId(value: string | undefined | null): value is HarnessId {
  return Boolean(value && HARNESS_PREFIXES.has(value));
}

export function harnessForSessionKey(sessionKey: string | undefined | null): HarnessId {
  const prefix = sessionKey?.split(":", 1)[0]?.toLowerCase();
  return isHarnessId(prefix) ? prefix : DEFAULT_HARNESS_ID;
}

export function defaultSessionKeyForHarness(
  harnessId: HarnessId,
  config: DefaultSessionKeyConfig,
  deviceId: string
): string {
  return harnessDescriptor(harnessId).defaultSessionKey(config, deviceId);
}

export interface RealtimeHarnessSessionKeys {
  requestKey: string;
  fallbackSessionKey: string;
}

export function realtimeSessionKeysForHarness(
  harnessId: HarnessId,
  deviceId: string,
  config: Pick<BridgeConfig, "openClawChatAgentId">
): RealtimeHarnessSessionKeys {
  const baseRequestKey = `realtime-${sanitizeSessionSegment(deviceId)}-${randomUUID()}`;
  const requestKey = harnessId === DEFAULT_HARNESS_ID ? baseRequestKey : `${harnessId}:${baseRequestKey}`;
  return {
    requestKey,
    fallbackSessionKey: harnessId === DEFAULT_HARNESS_ID
      ? `agent:${config.openClawChatAgentId}:explicit:${requestKey}`
      : requestKey
  };
}

export function namespaceModelOption(model: ChatModelOption, harnessId: HarnessId): ChatModelOption {
  const modelId = model.modelId ?? model.id;
  return {
    ...model,
    id: encodeHarnessModel(harnessId, modelId),
    modelId,
    harnessId,
    harnessLabel: harnessLabel(harnessId),
    provider: model.provider ?? harnessId
  };
}

export function namespaceSessionSummary(session: ChatSessionSummary, harnessId: HarnessId): ChatSessionSummary {
  return {
    ...session,
    harnessId,
    harnessLabel: harnessLabel(harnessId),
    model: session.model ? encodeHarnessModel(harnessId, session.model) : session.model,
    modelProvider: session.modelProvider ?? harnessId
  };
}

function sanitizeSessionSegment(value: string): string {
  return value.trim().toLowerCase().replace(/[^a-z0-9_.:-]+/g, "-").replace(/^-+|-+$/g, "") || "default";
}
