import { CODEX_WORKSPACE_NOT_FOUND_CODE } from "../chat/ChatErrors.js";
import { prepareHostWorkspace } from "../workspace/HostWorkspace.js";

export function prepareCodexWorkspace(path: string | null | undefined, createIfMissing: boolean): string | undefined {
  return prepareHostWorkspace({
    path,
    createIfMissing,
    productLabel: "Codex",
    notFoundCode: CODEX_WORKSPACE_NOT_FOUND_CODE
  });
}
