import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import type { ChatAttachmentReference } from "../../protocol/messages.js";
import {
  attachmentMetadata,
  inlineCompatibilityAttachment
} from "../../attachments/AttachmentCompatibility.js";
import {
  ChatAttachmentResolutionError,
  resolveHostChatAttachments
} from "./HostChatAttachments.js";

const reference: ChatAttachmentReference = {
  id: "blob_attachment-1",
  kind: "image",
  displayName: "photo.png",
  mimeType: "image/png",
  sizeBytes: 5,
  sha256: "a".repeat(64)
};

test("resolveHostChatAttachments enforces owner checksum and metadata", () => {
  const owner = { deviceId: "phone", sessionKey: "codex:session" };
  const blobs = {
    resolve(id: string, candidateOwner: typeof owner, sha256?: string) {
      assert.equal(id, reference.id);
      assert.deepEqual(candidateOwner, owner);
      assert.equal(sha256, reference.sha256);
      return {
        version: 1 as const,
        ...reference,
        ...owner,
        path: "/private/blob",
        createdAt: 1
      };
    }
  };

  const resolved = resolveHostChatAttachments(blobs as never, owner, [reference]);

  assert.equal(resolved[0]?.localPath, "/private/blob");
  assert.deepEqual(attachmentMetadata(resolved[0]!), {
    id: reference.id,
    kind: reference.kind,
    displayName: reference.displayName,
    mimeType: reference.mimeType,
    sizeBytes: reference.sizeBytes
  });
});

test("resolveHostChatAttachments fails closed for unavailable and mismatched blobs", () => {
  const owner = { deviceId: "phone", sessionKey: "codex:session" };
  assert.throws(
    () => resolveHostChatAttachments(undefined, owner, [reference]),
    ChatAttachmentResolutionError
  );
  assert.throws(
    () => resolveHostChatAttachments({ resolve: () => undefined } as never, owner, [reference]),
    /unavailable or belongs to another chat/u
  );
  assert.throws(
    () => resolveHostChatAttachments({
      resolve: () => ({ ...reference, ...owner, version: 1, path: "/private/blob", createdAt: 1, displayName: "other.png" })
    } as never, owner, [reference]),
    /metadata changed/u
  );
});

test("inline compatibility encoding is bounded and omits the host path", () => {
  const root = mkdtempSync(join(tmpdir(), "lynk-inline-attachment-"));
  const path = join(root, "payload.blob");
  try {
    writeFileSync(path, "hello");
    const encoded = inlineCompatibilityAttachment({ ...reference, localPath: path });
    assert.equal(encoded.contentBase64, "aGVsbG8=");
    assert.equal("localPath" in encoded, false);
    assert.throws(
      () => inlineCompatibilityAttachment({ ...reference, sizeBytes: 9 * 1024 * 1024, localPath: path }),
      /compatibility limit/u
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
