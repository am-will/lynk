import { randomUUID } from "node:crypto";
import type { AgentRunResult, AgentTaskKind } from "../dispatcher/AgentClient.js";
import type { Dispatcher } from "../dispatcher/dispatcher.js";
import type { HarnessId } from "./AgentHarness.js";
import {
  defaultSessionKeyForHarness,
  harnessForSessionKey,
  harnessInfos,
  harnessLabel,
  isHarnessId,
  isWorkspaceAwareHarness
} from "./AgentHarness.js";
import type {
  BackendReadinessStatus,
  HarnessReadinessStatus
} from "./OpenClawHarnessReadiness.js";
import {
  harnessUnavailableError,
  healthForHarness,
  readinessAction
} from "./OpenClawHarnessReadiness.js";
import type {
  ChatControlCommandMessage,
  ChatErrorMessage,
  ChatFinalMessage,
  ChatHistoryMessage,
  ChatNewSessionMessage,
  ChatOpenMessage,
  ChatOutboundMessage,
  ChatSelectSessionMessage,
  ChatSendMessage,
  ChatSetModelMessage,
  ChatSetReasoningMessage,
  ChatStopMessage,
  UserRequestMessage
} from "../protocol/messages.js";
import type { AuditLog } from "./AuditLog.js";
import type { BridgeConfig } from "./config.js";
import { HarnessDeviceStateStore } from "./harness/HarnessDeviceStateStore.js";
import { HarnessChatRouter } from "./harness/HarnessChatRouter.js";
import { OpenClawControlCommandRouter } from "./OpenClawControlCommands.js";
import {
  firstMessageDisplayName,
  isExplicitPhoneTask,
  isSameModelSelection,
  messageForGateway,
  normalizeThinkingLevel,
  reasoningStreamEnabled
} from "./OpenClawChatPolicy.js";
import {
  formatCommandList,
  formatHelp,
  formatStatusReport,
  formatTaskList,
  formatToolList
} from "./OpenClawChatFormatters.js";
import {
  buildChatReplyAvailableMessage,
  buildChatRoleMessage,
  buildChatStateMessage
} from "./OpenClawChatMessages.js";
import type { DeviceChatState, GatewayChatClient, PendingChatRun } from "./OpenClawChatTypes.js";
import { defaultSessionLabelForDevice } from "./OpenClawChatTypes.js";
import { OpenClawFallbackSender } from "./OpenClawFallbackSender.js";
import { OpenClawGatewayEventRouter } from "./OpenClawGatewayEventRouter.js";
import {
  chatMessagesFromHistory,
  enrichSessionsWithModelContext,
  normalizeCommands,
  normalizeModels,
  normalizeReasoningOptions,
  normalizeSessions,
  normalizeTools,
  requestKeyFromSessionKey,
  stringField,
  usageFromSession
} from "./chat/ChatNormalizers.js";
import { buildChatErrorMessage, ChatClientError } from "./chat/ChatErrors.js";
import { normalizeChatSendContent } from "./chat/ChatSendAttachments.js";
import { OpenClawRealtimeSessions } from "./OpenClawRealtimeSessions.js";
import { OpenClawRunWaiters } from "./OpenClawRunWaiters.js";
import { PhoneHub } from "./PhoneHub.js";

interface RealtimeChatOptions {
  taskKind: AgentTaskKind;
  callId?: string;
  model?: string;
  reasoningEffort?: string;
}

function sameChatUserMessage(a: ChatHistoryMessage, b: ChatHistoryMessage): boolean {
  return a.role === "user" &&
    b.role === "user" &&
    a.text === b.text &&
    JSON.stringify(a.attachments ?? []) === JSON.stringify(b.attachments ?? []);
}

export class OpenClawChatBridge {
  private readonly client: GatewayChatClient;
  private readonly states: HarnessDeviceStateStore;
  private readonly runWaiters = new OpenClawRunWaiters();
  private readonly commandRouter: OpenClawControlCommandRouter;
  private readonly fallbackSender: OpenClawFallbackSender;
  private readonly gatewayEventRouter: OpenClawGatewayEventRouter;
  private readonly realtimeSessions: OpenClawRealtimeSessions;

