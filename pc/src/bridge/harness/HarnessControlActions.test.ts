import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseHarnessPermissionReply } from "./HarnessControlActions.js";

describe("HarnessControlActions", () => {
  it("preserves existing OpenCode decisions", () => {
    assert.equal(parseHarnessPermissionReply("opencode.permission", {
      permissionId: "p1",
      response: "always"
    })?.response, "always");
  });

  it("parses arbitrary Devin ACP option IDs as a discriminated decision", () => {
    assert.deepEqual(parseHarnessPermissionReply("devin.permission", {
      permissionId: "p2",
      optionId: "reject_always:opaque/value"
    })?.response, { kind: "acp_option", optionId: "reject_always:opaque/value" });
  });

  it("preserves opaque Devin ACP option IDs byte-for-byte", () => {
    const optionId = "  opaque option/id\t";
    assert.deepEqual(parseHarnessPermissionReply("devin.permission", {
      permissionId: "p3",
      optionId
    })?.response, { kind: "acp_option", optionId });
  });

  it("rejects incomplete replies", () => {
    assert.equal(parseHarnessPermissionReply("devin.permission", { permissionId: "p2" })?.permissionId, "");
  });
});
