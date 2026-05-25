import { randomUUID } from "node:crypto";
import type { BridgeConfig } from "./config.js";
import {
  isDuplicateSessionLabelError,
  numberedLabel,
  realtimeSessionLabel
} from "./OpenClawChatPolicy.js";
import type { DeviceChatState, GatewayChatClient } from "./OpenClawChatTypes.js";
import { DeviceChatStateStore } from "./OpenClawChatTypes.js";

const REALTIME_CHAT_REUSE_WINDOW_MS = 15 * 60 * 1000;

interface RealtimeChatSessionsOptions {
  config: Pick<BridgeConfig, "openClawChatAgentId">;
  client: GatewayChatClient;
  states: DeviceChatStateStore;
  sendState(deviceId: string, status?: string): void;
  refreshDevice(deviceId: string): Promise<void>;
}

export class OpenClawRealtimeSessions {
  constructor(private readonly options: RealtimeChatSessionsOptions) {}

  async ensureFreshRealtimeSession(deviceId: string, firstRequestText: string): Promise<void> {
    const state = this.options.states.stateFor(deviceId);
    const lastRealtimeRequestAt = state.lastRealtimeRequestAt ?? 0;
    if (lastRealtimeRequestAt > 0 && Date.now() - lastRealtimeRequestAt <= REALTIME_CHAT_REUSE_WINDOW_MS) {
      return;
    }

    const baseLabel = realtimeSessionLabel(firstRequestText);
    const existingLabels = new Set<string>();
    const { created, requestKey } = await this.createRealtimeSessionWithUniqueLabel(deviceId, state, baseLabel, existingLabels);
    const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
    const key = typeof record.key === "string" && record.key.trim() ? record.key.trim() : undefined;
    state.sessionKey = key ?? fallbackSessionKey(this.options.config, state, requestKey);
    state.sessionId = typeof record.sessionId === "string" ? record.sessionId : null;
    state.runId = null;
    state.pendingFirstMessageDisplayName = false;
    this.options.sendState(deviceId, "Started a new realtime chat");
    await this.options.refreshDevice(deviceId);
  }

  private async createRealtimeSessionWithUniqueLabel(
    deviceId: string,
    state: DeviceChatState,
    baseLabel: string,
    existingLabels: Set<string>
  ): Promise<{ created: unknown; requestKey: string }> {
    let lastDuplicateError: unknown;
    for (let attempt = 0; attempt < 25; attempt += 1) {
      const label = numberedLabel(baseLabel, attempt);
      if (existingLabels.has(label.toLowerCase())) {
        continue;
      }
      const requestKey = realtimeRequestKey(deviceId, state);
      try {
        const created = await this.options.client.createSession({
          key: requestKey,
          label,
          model: state.model ?? undefined
        });
        return { created, requestKey };
      } catch (error) {
        if (!isDuplicateSessionLabelError(error)) {
          throw error;
        }
        lastDuplicateError = error;
        existingLabels.add(label.toLowerCase());
      }
    }

    const requestKey = realtimeRequestKey(deviceId, state);
    const suffix = Date.now().toString(36).slice(-4);
    try {
      const created = await this.options.client.createSession({
        key: requestKey,
        label: numberedLabel(`${baseLabel} ${suffix}`, 0),
        model: state.model ?? undefined
      });
      return { created, requestKey };
    } catch {
      throw lastDuplicateError instanceof Error ? lastDuplicateError : new Error("Could not create a unique realtime chat session label");
    }
  }
}

function realtimeRequestKey(deviceId: string, state: DeviceChatState): string {
  const requestKey = `realtime-${deviceId}-${randomUUID()}`;
  return state.harnessId === "openclaw" ? requestKey : `${state.harnessId}:${requestKey}`;
}

function fallbackSessionKey(
  config: Pick<BridgeConfig, "openClawChatAgentId">,
  state: DeviceChatState,
  requestKey: string
): string {
  if (state.harnessId === "openclaw") {
    return `agent:${config.openClawChatAgentId}:explicit:${requestKey}`;
  }
  return requestKey;
}