  constructor(
    private readonly config: BridgeConfig,
    private readonly hub: PhoneHub,
    private readonly dispatcher: Pick<Dispatcher, "handleUserRequest" | "stopActiveTurn">,
    private readonly audit?: AuditLog,
    client?: GatewayChatClient
  ) {
    this.client = client ?? new HarnessChatRouter(config, audit);
    this.states = new HarnessDeviceStateStore(config);
    this.commandRouter = new OpenClawControlCommandRouter({
      stateFor: (deviceId) => this.stateFor(deviceId),
      newSession: (message) => this.newSession(message),
      sendStatusReport: (deviceId) => this.sendStatusReport(deviceId),
      sendHelp: (deviceId) => this.sendHelp(deviceId),
      sendCommandList: (deviceId) => this.sendCommandList(deviceId),
      sendToolList: (deviceId, mode) => this.sendToolList(deviceId, mode),
      sendTaskList: (deviceId) => this.sendTaskList(deviceId),
      sendSlashCommand: (deviceId, text, sessionKey, status, successMessage, options) =>
        this.sendSlashCommand(deviceId, text, sessionKey, status, successMessage, options),
      patchSession: (deviceId, sessionKey, patch, status) =>
        this.patchSession(deviceId, sessionKey, patch, status),
      sendState: (deviceId, status) => this.sendState(deviceId, status),
      send: (message) => this.send(message),
      respondToPermission: (sessionKey, permissionId, response) =>
        this.respondToPermission(sessionKey, permissionId, response)
    });
    this.fallbackSender = new OpenClawFallbackSender({
      states: this.states,
      dispatcher: this.dispatcher,
      sendChat: (deviceId, message) => this.sendChat(deviceId, message),
      sendState: (deviceId, status) => this.sendState(deviceId, status),
      sendReplyAvailable: (deviceId, message, sessionKey, pendingRun) => this.sendReplyAvailable(deviceId, message, sessionKey, pendingRun)
    });
    this.gatewayEventRouter = new OpenClawGatewayEventRouter({
      states: this.states,
      sendChat: (deviceId, message) => this.sendChat(deviceId, message),
      sendState: (deviceId, status) => this.sendState(deviceId, status),
      sendReasoningClear: (deviceId, sessionKey, runId) => this.sendReasoningClear(deviceId, sessionKey, runId),
      settleRun: (message) => this.runWaiters.settleRun(message),
      drainQueuedSends: (deviceId) => this.drainQueuedSends(deviceId),
      sendReplyAvailable: (deviceId, message, sessionKey, pendingRun) => this.sendReplyAvailable(deviceId, message, sessionKey, pendingRun),
      refreshMetadata: (deviceId) => this.refreshMetadata(deviceId),
      sendHistory: (deviceId) => this.sendHistory(deviceId)
    });
    this.realtimeSessions = new OpenClawRealtimeSessions({
      config: this.config,
      client: this.client,
      states: this.states,
      sendState: (deviceId, status) => this.sendState(deviceId, status),
      refreshDevice: (deviceId) => this.refreshDevice(deviceId)
    });
    this.client.addEventListener((event) => this.gatewayEventRouter.handleEvent(event));
  }

  async open(message: ChatOpenMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    if (message.sessionKey) {
      this.states.activateSession(state, message.sessionKey);
    }
    this.sendState(message.deviceId, `Loading ${harnessLabel(state.harnessId)} chat`);
    await this.refreshDevice(message.deviceId);
  }

  async health(): Promise<unknown> {
    return await this.client.health();
  }

  async backendReadiness(): Promise<BackendReadinessStatus> {
    const modelCounts = await this.harnessModelCounts();
    const harnesses = {} as Record<HarnessId, HarnessReadinessStatus>;
    for (const info of harnessInfos(this.config)) {
      const modelCount = modelCounts[info.id] ?? 0;
      const ready = info.enabled && modelCount > 0;
      const state = ready ? "ready" : info.enabled ? "no_models" : "missing_config";
      harnesses[info.id] = {
        ok: ready,
        configured: info.enabled,
        label: info.label,
        modelCount,
        state,
        message: ready
          ? `${info.label} backend is ready.`
          : info.enabled
            ? `${info.label} is configured on the PC bridge, but no live models are available yet.`
          : `${info.label} is not configured on the PC bridge.`,
        ...(state === "missing_config" ? { action: readinessAction(info.id) } : {})
      };
    }
    return { harnesses };
  }

