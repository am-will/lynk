import type { ChatAttachment } from "../../protocol/messages.js";
import type { HarnessId } from "../AgentHarness.js";
import { HarnessAttachmentUnsupportedError, type HarnessCapabilities } from "./ChatTransportTypes.js";

export function normalizeChatSendContent<Attachment extends ChatAttachment>(text: string, attachments: Attachment[] | undefined): {
  text: string;
  attachments: Attachment[];
  requestText: string;
} {
  const normalizedText = text.trim();
  const normalizedAttachments = attachments ?? [] as Attachment[];
  return {
    text: normalizedText,
    attachments: normalizedAttachments,
    requestText: normalizedText || defaultAttachmentPrompt(normalizedAttachments)
  };
}

export function defaultAttachmentPrompt(attachments: ChatAttachment[]): string {
  const hasImage = attachments.some((attachment) => attachment.kind === "image" || attachment.mimeType.startsWith("image/"));
  return hasImage ? "Please review the attached image." : "Please review the attached file.";
}

export function assertHarnessSupportsAttachments(
  harnessId: HarnessId,
  capabilities: HarnessCapabilities,
  attachments: ChatAttachment[] | undefined
): void {
  if (attachments?.length && !capabilities.supportsAttachments) {
    throw new HarnessAttachmentUnsupportedError(harnessId);
  }
}
