import type { ChatAttachment } from "../../protocol/messages.js";
import { chatAttachmentSchema } from "../../protocol/messages.js";

export function normalizeChatAttachments(value: unknown): ChatAttachment[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const attachments: ChatAttachment[] = [];
  for (const item of value) {
    const parsed = chatAttachmentSchema.safeParse(item);
    if (parsed.success) {
      attachments.push(parsed.data);
    }
  }
  return attachments;
}
