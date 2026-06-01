import { existsSync, mkdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { ChatClientError } from "../chat/ChatErrors.js";

export function prepareHostWorkspace(options: {
  path: string | null | undefined;
  createIfMissing: boolean;
  productLabel: string;
  notFoundCode: string;
}): string | undefined {
  const workspacePath = expandHomePath(options.path);
  ensureWorkspaceDirectory(workspacePath, options.path, options.createIfMissing, options.productLabel, options.notFoundCode);
  return workspacePath;
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
    return join(homedir(), trimmed.slice(2));
  }
  return trimmed;
}

function ensureWorkspaceDirectory(
  path: string | undefined,
  displayPath: string | null | undefined,
  createIfMissing: boolean,
  productLabel: string,
  notFoundCode: string
): void {
  if (!path) {
    return;
  }
  if (!existsSync(path)) {
    if (!createIfMissing) {
      const workspacePath = displayPath?.trim() || path;
      throw new ChatClientError(`${productLabel} workspace folder not found: ${workspacePath}`, {
        code: notFoundCode,
        workspacePath
      });
    }
    mkdirSync(path, { recursive: true });
  }
  if (!statSync(path).isDirectory()) {
    throw new Error(`${productLabel} workspace path is not a folder: ${displayPath?.trim() || path}`);
  }
}
