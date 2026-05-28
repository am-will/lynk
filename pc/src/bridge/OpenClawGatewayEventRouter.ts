import type {
  ChatErrorMessage,
  ChatFinalMessage,
  ChatOutboundMessage
} from "../protocol/messages.js";
import type { GatewayEvent } from "./chat/ChatTransportTypes.js";
import {
  mapGatewayChatEvent,
  normalizeGatewayReasoningEvent,
  normalizeGatewayToolEvent
} from "./chat/ChatNormalizers.js";
import type { DeviceChatState, PendingChatRun } from "./OpenClawChatTypes.js";
import { DeviceChatStateStore } from "./OpenClawChatTypes.js";

interface GatewayEventRouterOptions {
  states: DeviceChatStateStore;
  sendChat(deviceId: string, message: ChatOutboundMessage): void;
  sendState(deviceId: string, status?: string): void;
  sendReasoningClear(deviceId: string, sessionKey: string, runId?: string | null): void;
  settleRun(message: Extract<ChatOutboundMessage, { type: "chat.final" | "chat.error" }>): void;
  drainQueuedSends(deviceId: string): void;
  sendReplyAvailable(
    deviceId: string,
    message: ChatFinalMessage | ChatErrorMessage,
    sessionKey: string,
    pendingRun?: PendingChatRun
  ): void;
  refreshMetadata(deviceId: string): Promise<void>;
  sendHistory(deviceId: string): Promise<void>;
}

export class OpenClawGatewayEventRouter {
  constructor(private readonly options: GatewayEventRouterOptions) {}

  handleEvent(event: GatewayEvent): void {
    const eventName = event.event.toLowerCase();
    if (event.event === "chat") {
      this.handleGatewayChatEvent(event.payload);
    } else if (event.event === "agent") {
      this.handleGatewayAgentEvent(event.payload, event.event);
    } else if (eventName.includes("thinking") || eventName.includes("reasoning")) {
      this.handleGatewayReasoningEvent(event.payload, event.event);
    }
  }

  private handleGatewayChatEvent(payload: unknown): void {
    for (const [deviceId, state] of this.options.states.entries()) {
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
    const isIgnoredRun = typeof messageRunId === "string" && state.ignoredRunIds.has(messageRunId);
    const isSelectedSession = Boolean(messageSessionKey && messageSessionKey === state.sessionKey);
    const isTrackedPendingRun = Boolean(
      pendingRun && (!messageSessionKey || pendingRun.sessionKey === messageSessionKey)
    );
    const isTerminalMessage = message.type === "chat.final" || message.type === "chat.error";
    const isNotificationEligibleTerminal = Boolean(isTerminalMessage && messageSessionKey && messageRunId);

    if (!isSelectedSession && !isTrackedPendingRun && !isNotificationEligibleTerminal) {
      return false;
    }

    if (isIgnoredRun) {
      if (isTerminalMessage && messageRunId) {
        state.ignoredRunIds.delete(messageRunId);
        void this.options.refreshMetadata(deviceId);
        if (isSelectedSession) {
          void this.options.sendHistory(deviceId);
        }
      }
      return true;
    }

    if (isSelectedSession) {
      if (message.type === "chat.delta" || message.type === "chat.final" || message.type === "chat.error") {
        this.options.sendReasoningClear(deviceId, state.sessionKey, messageRunId ?? state.runId ?? null);
      }
      this.options.sendChat(deviceId, message);
    }

    if (isTerminalMessage) {
      this.options.settleRun(message);
      if (messageRunId && state.runId === messageRunId) {
        state.runId = null;
        state.activeTaskKind = null;
      }
      if (messageRunId) {
        const replySessionKey = messageSessionKey ?? pendingRun?.sessionKey;
        if (replySessionKey) {
          this.options.sendReplyAvailable(deviceId, message, replySessionKey, pendingRun);
        }
        if (pendingRun) {
          state.pendingRuns.delete(messageRunId);
        }
      }
      if (isSelectedSession) {
        this.options.sendState(deviceId, message.type === "chat.final" ? "OpenClaw finished" : "OpenClaw failed");
      }
      void this.options.refreshMetadata(deviceId);
      if (isSelectedSession) {
        void this.options.sendHistory(deviceId);
      }
      this.options.drainQueuedSends(deviceId);
    }

    return true;
  }

  private handleGatewayReasoningEvent(payload: unknown, eventName?: string): void {
    const record = payload && typeof payload === "object" ? payload as Record<string, unknown> : {};
    const runId = typeof record.runId === "string" ? record.runId : undefined;
    const sessionKey = typeof record.sessionKey === "string" ? record.sessionKey : undefined;
    for (const [deviceId, state] of this.options.states.entries()) {
      if (runId && state.ignoredRunIds.has(runId)) {
        continue;
      }
      if (runId && state.runId && runId !== state.runId) {
        continue;
      }
      if (sessionKey && sessionKey !== state.sessionKey) {
        continue;
      }
      const reasoningEvent = normalizeGatewayReasoningEvent(deviceId, state.sessionKey, payload, eventName);
      if (reasoningEvent && reasoningEvent.sessionKey === state.sessionKey) {
        this.options.sendChat(deviceId, reasoningEvent);
      }
    }
  }

  private handleGatewayAgentEvent(payload: unknown, eventName?: string): void {
    const record = payload && typeof payload === "object" ? payload as Record<string, unknown> : {};
    const runId = typeof record.runId === "string" ? record.runId : undefined;
    const sessionKey = typeof record.sessionKey === "string" ? record.sessionKey : undefined;
    for (const [deviceId, state] of this.options.states.entries()) {
      const pendingRun = runId ? state.pendingRuns.get(runId) : undefined;
      if (runId && state.ignoredRunIds.has(runId)) {
        if (record.type === "run.completed") {
          state.ignoredRunIds.delete(runId);
        }
        continue;
      }
      if (runId && state.runId && runId !== state.runId && !pendingRun) {
        continue;
      }
      if (sessionKey && sessionKey !== state.sessionKey && pendingRun?.sessionKey !== sessionKey) {
        continue;
      }
      const reasoningEvent = normalizeGatewayReasoningEvent(deviceId, state.sessionKey, payload, eventName);
      if (reasoningEvent && reasoningEvent.sessionKey === state.sessionKey) {
        this.options.sendChat(deviceId, reasoningEvent);
        continue;
      }
      const chatMessage = mapGatewayChatEvent(deviceId, payload);
      if (chatMessage && this.handleMappedChatMessage(deviceId, state, chatMessage)) {
        continue;
      }
      const toolEvent = normalizeGatewayToolEvent(deviceId, state.sessionKey, payload);
      if (toolEvent) {
        this.options.sendChat(deviceId, toolEvent);
      }
      if (record.type === "run.completed") {
        state.runId = null;
        state.activeTaskKind = null;
        this.options.sendState(deviceId, "OpenClaw finished");
        void this.options.refreshMetadata(deviceId);
        void this.options.sendHistory(deviceId);
        this.options.drainQueuedSends(deviceId);
      }
    }
  }
}
