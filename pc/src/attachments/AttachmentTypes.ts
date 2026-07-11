import type { ChatAttachmentReference } from "../protocol/messages.js";

/** Runtime-only attachment resolved inside the host bridge. Never serialize this type. */
export interface ResolvedChatAttachment extends ChatAttachmentReference {
  localPath: string;
}
