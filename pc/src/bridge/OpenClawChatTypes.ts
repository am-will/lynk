import type { ChatHistoryMessage, ChatModelOption, ChatSendMessage, ChatSessionSummary, ChatTaskKind } from "../protocol/messages.js";
import type { HarnessId } from "./AgentHarness.js";
import type { BridgeConfig } from "./config.js";
import type { GatewayChatSendResult, GatewayEventHandler, HarnessChatSendOptions, HarnessChatSteerOptions, HarnessPermissionReplyOptions } from "./chat/ChatTransportTypes.js";
import { requestKeyFromSessionKey } from "./chat/ChatNormalizers.js";

export interface DeviceChatState {
  harnessId: HarnessId;
  sessionKey: string;
  sessionId?: string | null;
  runId?: string | null;
  model?: string | null;
  reasoningEffort?: string | null;
  reasoningStream?: boolean | null;
  fastMode?: boolean | null;
  verboseLevel?: string | null;
  activeTaskKind?: ChatTaskKind | null;
  pendingFirstMessageDisplayName?: boolean;
  lastRealtimeRequestAt?: number | null;
  sessionKeysByHarness: Map<HarnessId, string>;
  modelsByHarness: Map<HarnessId, string | null>;
  modelOptions: Map<string, ChatModelOption>;
  pendingRuns: Map<string, PendingChatRun>;
  completedRunIds: Set<string>;
  ignoredRunIds: Set<string>;
  queuedSends: ChatSendMessage[];
  drainingQueuedSends?: boolean;
  sessionSummaries: Map<string, ChatSessionSummary>;
}

export interface PendingChatRun {
  sessionKey: string;
  sessionId?: string | null;
  taskKind?: ChatTaskKind;
  startedAt: number;
  userMessage?: ChatHistoryMessage;
}

export interface GatewayChatClient {
  addEventListener(handler: GatewayEventHandler): () => void;
  history(sessionKey: string): Promise<unknown>;
  sendChat(options: HarnessChatSendOptions): Promise<GatewayChatSendResult>;
  steerChat?(options: HarnessChatSteerOptions): Promise<GatewayChatSendResult>;
  abort(sessionKey: string, runId?: string): Promise<unknown>;
  listModels(): Promise<unknown>;
  listSessions(limit?: number, harnessId?: HarnessId): Promise<unknown>;
  syncRemoteReplies?(harnessId?: HarnessId, limit?: number): Promise<void>;
  createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }): Promise<unknown>;
  patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown>;
  listCommands(sessionKey?: string): Promise<unknown>;
  effectiveTools(sessionKey: string): Promise<unknown>;
  respondToPermission?(options: HarnessPermissionReplyOptions): Promise<unknown>;
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
    const created = this.createState(deviceId);
    this.devices.set(deviceId, created);
    return created;
  }

  protected createState(deviceId: string): DeviceChatState {
    const sessionKey = defaultSessionKeyForDevice(this.config, deviceId);
    return {
      harnessId: "openclaw",
      sessionKey,
      runId: null,
      model: null,
      reasoningEffort: "medium",
      reasoningStream: null,
      fastMode: null,
      verboseLevel: null,
      pendingFirstMessageDisplayName: false,
      lastRealtimeRequestAt: null,
      sessionKeysByHarness: new Map([["openclaw", sessionKey]]),
      modelsByHarness: new Map(),
      modelOptions: new Map(),
      pendingRuns: new Map(),
      completedRunIds: new Set(),
      ignoredRunIds: new Set(),
      queuedSends: [],
      sessionSummaries: new Map()
    };
  }

  entries(): IterableIterator<[string, DeviceChatState]> {
    return this.devices.entries();
  }

  trackPendingRun(
    state: DeviceChatState,
    runId: string,
    sessionKey: string,
    sessionId?: string | null,
    taskKind: ChatTaskKind = "general",
    userMessage?: ChatHistoryMessage
  ): void {
    state.pendingRuns.set(runId, {
      sessionKey,
      sessionId: sessionId ?? null,
      taskKind,
      startedAt: Date.now(),
      ...(userMessage ? { userMessage } : {})
    });
    state.activeTaskKind = taskKind;
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
  return `OpenAgent (${deviceId})`;
}

function deviceSessionSuffix(deviceId: string): string {
  const normalized = deviceId.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  return normalized || "device";
}
