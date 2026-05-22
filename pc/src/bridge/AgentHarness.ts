import type { ChatModelOption, ChatSessionSummary } from "../protocol/messages.js";
import type { BridgeConfig } from "./config.js";
import { defaultSessionKeyForDevice } from "./OpenClawChatTypes.js";

export const HARNESS_IDS = ["openclaw", "hermes", "codex"] as const;
export type HarnessId = typeof HARNESS_IDS[number];

export interface HarnessInfo {
  id: HarnessId;
  label: string;
  enabled: boolean;
}

export interface HarnessModelSelection {
  harnessId: HarnessId;
  modelId: string;
  selectionId: string;
}

export const DEFAULT_HARNESS_ID: HarnessId = "openclaw";

const HARNESS_LABELS = {
  openclaw: "OpenClaw",
  hermes: "Hermes",
  codex: "Codex"
} as const satisfies Record<HarnessId, string>;

const HARNESS_PREFIXES = new Set<string>(HARNESS_IDS);

export function harnessLabel(harnessId: HarnessId): string {
  return HARNESS_LABELS[harnessId];
}

export function harnessInfos(config: Pick<BridgeConfig, "hermesApiKey">): HarnessInfo[] {
  return [
    { id: "openclaw", label: harnessLabel("openclaw"), enabled: true },
    { id: "hermes", label: harnessLabel("hermes"), enabled: Boolean(config.hermesApiKey) },
    { id: "codex", label: harnessLabel("codex"), enabled: true }
  ];
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
  config: Pick<BridgeConfig, "openClawChatAgentId" | "openClawChatSessionKey" | "hermesDefaultSessionId">,
  deviceId: string
): string {
  switch (harnessId) {
    case "openclaw":
      return defaultSessionKeyForDevice(config, deviceId);
    case "hermes":
      return `hermes:${sanitizeSessionSegment(config.hermesDefaultSessionId)}-${sanitizeSessionSegment(deviceId)}`;
    case "codex":
      return `codex:${sanitizeSessionSegment(deviceId)}`;
    default: {
      const exhaustive: never = harnessId;
      return exhaustive;
    }
  }
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
