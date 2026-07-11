import type { AgentTaskKind } from "../dispatcher/AgentClient.js";
import type { Dispatcher } from "../dispatcher/dispatcher.js";
import type {
  ChatErrorMessage,
  ChatFinalMessage,
  ChatOutboundMessage,
  ChatSendMessage,
  UserRequestMessage
} from "../protocol/messages.js";
import type { PendingChatRun } from "./OpenClawChatTypes.js";
import { DeviceChatStateStore } from "./OpenClawChatTypes.js";

interface FallbackSenderOptions {
  states: DeviceChatStateStore;
  dispatcher: Pick<Dispatcher, "handleUserRequest">;
  sendChat(deviceId: string, message: ChatOutboundMessage): void;
  sendState(deviceId: string, status?: string): void;
  sendReplyAvailable(
    deviceId: string,
    message: ChatFinalMessage | ChatErrorMessage,
    sessionKey: string,
    pendingRun?: PendingChatRun
  ): void;
}

export class OpenClawFallbackSender {
  constructor(private readonly options: FallbackSenderOptions) {}

  async send(
    message: ChatSendMessage,
    runId: string,
    taskKind: AgentTaskKind = "general",
    context?: { sessionKey: string; sessionId: string | null }
  ): Promise<void> {
    const state = this.options.states.stateFor(message.deviceId);
    const sessionKey = context?.sessionKey ?? state.sessionKey;
    const sessionId = context?.sessionId ?? state.sessionId ?? null;
    let emittedTerminal = false;
    this.options.states.trackPendingRun(state, runId, sessionKey, sessionId, taskKind);
    this.options.sendChat(message.deviceId, {
      type: "chat.history",
      deviceId: message.deviceId,
      sessionKey,
      sessionId,
      messages: [
        {
          id: `user_${runId}`,
          role: "user",
          text: message.text,
          attachments: message.attachments,
          timestamp: Date.now()
        }
      ]
    });
    this.options.sendState(message.deviceId, taskKind === "phone" ? "Using Android phone tools" : "Using OpenClaw fallback");
    try {
      if (message.attachments?.length) {
        throw new Error("OpenClaw fallback does not support chat attachments.");
      }
      const legacyRequest: UserRequestMessage = {
        type: "user_request",
        inputType: "text",
        deviceId: message.deviceId,
        text: message.text,
        model: undefined,
        reasoningEffort: undefined
      };
      const result = await this.options.dispatcher.handleUserRequest(legacyRequest, { taskKind });
      const finalMessage: ChatFinalMessage = {
        type: "chat.final",
        deviceId: message.deviceId,
        sessionKey,
        runId,
        text: result.finalMessage ?? "OpenClaw task completed."
      };
      if (!state.completedRunIds.has(runId)) {
        state.completedRunIds.add(runId);
        emittedTerminal = true;
        this.options.sendChat(message.deviceId, finalMessage);
        this.options.sendReplyAvailable(message.deviceId, finalMessage, sessionKey, state.pendingRuns.get(runId));
      }
    } catch (error) {
      const errorMessage: ChatErrorMessage = {
        type: "chat.error",
        deviceId: message.deviceId,
        sessionKey,
        runId,
        message: error instanceof Error ? error.message : String(error)
      };
      if (!state.completedRunIds.has(runId)) {
        state.completedRunIds.add(runId);
        emittedTerminal = true;
        this.options.sendChat(message.deviceId, errorMessage);
        this.options.sendReplyAvailable(message.deviceId, errorMessage, sessionKey, state.pendingRuns.get(runId));
      }
    } finally {
      state.pendingRuns.delete(runId);
      if (emittedTerminal) {
        this.options.sendState(message.deviceId, "OpenClaw finished");
      }
    }
  }
}