  async send(message: ChatSendMessage): Promise<void> {
    const { text, attachments, requestText } = normalizeChatSendContent(message.text, message.attachments);
    if (!text && attachments.length === 0) {
      return;
    }
    const state = this.stateFor(message.deviceId);
    const previousModel = state.model;
    this.states.applyModelSelection(message.deviceId, state, message.model);
    if (message.sessionKey && harnessForSessionKey(message.sessionKey) === state.harnessId) {
      this.states.activateSession(state, message.sessionKey);
    }

    const idempotencyKey = message.idempotencyKey ?? randomUUID();
    if (text && attachments.length === 0 && await this.commandRouter.handleVisibleSlashCommand(message.deviceId, text, state.sessionKey)) {
      return;
    }
    const taskKind = isExplicitPhoneTask(requestText) ? "phone" : "general";
    const delivery = message.delivery ?? "normal";
    if (delivery === "queue" && state.runId) {
      state.queuedSends.push({
        ...message,
        idempotencyKey,
        delivery: "normal"
      });
      this.audit?.record("chat_send_queued", message.deviceId, {
        sessionKey: state.sessionKey,
        runId: state.runId,
        queued: state.queuedSends.length,
        length: requestText.length,
        attachments: attachments.length
      });
      this.sendState(message.deviceId, `${harnessLabel(state.harnessId)} queued message for next turn`);
      return;
    }
    if (delivery === "steer" && state.runId) {
      await this.steerChatMessage(message, state, requestText, idempotencyKey, taskKind);
      return;
    }
    const healthError = await this.activeHarnessHealthError(state);
    if (healthError) {
      this.sendChat(message.deviceId, buildChatErrorMessage({
        deviceId: message.deviceId,
        sessionKey: state.sessionKey,
        runId: idempotencyKey,
        error: healthError
      }));
      this.sendState(message.deviceId, `${harnessLabel(state.harnessId)} is not reachable`);
      return;
    }
    try {
      state.reasoningEffort = normalizeThinkingLevel(message.reasoningEffort, state.reasoningEffort);
      const requestedModel = this.states.rawModelForSelection(message.model);
      if (requestedModel && !isSameModelSelection(message.model?.trim() ?? requestedModel, previousModel)) {
        await this.patchSession(message.deviceId, state.sessionKey, { model: requestedModel });
        this.states.setSelectedModel(state, this.states.selectionIdForModel(message.model) ?? requestedModel);
      }
      const result = await this.client.sendChat({
        sessionKey: state.sessionKey,
        sessionId: message.sessionId,
        message: messageForGateway(requestText, taskKind),
        attachments,
        thinking: state.reasoningEffort ?? undefined,
        idempotencyKey
      });
      if (state.completedRunIds.has(result.runId)) {
        state.sessionKey = result.sessionKey;
        state.runId = null;
        state.activeTaskKind = null;
        this.sendState(message.deviceId, `${harnessLabel(state.harnessId)} finished`);
        void this.refreshDevice(message.deviceId);
        this.drainQueuedSends(message.deviceId);
        return;
      }
      state.sessionKey = result.sessionKey;
      state.runId = result.runId;
      const userMessage: ChatHistoryMessage = {
        id: `user_${result.runId}`,
        role: "user",
        text: requestText,
        ...(attachments.length ? { attachments } : {}),
        timestamp: Date.now()
      };
      this.states.trackPendingRun(
        state,
        result.runId,
        result.sessionKey,
        message.sessionId ?? state.sessionId ?? null,
        taskKind,
        userMessage
      );
      await this.maybeSetFirstMessageDisplayName(message.deviceId, requestText);
      this.audit?.record("openclaw_chat_send", message.deviceId, {
        sessionKey: result.sessionKey,
        runId: result.runId,
        length: requestText.length,
        attachments: attachments.length
      });
      this.sendPendingUserHistory(message.deviceId, state, result.sessionKey);
      if (state.harnessId === "opencode") {
        void this.refreshMetadata(message.deviceId);
      }
      this.sendState(message.deviceId, `${harnessLabel(state.harnessId)} is working`);
    } catch (error) {
      await this.handleSendFailure(message, state, idempotencyKey, taskKind, error);
    }
  }

  private async handleSendFailure(
    message: ChatSendMessage,
    state: DeviceChatState,
    idempotencyKey: string,
    taskKind: "general" | "phone",
    error: unknown
  ): Promise<void> {
    this.sendChat(message.deviceId, buildChatErrorMessage({
      deviceId: message.deviceId,
      sessionKey: state.sessionKey,
      runId: idempotencyKey,
      error
    }));
    if (state.harnessId !== "openclaw") {
      return;
    }
    await this.fallbackSender.send(message, idempotencyKey, taskKind);
  }

  private async activeHarnessHealthError(state: DeviceChatState): Promise<ChatClientError | undefined> {
    const label = harnessLabel(state.harnessId);
    let payload: unknown;
    try {
      payload = await this.client.health();
    } catch (error) {
      return harnessUnavailableError(state.harnessId, label, error);
    }
    const health = healthForHarness(payload, state.harnessId);
    if (!health) {
      return undefined;
    }
    if (health.ok === false) {
      return harnessUnavailableError(state.harnessId, label, health.error ?? health.message ?? payload);
    }
    return undefined;
  }

  private applyRealtimeChatOptions(
    deviceId: string,
    state: DeviceChatState,
    options: Pick<RealtimeChatOptions, "model" | "reasoningEffort"> | undefined
  ): void {
    const model = options?.model?.trim();
    if (model && model !== "local-litertlm") {
      this.states.applyModelSelection(deviceId, state, model);
    }
    state.reasoningEffort = normalizeThinkingLevel(options?.reasoningEffort, state.reasoningEffort);
  }

