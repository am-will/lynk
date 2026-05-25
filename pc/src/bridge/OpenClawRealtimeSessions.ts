import type { BridgeConfig } from "./config.js";
import type { RealtimeHarnessSessionKeys } from "./AgentHarness.js";
import { realtimeSessionKeysForHarness } from "./AgentHarness.js";
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
    const { created, keys } = await this.createRealtimeSessionWithUniqueLabel(deviceId, state, baseLabel, existingLabels);
    const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
    const key = typeof record.key === "string" && record.key.trim() ? record.key.trim() : undefined;
    state.sessionKey = key ?? keys.fallbackSessionKey;
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
  ): Promise<{ created: unknown; keys: RealtimeHarnessSessionKeys }> {
    let lastDuplicateError: unknown;
    for (let attempt = 0; attempt < 25; attempt += 1) {
      const label = numberedLabel(baseLabel, attempt);
      if (existingLabels.has(label.toLowerCase())) {
        continue;
      }
      const keys = realtimeSessionKeysForHarness(state.harnessId, deviceId, this.options.config);
      try {
        const created = await this.options.client.createSession({
          key: keys.requestKey,
          label,
          model: state.model ?? undefined
        });
        return { created, keys };
      } catch (error) {
        if (!isDuplicateSessionLabelError(error)) {
          throw error;
        }
        lastDuplicateError = error;
        existingLabels.add(label.toLowerCase());
      }
    }

    const keys = realtimeSessionKeysForHarness(state.harnessId, deviceId, this.options.config);
    const suffix = Date.now().toString(36).slice(-4);
    try {
      const created = await this.options.client.createSession({
        key: keys.requestKey,
        label: numberedLabel(`${baseLabel} ${suffix}`, 0),
        model: state.model ?? undefined
      });
      return { created, keys };
    } catch {
      throw lastDuplicateError instanceof Error ? lastDuplicateError : new Error("Could not create a unique realtime chat session label");
    }
  }
}
