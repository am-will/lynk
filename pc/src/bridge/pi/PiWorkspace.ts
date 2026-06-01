import { PI_WORKSPACE_NOT_FOUND_CODE } from "../chat/ChatErrors.js";
import { prepareHostWorkspace } from "../workspace/HostWorkspace.js";

export function preparePiWorkspace(path: string | null | undefined, createIfMissing: boolean): string | undefined {
  return prepareHostWorkspace({
    path,
    createIfMissing,
    productLabel: "Pi",
    notFoundCode: PI_WORKSPACE_NOT_FOUND_CODE
  });
}