  async stop(message: ChatStopMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const sessionKey = message.sessionKey ?? state.sessionKey;
    const runId = message.runId ?? state.runId ?? undefined;
    try {
      await this.client.abort(sessionKey, runId);
    } catch (error) {
      await this.dispatcher.stopActiveTurn(message.deviceId, message.reason ?? "Stopped from Android chat");
      this.sendChat(message.deviceId, buildChatErrorMessage({
        deviceId: message.deviceId,
        sessionKey,
        runId,
        error
      }));
    } finally {
      state.runId = null;
      state.activeTaskKind = null;
      if (runId) {
        state.pendingRuns.delete(runId);
      }
      this.sendState(message.deviceId, "Stop requested");
      this.drainQueuedSends(message.deviceId);
    }
  }

  private async steerChatMessage(
    message: ChatSendMessage,
    state: DeviceChatState,
    text: string,
    idempotencyKey: string,
    taskKind: AgentTaskKind
  ): Promise<void> {
    const attachments = message.attachments ?? [];
    try {
      state.reasoningEffort = normalizeThinkingLevel(message.reasoningEffort, state.reasoningEffort);
      if (this.client.steerChat) {
        await this.client.steerChat({
          sessionKey: state.sessionKey,
          sessionId: message.sessionId ?? state.sessionId ?? undefined,
          runId: state.runId ?? undefined,
          message: messageForGateway(text, taskKind),
          attachments,
          thinking: state.reasoningEffort ?? undefined,
          idempotencyKey
        });
      } else {
        await this.client.sendChat({
          sessionKey: state.sessionKey,
          sessionId: message.sessionId ?? state.sessionId ?? undefined,
          message: `/steer ${messageForGateway(text, taskKind)}`,
          attachments,
          thinking: state.reasoningEffort ?? undefined,
          idempotencyKey
        });
      }
      this.audit?.record("chat_send_steered", message.deviceId, {
        sessionKey: state.sessionKey,
        runId: state.runId,
        length: text.length,
        attachments: attachments.length
      });
      this.sendState(message.deviceId, `Steered ${harnessLabel(state.harnessId)}`);
    } catch (error) {
      this.sendChatError(message.deviceId, state.sessionKey, error);
    }
  }

  private drainQueuedSends(deviceId: string): void {
    const state = this.stateFor(deviceId);
    if (state.drainingQueuedSends || state.runId || state.queuedSends.length === 0) {
      return;
    }
    const next = state.queuedSends.shift();
    if (!next) {
      return;
    }
    state.drainingQueuedSends = true;
    void this.send({
      ...next,
      delivery: "normal"
    }).finally(() => {
      state.drainingQueuedSends = false;
      if (!state.runId) {
        this.drainQueuedSends(deviceId);
      }
    });
  }

  async handleRealtimeRequest(
    request: UserRequestMessage,
    options: RealtimeChatOptions = { taskKind: "general" }
  ): Promise<AgentRunResult> {
    const text = request.text.trim();
    if (!text) {
      throw new Error("Realtime request text is required");
    }

    const state = this.stateFor(request.deviceId);
    this.applyRealtimeChatOptions(request.deviceId, state, options);
    await this.realtimeSessions.ensureFreshRealtimeSession(request.deviceId, text);
    const idempotencyKey = options.callId ? `realtime_${options.callId}` : `realtime_${randomUUID()}`;
    this.appendUserMessage(request.deviceId, text, `user_${idempotencyKey}`);

    try {
      const result = await this.client.sendChat({
        sessionKey: state.sessionKey,
        sessionId: state.sessionId ?? undefined,
        message: messageForGateway(text, options.taskKind),
        thinking: state.reasoningEffort ?? undefined,
        idempotencyKey
      });
      state.sessionKey = result.sessionKey;
      state.runId = result.runId;
      state.activeTaskKind = options.taskKind;
      state.lastRealtimeRequestAt = Date.now();
      this.audit?.record("realtime_chat_send", request.deviceId, {
        sessionKey: result.sessionKey,
        runId: result.runId,
        taskKind: options.taskKind,
        length: text.length
      });
      this.sendState(request.deviceId, options.taskKind === "phone" ? `${harnessLabel(state.harnessId)} is working on phone task` : `${harnessLabel(state.harnessId)} is working`);
      return await this.runWaiters.waitForRun(request.deviceId, state.sessionKey, result.runId);
    } catch (error) {
      this.sendChatError(request.deviceId, state.sessionKey, error);
      throw error;
    }
  }

