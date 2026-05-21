import { randomUUID } from "node:crypto";
import type { AgentRunResult, AgentTaskKind } from "../dispatcher/AgentClient.js";
import type { Dispatcher } from "../dispatcher/dispatcher.js";
import type {
  ChatControlCommandMessage,
  ChatCommandOption,
  ChatErrorMessage,
  ChatFinalMessage,
  ChatHistoryMessage,
  ChatNewSessionMessage,
  ChatOpenMessage,
  ChatOutboundMessage,
  ChatReplyAvailableMessage,
  ChatSelectSessionMessage,
  ChatSendMessage,
  ChatSetModelMessage,
  ChatSetReasoningMessage,
  ChatSessionSummary,
  ChatStopMessage,
  UserRequestMessage
} from "../protocol/messages.js";
import type { AuditLog } from "./AuditLog.js";
import type { BridgeConfig } from "./config.js";
import { PhoneHub } from "./PhoneHub.js";
import {
  chatMessagesFromHistory,
  mapGatewayChatEvent,
  normalizeCommands,
  normalizeGatewayReasoningEvent,
  normalizeGatewayToolEvent,
  normalizeModels,
  normalizeReasoningOptions,
  normalizeSessions,
  normalizeTools,
  OpenClawGatewayChatClient,
  requestKeyFromSessionKey,
  usageFromSession
} from "./OpenClawGatewayChatClient.js";
import type { GatewayChatSendResult, GatewayEventHandler } from "./OpenClawGatewayChatClient.js";

interface DeviceChatState {
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

interface PendingChatRun {
  sessionKey: string;
  sessionId?: string | null;
  startedAt: number;
}

interface GatewayChatClient {
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

interface RunWaiter {
  deviceId: string;
  sessionKey: string;
  runId: string;
  resolve: (result: AgentRunResult) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

const REALTIME_CHAT_REUSE_WINDOW_MS = 15 * 60 * 1000;
const REALTIME_CHAT_RUN_TIMEOUT_MS = 10 * 60 * 1000;
const FAST_PHONE_LOOP_INSTRUCTION = [
  "Phone-control speed policy:",
  "- Use the observation already returned by phone action tools as the next screen state.",
  "- Avoid extra phone_observe calls unless current context is missing, ambiguous, or stale.",
  "- Avoid screenshots unless the accessibility tree is insufficient or coordinates must come from pixels.",
  "- Use phone_wait only for visible loading/animation; prefer 300-1000 ms and avoid longer waits unless the screen is clearly still changing."
].join("\n");

export class OpenClawChatBridge {
  private readonly client: GatewayChatClient;
  private readonly devices = new Map<string, DeviceChatState>();
  private readonly runWaiters = new Map<string, RunWaiter>();

  constructor(
    private readonly config: BridgeConfig,
    private readonly hub: PhoneHub,
    private readonly dispatcher: Pick<Dispatcher, "handleUserRequest" | "stopActiveTurn">,
    private readonly audit?: AuditLog,
    client?: GatewayChatClient
  ) {
    this.client = client ?? new OpenClawGatewayChatClient(config);
    this.client.addEventListener((event) => {
      const eventName = event.event.toLowerCase();
      if (event.event === "chat") {
        this.handleGatewayChatEvent(event.payload);
      } else if (event.event === "agent") {
        this.handleGatewayAgentEvent(event.payload, event.event);
      } else if (eventName.includes("thinking") || eventName.includes("reasoning")) {
        this.handleGatewayReasoningEvent(event.payload, event.event);
      }
    });
  }

  async open(message: ChatOpenMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    if (message.sessionKey) {
      state.sessionKey = message.sessionKey;
    }
    this.sendState(message.deviceId, "Loading OpenClaw chat");
    await this.refreshDevice(message.deviceId);
  }

  async send(message: ChatSendMessage): Promise<void> {
    const text = message.text.trim();
    if (!text) {
      return;
    }
    const state = this.stateFor(message.deviceId);
    if (message.sessionKey) {
      state.sessionKey = message.sessionKey;
    }

    const idempotencyKey = message.idempotencyKey ?? randomUUID();
    if (await this.handleVisibleSlashCommand(message.deviceId, text, state.sessionKey)) {
      return;
    }
    try {
      const taskKind = isExplicitPhoneTask(text) ? "phone" : "general";
      state.reasoningEffort = normalizeThinkingLevel(message.reasoningEffort, state.reasoningEffort);
      const requestedModel = message.model?.trim();
      if (requestedModel && !isSameModelSelection(requestedModel, state.model)) {
        await this.patchSession(message.deviceId, state.sessionKey, { model: requestedModel });
        state.model = requestedModel;
      }
      const result = await this.client.sendChat({
        sessionKey: state.sessionKey,
        sessionId: message.sessionId,
        message: messageForGateway(text, taskKind),
        thinking: state.reasoningEffort ?? undefined,
        idempotencyKey
      });
      state.sessionKey = result.sessionKey;
      state.runId = result.runId;
      this.trackPendingRun(
        state,
        result.runId,
        result.sessionKey,
        message.sessionId ?? state.sessionId ?? null
      );
      await this.maybeSetFirstMessageDisplayName(message.deviceId, text);
      this.audit?.record("openclaw_chat_send", message.deviceId, {
        sessionKey: result.sessionKey,
        runId: result.runId,
        length: text.length
      });
      this.sendState(message.deviceId, "OpenClaw is working");
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.sendChat(message.deviceId, {
        type: "chat.error",
        deviceId: message.deviceId,
        sessionKey: state.sessionKey,
        runId: idempotencyKey,
        message: errorMessage
      });
      await this.fallbackSend(message, idempotencyKey);
    }
  }

