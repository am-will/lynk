import assert from "node:assert/strict";
import test from "node:test";

import type { ChatAttachment } from "../../protocol/messages.js";
import { assertHarnessSupportsAttachments, normalizeChatSendContent } from "./ChatSendAttachments.js";

const attachment: ChatAttachment = {
  id: "att_1",
  kind: "image",
  displayName: "photo.png",
  mimeType: "image/png",
  sizeBytes: 12
};

test("normalizeChatSendContent supplies an attachment-only prompt", () => {
  const normalized = normalizeChatSendContent("   ", [attachment]);

  assert.equal(normalized.text, "");
  assert.equal(normalized.requestText, "Please review the attached image.");
  assert.deepEqual(normalized.attachments, [attachment]);
});

test("assertHarnessSupportsAttachments rejects unsupported harnesses", () => {
  assert.doesNotThrow(() => {
    assertHarnessSupportsAttachments("openclaw", { supportsAttachments: true }, [attachment]);
  });

  assert.throws(
    () => assertHarnessSupportsAttachments("codex", { supportsAttachments: false }, [attachment]),
    /codex harness does not support chat attachments/
  );
});