  async steerRealtimeTurn(deviceId: string, guidance: string, options?: RealtimeChatOptions): Promise<void> {
    const text = guidance.trim();
    if (!text) {
      throw new Error("Realtime steering guidance is required");
    }

    const state = this.stateFor(deviceId);
    this.applyRealtimeChatOptions(deviceId, state, options);
    const idempotencyKey = options?.callId ? `realtime_steer_${options.callId}` : `realtime_steer_${randomUUID()}`;
    this.appendUserMessage(deviceId, text, `user_${idempotencyKey}`);
    const result = await this.client.sendChat({
      sessionKey: state.sessionKey,
      sessionId: state.sessionId ?? undefined,
      message: messageForGateway(text, options?.taskKind ?? "general"),
      thinking: state.reasoningEffort ?? undefined,
      idempotencyKey
    });
    state.sessionKey = result.sessionKey;
    state.runId = state.runId ?? result.runId;
    state.lastRealtimeRequestAt = Date.now();
    this.audit?.record("realtime_chat_steer", deviceId, {
      sessionKey: result.sessionKey,
      runId: result.runId,
      length: text.length
    });
    this.sendState(deviceId, "Steered OpenClaw from realtime chat");
  }

  async stopRealtimeTurn(deviceId: string, reason = "Stopped by realtime chat"): Promise<void> {
    const state = this.stateFor(deviceId);
    const runId = state.runId ?? undefined;
    const text = reason.trim() || "Stopped by realtime chat";
    this.appendUserMessage(deviceId, text, `user_realtime_stop_${randomUUID()}`);
    state.lastRealtimeRequestAt = Date.now();
    await this.client.abort(state.sessionKey, runId);
    this.runWaiters.rejectForDevice(deviceId, new Error(text));
    state.runId = null;
    this.sendState(deviceId, "Stop requested");
  }

  async selectSession(message: ChatSelectSessionMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const pendingRunForTarget = [...state.pendingRuns.entries()]
      .find(([, pending]) => pending.sessionKey === message.sessionKey);
    this.states.activateSession(state, message.sessionKey);
    state.runId = pendingRunForTarget?.[0] ?? null;
    state.activeTaskKind = pendingRunForTarget?.[1].taskKind ?? null;
    state.model = this.states.selectedModelForActiveHarness(state);
    state.pendingFirstMessageDisplayName = false;
    state.lastRealtimeRequestAt = null;
    this.sendState(message.deviceId, "Switched session");
    await this.refreshDevice(message.deviceId);
  }

  async newSession(message: ChatNewSessionMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const selection = this.states.applyModelSelection(message.deviceId, state, message.model ?? state.model ?? undefined);
    const sessionUuid = randomUUID();
    const requestKey = typeof message.key === "string" && message.key.trim()
      ? message.key.trim()
      : state.harnessId === "openclaw"
        ? `phone-${message.deviceId}-${sessionUuid}`
        : `${state.harnessId}:phone-${message.deviceId}-${sessionUuid}`;
    const explicitLabel = typeof message.label === "string" && message.label.trim()
      ? message.label.trim()
      : undefined;
    const workspacePath = isWorkspaceAwareHarness(state.harnessId)
      ? workspacePathForNewSession(state, message.workspacePath)
      : undefined;
    const created = await this.client.createSession({
      key: requestKey,
      label: explicitLabel ?? sessionUuid,
      model: selection?.modelId ?? this.states.rawModelForSelection(state.model) ?? undefined,
      ...(workspacePath ? { workspacePath } : {}),
      ...(isWorkspaceAwareHarness(state.harnessId) && message.createWorkspaceIfMissing ? { createWorkspaceIfMissing: true } : {})
    });
    const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
    const key = typeof record.key === "string" && record.key.trim() ? record.key.trim() : undefined;
    state.sessionKey = key ?? defaultSessionKeyForHarness(state.harnessId, this.config, message.deviceId);
    this.states.rememberActiveSession(state);
    state.sessionId = typeof record.sessionId === "string" ? record.sessionId : null;
    state.runId = null;
    state.pendingFirstMessageDisplayName = explicitLabel ? false : true;
    state.lastRealtimeRequestAt = null;
    this.sendState(message.deviceId, "Started a new chat");
    await this.refreshDevice(message.deviceId);
  }

  async setModel(message: ChatSetModelMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    this.states.applyModelSelection(message.deviceId, state, message.model);
    const sessionKey = message.sessionKey && harnessForSessionKey(message.sessionKey) === state.harnessId
      ? message.sessionKey
      : state.sessionKey;
    const rawModel = this.states.rawModelForSelection(message.model) ?? message.model;
    this.states.setSelectedModel(state, this.states.selectionIdForModel(message.model) ?? message.model);
    if (state.harnessId === "openclaw") {
      await this.sendSlashCommand(
        message.deviceId,
        `/model ${rawModel}`,
        sessionKey,
        `Model: ${rawModel}`,
        undefined,
        { ignoreRunEvents: Boolean(state.runId) }
      );
    } else {
      await this.patchSession(message.deviceId, sessionKey, { model: rawModel });
      await this.refreshDevice(message.deviceId);
    }
  }

