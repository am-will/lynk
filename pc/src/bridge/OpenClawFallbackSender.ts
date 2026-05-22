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

  async send(message: ChatSendMessage, runId: string, taskKind: AgentTaskKind = "general"): Promise<void> {
    const state = this.options.states.stateFor(message.deviceId);
    state.runId = runId;
    this.options.states.trackPendingRun(state, runId, state.sessionKey, state.sessionId ?? null);
    this.options.sendChat(message.deviceId, {
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
    this.options.sendState(message.deviceId, taskKind === "phone" ? "Using Android phone tools" : "Using OpenClaw fallback");
    try {
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
        sessionKey: state.sessionKey,
        runId,
        text: result.finalMessage ?? "OpenClaw task completed."
      };
      this.options.sendChat(message.deviceId, finalMessage);
      this.options.sendReplyAvailable(message.deviceId, finalMessage, state.sessionKey, state.pendingRuns.get(runId));
    } catch (error) {
      const errorMessage: ChatErrorMessage = {
        type: "chat.error",
        deviceId: message.deviceId,
        sessionKey: state.sessionKey,
        runId,
        message: error instanceof Error ? error.message : String(error)
      };
      this.options.sendChat(message.deviceId, errorMessage);
      this.options.sendReplyAvailable(message.deviceId, errorMessage, state.sessionKey, state.pendingRuns.get(runId));
    } finally {
      state.runId = null;
      state.pendingRuns.delete(runId);
      this.options.sendState(message.deviceId, "OpenClaw finished");
    }
  }
}
