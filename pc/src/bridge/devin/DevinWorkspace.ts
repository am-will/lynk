import { isAbsolute, resolve } from "node:path";
import { homedir } from "node:os";
import { DEVIN_WORKSPACE_NOT_FOUND_CODE } from "../chat/ChatErrors.js";
import { prepareHostWorkspace } from "../workspace/HostWorkspace.js";

export function prepareDevinWorkspace(
  path: string | null | undefined,
  createIfMissing: boolean,
  baseCwd: string
): string | undefined {
  const expanded = expandHomePath(path);
  if (expanded === undefined) {
    return undefined;
  }
  const absolute = isAbsolute(expanded) ? expanded : resolve(baseCwd, expanded);
  return prepareHostWorkspace({
    path: absolute,
    createIfMissing,
    productLabel: "Devin",
    notFoundCode: DEVIN_WORKSPACE_NOT_FOUND_CODE
  });
}

function expandHomePath(path: string | null | undefined): string | undefined {
  const trimmed = path?.trim();
  if (!trimmed) {
    return undefined;
  }
  if (trimmed === "~") {
    return homedir();
  }
  if (trimmed.startsWith("~/")) {
    return resolve(homedir(), trimmed.slice(2));
  }
  return trimmed;
}