  async setReasoning(message: ChatSetReasoningMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const sessionKey = message.sessionKey ?? state.sessionKey;
    state.reasoningEffort = normalizeThinkingLevel(message.reasoningEffort, state.reasoningEffort);
    if (state.harnessId === "openclaw") {
      await this.sendSlashCommand(
        message.deviceId,
        `/think ${state.reasoningEffort}`,
        sessionKey,
        `Reasoning: ${state.reasoningEffort}`,
        undefined,
        { ignoreRunEvents: Boolean(state.runId) }
      );
    } else {
      await this.patchSession(message.deviceId, sessionKey, { thinking: state.reasoningEffort });
      await this.refreshDevice(message.deviceId);
    }
  }

  async controlCommand(message: ChatControlCommandMessage): Promise<void> {
    await this.commandRouter.controlCommand(message);
  }

  private async respondToPermission(
    sessionKey: string,
    permissionId: string,
    response: "once" | "always" | "reject"
  ): Promise<void> {
    if (!this.client.respondToPermission) {
      throw new Error("Current harness does not support permission replies.");
    }
    await this.client.respondToPermission({ sessionKey, permissionId, response });
  }

  close(): void {
    this.runWaiters.close();
    this.client.close();
  }

  private stateFor(deviceId: string): DeviceChatState {
    return this.states.stateFor(deviceId);
  }

  private async refreshDevice(deviceId: string): Promise<void> {
    await this.ensureSession(deviceId);
    await Promise.allSettled([
      this.refreshMetadata(deviceId),
      this.sendHistory(deviceId),
      this.sendCommands(deviceId),
      this.sendTools(deviceId)
    ]);
  }

  private async refreshMetadata(deviceId: string): Promise<void> {
    await this.sendModels(deviceId).catch(() => undefined);
    await this.sendSessions(deviceId).catch(() => undefined);
    const state = this.stateFor(deviceId);
    await this.client.syncRemoteReplies?.(state.harnessId, 50).catch(() => undefined);
  }

  private async ensureSession(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    try {
      await this.client.history(state.sessionKey);
    } catch {
      const key = requestKeyFromSessionKey(state.sessionKey, this.config.openClawChatAgentId);
      const created = await this.client.createSession({ key, label: defaultSessionLabelForDevice(deviceId) });
      const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
      if (typeof record.key === "string" && record.key.trim()) {
        state.sessionKey = record.key;
      }
      state.pendingFirstMessageDisplayName = false;
    }
  }

  private async sendModels(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const [modelsPayload, sessionsPayload] = await Promise.all([
      this.client.listModels(),
      this.client.listSessions(1, state.harnessId).catch(() => undefined)
    ]);
    const models = normalizeModels(modelsPayload);
    state.modelOptions = new Map(models.map((model) => [model.id, model]));
    this.sendChat(deviceId, {
      type: "chat.models",
      deviceId,
      models,
      reasoningOptions: normalizeReasoningOptions(sessionsPayload)
    });
  }

  private async sendSessions(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const payload = await this.client.listSessions(state.harnessId === "codex" ? 500 : 50, state.harnessId);
    const sessions = enrichSessionsWithModelContext(normalizeSessions(payload), state.modelOptions.values());
    state.sessionSummaries = new Map(sessions.map((session) => [session.key, session]));
    const selected = sessions.find((session) => session.key === state.sessionKey);
    if (selected) {
      state.sessionId = selected.sessionId ?? null;
      this.states.setSelectedModel(state, state.model ?? selected.model ?? null);
      state.reasoningEffort = normalizeThinkingLevel(selected.thinkingLevel, state.reasoningEffort);
      state.reasoningStream = reasoningStreamEnabled(selected.reasoningLevel) ?? state.reasoningStream ?? null;
      state.fastMode = selected.fastMode ?? null;
      state.verboseLevel = selected.verboseLevel ?? null;
    }
    this.sendChat(deviceId, {
      type: "chat.sessions",
      deviceId,
      sessions,
      selectedSessionKey: state.sessionKey
    });
    this.sendChat(deviceId, {
      type: "chat.usage",
      deviceId,
      sessionKey: state.sessionKey,
      usage: usageFromSession(selected)
    });
    this.sendState(deviceId);
  }

  private async sendHistory(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const payload = await this.client.history(state.sessionKey);
    const record = payload && typeof payload === "object" ? payload as Record<string, unknown> : {};
    state.sessionId = typeof record.sessionId === "string" ? record.sessionId : state.sessionId ?? null;
    state.reasoningEffort = normalizeThinkingLevel(
      typeof record.thinkingLevel === "string" ? record.thinkingLevel : undefined,
      state.reasoningEffort
    );
    state.reasoningStream = typeof record.reasoningLevel === "string" ? reasoningStreamEnabled(record.reasoningLevel) : state.reasoningStream ?? null;
    state.fastMode = typeof record.fastMode === "boolean" ? record.fastMode : state.fastMode ?? null;
    state.verboseLevel = typeof record.verboseLevel === "string" ? record.verboseLevel : state.verboseLevel ?? null;
    const historyMessages = this.withPendingUserMessages(state, state.sessionKey, chatMessagesFromHistory(payload));
    this.sendChat(deviceId, {
      type: "chat.history",
      deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      messages: historyMessages
    });
    this.sendState(deviceId);
  }

