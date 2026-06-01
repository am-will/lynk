import { OPENCODE_WORKSPACE_NOT_FOUND_CODE } from "../chat/ChatErrors.js";
import { prepareHostWorkspace } from "../workspace/HostWorkspace.js";

export function prepareOpenCodeWorkspace(path: string | null | undefined, createIfMissing: boolean): string | undefined {
  return prepareHostWorkspace({
    path,
    createIfMissing,
    productLabel: "OpenCode",
    notFoundCode: OPENCODE_WORKSPACE_NOT_FOUND_CODE
  });
}
