import assert from "node:assert/strict";
import test from "node:test";

import {
  CHAT_ATTACHMENT_MAX_BYTES,
  chatAttachmentSchema,
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

test("attachment contract rejects malformed inline payloads", () => {
  const result = chatSendMessageSchema.safeParse({
    type: "chat.send",
    deviceId: "phone-1",
    text: "inspect",
    attachments: [{
      ...metadata,
      sizeBytes: 4,
      contentBase64: "not base64"
    }]
  });

  assert.equal(result.success, false);
});

test("attachment contract rejects decoded inline payloads over the limit", () => {
  const oversizedBase64 = Buffer.alloc(CHAT_ATTACHMENT_MAX_BYTES + 1).toString("base64");
  const result = chatSendMessageSchema.safeParse({
    type: "chat.send",
    deviceId: "phone-1",
    text: "inspect",
    attachments: [{
      ...metadata,
      sizeBytes: CHAT_ATTACHMENT_MAX_BYTES,
      contentBase64: oversizedBase64
    }]
  });

  assert.equal(result.success, false);
});
