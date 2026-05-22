import type { ChatSessionSummary } from "../protocol/messages.js";
import type { BridgeConfig } from "./config.js";
import type { GatewayChatSendResult, GatewayEventHandler } from "./OpenClawGatewayChatClient.js";
import { requestKeyFromSessionKey } from "./OpenClawGatewayChatClient.js";

export interface DeviceChatState {
  sessionKey: string;
  sessionId?: string | null;
  runId?: string | null;
  model?: string | null;
  reasoningEffort?: string | null;
  reasoningStream?: boolean | null;
  fastMode?: boolean | null;
  verboseLevel?: string | null;
  pendingFirstMessageDisplayName?: boolean;
  lastRealtimeRequestAt?: number | null;
  pendingRuns: Map<string, PendingChatRun>;
  sessionSummaries: Map<string, ChatSessionSummary>;
}

export interface PendingChatRun {
  sessionKey: string;
  sessionId?: string | null;
  startedAt: number;
}

export interface GatewayChatClient {
  addEventListener(handler: GatewayEventHandler): () => void;
  history(sessionKey: string): Promise<unknown>;
  sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult>;
  abort(sessionKey: string, runId?: string): Promise<unknown>;
  listModels(): Promise<unknown>;
  listSessions(limit?: number): Promise<unknown>;
  createSession(options: { key?: string; label?: string; model?: string }): Promise<unknown>;
  patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown>;
  listCommands(): Promise<unknown>;
  effectiveTools(sessionKey: string): Promise<unknown>;
  health(): Promise<unknown>;
  close(): void;
}

export class DeviceChatStateStore {
  private readonly devices = new Map<string, DeviceChatState>();

  constructor(private readonly config: Pick<BridgeConfig, "openClawChatAgentId" | "openClawChatSessionKey">) {}

  stateFor(deviceId: string): DeviceChatState {
    const existing = this.devices.get(deviceId);
    if (existing) {
      return existing;
    }
    const created: DeviceChatState = {
      sessionKey: defaultSessionKeyForDevice(this.config, deviceId),
      runId: null,
      model: null,
      reasoningEffort: "medium",
      reasoningStream: null,
      fastMode: null,
      verboseLevel: null,
      pendingFirstMessageDisplayName: false,
      lastRealtimeRequestAt: null,
      pendingRuns: new Map(),
      sessionSummaries: new Map()
    };
    this.devices.set(deviceId, created);
    return created;
  }

  entries(): IterableIterator<[string, DeviceChatState]> {
    return this.devices.entries();
  }

  trackPendingRun(
    state: DeviceChatState,
    runId: string,
    sessionKey: string,
    sessionId?: string | null
  ): void {
    state.pendingRuns.set(runId, {
      sessionKey,
      sessionId: sessionId ?? null,
      startedAt: Date.now()
    });
  }
}

export function defaultSessionKeyForDevice(
  config: Pick<BridgeConfig, "openClawChatAgentId" | "openClawChatSessionKey">,
  deviceId: string
): string {
  const baseRequestKey = requestKeyFromSessionKey(config.openClawChatSessionKey, config.openClawChatAgentId);
  const deviceSuffix = deviceSessionSuffix(deviceId);
  const requestKey = baseRequestKey.endsWith(`-${deviceSuffix}`)
    ? baseRequestKey
    : `${baseRequestKey}-${deviceSuffix}`;
  return `agent:${config.openClawChatAgentId}:explicit:${requestKey}`;
}

export function defaultSessionLabelForDevice(deviceId: string): string {
  return `Open Claw Agent (${deviceId})`;
}

function deviceSessionSuffix(deviceId: string): string {
  const normalized = deviceId.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  return normalized || "device";
}
