import {
  defaultSessionKeyForHarness,
  harnessForSessionKey,
  parseHarnessModel,
  type HarnessId,
  type HarnessModelSelection
} from "../AgentHarness.js";
import type { BridgeConfig } from "../config.js";
import type { ChatTaskKind } from "../../protocol/messages.js";
import { DeviceChatStateStore, type DeviceChatState } from "../OpenClawChatTypes.js";
import {
  HarnessRunLifecycle,
  type HarnessRunPromotion,
  type HarnessRunReservation,
  type HarnessRunState
} from "./HarnessRunLifecycle.js";

export interface HostChatRunMetadata {
  idempotencyKey: string;
  taskKind: ChatTaskKind;
  execution: "harness" | "fallback";
}

export type HostChatRunReservation = HarnessRunReservation<HostChatRunMetadata>;
export type HostChatRunState = HarnessRunState<void, HostChatRunMetadata>;

export class HarnessDeviceStateStore extends DeviceChatStateStore {
  private readonly runLifecycles = new Map<string, HarnessRunLifecycle<void, HostChatRunMetadata>>();

  constructor(
    private readonly harnessConfig: Pick<BridgeConfig, "openClawChatAgentId" | "openClawChatSessionKey" | "hermesDefaultSessionId" | "hermesApiKey">
  ) {
    super(harnessConfig);
  }

  protected override createState(deviceId: string): DeviceChatState {
    const state = super.createState(deviceId);
    if (!this.harnessConfig.hermesApiKey) {
      return state;
    }
    state.harnessId = "hermes";
    state.sessionKey = defaultSessionKeyForHarness("hermes", this.harnessConfig, deviceId);
    state.sessionKeysByHarness.set("hermes", state.sessionKey);
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
    this.syncSelectedRun(deviceId, state);
    state.queuedSends = [];
    state.drainingQueuedSends = false;
    state.pendingFirstMessageDisplayName = false;
    state.lastRealtimeRequestAt = null;
    state.model = this.selectedModelForActiveHarness(state);
  }

  activateSession(deviceId: string, state: DeviceChatState, sessionKey: string): void {
    this.rememberActiveSession(state);
    state.harnessId = harnessForSessionKey(sessionKey);
    state.sessionKey = sessionKey;
    this.rememberActiveSession(state);
    this.syncSelectedRun(deviceId, state);
  }

  runStateFor(deviceId: string, sessionKey: string): HostChatRunState {
    return this.runLifecycleFor(deviceId).stateFor(sessionKey);
  }

  reserveRun(
    deviceId: string,
    sessionKey: string,
    metadata: HostChatRunMetadata
  ): HostChatRunReservation {
    const reservation = this.runLifecycleFor(deviceId).reserve(sessionKey, metadata);
    this.syncSelectedRun(deviceId, this.stateFor(deviceId));
    return reservation;
  }

  canEnterHarness(deviceId: string, reservation: HostChatRunReservation): boolean {
    return this.runLifecycleFor(deviceId).canEnterHarness(reservation);
  }

  stateForReservation(deviceId: string, reservation: HostChatRunReservation): HostChatRunState | undefined {
    return this.runLifecycleFor(deviceId).stateForReservation(reservation);
  }

  promoteRun(
    deviceId: string,
    state: DeviceChatState,
    reservation: HostChatRunReservation,
    sessionKey: string,
    runId: string
  ): HarnessRunPromotion<void, HostChatRunMetadata> {
    const wasSelected = state.sessionKey === reservation.sessionKey;
    const promotion = this.runLifecycleFor(deviceId).promote(reservation, sessionKey, runId, undefined);
    if (wasSelected) {
      state.sessionKey = sessionKey;
      this.rememberActiveSession(state);
    }
    this.syncSelectedRun(deviceId, state);
    return promotion;
  }

  requestRunStop(deviceId: string, state: DeviceChatState, sessionKey: string, reason: string, runId?: string): HostChatRunState {
    const stopping = this.runLifecycleFor(deviceId).requestStop(sessionKey, reason, runId);
    this.syncSelectedRun(deviceId, state);
    return stopping;
  }

  rollbackRun(deviceId: string, state: DeviceChatState, reservation: HostChatRunReservation): boolean {
    const rolledBack = this.runLifecycleFor(deviceId).rollback(reservation);
    this.syncSelectedRun(deviceId, state);
    return rolledBack;
  }

  settleRun(deviceId: string, state: DeviceChatState, sessionKey: string, runId?: string): boolean {
    const settled = this.runLifecycleFor(deviceId).settle(sessionKey, runId);
    this.syncSelectedRun(deviceId, state);
    return settled;
  }

  syncSelectedRun(deviceId: string, state: DeviceChatState): void {
    const run = this.runLifecycleFor(deviceId).stateFor(state.sessionKey);
    state.runId = run.phase === "running" || (run.phase === "stopping" && run.runId !== null)
      ? run.runId
      : null;
    state.activeTaskKind = run.phase === "idle" ? null : run.metadata?.taskKind ?? null;
  }

  closeRunLifecycles(): void {
    for (const lifecycle of this.runLifecycles.values()) {
      lifecycle.close();
    }
    this.runLifecycles.clear();
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

  private runLifecycleFor(deviceId: string): HarnessRunLifecycle<void, HostChatRunMetadata> {
    const existing = this.runLifecycles.get(deviceId);
    if (existing) {
      return existing;
    }
    const lifecycle = new HarnessRunLifecycle<void, HostChatRunMetadata>(undefined, {
      concurrency: "per-session",
      busyMessage: "A chat run is already active for this session."
    });
    this.runLifecycles.set(deviceId, lifecycle);
    return lifecycle;
  }
}