  private sendPendingUserHistory(deviceId: string, state: DeviceChatState, sessionKey: string): void {
    const messages = this.withPendingUserMessages(state, sessionKey, []);
    if (messages.length === 0) {
      return;
    }
    for (const message of messages) {
      this.sendChat(deviceId, {
        type: "chat.message",
        deviceId,
        sessionKey,
        sessionId: state.sessionId,
        message
      });
    }
  }

  private withPendingUserMessages(
    state: DeviceChatState,
    sessionKey: string,
    historyMessages: ChatHistoryMessage[]
  ): ChatHistoryMessage[] {
    const pendingMessages = [...state.pendingRuns.values()]
      .filter((pending) => pending.sessionKey === sessionKey)
      .map((pending) => pending.userMessage)
      .filter((message): message is ChatHistoryMessage => Boolean(message))
      .filter((message) => !historyMessages.some((existing) => sameChatUserMessage(existing, message)));
    return pendingMessages.length ? [...historyMessages, ...pendingMessages] : historyMessages;
  }

  private async sendCommands(deviceId: string): Promise<void> {
    const payload = await this.client.listCommands(this.stateFor(deviceId).sessionKey);
    this.sendChat(deviceId, {
      type: "chat.commands",
      deviceId,
      commands: normalizeCommands(payload)
    });
  }

  private async sendCommandList(deviceId: string): Promise<void> {
    try {
      const payload = await this.client.listCommands(this.stateFor(deviceId).sessionKey);
      const commands = normalizeCommands(payload);
      this.sendChat(deviceId, {
        type: "chat.commands",
        deviceId,
        commands
      });
      this.appendSystemMessage(deviceId, formatCommandList(commands), `system_${randomUUID()}`);
      this.sendState(deviceId, "Listed slash commands");
    } catch (error) {
      this.sendChatError(deviceId, this.stateFor(deviceId).sessionKey, error);
    }
  }

  private async sendHelp(deviceId: string): Promise<void> {
    try {
      const payload = await this.client.listCommands(this.stateFor(deviceId).sessionKey);
      const commands = normalizeCommands(payload);
      this.sendChat(deviceId, {
        type: "chat.commands",
        deviceId,
        commands
      });
      this.appendSystemMessage(deviceId, formatHelp(commands), `system_${randomUUID()}`);
      this.sendState(deviceId, "Shown help");
    } catch (error) {
      this.sendChatError(deviceId, this.stateFor(deviceId).sessionKey, error);
    }
  }

  private async sendToolList(deviceId: string, mode?: string): Promise<void> {
    const state = this.stateFor(deviceId);
    try {
      await this.ensureSession(deviceId);
      const payload = await this.client.effectiveTools(state.sessionKey);
      const tools = normalizeTools(payload);
      this.sendChat(deviceId, {
        type: "chat.tools",
        deviceId,
        sessionKey: state.sessionKey,
        tools
      });
      this.appendSystemMessage(deviceId, formatToolList(tools, mode), `system_${randomUUID()}`);
      this.sendState(deviceId, "Listed tools");
    } catch (error) {
      this.sendChatError(deviceId, state.sessionKey, error);
    }
  }

  private sendTaskList(deviceId: string): void {
    const state = this.stateFor(deviceId);
    this.appendSystemMessage(deviceId, formatTaskList(state), `system_${randomUUID()}`);
    this.sendState(deviceId, "Listed tasks");
  }

  private async sendStatusReport(deviceId: string): Promise<void> {
    this.sendState(deviceId, "Refreshing status");
    await this.refreshDevice(deviceId);
    const latest = this.stateFor(deviceId);
    const health = await this.client.health().catch(() => undefined);
    this.appendSystemMessage(deviceId, formatStatusReport(latest, health), `system_${randomUUID()}`);
    this.sendState(deviceId, "Status refreshed");
  }

  private async sendTools(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const payload = await this.client.effectiveTools(state.sessionKey);
    this.sendChat(deviceId, {
      type: "chat.tools",
      deviceId,
      sessionKey: state.sessionKey,
      tools: normalizeTools(payload)
    });
  }

  private async patchSession(deviceId: string, sessionKey: string, patch: Record<string, unknown>, status = "Updated session"): Promise<void> {
    try {
      await this.client.patchSession(sessionKey, patch);
      this.sendState(deviceId, status);
    } catch (error) {
      this.sendChatError(deviceId, sessionKey, error);
    }
  }

