import type {
  ChatErrorMessage,
  ChatFinalMessage,
  ChatHistoryMessage,
  ChatMessageOutboundMessage,
  ChatReplyAvailableMessage,
  ChatStateMessage
} from "../protocol/messages.js";
import {
  harnessForSessionKey,
  harnessLabel
} from "./AgentHarness.js";
import { previewText } from "./OpenClawChatFormatters.js";
import type { DeviceChatState, PendingChatRun } from "./OpenClawChatTypes.js";

export function buildChatStateMessage(deviceId: string, state: DeviceChatState, status?: string): ChatStateMessage {
  return {
    type: "chat.state",
    deviceId,
    sessionKey: state.sessionKey,
    sessionId: state.sessionId,
    harnessId: state.harnessId,
    harnessLabel: harnessLabel(state.harnessId),
    runId: state.runId ?? null,
    isRunning: Boolean(state.runId),
    status: status ?? null,
    taskKind: state.runId ? state.activeTaskKind ?? null : null,
    model: state.model ?? null,
    reasoningEffort: state.reasoningEffort ?? null,
    reasoningStream: state.reasoningStream ?? null,
    fastMode: state.fastMode ?? null,
    verboseLevel: state.verboseLevel ?? null
  };
}

export function buildChatRoleMessage(
  deviceId: string,
  state: DeviceChatState,
  role: "user" | "system",
  text: string,
  id: string
): ChatMessageOutboundMessage {
  const message: ChatHistoryMessage = {
    id,
    role,
    text,
    timestamp: Date.now()
  };
  return {
    type: "chat.message",
    deviceId,
    sessionKey: state.sessionKey,
    sessionId: state.sessionId,
    message
  };
}

export function buildChatReplyAvailableMessage(
  deviceId: string,
  state: DeviceChatState,
  message: ChatFinalMessage | ChatErrorMessage,
  sessionKey: string,
  pendingRun?: PendingChatRun
): ChatReplyAvailableMessage | undefined {
  const runId = message.runId;
  if (!runId) {
    return undefined;
  }
  const session = state.sessionSummaries.get(sessionKey);
  const sourceHarnessId = harnessForSessionKey(sessionKey);
  return {
    type: "chat.reply_available",
    deviceId,
    sessionKey,
    runId,
    status: message.type === "chat.final" ? "completed" : "failed",
    textPreview: previewText(message.type === "chat.final" ? message.text : message.message),
    sessionId: session?.sessionId ?? pendingRun?.sessionId ?? null,
    sessionLabel: session?.label ?? null,
    sessionDisplayName: session?.displayName ?? null,
    harnessId: session?.harnessId ?? sourceHarnessId,
    harnessLabel: session?.harnessLabel ?? harnessLabel(sourceHarnessId),
    model: session?.model ?? (sessionKey === state.sessionKey ? state.model : null)
  };
}