  async stop(message: ChatStopMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const sessionKey = message.sessionKey ?? state.sessionKey;
    const runId = message.runId ?? state.runId ?? undefined;
    try {
      await this.client.abort(sessionKey, runId);
    } catch (error) {
      await this.dispatcher.stopActiveTurn(message.deviceId, message.reason ?? "Stopped from Android chat");
      this.sendChat(message.deviceId, {
        type: "chat.error",
        deviceId: message.deviceId,
        sessionKey,
        runId,
        message: error instanceof Error ? error.message : String(error)
      });
    } finally {
      state.runId = null;
      if (runId) {
        state.pendingRuns.delete(runId);
      }
      this.sendState(message.deviceId, "Stop requested");
    }
  }

  async handleRealtimeRequest(
    request: UserRequestMessage,
    options: { taskKind: AgentTaskKind; callId?: string } = { taskKind: "general" }
  ): Promise<AgentRunResult> {
    const text = request.text.trim();
    if (!text) {
      throw new Error("Realtime request text is required");
    }

    await this.ensureFreshRealtimeSession(request.deviceId, text);
    const state = this.stateFor(request.deviceId);
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
      state.lastRealtimeRequestAt = Date.now();
      this.audit?.record("realtime_chat_send", request.deviceId, {
        sessionKey: result.sessionKey,
        runId: result.runId,
        taskKind: options.taskKind,
        length: text.length
      });
      this.sendState(request.deviceId, options.taskKind === "phone" ? "OpenClaw is working on phone task" : "OpenClaw is working");
      return await this.waitForRun(request.deviceId, state.sessionKey, result.runId);
    } catch (error) {
      this.sendChatError(request.deviceId, state.sessionKey, error);
      throw error;
    }
  }

  async steerRealtimeTurn(deviceId: string, guidance: string, options?: { taskKind: AgentTaskKind; callId: string }): Promise<void> {
    const text = guidance.trim();
    if (!text) {
      throw new Error("Realtime steering guidance is required");
    }

    const state = this.stateFor(deviceId);
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
    this.rejectWaitersForDevice(deviceId, new Error(text));
    state.runId = null;
    this.sendState(deviceId, "Stop requested");
  }

  async selectSession(message: ChatSelectSessionMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    state.sessionKey = message.sessionKey;
    state.runId = null;
    state.model = null;
    state.pendingFirstMessageDisplayName = false;
    state.lastRealtimeRequestAt = null;
    this.sendState(message.deviceId, "Switched session");
    await this.refreshDevice(message.deviceId);
  }

  async newSession(message: ChatNewSessionMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const sessionUuid = randomUUID();
    const requestKey = typeof message.key === "string" && message.key.trim()
      ? message.key.trim()
      : `phone-${message.deviceId}-${sessionUuid}`;
    const explicitLabel = typeof message.label === "string" && message.label.trim()
      ? message.label.trim()
      : undefined;
    const created = await this.client.createSession({
      key: requestKey,
      label: explicitLabel ?? sessionUuid,
      model: message.model ?? state.model ?? undefined
    });
    const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
    const key = typeof record.key === "string" && record.key.trim() ? record.key.trim() : undefined;
    state.sessionKey = key ?? `agent:${this.config.openClawChatAgentId}:explicit:${requestKey}`;
    state.sessionId = typeof record.sessionId === "string" ? record.sessionId : null;
    state.runId = null;
    state.pendingFirstMessageDisplayName = explicitLabel ? false : true;
    state.lastRealtimeRequestAt = null;
    this.sendState(message.deviceId, "Started a new chat");
    await this.refreshDevice(message.deviceId);
  }

  async setModel(message: ChatSetModelMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const sessionKey = message.sessionKey ?? state.sessionKey;
    state.model = message.model;
    await this.sendSlashCommand(message.deviceId, `/model ${message.model}`, sessionKey, `Model: ${message.model}`);
  }