  private async maybeSetFirstMessageDisplayName(deviceId: string, text: string): Promise<void> {
    const state = this.stateFor(deviceId);
    if (!state.pendingFirstMessageDisplayName) {
      return;
    }
    const displayName = firstMessageDisplayName(text);
    if (!displayName) {
      return;
    }
    state.pendingFirstMessageDisplayName = false;
    try {
      await this.client.patchSession(state.sessionKey, { displayName });
      void this.refreshMetadata(deviceId);
    } catch (error) {
      console.warn(`[chat] ${deviceId}: failed to set session display name: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private async sendSlashCommand(
    deviceId: string,
    text: string,
    sessionKey: string,
    status: string,
    successMessage?: string,
    options: { ignoreRunEvents?: boolean } = {}
  ): Promise<void> {
    try {
      const state = this.stateFor(deviceId);
      const activeRunId = state.runId ?? null;
      const result = await this.client.sendChat({
        sessionKey,
        message: text,
        idempotencyKey: randomUUID()
      });
      state.sessionKey = result.sessionKey;
      if (options.ignoreRunEvents) {
        state.ignoredRunIds.add(result.runId);
        state.runId = activeRunId;
      } else {
        state.runId = result.runId;
      }
      this.sendState(deviceId, status);
      if (successMessage) {
        this.appendSystemMessage(deviceId, successMessage, `system_${result.runId}`);
      }
    } catch (error) {
      this.sendChatError(deviceId, sessionKey, error);
    }
  }

  private sendState(deviceId: string, status?: string): void {
    const state = this.stateFor(deviceId);
    if (state.runId && state.completedRunIds.has(state.runId)) {
      state.runId = null;
      state.activeTaskKind = null;
    }
    this.sendChat(deviceId, buildChatStateMessage(deviceId, state, status));
  }

  private sendReasoningClear(deviceId: string, sessionKey: string, runId?: string | null): void {
    this.sendChat(deviceId, {
      type: "chat.reasoning_clear",
      deviceId,
      sessionKey,
      runId: runId ?? null
    });
  }

  private appendUserMessage(deviceId: string, text: string, id: string): void {
    const state = this.stateFor(deviceId);
    this.sendChat(deviceId, buildChatRoleMessage(deviceId, state, "user", text, id));
  }

  private appendSystemMessage(deviceId: string, text: string, id: string): void {
    const state = this.stateFor(deviceId);
    this.sendChat(deviceId, buildChatRoleMessage(deviceId, state, "system", text, id));
  }

  private sendChat(deviceId: string, message: ChatOutboundMessage): void {
    try {
      this.hub.sendChat(deviceId, message);
    } catch (error) {
      console.warn(`[chat] ${deviceId}: failed to send ${message.type}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private sendChatError(deviceId: string, sessionKey: string, error: unknown): void {
    this.sendChat(deviceId, buildChatErrorMessage({
      deviceId,
      sessionKey,
      error
    }));
  }

  private async harnessModelCounts(): Promise<Record<string, number>> {
    const payload = await this.client.listModels().catch(() => undefined);
    const record = payload && typeof payload === "object" ? payload as Record<string, unknown> : undefined;
    const models = Array.isArray(record?.models) ? record.models : [];
    const counts: Record<string, number> = {};
    for (const model of models) {
      const harnessId = this.harnessIdFromModel(model);
      counts[harnessId] = (counts[harnessId] ?? 0) + 1;
    }
    return counts;
  }

  private harnessIdFromModel(model: unknown): string {
    const record = model && typeof model === "object" ? model as Record<string, unknown> : undefined;
    const harnessId = stringField(record, "harnessId");
    if (isHarnessId(harnessId) || harnessId === "local") {
      return harnessId;
    }
    const id = stringField(record, "id") ?? "";
    const prefix = id.split(":", 1)[0]?.toLowerCase();
    if (isHarnessId(prefix) || prefix === "local") {
      return prefix;
    }
    const provider = stringField(record, "provider");
    return isHarnessId(provider) || provider === "local" ? provider : "openclaw";
  }

  private sendReplyAvailable(
    deviceId: string,
    message: ChatFinalMessage | ChatErrorMessage,
    sessionKey: string,
    pendingRun?: PendingChatRun
  ): void {
    const state = this.stateFor(deviceId);
    const reply = buildChatReplyAvailableMessage(deviceId, state, message, sessionKey, pendingRun);
    if (reply) {
      this.sendChat(deviceId, reply);
    }
  }
}

function workspacePathForNewSession(state: DeviceChatState, requestedWorkspacePath: string | undefined): string | undefined {
  const requested = requestedWorkspacePath?.trim();
  if (requested) {
    return requested;
  }
  const currentWorkspace = state.sessionSummaries.get(state.sessionKey)?.workspacePath?.trim();
  return currentWorkspace || undefined;
}
