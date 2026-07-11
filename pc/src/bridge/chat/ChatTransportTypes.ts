import type { ResolvedChatAttachment } from "../../attachments/AttachmentTypes.js";
import type { HarnessId } from "../AgentHarness.js";

export interface GatewayEvent {
  event: string;
  payload: unknown;
  seq?: number;
}

export interface GatewayChatSendResult {
  runId: string;
  sessionKey: string;
}

export type GatewayEventHandler = (event: GatewayEvent) => void;

export interface HarnessCapabilities {
  supportsAttachments: boolean;
}

export interface HarnessChatSendOptions {
  sessionKey: string;
  sessionId?: string;
  message: string;
  attachments?: ResolvedChatAttachment[];
  thinking?: string;
  idempotencyKey?: string;
}

export interface HarnessChatSteerOptions extends HarnessChatSendOptions {
  runId?: string;
}

export interface HarnessPermissionReplyOptions {
  sessionKey: string;
  permissionId: string;
  response: HarnessPermissionResponse;
}

export type HarnessPermissionResponse =
  | "once"
  | "always"
  | "reject"
  | { kind: "acp_option"; optionId: string };

export class HarnessAttachmentUnsupportedError extends Error {
  constructor(readonly harnessId: HarnessId) {
    super(`${harnessId} harness does not support chat attachments.`);
    this.name = "HarnessAttachmentUnsupportedError";
  }
}