  async setReasoning(message: ChatSetReasoningMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const sessionKey = message.sessionKey ?? state.sessionKey;
    state.reasoningEffort = normalizeThinkingLevel(message.reasoningEffort, state.reasoningEffort);
    await this.sendSlashCommand(
      message.deviceId,
      `/think ${state.reasoningEffort}`,
      sessionKey,
      `Reasoning: ${state.reasoningEffort}`
    );
  }

  async controlCommand(message: ChatControlCommandMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const command = message.command.trim();
    if (!command) {
      return;
    }
    const normalized = command.startsWith("/") ? command.slice(1).trim() : command;
    const [rawName = "", ...parts] = normalized.split(/\s+/);
    const name = rawName.toLowerCase();
    const firstArg = parts[0];

    if (name === "new") {
      await this.newSession({
        type: "chat.new_session",
        deviceId: message.deviceId
      });
      return;
    }

    if (name === "status") {
      await this.sendStatusReport(message.deviceId);
      return;
    }

    if (name === "help") {
      await this.sendHelp(message.deviceId);
      return;
    }

    if (name === "commands") {
      await this.sendCommandList(message.deviceId);
      return;
    }

    if (name === "tools") {
      await this.sendToolList(message.deviceId, firstArg);
      return;
    }

    if (name === "tasks") {
      this.sendTaskList(message.deviceId);
      return;
    }

    if (name === "fast") {
      const enabled = typeof message.args.enabled === "boolean"
        ? message.args.enabled
        : firstArg === "off"
          ? false
          : firstArg === "on"
            ? true
            : undefined;
      await this.sendSlashCommand(
        message.deviceId,
        `/fast ${enabled === false ? "off" : "on"}`,
        state.sessionKey,
        "Updating fast mode",
        `Fast mode ${enabled === false ? "disabled" : "enabled"}`
      );
      return;
    }

    if (name === "verbose") {
      const level = typeof message.args.level === "string" && message.args.level.trim()
        ? message.args.level.trim()
        : firstArg && ["on", "off", "full"].includes(firstArg)
          ? firstArg
          : "on";
      await this.sendSlashCommand(message.deviceId, `/verbose ${level}`, state.sessionKey, "Updating verbosity", `Verbose mode set to ${level}`);
      return;
    }

    if (name === "reasoning") {
      const level = typeof message.args.level === "string" && message.args.level.trim() === "stream"
        ? "stream"
        : firstArg === "stream"
          ? "stream"
          : "off";
      state.reasoningStream = level === "stream";
      await this.sendSlashCommand(
        message.deviceId,
        `/reasoning ${level}`,
        state.sessionKey,
        `Reasoning Stream: ${state.reasoningStream ? "On" : "Off"}`,
        `Reasoning Stream ${state.reasoningStream ? "enabled" : "disabled"}`
      );
      return;
    }

    const slashText = command.startsWith("/") ? command : `/${command}`;
    await this.send({
      type: "chat.send",
      deviceId: message.deviceId,
      text: slashText,
      sessionKey: state.sessionKey
    });
  }

  private async handleVisibleSlashCommand(deviceId: string, text: string, sessionKey: string): Promise<boolean> {
    const normalized = text.trim();
    if (!normalized.startsWith("/")) {
      return false;
    }
    const [rawName, ...parts] = normalized.slice(1).trim().split(/\s+/);
    const name = rawName?.toLowerCase();
    const firstArg = parts[0]?.toLowerCase();
    if (name !== "reasoning" && name !== "reason") {
      return false;
    }

    const currentEnabled = this.stateFor(deviceId).reasoningStream === true;
    const level = firstArg === "stream" || firstArg === "on"
      ? "stream"
      : firstArg === "off"
        ? "off"
        : currentEnabled
          ? "stream"
          : "off";
    const nextEnabled = level === "stream";
    this.stateFor(deviceId).reasoningStream = nextEnabled;
    await this.sendSlashCommand(
      deviceId,
      firstArg ? `/reasoning ${level}` : "/reasoning",
      sessionKey,
      `Reasoning Stream: ${nextEnabled ? "On" : "Off"}`,
      `Reasoning Stream ${nextEnabled ? "enabled" : "disabled"}`
    );
    return true;
  }

  close(): void {
    for (const [key, waiter] of this.runWaiters) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error("OpenClaw chat bridge closed"));
      this.runWaiters.delete(key);
    }
    this.client.close();
  }

