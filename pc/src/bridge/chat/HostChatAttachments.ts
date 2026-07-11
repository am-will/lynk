import type {
  ChatAttachmentReference
} from "../../protocol/messages.js";
import type { ResolvedChatAttachment } from "../../attachments/AttachmentTypes.js";
import type { HostBlobOwner, HostBlobStore } from "../blob/HostBlobStore.js";

export class ChatAttachmentResolutionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ChatAttachmentResolutionError";
  }
}

export function resolveHostChatAttachments(
  blobs: Pick<HostBlobStore, "resolve"> | undefined,
  owner: HostBlobOwner,
  references: ChatAttachmentReference[]
): ResolvedChatAttachment[] {
  if (references.length === 0) return [];
  if (!blobs) throw new ChatAttachmentResolutionError("Host attachment storage is unavailable.");
  return references.map((reference) => {
    const blob = blobs.resolve(reference.id, owner, reference.sha256);
    if (!blob) {
      throw new ChatAttachmentResolutionError(`Attachment ${reference.displayName} is unavailable or belongs to another chat.`);
    }
    if (
      blob.displayName !== reference.displayName
      || blob.mimeType !== reference.mimeType
      || blob.kind !== reference.kind
      || blob.sizeBytes !== reference.sizeBytes
    ) {
      throw new ChatAttachmentResolutionError(`Attachment metadata changed for ${reference.displayName}.`);
    }
    return { ...reference, localPath: blob.path };
  });
}
