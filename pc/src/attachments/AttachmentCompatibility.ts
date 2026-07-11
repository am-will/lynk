import { readFileSync, statSync } from "node:fs";

import type { ChatAttachment } from "../protocol/messages.js";
import type { ResolvedChatAttachment } from "./AttachmentTypes.js";

export const INLINE_ATTACHMENT_COMPATIBILITY_MAX_BYTES = 8 * 1024 * 1024;

export interface InlineCompatibilityAttachment extends ChatAttachment {
  contentBase64: string;
}

export class AttachmentCompatibilityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AttachmentCompatibilityError";
  }
}

export function attachmentMetadata(attachment: ChatAttachment): ChatAttachment {
  return {
    id: attachment.id,
    kind: attachment.kind,
    displayName: attachment.displayName,
    mimeType: attachment.mimeType,
    sizeBytes: attachment.sizeBytes
  };
}

export function inlineCompatibilityAttachment(attachment: ResolvedChatAttachment): InlineCompatibilityAttachment {
  if (attachment.sizeBytes > INLINE_ATTACHMENT_COMPATIBILITY_MAX_BYTES) {
    throw new AttachmentCompatibilityError(
      `${attachment.displayName} exceeds the ${INLINE_ATTACHMENT_COMPATIBILITY_MAX_BYTES / (1024 * 1024)} MB compatibility limit for this harness.`
    );
  }
  const stats = statSync(attachment.localPath);
  if (!stats.isFile() || stats.size !== attachment.sizeBytes) {
    throw new AttachmentCompatibilityError(`Attachment payload changed for ${attachment.displayName}.`);
  }
  return {
    ...attachmentMetadata(attachment),
    contentBase64: readFileSync(attachment.localPath).toString("base64")
  };
}

export function inlineCompatibilityAttachments(attachments: ResolvedChatAttachment[] | undefined): InlineCompatibilityAttachment[] {
  return (attachments ?? []).map(inlineCompatibilityAttachment);
}