  private stateFor(deviceId: string): DeviceChatState {
    const existing = this.devices.get(deviceId);
    if (existing) {
      return existing;
    }
    const created: DeviceChatState = {
      sessionKey: this.config.openClawChatSessionKey,
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

  private async ensureFreshRealtimeSession(deviceId: string, firstRequestText: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const lastRealtimeRequestAt = state.lastRealtimeRequestAt ?? 0;
    if (lastRealtimeRequestAt > 0 && Date.now() - lastRealtimeRequestAt <= REALTIME_CHAT_REUSE_WINDOW_MS) {
      return;
    }

    const baseLabel = realtimeSessionLabel(firstRequestText);
    const existingLabels = new Set<string>();
    const { created, requestKey } = await this.createRealtimeSessionWithUniqueLabel(deviceId, state, baseLabel, existingLabels);
    const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
    const key = typeof record.key === "string" && record.key.trim() ? record.key.trim() : undefined;
    state.sessionKey = key ?? `agent:${this.config.openClawChatAgentId}:explicit:${requestKey}`;
    state.sessionId = typeof record.sessionId === "string" ? record.sessionId : null;
    state.runId = null;
    state.pendingFirstMessageDisplayName = false;
    this.sendState(deviceId, "Started a new realtime chat");
    await this.refreshDevice(deviceId);
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
      const requestKey = `realtime-${deviceId}-${randomUUID()}`;
      try {
        const created = await this.client.createSession({
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

    const requestKey = `realtime-${deviceId}-${randomUUID()}`;
    const suffix = Date.now().toString(36).slice(-4);
    try {
      const created = await this.client.createSession({
        key: requestKey,
        label: numberedLabel(`${baseLabel} ${suffix}`, 0),
        model: state.model ?? undefined
      });
      return { created, requestKey };
    } catch {
      throw lastDuplicateError instanceof Error ? lastDuplicateError : new Error("Could not create a unique realtime chat session label");
    }
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
  }

  private async ensureSession(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    try {
      await this.client.history(state.sessionKey);
    } catch {
      const key = requestKeyFromSessionKey(state.sessionKey, this.config.openClawChatAgentId);
      const created = await this.client.createSession({ key, label: "Open Claw Agent" });
      const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
      if (typeof record.key === "string" && record.key.trim()) {
        state.sessionKey = record.key;
      }
      state.pendingFirstMessageDisplayName = false;
    }
  }

  private async sendModels(deviceId: string): Promise<void> {
    const [modelsPayload, sessionsPayload] = await Promise.all([
      this.client.listModels(),
      this.client.listSessions(1).catch(() => undefined)
    ]);
    const models = normalizeModels(modelsPayload);
    this.sendChat(deviceId, {
      type: "chat.models",
      deviceId,
      models,
      reasoningOptions: normalizeReasoningOptions(sessionsPayload)
    });
  }

  private async sendSessions(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const payload = await this.client.listSessions(50);
    const sessions = normalizeSessions(payload);
    state.sessionSummaries = new Map(sessions.map((session) => [session.key, session]));
    const selected = sessions.find((session) => session.key === state.sessionKey);
    if (selected) {
      state.sessionId = selected.sessionId ?? null;
      state.model = state.model ?? selected.model ?? null;
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
    this.sendChat(deviceId, {
      type: "chat.history",
      deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      messages: chatMessagesFromHistory(payload)
    });
    this.sendState(deviceId);
  }

  private async sendCommands(deviceId: string): Promise<void> {
    const payload = await this.client.listCommands();
    this.sendChat(deviceId, {
      type: "chat.commands",
      deviceId,
      commands: normalizeCommands(payload)
    });
  }

  private async sendCommandList(deviceId: string): Promise<void> {
    try {
      const payload = await this.client.listCommands();
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
      const payload = await this.client.listCommands();
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
    const state = this.stateFor(deviceId);
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

  private async patchSession(deviceId: string, sessionKey: string, patch: Record<string, unknown>): Promise<void> {
    try {
      await this.client.patchSession(sessionKey, patch);
      this.sendState(deviceId, "Updated session");
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

  private async sendSlashCommand(deviceId: string, text: string, sessionKey: string, status: string, successMessage?: string): Promise<void> {
    try {
      const result = await this.client.sendChat({
        sessionKey,
        message: text,
        idempotencyKey: randomUUID()
      });
      const state = this.stateFor(deviceId);
      state.sessionKey = result.sessionKey;
      state.runId = result.runId;
      this.sendState(deviceId, status);
      if (successMessage) {
        this.appendSystemMessage(deviceId, successMessage, `system_${result.runId}`);
      }
    } catch (error) {
      this.sendChatError(deviceId, sessionKey, error);
    }
  }

  private handleGatewayChatEvent(payload: unknown): void {
    for (const [deviceId, state] of this.devices) {
      const message = mapGatewayChatEvent(deviceId, payload);
      if (message) {
        this.handleMappedChatMessage(deviceId, state, message);
      }
    }
  }

  private handleMappedChatMessage(deviceId: string, state: DeviceChatState, message: ChatOutboundMessage): boolean {
    const messageSessionKey = "sessionKey" in message ? message.sessionKey : undefined;
    const messageRunId = "runId" in message ? message.runId : undefined;
    const pendingRun = typeof messageRunId === "string" ? state.pendingRuns.get(messageRunId) : undefined;
    const isSelectedSession = Boolean(messageSessionKey && messageSessionKey === state.sessionKey);
    const isTrackedPendingRun = Boolean(
      pendingRun && (!messageSessionKey || pendingRun.sessionKey === messageSessionKey)
    );

    if (!isSelectedSession && !isTrackedPendingRun) {
      return false;
    }

    if (isSelectedSession) {
      if (message.type === "chat.delta" || message.type === "chat.final" || message.type === "chat.error") {
        this.sendReasoningClear(deviceId, state.sessionKey, messageRunId ?? state.runId ?? null);
      }
      this.sendChat(deviceId, message);
    }

    if (message.type === "chat.final" || message.type === "chat.error") {
      this.settleRun(message);
      if (messageRunId && state.runId === messageRunId) {
        state.runId = null;
      }
      if (messageRunId && pendingRun) {
        this.sendReplyAvailable(deviceId, message, messageSessionKey ?? pendingRun.sessionKey, pendingRun);
        state.pendingRuns.delete(messageRunId);
      }
      if (isSelectedSession) {
        this.sendState(deviceId, message.type === "chat.final" ? "OpenClaw finished" : "OpenClaw failed");
      }
      void this.refreshMetadata(deviceId);
      if (isSelectedSession) {
        void this.sendHistory(deviceId);
      }
    }

    return true;
  }

  private handleGatewayReasoningEvent(payload: unknown, eventName?: string): void {
    const record = payload && typeof payload === "object" ? payload as Record<string, unknown> : {};
    const runId = typeof record.runId === "string" ? record.runId : undefined;
    const sessionKey = typeof record.sessionKey === "string" ? record.sessionKey : undefined;
    for (const [deviceId, state] of this.devices) {
      if (runId && state.runId && runId !== state.runId) {
        continue;
      }
      if (sessionKey && sessionKey !== state.sessionKey) {
        continue;
      }
      const reasoningEvent = normalizeGatewayReasoningEvent(deviceId, state.sessionKey, payload, eventName);
      if (reasoningEvent && reasoningEvent.sessionKey === state.sessionKey) {
        this.sendChat(deviceId, reasoningEvent);
      }
    }
  }

  private handleGatewayAgentEvent(payload: unknown, eventName?: string): void {
    const record = payload && typeof payload === "object" ? payload as Record<string, unknown> : {};
    const runId = typeof record.runId === "string" ? record.runId : undefined;
    const sessionKey = typeof record.sessionKey === "string" ? record.sessionKey : undefined;
    for (const [deviceId, state] of this.devices) {
      if (runId && state.runId && runId !== state.runId) {
        continue;
      }
      if (sessionKey && sessionKey !== state.sessionKey) {
        continue;
      }
      const reasoningEvent = normalizeGatewayReasoningEvent(deviceId, state.sessionKey, payload, eventName);
      if (reasoningEvent && reasoningEvent.sessionKey === state.sessionKey) {
        this.sendChat(deviceId, reasoningEvent);
        continue;
      }
      const chatMessage = mapGatewayChatEvent(deviceId, payload);
      if (chatMessage && this.handleMappedChatMessage(deviceId, state, chatMessage)) {
        continue;
      }
      const toolEvent = normalizeGatewayToolEvent(deviceId, state.sessionKey, payload);
      if (toolEvent) {
        this.sendChat(deviceId, toolEvent);
      }
      if (record.type === "run.completed") {
        state.runId = null;
        this.sendState(deviceId, "OpenClaw finished");
        void this.refreshMetadata(deviceId);
        void this.sendHistory(deviceId);
      }
    }
  }

  private sendState(deviceId: string, status?: string): void {
    const state = this.stateFor(deviceId);
    this.sendChat(deviceId, {
      type: "chat.state",
      deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      runId: state.runId ?? null,
      isRunning: Boolean(state.runId),
      status: status ?? null,
      model: state.model ?? null,
      reasoningEffort: state.reasoningEffort ?? null,
      reasoningStream: state.reasoningStream ?? null,
      fastMode: state.fastMode ?? null,
      verboseLevel: state.verboseLevel ?? null
    });
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
    const message: ChatHistoryMessage = {
      id,
      role: "user",
      text,
      timestamp: Date.now()
    };
    this.sendChat(deviceId, {
      type: "chat.message",
      deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      message
    });
  }

  private appendSystemMessage(deviceId: string, text: string, id: string): void {
    const state = this.stateFor(deviceId);
    const message: ChatHistoryMessage = {
      id,
      role: "system",
      text,
      timestamp: Date.now()
    };
    this.sendChat(deviceId, {
      type: "chat.message",
      deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      message
    });
  }

  private waitForRun(deviceId: string, sessionKey: string, runId: string): Promise<AgentRunResult> {
    return new Promise<AgentRunResult>((resolve, reject) => {
      const key = this.runWaiterKey(deviceId, runId);
      const timer = setTimeout(() => {
        this.runWaiters.delete(key);
        reject(new Error(`OpenClaw chat run ${runId} timed out`));
      }, REALTIME_CHAT_RUN_TIMEOUT_MS);
      this.runWaiters.set(key, {
        deviceId,
        sessionKey,
        runId,
        resolve,
        reject,
        timer
      });
    });
  }

  private settleRun(message: Extract<ChatOutboundMessage, { type: "chat.final" | "chat.error" }>): void {
    const runId = message.runId;
    if (!runId) {
      return;
    }
    const waiter = this.runWaiters.get(this.runWaiterKey(message.deviceId, runId));
    if (!waiter || ("sessionKey" in message && message.sessionKey && message.sessionKey !== waiter.sessionKey)) {
      return;
    }

    clearTimeout(waiter.timer);
    this.runWaiters.delete(this.runWaiterKey(message.deviceId, runId));
    if (message.type === "chat.final") {
      waiter.resolve({ finalMessage: message.text });
    } else {
      waiter.reject(new Error(message.message));
    }
  }

  private rejectWaitersForDevice(deviceId: string, error: Error): void {
    for (const [key, waiter] of this.runWaiters) {
      if (waiter.deviceId !== deviceId) {
        continue;
      }
      clearTimeout(waiter.timer);
      this.runWaiters.delete(key);
      waiter.reject(error);
    }
  }

  private runWaiterKey(deviceId: string, runId: string): string {
    return `${deviceId}:${runId}`;
  }

  private sendChat(deviceId: string, message: ChatOutboundMessage): void {
    try {
      this.hub.sendChat(deviceId, message);
    } catch (error) {
      console.warn(`[chat] ${deviceId}: failed to send ${message.type}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private sendChatError(deviceId: string, sessionKey: string, error: unknown): void {
    this.sendChat(deviceId, {
      type: "chat.error",
      deviceId,
      sessionKey,
      message: error instanceof Error ? error.message : String(error)
    });
  }

  private async fallbackSend(message: ChatSendMessage, runId: string, taskKind: "general" | "phone" = "general"): Promise<void> {
    const state = this.stateFor(message.deviceId);
    state.runId = runId;
    this.trackPendingRun(state, runId, state.sessionKey, state.sessionId ?? null);
    this.sendChat(message.deviceId, {
      type: "chat.history",
      deviceId: message.deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      messages: [
        {
          id: `user_${runId}`,
          role: "user",
          text: message.text,
          timestamp: Date.now()
        }
      ]
    });
    this.sendState(message.deviceId, taskKind === "phone" ? "Using Android phone tools" : "Using OpenClaw fallback");
    try {
      const legacyRequest: UserRequestMessage = {
        type: "user_request",
        inputType: "text",
        deviceId: message.deviceId,
        text: message.text,
        model: undefined,
        reasoningEffort: undefined
      };
      const result = await this.dispatcher.handleUserRequest(legacyRequest, { taskKind });
      const finalMessage: ChatFinalMessage = {
        type: "chat.final",
        deviceId: message.deviceId,
        sessionKey: state.sessionKey,
        runId,
        text: result.finalMessage ?? "OpenClaw task completed."
      };
      this.sendChat(message.deviceId, finalMessage);
      this.sendReplyAvailable(message.deviceId, finalMessage, state.sessionKey, state.pendingRuns.get(runId));
    } catch (error) {
      const errorMessage: ChatErrorMessage = {
        type: "chat.error",
        deviceId: message.deviceId,
        sessionKey: state.sessionKey,
        runId,
        message: error instanceof Error ? error.message : String(error)
      };
      this.sendChat(message.deviceId, errorMessage);
      this.sendReplyAvailable(message.deviceId, errorMessage, state.sessionKey, state.pendingRuns.get(runId));
    } finally {
      state.runId = null;
      state.pendingRuns.delete(runId);
      this.sendState(message.deviceId, "OpenClaw finished");
    }
  }

  private trackPendingRun(
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

  private sendReplyAvailable(
    deviceId: string,
    message: ChatFinalMessage | ChatErrorMessage,
    sessionKey: string,
    pendingRun?: PendingChatRun
  ): void {
    const runId = message.runId;
    if (!runId) {
      return;
    }
    const state = this.stateFor(deviceId);
    const session = state.sessionSummaries.get(sessionKey);
    const reply: ChatReplyAvailableMessage = {
      type: "chat.reply_available",
      deviceId,
      sessionKey,
      runId,
      status: message.type === "chat.final" ? "completed" : "failed",
      textPreview: previewText(message.type === "chat.final" ? message.text : message.message),
      sessionId: session?.sessionId ?? pendingRun?.sessionId ?? null,
      sessionLabel: session?.label ?? null,
      sessionDisplayName: session?.displayName ?? null
    };
    this.sendChat(deviceId, reply);
  }
}

function formatHelp(commands: ChatCommandOption[]): string {
  const commandByName = commandLookup(commands);
  const session = ["/new", "/reset", "/compact [instructions]", "/stop"];
  const options = [
    "/think <level>",
    "/model <id>",
    "/fast status|on|off",
    "/verbose on|off|full",
    "/trace on|off|raw"
  ].filter((entry) => commandByName.has(entry.slice(1).split(/[ <]/)[0]));
  const status = ["/status", "/tasks", "/whoami", "/context"].filter((entry) => commandByName.has(entry.slice(1)));
  const hasSkill = commandByName.has("skill");

  return [
    "ℹ️ Help",
    "",
    "Session",
    session.filter((entry) => commandByName.has(entry.slice(1).split(/[ <\[]/)[0])).join(" | "),
    "",
    "Options",
    options.join(" | "),
    "",
    "Status",
    status.join(" | "),
    "",
    "Skills",
    hasSkill ? "/skill <name> [input]" : "",
    "",
    "More: /commands for full list, /tools for available capabilities"
  ].filter((line, index, lines) => line !== "" || lines[index - 1] !== "").join("\n").trim();
}

function formatCommandList(commands: ChatCommandOption[]): string {
  if (commands.length === 0) {
    return "No slash commands are available from OpenClaw right now.";
  }

  const native = commands.filter((command) => command.source !== "skill");
  const skills = commands.filter((command) => command.source === "skill");
  const lines = [
    `ℹ️ Commands (${commands.length})`,
    "",
    ...formatCommandGroups(native),
    ...(skills.length > 0 ? ["", `Skills (${skills.length})`, ...skills.slice(0, 40).map(formatCommandLine)] : [])
  ];
  if (skills.length > 40) {
    lines.push(`...and ${skills.length - 40} more skills. Use /skill <name> [input] to run one.`);
  }
  return lines.join("\n").trim();
}

function formatToolList(tools: ReturnType<typeof normalizeTools>, mode?: string): string {
  if (tools.length === 0) {
    return "🧰 Tools\nNo runtime tools are available for this session.";
  }
  const verbose = mode?.toLowerCase() === "verbose";
  const grouped = groupBy(tools, (tool) => tool.group ?? tool.source ?? "Tools");
  const lines = [`🧰 Tools (${tools.length})`];
  for (const [group, groupTools] of grouped) {
    lines.push("", group);
    for (const tool of groupTools.slice(0, verbose ? 30 : 20)) {
      const label = tool.label && tool.label !== tool.id ? `${tool.label} (${tool.id})` : tool.id;
      const description = verbose && tool.description ? ` - ${tool.description}` : "";
      lines.push(`/${label}${description}`);
    }
    if (groupTools.length > (verbose ? 30 : 20)) {
      lines.push(`...and ${groupTools.length - (verbose ? 30 : 20)} more in ${group}`);
    }
  }
  if (!verbose) {
    lines.push("", "Use /tools verbose for descriptions.");
  }
  return lines.join("\n");
}

function formatTaskList(state: DeviceChatState): string {
  const pending = [...state.pendingRuns.entries()];
  if (!state.runId && pending.length === 0) {
    return "📋 Tasks\nNo background tasks are running for this session.";
  }
  const lines = ["📋 Tasks"];
  if (state.runId) {
    lines.push(`Active run: ${state.runId}`);
  }
  for (const [runId, run] of pending) {
    lines.push(`/${runId} - ${run.sessionKey === state.sessionKey ? "current session" : run.sessionKey}`);
  }
  return lines.join("\n");
}

function formatStatusReport(state: DeviceChatState, health: unknown): string {
  const record = health && typeof health === "object" ? health as Record<string, unknown> : undefined;
  const eventLoop = record?.eventLoop && typeof record.eventLoop === "object" ? record.eventLoop as Record<string, unknown> : undefined;
  const sessions = state.sessionSummaries.size;
  return [
    "ℹ️ Status",
    "",
    `Session: ${state.sessionKey}`,
    `Run: ${state.runId ?? "idle"}`,
    `Model: ${state.model ?? "default"}`,
    `Thinking: ${state.reasoningEffort ?? "default"}`,
    `Reasoning stream: ${state.reasoningStream === true ? "on" : "off"}`,
    `Fast mode: ${state.fastMode === true ? "on" : state.fastMode === false ? "off" : "unknown"}`,
    `Verbose: ${state.verboseLevel ?? "unknown"}`,
    `Known sessions: ${sessions}`,
    record ? `Gateway: ${record.ok === true ? "ok" : "not ok"}${eventLoop?.degraded === true ? " (degraded)" : ""}` : "Gateway: unavailable"
  ].join("\n");
}

function isSameModelSelection(requestedModel: string, currentModel?: string | null): boolean {
  if (!currentModel) {
    return false;
  }
  return requestedModel === currentModel || currentModel.endsWith(`/${requestedModel}`);
}

function formatCommandGroups(commands: ChatCommandOption[]): string[] {
  const lines: string[] = [];
  for (const [category, categoryCommands] of groupBy(commands, (command) => titleCase(command.category ?? "other"))) {
    lines.push(category);
    for (const command of categoryCommands) {
      lines.push(formatCommandLine(command));
    }
    lines.push("");
  }
  while (lines.at(-1) === "") {
    lines.pop();
  }
  return lines;
}

function formatCommandLine(command: ChatCommandOption): string {
  const aliases = command.textAliases?.filter((alias) => alias.trim()) ?? [];
  const primary = aliases.find((alias) => alias.startsWith("/")) ?? `/${command.name}`;
  const secondary = aliases.filter((alias) => alias !== primary).slice(0, 3).join(", ");
  const args = command.args?.length
    ? ` ${command.args.map((arg) => arg.required ? `<${arg.name}>` : `[${arg.name}]`).join(" ")}`
    : command.acceptsArgs
      ? " [args]"
      : "";
  const aliasText = secondary ? ` (${secondary})` : "";
  const description = command.description ? ` - ${command.description}` : "";
  return `${primary}${args}${aliasText}${description}`;
}

function commandLookup(commands: ChatCommandOption[]): Set<string> {
  return new Set(commands.flatMap((command) => [
    command.name,
    ...(command.textAliases ?? []).map((alias) => alias.replace(/^\//, ""))
  ]));
}

function groupBy<T>(items: T[], keyFor: (item: T) => string): Map<string, T[]> {
  const grouped = new Map<string, T[]>();
  for (const item of items) {
    const key = keyFor(item);
    grouped.set(key, [...(grouped.get(key) ?? []), item]);
  }
  return grouped;
}

function titleCase(value: string): string {
  return value.replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}

function messageForGateway(text: string, taskKind: AgentTaskKind): string {
  if (taskKind !== "phone") {
    return text;
  }
  return `${FAST_PHONE_LOOP_INSTRUCTION}\n\nUser request:\n${text}`;
}

function isExplicitPhoneTask(text: string): boolean {
  const normalized = text.toLowerCase();
  return /\b(android|phone|device|screen|tap|swipe|scroll|keyboard|notification|settings app|facebook app|instagram app|messages app|sms)\b/.test(normalized)
    && !/\b(mac|desktop|pc|laptop|browser|terminal|repo|codebase)\b/.test(normalized);
}

function firstMessageDisplayName(text: string): string | undefined {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (!normalized) {
    return undefined;
  }
  return normalized.length <= 64 ? normalized : `${normalized.slice(0, 61).trimEnd()}...`;
}

function realtimeSessionLabel(text: string): string {
  return firstMessageDisplayName(text) ?? "Realtime voice";
}

function numberedLabel(baseLabel: string, attempt: number): string {
  const suffix = attempt <= 0 ? "" : ` ${attempt + 1}`;
  const maxBaseLength = 64 - suffix.length;
  const base = baseLabel.length <= maxBaseLength
    ? baseLabel
    : baseLabel.slice(0, maxBaseLength).trimEnd();
  return `${base}${suffix}`;
}

function isDuplicateSessionLabelError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /label|name|display/i.test(message) && /already|duplicate|exists|unique|used/i.test(message);
}

function previewText(text: string): string | null {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (!normalized) {
    return null;
  }
  return normalized.length <= 180 ? normalized : `${normalized.slice(0, 177).trimEnd()}...`;
}

const ALLOWED_THINKING_LEVELS = new Set(["low", "medium", "high", "xhigh"]);

function normalizeThinkingLevel(incoming?: string | null, current?: string | null): string {
  const normalizedIncoming = incoming?.trim().toLowerCase();
  if (normalizedIncoming && ALLOWED_THINKING_LEVELS.has(normalizedIncoming)) {
    return normalizedIncoming;
  }
  const normalizedCurrent = current?.trim().toLowerCase();
  if (normalizedCurrent && ALLOWED_THINKING_LEVELS.has(normalizedCurrent)) {
    return normalizedCurrent;
  }
  return "medium";
}

function reasoningStreamEnabled(level: string | null | undefined): boolean | null {
  if (!level) {
    return null;
  }
  const normalized = level.toLowerCase();
  if (normalized === "stream") {
    return true;
  }
  if (normalized === "off" || normalized === "false") {
    return false;
  }
  return null;
}
