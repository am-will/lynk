import {
  defaultSessionKeyForHarness,
  harnessForSessionKey,
  parseHarnessModel,
  type HarnessId,
  type HarnessModelSelection
} from "../AgentHarness.js";
import type { BridgeConfig } from "../config.js";
import { DeviceChatStateStore, type DeviceChatState, type PendingChatRun } from "../OpenClawChatTypes.js";

export class HarnessDeviceStateStore extends DeviceChatStateStore {
  constructor(
    private readonly harnessConfig: Pick<BridgeConfig, "openClawChatAgentId" | "openClawChatSessionKey" | "hermesDefaultSessionId" | "hermesApiKey">
  ) {
    super(harnessConfig);
  }

  override stateFor(deviceId: string): DeviceChatState {
    const state = super.stateFor(deviceId);
    // When a device connects for the first time and Hermes is configured, default to
    // the hermes harness so users don't land on OpenClaw (which may not be running).
    if (state.harnessId === "openclaw" && this.harnessConfig.hermesApiKey) {
      state.harnessId = "hermes";
      state.sessionKey = defaultSessionKeyForHarness("hermes", this.harnessConfig, deviceId);
      state.sessionKeysByHarness.set("hermes", state.sessionKey);
    }
    return state;
  }

  switchHarness(deviceId: string, state: DeviceChatState, harnessId: HarnessId): void {
    if (state.harnessId === harnessId) {
      return;
    }
    this.rememberActiveSession(state);
    this.rememberSelectedModel(state);
    state.harnessId = harnessId;
    state.sessionKey = state.sessionKeysByHarness.get(harnessId)
      ?? defaultSessionKeyForHarness(harnessId, this.harnessConfig, deviceId);
    this.rememberActiveSession(state);
    state.sessionId = null;
    const activeRun = this.pendingRunForSession(state, state.sessionKey);
    state.runId = activeRun?.[0] ?? null;
    state.activeTaskKind = activeRun?.[1].taskKind ?? null;
    state.queuedSends = [];
    state.drainingQueuedSends = false;
    state.pendingFirstMessageDisplayName = false;
    state.lastRealtimeRequestAt = null;
    state.model = this.selectedModelForActiveHarness(state);
  }

  activateSession(state: DeviceChatState, sessionKey: string): void {
    this.rememberActiveSession(state);
    state.harnessId = harnessForSessionKey(sessionKey);
    state.sessionKey = sessionKey;
    this.rememberActiveSession(state);
  }

  applyModelSelection(deviceId: string, state: DeviceChatState, model: string | undefined): HarnessModelSelection | undefined {
    const selection = parseHarnessModel(model);
    if (!selection) {
      return undefined;
    }
    this.switchHarness(deviceId, state, selection.harnessId);
    this.setSelectedModel(state, selection.selectionId);
    return selection;
  }

  rawModelForSelection(model: string | undefined | null): string | undefined {
    return parseHarnessModel(model)?.modelId ?? model?.trim() ?? undefined;
  }

  selectionIdForModel(model: string | undefined | null): string | undefined {
    return parseHarnessModel(model)?.selectionId ?? model?.trim() ?? undefined;
  }

  setSelectedModel(state: DeviceChatState, model: string | null | undefined): void {
    state.model = model ?? null;
    state.modelsByHarness.set(state.harnessId, state.model);
  }

  selectedModelForActiveHarness(state: DeviceChatState): string | null {
    return state.modelsByHarness.get(state.harnessId) ?? null;
  }

  rememberActiveSession(state: DeviceChatState): void {
    state.sessionKeysByHarness.set(state.harnessId, state.sessionKey);
  }

  rememberSelectedModel(state: DeviceChatState): void {
    state.modelsByHarness.set(state.harnessId, state.model ?? null);
  }

  private pendingRunForSession(state: DeviceChatState, sessionKey: string): [string, PendingChatRun] | undefined {
    return [...state.pendingRuns.entries()]
      .find(([, pending]) => pending.sessionKey === sessionKey);
  }
}
