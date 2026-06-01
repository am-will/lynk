import type { ChatErrorMessage } from "../../protocol/messages.js";

export const CODEX_WORKSPACE_NOT_FOUND_CODE = "codex.workspace_not_found";
export const OPENCODE_WORKSPACE_NOT_FOUND_CODE = "opencode.workspace_not_found";

export class ChatClientError extends Error {
  readonly code: string;
  readonly workspacePath?: string;

  constructor(message: string, options: { code: string; workspacePath?: string }) {
    super(message);
    this.name = "ChatClientError";
    this.code = options.code;
    this.workspacePath = options.workspacePath;
  }
}

export function buildChatErrorMessage(options: {
  deviceId: string;
  sessionKey?: string;
  runId?: string;
  error: unknown;
}): ChatErrorMessage {
  const error = chatErrorDetails(options.error);
  return {
    type: "chat.error",
    deviceId: options.deviceId,
    ...(options.sessionKey ? { sessionKey: options.sessionKey } : {}),
    ...(options.runId ? { runId: options.runId } : {}),
    message: error.message,
    ...(error.code ? { code: error.code } : {}),
    ...(error.workspacePath ? { workspacePath: error.workspacePath } : {})
  };
}

function chatErrorDetails(error: unknown): { message: string; code?: string; workspacePath?: string } {
  const record = error && typeof error === "object" ? error as Record<string, unknown> : undefined;
  const message = error instanceof Error ? error.message : String(error);
  const code = typeof record?.code === "string" && record.code.trim() ? record.code.trim() : undefined;
  const workspacePath = typeof record?.workspacePath === "string" && record.workspacePath.trim()
    ? record.workspacePath.trim()
    : undefined;
  return { message, code, workspacePath };
}
