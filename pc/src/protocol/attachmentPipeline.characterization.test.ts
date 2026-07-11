import assert from "node:assert/strict";
import test from "node:test";

import {
  CHAT_ATTACHMENT_MAX_BYTES,
  chatAttachmentSchema,
  chatAttachmentReferenceSchema,
  chatSendMessageSchema
} from "./messages.js";

const metadata = {
  id: "att_characterization",
  kind: "file" as const,
  displayName: "report.bin",
  mimeType: "application/octet-stream"
};

test("attachment contract accepts the exact declared item limit", () => {
  const result = chatAttachmentSchema.safeParse({
    ...metadata,
    sizeBytes: CHAT_ATTACHMENT_MAX_BYTES
  });

  assert.equal(result.success, true);
});

test("attachment contract rejects a declared item over the limit", () => {
  const result = chatAttachmentSchema.safeParse({
    ...metadata,
    sizeBytes: CHAT_ATTACHMENT_MAX_BYTES + 1
  });

  assert.equal(result.success, false);
});

test("attachment send contract rejects all inline payloads", () => {
  const result = chatSendMessageSchema.safeParse({
    type: "chat.send",
    deviceId: "phone-1",
    sessionKey: "codex:session",
    text: "inspect",
    attachments: [{
      ...metadata,
      id: "blob_characterization-inline",
      sizeBytes: 4,
      sha256: "a".repeat(64),
      contentBase64: "aGVsbG8="
    }]
  });

  assert.equal(result.success, false);
});

test("attachment reference requires a content id and checksum", () => {
  assert.equal(chatAttachmentReferenceSchema.safeParse({ ...metadata, sizeBytes: 4 }).success, false);
  assert.equal(chatAttachmentReferenceSchema.safeParse({
    ...metadata,
    id: "blob_characterization-valid",
    sizeBytes: 4,
    sha256: "a".repeat(64)
  }).success, true);
});
